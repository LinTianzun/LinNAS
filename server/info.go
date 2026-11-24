package main

import (
	"bytes"
	"encoding/json"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// findFFprobe locates ffprobe next to ffmpeg (PATH is also tried).
func findFFprobe() string {
	if p, err := exec.LookPath("ffprobe"); err == nil {
		return p
	}
	ff := findFFmpeg()
	if ff == "" {
		return ""
	}
	base := "ffprobe"
	if strings.HasSuffix(strings.ToLower(ff), ".exe") {
		base = "ffprobe.exe"
	}
	cand := filepath.Join(filepath.Dir(ff), base)
	if st, err := os.Stat(cand); err == nil && !st.IsDir() {
		return cand
	}
	return ""
}

// infoResp is the detailed metadata of a file or folder.
type infoResp struct {
	Name       string  `json:"name"`
	Path       string  `json:"path"`
	IsDir      bool    `json:"isDir"`
	Size       int64   `json:"size"`
	ModTime    int64   `json:"modTime"`
	Kind       string  `json:"kind"`
	Ext        string  `json:"ext,omitempty"`
	Files      int64   `json:"files,omitempty"`
	Dirs       int64   `json:"dirs,omitempty"`
	TotalBytes int64   `json:"totalBytes,omitempty"`
	Width      int     `json:"width,omitempty"`
	Height     int     `json:"height,omitempty"`
	Duration   float64 `json:"duration,omitempty"`
	BitRate    int64   `json:"bitRate,omitempty"`
	VideoCodec string  `json:"videoCodec,omitempty"`
	AudioCodec string  `json:"audioCodec,omitempty"`
	FPS        string  `json:"fps,omitempty"`
}

type probeResult struct {
	Width, Height int
	Duration      float64
	BitRate       int64
	VideoCodec    string
	AudioCodec    string
	FPS           string
}

func parseRate(s string) float64 {
	// e.g. "30000/1001" or "25" or "0/0"
	if s == "" || s == "0/0" {
		return 0
	}
	if i := strings.Index(s, "/"); i >= 0 {
		num, _ := strconv.ParseFloat(s[:i], 64)
		den, _ := strconv.ParseFloat(s[i+1:], 64)
		if den != 0 {
			return num / den
		}
		return 0
	}
	v, _ := strconv.ParseFloat(s, 64)
	return v
}

// probeMedia runs ffprobe to extract resolution/duration/codecs.
func probeMedia(abs string) probeResult {
	var pr probeResult
	fp := findFFprobe()
	if fp == "" {
		return pr
	}
	var buf bytes.Buffer
	cmd := exec.Command(fp, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", abs)
	cmd.Stdout = &buf
	if err := cmd.Run(); err != nil {
		return pr
	}
	var probe struct {
		Streams []struct {
			CodecType    string `json:"codec_type"`
			CodecName    string `json:"codec_name"`
			Width        int    `json:"width"`
			Height       int    `json:"height"`
			AvgFrameRate string `json:"avg_frame_rate"`
			RFrameRate   string `json:"r_frame_rate"`
		} `json:"streams"`
		Format struct {
			Duration string `json:"duration"`
			BitRate  string `json:"bit_rate"`
		} `json:"format"`
	}
	if err := json.Unmarshal(buf.Bytes(), &probe); err != nil {
		return pr
	}
	if probe.Format.Duration != "" {
		pr.Duration, _ = strconv.ParseFloat(probe.Format.Duration, 64)
	}
	if probe.Format.BitRate != "" {
		pr.BitRate, _ = strconv.ParseInt(probe.Format.BitRate, 10, 64)
	}
	for _, st := range probe.Streams {
		switch st.CodecType {
		case "video":
			if pr.VideoCodec == "" {
				pr.VideoCodec = st.CodecName
				pr.Width = st.Width
				pr.Height = st.Height
				r := parseRate(st.AvgFrameRate)
				if r == 0 {
					r = parseRate(st.RFrameRate)
				}
				if r > 0 {
					pr.FPS = strconv.FormatFloat(r, 'f', 2, 64)
				}
			}
		case "audio":
			if pr.AudioCodec == "" {
				pr.AudioCodec = st.CodecName
			}
		}
	}
	return pr
}

// handleInfo returns detailed metadata for a file or folder.
// GET /api/info?vpath=/lib1/a.mp4
func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	vp := r.URL.Query().Get("vpath")
	lib, abs, err := s.cfg.Resolve(vp)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」访问")
		return
	}
	if s.excluded(lib, abs) {
		writeErr(w, 403, "该目录已被排除")
		return
	}
	info, err := os.Stat(abs)
	if err != nil {
		writeErr(w, 404, "路径不存在")
		return
	}
	resp := infoResp{
		Name:    filepath.Base(abs),
		Path:    vp,
		IsDir:   info.IsDir(),
		Size:    info.Size(),
		ModTime: info.ModTime().Unix(),
	}
	if info.IsDir() {
		resp.Kind = "dir"
		vaultDir := s.vaultDir()
		_ = filepath.WalkDir(abs, func(p string, d fs.DirEntry, werr error) error {
			if werr != nil {
				return nil
			}
			if d.IsDir() {
				if p != abs {
					if vaultDir != "" && withinRoot(vaultDir, p) {
						return filepath.SkipDir
					}
					if s.inTrash(p) || s.cfg.ExcludedAbs(lib, p) {
						return filepath.SkipDir
					}
					resp.Dirs++
				}
				return nil
			}
			resp.Files++
			if fi, e := d.Info(); e == nil {
				resp.TotalBytes += fi.Size()
			}
			return nil
		})
		writeJSON(w, 200, resp)
		return
	}
	resp.Ext = fileExt(abs)
	resp.Kind = kindForExt(resp.Ext)
	if k := resp.Kind; k == "video" || k == "image" || k == "audio" {
		pr := probeMedia(abs)
		resp.Width = pr.Width
		resp.Height = pr.Height
		resp.Duration = pr.Duration
		resp.BitRate = pr.BitRate
		resp.VideoCodec = pr.VideoCodec
		resp.AudioCodec = pr.AudioCodec
		resp.FPS = pr.FPS
	}
	writeJSON(w, 200, resp)
}
