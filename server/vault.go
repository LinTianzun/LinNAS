package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
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
//  隐私保险箱:密码门禁 + 隐藏目录 + 静态加密
//  目录默认在存储库内的隐藏子目录 .linNasVault,支持多级文件夹;
//  访问需要密码解锁后拿到的会话 token(默认 2 小时过期)。
//  解锁会话同时持有主密钥 VMK(只在内存),文件以 .lvault 容器加密落盘,
//  密钥管理与容器格式见 vault_keystore.go / vault_container.go。
// ============================================================

const vaultSessionTTL = 2 * time.Hour

type vaultState struct {
	mu       sync.Mutex
	sessions map[string]int64  // token -> 过期 unix 秒
	keys     map[string][]byte // token -> 32 字节 VMK(仅内存)
}

var vault = &vaultState{sessions: map[string]int64{}, keys: map[string][]byte{}}

// pruneVaultSessions 清理已过期的保险箱解锁会话(连同主密钥),返回清理数量。
func pruneVaultSessions() int {
	now := time.Now().Unix()
	vault.mu.Lock()
	defer vault.mu.Unlock()
	removed := 0
	for tok, exp := range vault.sessions {
		if exp < now {
			delete(vault.sessions, tok)
			if key, ok := vault.keys[tok]; ok {
				zeroKeyBytes(key)
				delete(vault.keys, tok)
			}
			removed++
		}
	}
	return removed
}

// vaultConfig 是 DataDir/vault.json 的内容。
// v2：WrappedKey/KeyNonce 保存被 KEK 包裹的随机主密钥 VMK；
// v1(遗留)：Hash 字段就是 PBKDF2 派生值本身,解锁时会自动升级并清除。
type vaultConfig struct {
	Version    int    `json:"version,omitempty"`
	Salt       string `json:"salt"`
	Iter       int    `json:"iter"`
	Hash       string `json:"hash,omitempty"`
	WrappedKey string `json:"wrappedKey,omitempty"`
	KeyNonce   string `json:"keyNonce,omitempty"`
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
	tok := vaultToken(r)
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
		if key, has := vault.keys[tok]; has {
			zeroKeyBytes(key)
			delete(vault.keys, tok)
		}
		return false
	}
	return true
}

// vaultRequestKey 校验解锁会话并返回主密钥;失败时已写好 401 响应。
func (s *Server) vaultRequestKey(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	vmk, ok := vaultKeyForToken(vaultToken(r))
	if !ok {
		writeErr(w, 401, "保险箱未解锁")
		return nil, false
	}
	return vmk, true
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
	vc, err := newVaultConfig(req.Password)
	if err != nil {
		writeErr(w, 500, "生成保险箱密钥失败: "+err.Error())
		return
	}
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
	_ = hideVaultPath(s.vaultDir())
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handleVaultChangePassword POST /api/vault/password {oldPassword,newPassword}
// 信封加密:只重新包裹主密钥 VMK,不重写任何 .lvault 文件。
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
	vmk, _, uerr := vc.unlockVaultKey(req.OldPassword)
	if uerr != nil {
		if errors.Is(uerr, errVaultPassword) {
			writeErr(w, 403, "原密码不正确")
			return
		}
		writeErr(w, 500, "保险箱密钥不可用")
		return
	}
	defer zeroKeyBytes(vmk)
	next, cerr := newVaultConfigFor(vmk, req.NewPassword)
	if cerr != nil {
		writeErr(w, 500, "生成新密钥失败")
		return
	}
	if err := s.writeVaultCfg(next); err != nil {
		writeErr(w, 500, "保存失败")
		return
	}
	clearVaultSessions() // 改密码后所有会话失效,需要用新密码重新解锁
	resetVaultThumbCache()
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
	vmk, next, uerr := vc.unlockVaultKey(req.Password)
	if uerr != nil {
		if errors.Is(uerr, errVaultPassword) {
			writeErr(w, 403, "密码不正确")
			return
		}
		writeErr(w, 500, "保险箱密钥配置损坏")
		return
	}
	defer zeroKeyBytes(vmk)
	if next != nil {
		// v1 旧配置:换成随机 VMK 并清除明文派生值。
		if err := s.writeVaultCfg(next); err != nil {
			writeErr(w, 500, "升级保险箱密钥配置失败: "+err.Error())
			return
		}
	}
	tok := randHex(24)
	exp := time.Now().Add(vaultSessionTTL).Unix()
	setVaultSession(tok, exp, vmk)
	_ = hideVaultPath(s.vaultDir())
	writeJSON(w, 200, map[string]any{"token": tok, "expires": exp})
}

