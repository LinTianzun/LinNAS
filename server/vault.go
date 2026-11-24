package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// ============================================================
//  隐私保险箱:密码门禁 + 隐藏目录
//  目录默认在存储库内的隐藏子目录 .linNasVault,支持多级文件夹;
//  访问需要密码解锁后拿到的会话 token(默认 2 小时过期)。
// ============================================================

const vaultSessionTTL = 2 * time.Hour

type vaultState struct {
	mu       sync.Mutex
	sessions map[string]int64 // token -> 过期 unix 秒
}

var vault = &vaultState{sessions: map[string]int64{}}

type vaultConfig struct {
	Salt string `json:"salt"`
	Hash string `json:"hash"` // pbkdf2 派生密钥的 hex
	Iter int    `json:"iter"`
}

func (s *Server) vaultDir() string {
	return vaultDirForConfig(s.cfg)
}

// vaultDirForConfig 计算保险箱目录:显式配置优先;否则放在第一个可写存储库的
// 隐藏子目录 .linNasVault;没有存储库时退回 DataDir/vault。
func vaultDirForConfig(cfg *Config) string {
	if strings.TrimSpace(cfg.VaultDir) != "" {
		return filepath.Clean(cfg.VaultDir)
	}
	var fallback string
	for _, l := range cfg.Libraries {
		if !l.Enabled {
			continue
		}
		if fallback == "" {
			fallback = l.Path
		}
		if !l.ReadOnly {
			return filepath.Join(l.Path, ".linNasVault")
		}
	}
	if fallback != "" {
		return filepath.Join(fallback, ".linNasVault")
	}
	return filepath.Join(cfg.DataDir, "vault")
}

func (s *Server) defaultVaultDir() string { return vaultDirForConfig(s.cfg) }

// inVault reports whether abs is the vault dir or inside it.
func (s *Server) inVault(abs string) bool {
	return withinRoot(s.vaultDir(), abs)
}

func (s *Server) vaultCfgPath() string { return filepath.Join(s.cfg.DataDir, "vault.json") }

