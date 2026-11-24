package main

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

// ============================================================
//  目录选择器 / 盘符枚举(供「添加目录」使用,避免手写路径)
// ============================================================

var (
	procGetLogicalDrives = modkernel32.NewProc("GetLogicalDrives")
	procGetDriveTypeW    = modkernel32.NewProc("GetDriveTypeW")
)

// logicalDrives lists usable Windows drive roots.
func logicalDrives() []map[string]any {
	out := []map[string]any{}
	mask, _, _ := procGetLogicalDrives.Call()
	for i := 0; i < 26; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		letter := string(rune('A'+i)) + ":\\"
		lp, err := syscall.UTF16PtrFromString(letter)
		if err != nil {
			continue
		}
		t, _, _ := procGetDriveTypeW.Call(uintptr(unsafe.Pointer(lp)))
		typ := "unknown"
		switch t {
		case 2:
			typ = "removable"
		case 3:
			typ = "fixed"
		case 4:
			typ = "network"
		case 5:
			typ = "cdrom"
		case 6:
			typ = "ramdisk"
		}
		if typ == "cdrom" || typ == "unknown" {
			continue
		}
		m := map[string]any{"letter": letter, "name": letter, "type": typ}
		if free, total, derr := diskUsage(letter); derr == nil {
			m["freeMB"] = free / 1e6
			m["totalMB"] = total / 1e6
		}
		out = append(out, m)
	}
	return out
}

// handleFsDirs lists subdirectories of an absolute server path.
// GET /api/fs/dirs?path=H:\  (path 为空则只返回盘符列表)
func (s *Server) handleFsDirs(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimSpace(r.URL.Query().Get("path"))
	if p == "" {
		writeJSON(w, 200, map[string]any{
			"path": "", "parent": "", "dirs": []any{}, "drives": logicalDrives(),
		})
		return
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		writeErr(w, 400, "路径无效")
		return
	}
	ents, err := os.ReadDir(abs)
	if err != nil {
		writeErr(w, 400, "无法读取目录: "+err.Error())
		return
	}
	dirs := []map[string]any{}
	for _, e := range ents {
		if !e.IsDir() {
			continue
		}
		name := e.Name()
		if strings.HasPrefix(name, "$") || strings.EqualFold(name, "System Volume Information") {
			continue // 隐藏系统目录
		}
		if s.inVault(filepath.Join(abs, name)) {
			continue // 隐藏保险箱目录
		}
		dirs = append(dirs, map[string]any{"name": name, "path": filepath.Join(abs, name)})
	}
	parent := filepath.Dir(abs)
	if parent == abs {
		parent = ""
	}
	writeJSON(w, 200, map[string]any{
		"path":   abs,
		"parent": parent,
		"dirs":   dirs,
		"drives": logicalDrives(),
	})
}

// ============================================================
//  编辑已有存储库
// ============================================================