// handleVaultLock POST /api/vault/lock {token}
func (s *Server) handleVaultLock(w http.ResponseWriter, r *http.Request) {
	tok := vaultToken(r)
	if tok == "" {
		var req struct {
			Token string `json:"token"`
		}
		_ = readJSON(r, &req)
		tok = req.Token
	}
	dropVaultSession(tok)
	resetVaultThumbCache() // 锁定后不再保留解密缩略图
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// vaultEntry describes one item in the vault.
type vaultEntry struct {
	Name      string `json:"name"`
	Path      string `json:"path"` // 相对保险箱根的路径(/分隔),显示名不含 .lvault
	Size      int64  `json:"size"`
	ModTime   int64  `json:"modTime"`
	IsDir     bool   `json:"isDir"`
	Encrypted bool   `json:"encrypted,omitempty"` // false = 旧明文文件(未迁移)
}

// handleVaultList GET /api/vault/ls?vt=&path=
// 文件大小取容器头里的明文长度;v2 容器从 header 解出显示名(磁盘名是 token)。
func (s *Server) handleVaultList(w http.ResponseWriter, r *http.Request) {
	mk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(mk)
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
	type listed struct {
		entry vaultEntry
		raw   string
		enc   bool
	}
	items := make([]listed, 0, len(ents))
	displayCount := map[string]int{}
	for _, e := range ents {
		info, ierr := e.Info()
		if ierr != nil {
			continue
		}
		it := listed{raw: e.Name()}
		if e.IsDir() {
			it.entry = vaultEntry{Name: e.Name(), ModTime: info.ModTime().Unix(), IsDir: true}
		} else {
			full := filepath.Join(abs, e.Name())
			display, size, encrypted := vaultEntryInfo(mk, full, e.Name(), info)
			it.enc = encrypted
			it.entry = vaultEntry{Name: display, Size: size, ModTime: info.ModTime().Unix(), Encrypted: encrypted}
		}
		displayCount[it.entry.Name]++
		items = append(items, it)
	}
	out := make([]vaultEntry, 0, len(items))
	for _, it := range items {
		display := it.entry.Name
		// 极端情况:显示名与其他条目撞车(旧明文 x 与容器并存)。
		// 此时加密项退回磁盘名,保证列表里的路径仍能定位到文件。
		if it.enc && displayCount[display] > 1 {
			display = it.raw
		}
		it.entry.Name = display
		it.entry.Path = joinRel(rel, display)
		out = append(out, it.entry)
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
	_ = hideVaultPath(abs)
	writeJSON(w, 200, map[string]string{"path": req.Path})
}

// handleVaultUpload POST /api/vault/upload?name=&path=&vt=  (原始字节流;path=目标文件夹)
// 明文字节流按 64KB 分块加密,落盘为 <name>.lvault。
func (s *Server) handleVaultUpload(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
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
	display := s.vaultUniqueDisplay(dirAbs, vmk, name)
	dst := s.vaultFilePath(vmk, dirAbs, display)
	n, cerr := writeEncryptedStreamNamed(dst, r.Body, vmk, display)
	if cerr != nil {
		_ = os.Remove(dst)
		writeErr(w, 500, "加密写入失败: "+cerr.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"name": display, "path": joinRel(relDir, display), "size": n})
}

// handleVaultRaw GET /api/vault/raw?path=&vt=  支持 Range(视频可拖动)
func (s *Server) handleVaultRaw(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
	rel := r.URL.Query().Get("path")
	if rel == "" {
		rel = r.URL.Query().Get("name") // 兼容旧参数
	}
	abs, encrypted, disp, err := s.vaultLookup(rel, vmk)
	if err != nil {
		writeErr(w, 404, "文件不存在")
		return
	}
	info, ierr := os.Stat(abs)
	if ierr != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	if r.URL.Query().Get("dl") != "" {
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(disp))
	}
	w.Header().Set("Accept-Ranges", "bytes")
	if encrypted {
		ef, oerr := openEncryptedFile(abs, vmk)
		if oerr != nil {
			writeErr(w, 500, "解密失败: "+oerr.Error())
			return
		}
		defer ef.Close()
		http.ServeContent(w, r, disp, info.ModTime(), ef)
		return
	}
	// 兼容旧明文文件(M3 迁移前):直接按文件流读取。
	f, oerr := os.Open(abs)
	if oerr != nil {
		writeErr(w, 500, "打开失败")
		return
	}
	defer f.Close()
	http.ServeContent(w, r, disp, info.ModTime(), f)
}

// handleVaultDelete POST /api/vault/delete {paths:[], names:[]}
func (s *Server) handleVaultDelete(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
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
		abs, _, _, err := s.vaultLookup(rel, vmk)
		if err != nil || filepath.Clean(abs) == filepath.Clean(s.vaultDir()) {
			continue
		}
		if err := os.RemoveAll(abs); err == nil {
			count++
		}
	}
	writeJSON(w, 200, map[string]any{"ok": true, "count": count})
}

