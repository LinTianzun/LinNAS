package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ============================================================
//  回收站:删除的文件先移到存储库内的 .linNasTrash 目录,
//  可在界面上恢复或彻底删除。
// ============================================================

type trashItem struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	OrigPath string `json:"origPath"` // 原始位置(绝对路径)
	LibID    string `json:"libId"`
	Size     int64  `json:"size"`
	ModTime  int64  `json:"modTime"`
	IsDir    bool   `json:"isDir"`
	TrashedAt int64 `json:"trashedAt"`
}

// trashDirFor returns the trash folder of a library (inside the library root).
func trashDirFor(lib *Library) string {
	if lib == nil {
		return ""
	}
	return filepath.Join(lib.Path, ".linNasTrash")
}

// trashDirForLib returns the trash folder of a library value.
func trashDirForLib(lib Library) string {
	return filepath.Join(lib.Path, ".linNasTrash")
}

// findTrashDir returns the trash dir containing abs, if any.
func (s *Server) findTrashDir(abs string) (string, bool) {
	for _, lib := range s.cfg.Libraries {
		td := trashDirForLib(lib)
		if td != "" && withinRoot(td, abs) {
			return td, true
		}
	}
	return "", false
}

// inTrash reports whether abs is inside some library's trash folder.
func (s *Server) inTrash(abs string) bool {
	_, ok := s.findTrashDir(abs)
	return ok
}

// trashItemMetaPath stores the original location of a trashed item.
func trashMetaPath(trashDir, id string) string {
	return filepath.Join(trashDir, ".meta", id+".json")
}

// moveToTrash moves abs into the library's trash folder and records where it
// came from, so it can be restored.
func (s *Server) moveToTrash(lib *Library, abs string) (string, error) {
	td := trashDirForLib(*lib)
	if td == "" {
		return "", errNoLibrary
	}
	if err := os.MkdirAll(filepath.Join(td, ".meta"), 0o755); err != nil {
		return "", err
	}
	name := filepath.Base(abs)
	base := strings.TrimSuffix(name, filepath.Ext(name))
	id := time.Now().Format("20060102-150405") + "-" + base
	dst := filepath.Join(td, id+"__"+name)
	// ensure uniqueness
	for i := 1; ; i++ {
		if _, err := os.Stat(dst); err != nil {
			break
		}
		dst = filepath.Join(td, id+"_"+itoa(i)+"__"+name)
	}
	if err := os.Rename(abs, dst); err != nil {
		// 跨盘:复制再删除
		if cerr := copyTree(abs, dst); cerr != nil {
			return "", err
		}
		_ = os.RemoveAll(abs)
	}
	item := trashItem{
		ID:        id,
		Name:      name,
		OrigPath:  abs,
		LibID:     lib.ID,
		TrashedAt: time.Now().Unix(),
	}
	if info, err := os.Stat(dst); err == nil {
		item.Size = info.Size()
		item.ModTime = info.ModTime().Unix()
		item.IsDir = info.IsDir()
	}
	if raw, err := json.Marshal(item); err == nil {
		_ = os.WriteFile(trashMetaPath(td, id), raw, 0o644)
	}
	return dst, nil
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		return "-" + string(b)
	}
	return string(b)
}

// listTrash returns trashed items of one library (or all libraries).
func (s *Server) listTrash(libID string) []trashItem {
	out := []trashItem{}
	for _, lib := range s.cfg.Libraries {
		if libID != "" && lib.ID != libID {
			continue
		}
		td := trashDirForLib(lib)
		if td == "" {
			continue
		}
		ents, err := os.ReadDir(td)
		if err != nil {
			continue
		}
		for _, e := range ents {
			if e.Name() == ".meta" || !strings.Contains(e.Name(), "__") {
				continue
			}
			id := e.Name()[:strings.Index(e.Name(), "__")]
			it := trashItem{ID: id, LibID: lib.ID}
			if info, ierr := e.Info(); ierr == nil {
				it.TrashedAt = info.ModTime().Unix()
				it.Size = info.Size()
				it.ModTime = info.ModTime().Unix()
				it.IsDir = info.IsDir()
			}
			if raw, err := os.ReadFile(trashMetaPath(td, id)); err == nil {
				var m trashItem
				if json.Unmarshal(raw, &m) == nil {
					m.ID = id
					m.LibID = lib.ID
					it = m
				}
			}
			if it.Name == "" {
				it.Name = e.Name()
			}
			out = append(out, it)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].TrashedAt > out[j].TrashedAt })
	return out
}

// handleTrashList GET /api/trash?lib=
func (s *Server) handleTrashList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.listTrash(r.URL.Query().Get("lib")))
}

// handleTrashRestore POST /api/trash/restore {id, lib}
func (s *Server) handleTrashRestore(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID  string `json:"id"`
		Lib string `json:"lib"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib := s.cfg.LibraryByID(req.Lib)
	if lib == nil {
		writeErr(w, 404, "存储库不存在")
		return
	}
	td := trashDirFor(lib)
	items := s.listTrash(lib.ID)
	for _, it := range items {
		if it.ID != req.ID {
			continue
		}
		src := filepath.Join(td, it.ID+"__"+it.Name)
		if _, err := os.Stat(src); err != nil {
			writeErr(w, 404, "文件不存在")
			return
		}
		dst := it.OrigPath
		if dst == "" {
			writeErr(w, 400, "缺少原始位置")
			return
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			writeErr(w, 500, "创建目录失败")
			return
		}
		if _, err := os.Stat(dst); err == nil {
			dst = filepath.Join(filepath.Dir(dst), uniqueName(filepath.Dir(dst), filepath.Base(dst)))
		}
		if err := os.Rename(src, dst); err != nil {
			if cerr := copyTree(src, dst); cerr != nil {
				writeErr(w, 500, "恢复失败: "+cerr.Error())
				return
			}
			_ = os.RemoveAll(src)
		}
		_ = os.Remove(trashMetaPath(td, it.ID))
		writeJSON(w, 200, map[string]string{"restored": dst})
		return
	}
	writeErr(w, 404, "回收站中没有该文件")
}

// handleTrashDelete POST /api/trash/delete {id, lib}  彻底删除
func (s *Server) handleTrashDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID  string `json:"id"`
		Lib string `json:"lib"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib := s.cfg.LibraryByID(req.Lib)
	if lib == nil {
		writeErr(w, 404, "存储库不存在")
		return
	}
	td := trashDirFor(lib)
	for _, it := range s.listTrash(lib.ID) {
		if it.ID != req.ID {
			continue
		}
		src := filepath.Join(td, it.ID+"__"+it.Name)
		_ = os.RemoveAll(src)
		_ = os.Remove(trashMetaPath(td, it.ID))
		writeJSON(w, 200, map[string]bool{"ok": true})
		return
	}
	writeErr(w, 404, "回收站中没有该文件")
}

// handleTrashEmpty POST /api/trash/empty {lib}  清空回收站
func (s *Server) handleTrashEmpty(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Lib string `json:"lib"`
	}
	_ = readJSON(r, &req)
	count := 0
	for _, lib := range s.cfg.Libraries {
		if req.Lib != "" && lib.ID != req.Lib {
			continue
		}
		td := trashDirForLib(lib)
		for _, it := range s.listTrash(lib.ID) {
			_ = os.RemoveAll(filepath.Join(td, it.ID+"__"+it.Name))
			_ = os.Remove(trashMetaPath(td, it.ID))
			count++
		}
	}
	writeJSON(w, 200, map[string]int{"count": count})
}
