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
	"strings"
)

// streamVideo transcodes a video with ffmpeg while streaming it to the client.
// When the request starts from the beginning (no ?t= seek), the output is also
// written to a disk cache so that repeat playback can be served with full
// Range/seek support (http.ServeContent) instead of re-transcoding.
func (s *Server) streamVideo(w http.ResponseWriter, r *http.Request, abs string, info os.FileInfo, t string) {
	ff := findFFmpeg()
	if ff == "" {
		writeErr(w, 503, "未安装 ffmpeg,无法转码该格式")
		return
	}
	cacheAllowed := t == "" || t == "0"
	var final, part string
	var pf *os.File
	if cacheAllowed {
		keyRaw := fmt.Sprintf("%s|%d|%d|v1", strings.ToLower(abs), info.ModTime().UnixNano(), info.Size())
		sum := sha1.Sum([]byte(keyRaw))
		dir := filepath.Join(s.cfg.DataDir, "transcode")
		if err := os.MkdirAll(dir, 0o755); err == nil {
			final = filepath.Join(dir, hex.EncodeToString(sum[:])+".mp4")
			if f, err := os.Open(final); err == nil {
				defer f.Close()
				st, _ := f.Stat()
				w.Header().Set("Content-Type", "video/mp4")
				w.Header().Set("Cache-Control", "public, max-age=86400")
				w.Header().Set("Accept-Ranges", "bytes")
				http.ServeContent(w, r, filepath.Base(final), st.ModTime(), f)
				return
			}
			part = final + ".part"
			if f, err := os.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644); err == nil {
				pf = f
			}
		}
	}
	cleanup := func() {
		if pf != nil {
			_ = pf.Close()
			pf = nil
		}
	}

	args := []string{"-v", "error", "-nostdin"}
	if t != "" && t != "0" {
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
		if part != "" {
			cleanup()
			_ = os.Remove(part)
		}
		writeErr(w, 500, "启动转码失败: "+err.Error())
		return
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		if part != "" {
			cleanup()
			_ = os.Remove(part)
		}
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
	var dst io.Writer = w
	if pf != nil {
		dst = io.MultiWriter(w, pf)
	}
	fw := &flushWriter{w: dst, f: flusher}
	_, copyErr := io.Copy(fw, stdout)
	close(stop)
	_ = cmd.Wait()
	if copyErr == nil && pf != nil {
		_ = pf.Close()
		pf = nil
		if err := os.Rename(part, final); err != nil {
			_ = os.Remove(part)
		}
	} else if pf != nil {
		cleanup()
		_ = os.Remove(part)
	}
	if copyErr != nil {
		log.Printf("转码流出错 %s: %v %s", abs, copyErr, strings.TrimSpace(stderr.String()))
	}
}
