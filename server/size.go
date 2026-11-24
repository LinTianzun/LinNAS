package main

import (
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
)

// sizeResp is the recursive size summary of a virtual path.
type sizeResp struct {
	Files int64 `json:"files"`
	Dirs  int64 `json:"dirs"`
	Bytes int64 `json:"bytes"`
}

// handleSize computes the recursive file/dir count and total bytes under a
// virtual path (skipping the vault, trash and excluded subdirectories).
// GET /api/size?vpath=/lib1/some/dir
func (s *Server) handleSize(w http.ResponseWriter, r *http.Request) {
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
	var resp sizeResp
	if !info.IsDir() {
		resp.Files = 1
		resp.Bytes = info.Size()
		writeJSON(w, 200, resp)
		return
	}
	vaultDir := s.vaultDir()
	walkErr := filepath.WalkDir(abs, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return nil // skip unreadable entries
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
			resp.Bytes += fi.Size()
		}
		return nil
	})
	if walkErr != nil {
		writeErr(w, 500, "统计失败: "+walkErr.Error())
		return
	}
	writeJSON(w, 200, resp)
}
