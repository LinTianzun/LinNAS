package main

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

// ---- ffmpeg discovery ----

var (
	ffmpegOnce sync.Once
	ffmpegPath string
)

// findFFmpeg locates ffmpeg (PATH first, then common Windows install spots).
func findFFmpeg() string {
	ffmpegOnce.Do(func() {
		if p, err := exec.LookPath("ffmpeg"); err == nil {
			ffmpegPath = p
			return
		}
		candidates := []string{
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "ffmpeg", "bin", "ffmpeg.exe"),
			`C:\ffmpeg\bin\ffmpeg.exe`,
			`C:\Program Files\ffmpeg\bin\ffmpeg.exe`,
		}
		for _, c := range candidates {
			if st, err := os.Stat(c); err == nil && !st.IsDir() {
				ffmpegPath = c
				return
			}
		}
	})
	return ffmpegPath
}

// ext lists
func isVideoExt(ext string) bool {
	switch ext {
	case "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "rmvb", "rm", "mpg", "mpeg", "vob", "3gp", "ogv", "asf":
		return true
	}
	return false
}

func isImageExt(ext string) bool {
	switch ext {
	case "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif", "tiff":
		return true
	}
	return false
}

// nativePlayable reports whether the container/codec can be served as-is
// to browsers/ExoPlayer without transcoding.
func nativePlayable(ext string) bool {
	switch ext {
	case "mp4", "m4v", "webm", "mov", "mkv", "ts", "m2ts", "3gp":
		return true
	}
	return false
}

func fileExt(name string) string {
	i := strings.LastIndex(name, ".")
	if i < 0 {
		return ""
	}
	return strings.ToLower(name[i+1:])
}

// ---- 缩略图 ----

// handleThumb generates/caches a JPEG thumbnail for an image or video.
// GET /api/thumb?vpath=/lib1/a.mp4&w=320
func (s *Server) handleThumb(w http.ResponseWriter, r *http.Request) {
	vp := r.URL.Query().Get("vpath")
	_, abs, err := s.cfg.Resolve(vp)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」访问")
		return
	}
	info, err := os.Stat(abs)
	if err != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	width := 320
	if v := r.URL.Query().Get("w"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			if n < 64 {
				n = 64
			}
			if n > 1280 {
				n = 1280
			}
			width = n
		}
	}
	ext := fileExt(abs)
	if !isImageExt(ext) && !isVideoExt(ext) {
		writeErr(w, 415, "不支持的类型")
		return
	}
	ff := findFFmpeg()
	if ff == "" {
		writeErr(w, 503, "未安装 ffmpeg,无法生成缩略图")
		return
	}

	thumbDir := filepath.Join(s.cfg.DataDir, "thumbs")
	if err := os.MkdirAll(thumbDir, 0o755); err != nil {
		writeErr(w, 500, "创建缓存目录失败")
		return
	}
	keyRaw := fmt.Sprintf("%s|%d|%d", strings.ToLower(abs), info.ModTime().UnixNano(), width)
	sum := sha1.Sum([]byte(keyRaw))
	out := filepath.Join(thumbDir, hex.EncodeToString(sum[:])+".jpg")

	if _, err := os.Stat(out); err != nil {
		tmp := out + "." + strconv.FormatInt(info.ModTime().UnixNano(), 36) + ".tmp.jpg"
		args := []string{"-v", "error", "-nostdin"}
		if isVideoExt(ext) {
			args = append(args, "-ss", "00:00:02")
		}
		args = append(args,
			"-i", abs,
			"-frames:v", "1",
			"-vf", fmt.Sprintf("scale=%d:-2", width),
			"-q:v", "4",
			"-f", "image2",
			"-y", tmp,
		)
		cmd := exec.Command(ff, args...)
		var stderr bytes.Buffer
		cmd.Stderr = &stderr
		if runErr := cmd.Run(); runErr != nil {
			// 视频首帧在 2s 处可能取不到(短片),退回 0s 再试一次
			if isVideoExt(ext) {
				args2 := []string{"-v", "error", "-nostdin", "-i", abs,
					"-frames:v", "1", "-vf", fmt.Sprintf("scale=%d:-2", width),
					"-q:v", "4", "-f", "image2", "-y", tmp}
				cmd2 := exec.Command(ff, args2...)
				if err2 := cmd2.Run(); err2 != nil {
					_ = os.Remove(tmp)
					writeErr(w, 500, "生成缩略图失败: "+strings.TrimSpace(stderr.String()))
					return
				}
			} else {
				_ = os.Remove(tmp)
				writeErr(w, 500, "生成缩略图失败: "+strings.TrimSpace(stderr.String()))
				return
			}
		}
		if st, err := os.Stat(tmp); err != nil || st.Size() == 0 {
			_ = os.Remove(tmp)
			writeErr(w, 500, "缩略图生成失败")
			return
		}
		_ = os.Rename(tmp, out)
	}

	f, err := os.Open(out)
	if err != nil {
		writeErr(w, 500, "读取缩略图失败")
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	st, _ := f.Stat()
	http.ServeContent(w, r, filepath.Base(out), st.ModTime(), f)
}

// ---- 转码流 ----

// handleStream serves a video: native formats are proxied to handleRaw
// (full Range/206 support), others are transcoded on the fly to fragmented MP4
// via ffmpeg. Optional ?t=SECONDS seeks to a start offset (transcode only).
// GET /api/stream?vpath=/lib1/old.avi&t=0
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	vp := r.URL.Query().Get("vpath")
	_, abs, err := s.cfg.Resolve(vp)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」访问")
		return
	}
	info, err := os.Stat(abs)
	if err != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	ext := fileExt(abs)
	force := r.URL.Query().Get("force") == "1"
	if !force && nativePlayable(ext) {
		s.handleRaw(w, r)
		return
	}
	ff := findFFmpeg()
	if ff == "" {
		writeErr(w, 503, "未安装 ffmpeg,无法转码该格式")
		return
	}

	args := []string{"-v", "error", "-nostdin"}
	if t := r.URL.Query().Get("t"); t != "" && t != "0" {
		args = append(args, "-ss", t)
	}
	args = append(args,
		"-i", abs,
		"-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
		"-c:a", "aac", "-b:a", "160k", "-ac", "2",
		"-movflags", "frag_keyframe+empty_moov+default_base_moof",
		"-f", "mp4",
		"pipe:1",
	)
	cmd := exec.Command(ff, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		writeErr(w, 500, "启动转码失败: "+err.Error())
		return
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		writeErr(w, 500, "启动转码失败: "+err.Error())
		return
	}
	stop := make(chan struct{})
	go func() {
		select {
		case <-r.Context().Done():
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
		case <-stop:
		}
	}()

	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	fw := &flushWriter{w: w, f: flusher}
	_, copyErr := io.Copy(fw, stdout)
	close(stop)
	_ = cmd.Wait()
	if copyErr != nil {
		log.Printf("转码流出错 %s: %v %s", vp, copyErr, strings.TrimSpace(stderr.String()))
	}
}

type flushWriter struct {
	w http.ResponseWriter
	f http.Flusher
}

func (fw *flushWriter) Write(p []byte) (int, error) {
	n, err := fw.w.Write(p)
	if fw.f != nil {
		fw.f.Flush()
	}
	return n, err
}
