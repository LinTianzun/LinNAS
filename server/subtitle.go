package main

import (
	"bytes"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func isSubExt(ext string) bool {
	switch ext {
	case "srt", "vtt", "ass", "ssa", "sub":
		return true
	}
	return false
}

type subTrack struct {
	Label string `json:"label"`
	Lang  string `json:"lang,omitempty"`
	Name  string `json:"name"`
	URL   string `json:"url"`
}

// handleSubtitleList returns the subtitle tracks found next to a video.
// GET /api/subtitle?vpath=/lib1/movie.mkv
func (s *Server) handleSubtitleList(w http.ResponseWriter, r *http.Request) {
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
	stem := strings.TrimSuffix(filepath.Base(abs), filepath.Ext(abs))
	dir := filepath.Dir(abs)
	entries, err := os.ReadDir(dir)
	if err != nil {
		writeJSON(w, 200, []subTrack{})
		return
	}
	tracks := []subTrack{}
	prefix := vp
	if i := strings.LastIndex(vp, "/"); i >= 0 {
		prefix = vp[:i+1]
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		ext := fileExt(name)
		if !isSubExt(ext) {
			continue
		}
		nstem := strings.TrimSuffix(name, filepath.Ext(name))
		if nstem != stem && !strings.HasPrefix(nstem, stem+".") {
			continue
		}
		extra := strings.Trim(strings.TrimPrefix(nstem, stem), ".")
		label := "字幕"
		if extra != "" {
			label = extra
		}
		subvp := prefix + name
		tracks = append(tracks, subTrack{
			Label: label,
			Lang:  extra,
			Name:  name,
			URL:   "/api/subtitle/content?vpath=" + url.PathEscape(subvp),
		})
	}
	writeJSON(w, 200, tracks)
}

func srtToVTT(s string) string {
	s = strings.TrimPrefix(s, "\ufeff")
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	var b strings.Builder
	b.WriteString("WEBVTT\n\n")
	wroteAny := false
	for _, ln := range strings.Split(s, "\n") {
		t := strings.TrimSpace(ln)
		if t == "" || isAllDigits(t) { // skip blank lines and SRT cue index lines
			continue
		}
		if strings.Contains(ln, "-->") {
			if wroteAny {
				b.WriteString("\n") // blank line separates cues in WebVTT
			}
			ln = strings.ReplaceAll(ln, ",", ".")
		}
		b.WriteString(strings.TrimRight(ln, " \t"))
		b.WriteString("\n")
		wroteAny = true
	}
	return b.String()
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

// handleSubtitleContent converts a subtitle file to WebVTT.
// GET /api/subtitle/content?vpath=/lib1/movie.zh.srt
func (s *Server) handleSubtitleContent(w http.ResponseWriter, r *http.Request) {
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
	ext := fileExt(abs)
	if !isSubExt(ext) {
		writeErr(w, 415, "不是字幕文件")
		return
	}
	raw, err := os.ReadFile(abs)
	if err != nil {
		writeErr(w, 404, "字幕文件不存在")
		return
	}
	w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	if ext == "vtt" {
		if !bytes.HasPrefix(raw, []byte("WEBVTT")) {
			w.Write([]byte("WEBVTT\n\n"))
		}
		w.Write(raw)
		return
	}
	if ext == "srt" || ext == "sub" {
		w.Write([]byte(srtToVTT(string(raw))))
		return
	}
	// ass/ssa: ask ffmpeg to convert to WebVTT
	ff := findFFmpeg()
	if ff == "" {
		writeErr(w, 503, "未安装 ffmpeg,无法转换该字幕")
		return
	}
	cmd := exec.Command(ff, "-v", "error", "-nostdin", "-i", abs, "-f", "webvtt", "pipe:1")
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		writeErr(w, 500, "字幕转换失败: "+strings.TrimSpace(errb.String()))
		return
	}
	w.Write(out.Bytes())
}