// handleLibUpdate 修改已有存储库的名称/路径/只读/启用。
// POST /api/libs/update {id,name,path,readOnly,enabled}
func (s *Server) handleLibUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID       string   `json:"id"`
		Name     string   `json:"name"`
		Path     string   `json:"path"`
		ReadOnly bool     `json:"readOnly"`
		Enabled  *bool    `json:"enabled"`
		Exclude  []string `json:"exclude"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib := s.cfg.LibraryByID(req.ID)
	if lib == nil {
		writeErr(w, 404, "存储库不存在")
		return
	}
	if strings.TrimSpace(req.Path) != "" {
		abs, err := filepath.Abs(strings.TrimSpace(req.Path))
		if err != nil {
			writeErr(w, 400, "路径无效")
			return
		}
		info, err := os.Stat(abs)
		if err != nil || !info.IsDir() {
			writeErr(w, 400, "路径不存在或不是目录")
			return
		}
		for _, l := range s.cfg.Libraries {
			if l.ID != lib.ID && strings.EqualFold(filepath.Clean(l.Path), filepath.Clean(abs)) {
				writeErr(w, 409, "该路径已被其它存储库使用")
				return
			}
		}
		lib.Path = abs
	}
	if n := strings.TrimSpace(req.Name); n != "" {
		lib.Name = n
	}
	lib.ReadOnly = req.ReadOnly
	if req.Enabled != nil {
		lib.Enabled = *req.Enabled
	}
	if req.Exclude != nil {
		clean := make([]string, 0, len(req.Exclude))
		for _, ex := range req.Exclude {
			ex = strings.Trim(strings.ReplaceAll(ex, "\\", "/"), "/")
			if ex != "" {
				clean = append(clean, ex)
			}
		}
		lib.Exclude = clean
	}
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	if s.index != nil {
		s.index.ScanAll(s.cfg)
	}
	writeJSON(w, 200, lib)
}

// ============================================================
//  移动 / 复制 文件或文件夹
// ============================================================

type fsOpReq struct {
	VPaths []string `json:"vpaths"`
	Dest   string   `json:"dest"` // 目标目录的虚拟路径
}
// handleMove 把一批文件/文件夹移动到目标目录。
// POST /api/move {vpaths:[], dest:"/lib1/target"}
func (s *Server) handleMove(w http.ResponseWriter, r *http.Request) {
	s.fsOp(w, r, true)
}

// handleCopy 把一批文件/文件夹复制到目标目录。
// POST /api/copy {vpaths:[], dest:"/lib1/target"}
func (s *Server) handleCopy(w http.ResponseWriter, r *http.Request) {
	s.fsOp(w, r, false)
}

func (s *Server) fsOp(w http.ResponseWriter, r *http.Request, move bool) {
	var req fsOpReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if len(req.VPaths) == 0 {
		writeErr(w, 400, "没有选中文件")
		return
	}
	destLib, destAbs, err := s.cfg.Resolve(req.Dest)
	if err != nil {
		writeErr(w, 400, "目标目录无效: "+err.Error())
		return
	}
	if s.inVault(destAbs) {
		writeErr(w, 403, "不能直接操作保险箱目录,请使用「隐私保险箱」")
		return
	}
	if s.excluded(destLib, destAbs) {
		writeErr(w, 403, "该目录已被排除")
		return
	}
	if destLib.ReadOnly {
		writeErr(w, 403, "目标存储库为只读")
		return
	}
	if info, err := os.Stat(destAbs); err != nil || !info.IsDir() {
		writeErr(w, 404, "目标目录不存在")
		return
	}

	type opErr struct {
		Path string `json:"path"`
		Msg  string `json:"msg"`
	}
	errs := []opErr{}
	moved := 0
	for _, vp := range req.VPaths {
		srcLib, srcAbs, rerr := s.cfg.Resolve(vp)
		if rerr != nil {
			errs = append(errs, opErr{vp, rerr.Error()})
			continue
		}
		if s.inVault(srcAbs) {
			errs = append(errs, opErr{vp, "保险箱内的文件请通过保险箱操作"})
			continue
		}
		if s.excluded(srcLib, srcAbs) {
			errs = append(errs, opErr{vp, "该路径已被排除"})
			continue
		}
		if srcLib.ReadOnly {
			errs = append(errs, opErr{vp, "存储库为只读"})
			continue
		}
		if filepath.Clean(srcAbs) == filepath.Clean(destAbs) {
			errs = append(errs, opErr{vp, "源和目标是同一个位置"})
			continue
		}
		// 不能移动到自身内部
		if withinRoot(srcAbs, destAbs) {
			errs = append(errs, opErr{vp, "不能移动到自身内部"})
			continue
		}
		name := filepath.Base(srcAbs)
		dst := filepath.Join(destAbs, name)
		if _, err := os.Stat(dst); err == nil {
			dst = filepath.Join(destAbs, uniqueName(destAbs, name))
		}
		if move {
			merr := os.Rename(srcAbs, dst)
			if merr != nil {
				// 跨盘:先复制再删除
				if cerr := copyTree(srcAbs, dst); cerr != nil {
					errs = append(errs, opErr{vp, "移动失败: " + cerr.Error()})
					continue
				}
				_ = os.RemoveAll(srcAbs)
			}
		} else {
			if cerr := copyTree(srcAbs, dst); cerr != nil {
				errs = append(errs, opErr{vp, "复制失败: " + cerr.Error()})
				continue
			}
		}
		moved++
	}
	writeJSON(w, 200, map[string]any{
		"ok":    len(errs) == 0,
		"count": moved,
		"errors": errs,
	})
}

// copyTree copies a file or directory recursively.
func copyTree(src, dst string) error {
	info, err := os.Stat(src)
	if err != nil {
		return err
	}
	if info.IsDir() {
		if err := os.MkdirAll(dst, 0o755); err != nil {
			return err
		}
		ents, err := os.ReadDir(src)
		if err != nil {
			return err
		}
		for _, e := range ents {
			if err := copyTree(filepath.Join(src, e.Name()), filepath.Join(dst, e.Name())); err != nil {
				return err
			}
		}
		return nil
	}
	return copyFile(src, dst, info.Mode())
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}

var errSamePath = errors.New("same path")