// handleVaultImport POST /api/vault/import {vpaths:[], dest}
// 把存储库里的文件/文件夹加密移入保险箱指定子目录(源文件随后删除)。
func (s *Server) handleVaultImport(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
	var req struct {
		VPaths []string `json:"vpaths"`
		Dest   string   `json:"dest"` // 相对保险箱的目标文件夹(""=根)
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
	var indexChanges []IndexChange
	for _, vp := range req.VPaths {
		lib, abs, rerr := s.cfg.Resolve(vp)
		if rerr != nil {
			errs = append(errs, rerr.Error())
			continue
		}
		// 只读存储库不允许被移出(导入等价于删除源文件),与导出时的只读校验保持一致
		if lib.ReadOnly {
			errs = append(errs, "源存储库为只读: "+lib.Name)
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
		if _, err := s.vaultImportOne(vmk, abs, targetDir); err != nil {
			errs = append(errs, "导入失败: "+err.Error())
			continue
		}
		indexChanges = append(indexChanges, IndexChange{Remove: vp})
		count++
	}
	s.indexUpdate(indexChanges...)
	s.hideVaultTree(destAbs)
	writeJSON(w, 200, map[string]any{"ok": len(errs) == 0, "count": count, "errors": errs})
}

// handleVaultExport POST /api/vault/export {paths:[], names:[], dest}
// 从保险箱解密移出到存储库(旧的明文文件原样搬出)。
func (s *Server) handleVaultExport(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
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
	var indexChanges []IndexChange
	for _, rel := range list {
		src, _, _, err := s.vaultLookup(rel, vmk)
		if err != nil || filepath.Clean(src) == filepath.Clean(s.vaultDir()) {
			continue
		}
		if _, serr := os.Stat(src); serr != nil {
			errs = append(errs, "不存在: "+rel)
			continue
		}
		dst, xerr := s.vaultExportOne(vmk, src, destAbs)
		if xerr != nil {
			errs = append(errs, "导出失败: "+xerr.Error())
			continue
		}
		indexChanges = append(indexChanges, IndexChange{Add: VirtualPath(lib, dst)})
		count++
	}
	s.indexUpdate(indexChanges...)
	writeJSON(w, 200, map[string]any{"ok": len(errs) == 0, "count": count, "errors": errs})
}