func (s *Server) readVaultCfg() (*vaultConfig, error) {
	raw, err := os.ReadFile(s.vaultCfgPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var vc vaultConfig
	if err := json.Unmarshal(raw, &vc); err != nil {
		return nil, err
	}
	return &vc, nil
}

func (s *Server) writeVaultCfg(vc *vaultConfig) error {
	raw, _ := json.MarshalIndent(vc, "", "  ")
	return os.WriteFile(s.vaultCfgPath(), raw, 0o600)
}

// pbkdf2Key derives a key from password+salt with HMAC-SHA256.
func pbkdf2Key(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	var out []byte
	blocks := (keyLen + sha256.Size - 1) / sha256.Size
	for block := 1; block <= blocks; block++ {
		prf.Reset()
		prf.Write(salt)
		prf.Write([]byte{byte(block >> 24), byte(block >> 16), byte(block >> 8), byte(block)})
		u := prf.Sum(nil)
		t := make([]byte, len(u))
		copy(t, u)
		for i := 1; i < iter; i++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// vaultAuthed reports whether the request carries a valid unlocked session.
func vaultAuthed(r *http.Request) bool {
	tok := r.URL.Query().Get("vt")
	if tok == "" {
		tok = r.Header.Get("X-Vault-Token")
	}
	if tok == "" {
		return false
	}
	vault.mu.Lock()
	defer vault.mu.Unlock()
	exp, ok := vault.sessions[tok]
	if !ok {
		return false
	}
	if time.Now().Unix() > exp {
		delete(vault.sessions, tok)
		return false
	}
	return true
}

// vaultAbs 把"相对保险箱根的路径"解析成安全的绝对路径(防穿越)。
func (s *Server) vaultAbs(rel string) (string, error) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	clean := strings.TrimPrefix(path.Clean("/"+rel), "/")
	abs := filepath.Join(s.vaultDir(), filepath.FromSlash(clean))
	if !withinRoot(s.vaultDir(), abs) {
		return "", errBadPath
	}
	return abs, nil
}

func joinRel(dir, name string) string {
	dir = strings.Trim(dir, "/")
	if dir == "" {
		return name
	}
	return dir + "/" + name
}

// handleVaultStatus GET /api/vault/status
func (s *Server) handleVaultStatus(w http.ResponseWriter, r *http.Request) {
	vc, err := s.readVaultCfg()
	if err != nil {
		writeErr(w, 500, "读取保险箱配置失败")
		return
	}
	writeJSON(w, 200, map[string]any{
		"exists":   vc != nil,
		"unlocked": vaultAuthed(r),
	})
}

// handleVaultSetup POST /api/vault/setup {password}
func (s *Server) handleVaultSetup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Password string `json:"password"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if len(strings.TrimSpace(req.Password)) < 4 {
		writeErr(w, 400, "密码至少 4 位")
		return
	}
	if vc, _ := s.readVaultCfg(); vc != nil {
		writeErr(w, 409, "保险箱已存在,请使用「修改密码」")
		return
	}
	salt := randHex(16)
	iter := 120000
	key := pbkdf2Key([]byte(req.Password), []byte(salt), iter, 32)
	vc := &vaultConfig{Salt: salt, Hash: hex.EncodeToString(key), Iter: iter}
	// 首次创建:把保险箱目录固定到存储库里(默认第一个可写库的 .linNasVault)
	if strings.TrimSpace(s.cfg.VaultDir) == "" {
		s.cfg.VaultDir = s.defaultVaultDir()
		_ = s.cfg.SaveConfig()
	}
	if err := s.writeVaultCfg(vc); err != nil {
		writeErr(w, 500, "保存失败: "+err.Error())
		return
	}
	if err := os.MkdirAll(s.vaultDir(), 0o755); err != nil {
		writeErr(w, 500, "创建保险箱目录失败")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handleVaultChangePassword POST /api/vault/password {oldPassword,newPassword}
func (s *Server) handleVaultChangePassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		OldPassword string `json:"oldPassword"`
		NewPassword string `json:"newPassword"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	vc, err := s.readVaultCfg()
	if err != nil || vc == nil {
		writeErr(w, 404, "保险箱尚未创建")
		return
	}
	if len(strings.TrimSpace(req.NewPassword)) < 4 {
		writeErr(w, 400, "新密码至少 4 位")
		return
	}
	got := pbkdf2Key([]byte(req.OldPassword), []byte(vc.Salt), vc.Iter, 32)
	want, _ := hex.DecodeString(vc.Hash)
	if subtle.ConstantTimeCompare(got, want) != 1 {
		writeErr(w, 403, "原密码不正确")
		return
	}
	salt := randHex(16)
	iter := 120000
	key := pbkdf2Key([]byte(req.NewPassword), []byte(salt), iter, 32)
	if err := s.writeVaultCfg(&vaultConfig{Salt: salt, Hash: hex.EncodeToString(key), Iter: iter}); err != nil {
		writeErr(w, 500, "保存失败")
		return
	}
	vault.mu.Lock()
	vault.sessions = map[string]int64{} // 改密码后所有会话失效
	vault.mu.Unlock()
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handleVaultUnlock POST /api/vault/unlock {password} -> {token,expires}
func (s *Server) handleVaultUnlock(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Password string `json:"password"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	vc, err := s.readVaultCfg()
	if err != nil || vc == nil {
		writeErr(w, 404, "保险箱尚未创建")
		return
	}
	got := pbkdf2Key([]byte(req.Password), []byte(vc.Salt), vc.Iter, 32)
	want, _ := hex.DecodeString(vc.Hash)
	if subtle.ConstantTimeCompare(got, want) != 1 {
		writeErr(w, 403, "密码不正确")
		return
	}
	tok := randHex(24)
	exp := time.Now().Add(vaultSessionTTL).Unix()
	vault.mu.Lock()
	vault.sessions[tok] = exp
	vault.mu.Unlock()
	writeJSON(w, 200, map[string]any{"token": tok, "expires": exp})
}

// handleVaultLock POST /api/vault/lock {token}
func (s *Server) handleVaultLock(w http.ResponseWriter, r *http.Request) {
	tok := r.URL.Query().Get("vt")
	if tok == "" {
		var req struct {
			Token string `json:"token"`
		}
		_ = readJSON(r, &req)
		tok = req.Token
	}
	vault.mu.Lock()
	delete(vault.sessions, tok)
	vault.mu.Unlock()
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// vaultEntry describes one item in the vault.
type vaultEntry struct {
	Name    string `json:"name"`
	Path    string `json:"path"` // 相对保险箱根的路径(/分隔)
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
	IsDir   bool   `json:"isDir"`
}

// handleVaultList GET /api/vault/ls?vt=&path=
func (s *Server) handleVaultList(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	rel := r.URL.Query().Get("path")
	abs, err := s.vaultAbs(rel)
	if err != nil {
		writeErr(w, 400, "路径无效")
		return
	}
	ents, err := os.ReadDir(abs)
	if err != nil {
		writeErr(w, 500, "读取失败: "+err.Error())
		return
	}
	out := []vaultEntry{}
	for _, e := range ents {
		info, ierr := e.Info()
		if ierr != nil {
			continue
		}
		out = append(out, vaultEntry{Name: e.Name(), Path: joinRel(rel, e.Name()), Size: info.Size(), ModTime: info.ModTime().Unix(), IsDir: e.IsDir()})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	writeJSON(w, 200, out)
}

// handleVaultMkdir POST /api/vault/mkdir {path}  在保险箱内新建文件夹
func (s *Server) handleVaultMkdir(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	var req struct {
		Path string `json:"path"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	abs, err := s.vaultAbs(req.Path)
	if err != nil || abs == filepath.Clean(s.vaultDir()) {
		writeErr(w, 400, "路径无效")
		return
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		writeErr(w, 500, "创建失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]string{"path": req.Path})
}

// handleVaultUpload POST /api/vault/upload?name=&path=&vt=  (原始字节流;path=目标文件夹)
func (s *Server) handleVaultUpload(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if !SafeName(name) {
		writeErr(w, 400, "文件名不合法")
		return
	}
	relDir := r.URL.Query().Get("path")
	dirAbs, err := s.vaultAbs(relDir)
	if err != nil {
		writeErr(w, 400, "路径无效")
		return
	}
	if err := os.MkdirAll(dirAbs, 0o755); err != nil {
		writeErr(w, 500, "创建目录失败")
		return
	}
	dst := filepath.Join(dirAbs, name)
	if _, err := os.Stat(dst); err == nil {
		dst = filepath.Join(dirAbs, uniqueName(dirAbs, name))
	}
	f, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		writeErr(w, 500, "写入失败: "+err.Error())
		return
	}
	n, cerr := io.Copy(f, r.Body)
	_ = f.Close()
	if cerr != nil {
		_ = os.Remove(dst)
		writeErr(w, 500, "接收失败: "+cerr.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"name": filepath.Base(dst), "path": joinRel(relDir, filepath.Base(dst)), "size": n})
}

// handleVaultRaw GET /api/vault/raw?path=&vt=  支持 Range(视频可拖动)
func (s *Server) handleVaultRaw(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	rel := r.URL.Query().Get("path")
	if rel == "" {
		rel = r.URL.Query().Get("name") // 兼容旧参数
	}
	abs, err := s.vaultAbs(rel)
	if err != nil {
		writeErr(w, 400, "路径无效")
		return
	}
	info, err := os.Stat(abs)
	if err != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	f, err := os.Open(abs)
	if err != nil {
		writeErr(w, 500, "打开失败")
		return
	}
	defer f.Close()
	if r.URL.Query().Get("dl") != "" {
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(info.Name()))
	}
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

// handleVaultDelete POST /api/vault/delete {paths:[], names:[]}
func (s *Server) handleVaultDelete(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	var req struct {
		Paths []string `json:"paths"`
		Names []string `json:"names"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	list := append(append([]string{}, req.Paths...), req.Names...)
	count := 0
	for _, rel := range list {
		abs, err := s.vaultAbs(rel)
		if err != nil || abs == filepath.Clean(s.vaultDir()) {
			continue
		}
		if err := os.RemoveAll(abs); err == nil {
			count++
		}
	}
	writeJSON(w, 200, map[string]any{"ok": true, "count": count})
}

// handleVaultImport POST /api/vault/import {vpaths:[], dest}  把存储库里的文件/文件夹移入保险箱指定子目录
func (s *Server) handleVaultImport(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	var req struct {
		VPaths []string `json:"vpaths"`
		Dest   string   `json:"dest"` // 相对保险箱子的目标文件夹(""=根)
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	destAbs, derr := s.vaultAbs(req.Dest)
	if derr != nil {
		writeErr(w, 400, "目标文件夹无效")
		return
	}
	if err := os.MkdirAll(destAbs, 0o755); err != nil {
		writeErr(w, 500, "创建目录失败")
		return
	}
	count := 0
	errs := []string{}
	for _, vp := range req.VPaths {
		lib, abs, rerr := s.cfg.Resolve(vp)
		if rerr != nil {
			errs = append(errs, rerr.Error())
			continue
		}
		// 保留原目录结构:相对该存储库根的子路径原样搬到保险箱目标目录下
		relDir := ""
		if rel, e := filepath.Rel(filepath.Clean(lib.Path), filepath.Dir(abs)); e == nil && rel != "." && !strings.HasPrefix(rel, "..") {
			relDir = rel
		}
		targetDir := filepath.Join(destAbs, relDir)
		if err := os.MkdirAll(targetDir, 0o755); err != nil {
			errs = append(errs, "创建目录失败: "+err.Error())
			continue
		}
		name := filepath.Base(abs)
		dst := filepath.Join(targetDir, name)
		if _, err := os.Stat(dst); err == nil {
			dst = filepath.Join(targetDir, uniqueName(targetDir, name))
		}
		if err := os.Rename(abs, dst); err != nil {
			if cerr := copyTree(abs, dst); cerr != nil {
				errs = append(errs, "导入失败: "+cerr.Error())
				continue
			}
			_ = os.RemoveAll(abs)
		}
		count++
	}
	writeJSON(w, 200, map[string]any{"ok": len(errs) == 0, "count": count, "errors": errs})
}

// handleVaultExport POST /api/vault/export {paths:[], names:[], dest}  从保险箱移出到存储库
func (s *Server) handleVaultExport(w http.ResponseWriter, r *http.Request) {
	if !vaultAuthed(r) {
		writeErr(w, 401, "保险箱未解锁")
		return
	}
	var req struct {
		Paths []string `json:"paths"`
		Names []string `json:"names"`
		Dest  string   `json:"dest"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib, destAbs, derr := s.cfg.Resolve(req.Dest)
	if derr != nil {
		writeErr(w, 400, "目标目录无效")
		return
	}
	if lib.ReadOnly {
		writeErr(w, 403, "目标存储库为只读")
		return
	}
	if info, err := os.Stat(destAbs); err != nil || !info.IsDir() {
		writeErr(w, 404, "目标目录不存在")
		return
	}
	list := append(append([]string{}, req.Paths...), req.Names...)
	count := 0
	errs := []string{}
	for _, rel := range list {
		src, err := s.vaultAbs(rel)
		if err != nil || src == filepath.Clean(s.vaultDir()) {
			continue
		}
		if _, serr := os.Stat(src); serr != nil {
			errs = append(errs, "不存在: "+rel)
			continue
		}
		name := filepath.Base(src)
		dst := filepath.Join(destAbs, name)
		if _, err := os.Stat(dst); err == nil {
			dst = filepath.Join(destAbs, uniqueName(destAbs, name))
		}
		if err := os.Rename(src, dst); err != nil {
			if cerr := copyTree(src, dst); cerr != nil {
				errs = append(errs, "导出失败: "+cerr.Error())
				continue
			}
			_ = os.RemoveAll(src)
		}
		count++
	}
	writeJSON(w, 200, map[string]any{"ok": len(errs) == 0, "count": count, "errors": errs})
}
