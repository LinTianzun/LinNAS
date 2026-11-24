package main

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Server ties config and handlers together.
type Server struct {
	cfg     *Config
	mux     *http.ServeMux
	index   *Index
	devices *deviceRegistry
}

// NewServer creates the HTTP mux with all routes registered.
func NewServer(cfg *Config) *Server {
	s := &Server{
		cfg:     cfg,
		mux:     http.NewServeMux(),
		devices: newDeviceRegistry(),
	}

	api := http.NewServeMux()
	api.HandleFunc("GET /api/health", s.handleHealth)
	api.HandleFunc("GET /api/libs", s.handleListLibs)
	api.HandleFunc("POST /api/libs", s.handleAddLib)
	api.HandleFunc("DELETE /api/libs", s.handleRemoveLib)
	api.HandleFunc("GET /api/disks", s.handleDisks)
	api.HandleFunc("GET /api/ls", s.handleList)
	api.HandleFunc("GET /api/size", s.handleSize)
	api.HandleFunc("GET /api/raw", s.handleRaw)
	api.HandleFunc("GET /api/stream", s.handleStream)
	api.HandleFunc("GET /api/thumb", s.handleThumb)
	api.HandleFunc("GET /api/search", s.handleSearch)
	api.HandleFunc("GET /api/media", s.handleMedia)
	api.HandleFunc("GET /api/index/status", s.handleIndexStatus)
	api.HandleFunc("POST /api/reindex", s.handleReindex)
	api.HandleFunc("POST /api/mkdir", s.handleMkdir)
	api.HandleFunc("POST /api/rename", s.handleRename)
	api.HandleFunc("POST /api/delete", s.handleDelete)
	api.HandleFunc("POST /api/upload/init", s.handleUploadInit)
	api.HandleFunc("GET /api/upload/status", s.handleUploadStatus)
	api.HandleFunc("POST /api/upload/chunk", s.handleUploadChunk)
	api.HandleFunc("POST /api/upload/complete", s.handleUploadComplete)
	api.HandleFunc("POST /api/upload/abort", s.handleUploadAbort)

	// 目录选择器 / 编辑存储库 / 文件移动复制
	api.HandleFunc("GET /api/fs/dirs", s.handleFsDirs)
	api.HandleFunc("POST /api/libs/update", s.handleLibUpdate)
	api.HandleFunc("POST /api/move", s.handleMove)
	api.HandleFunc("POST /api/copy", s.handleCopy)

	// 系统设置
	api.HandleFunc("GET /api/settings", s.handleSettingsInfo)
	api.HandleFunc("POST /api/settings", s.handleSettingsUpdate)

	// 隐私保险箱(隐藏目录 + 密码门禁)
	api.HandleFunc("GET /api/vault/status", s.handleVaultStatus)
	api.HandleFunc("POST /api/vault/setup", s.handleVaultSetup)
	api.HandleFunc("POST /api/vault/password", s.handleVaultChangePassword)
	api.HandleFunc("POST /api/vault/unlock", s.handleVaultUnlock)
	api.HandleFunc("POST /api/vault/lock", s.handleVaultLock)
	api.HandleFunc("GET /api/vault/ls", s.handleVaultList)
	api.HandleFunc("POST /api/vault/upload", s.handleVaultUpload)
	api.HandleFunc("GET /api/vault/raw", s.handleVaultRaw)
	api.HandleFunc("POST /api/vault/delete", s.handleVaultDelete)
	api.HandleFunc("POST /api/vault/mkdir", s.handleVaultMkdir)
	api.HandleFunc("POST /api/vault/import", s.handleVaultImport)
	api.HandleFunc("POST /api/vault/export", s.handleVaultExport)

	// 设备管理
	api.HandleFunc("GET /api/devices", s.handleDevices)
	api.HandleFunc("POST /api/devices/clear", s.handleDevicesClear)

	// 回收站
	api.HandleFunc("GET /api/trash", s.handleTrashList)
	api.HandleFunc("POST /api/trash/restore", s.handleTrashRestore)
	api.HandleFunc("POST /api/trash/delete", s.handleTrashDelete)
	api.HandleFunc("POST /api/trash/empty", s.handleTrashEmpty)

	s.mux.Handle("/api/", s.withToken(s.recordDevice(s.logRequests(api))))
	// 配对相关接口必须免 token(新设备还没有令牌),注册在更具体的路径上即可优先于 /api/
	s.mux.HandleFunc("GET /api/pair", s.handlePairInfo)
	s.mux.HandleFunc("GET /api/pair/qr", s.handlePairQR)
	s.mux.HandleFunc("GET /pair", s.handlePairPage)
	s.mux.Handle("/ui/", http.StripPrefix("/ui/", http.FileServer(http.FS(uiFS))))
	s.mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		http.Redirect(w, r, "/ui/", http.StatusFound)
	})
	return s
}

// Handler returns the root handler.
func (s *Server) Handler() http.Handler { return s.mux }

// ListenAddr formats host:port for net.Listen.
func (s *Server) ListenAddr() string {
	return net.JoinHostPort(s.cfg.Host, strconv.Itoa(s.cfg.Port))
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		lrw := &loggingResponseWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(lrw, r)
		log.Printf("%s %s %d %s from %s", r.Method, r.URL.Path, lrw.status,
			time.Since(start).Round(time.Millisecond), clientIP(r))
	})
}

type loggingResponseWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *loggingResponseWriter) WriteHeader(code int) {
	if !w.wrote {
		w.status = code
		w.wrote = true
	}
	w.ResponseWriter.WriteHeader(code)
}

// withToken enforces the shared token on all API routes (except health).
func (s *Server) withToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Token == "" || r.URL.Path == "/api/health" {
			next.ServeHTTP(w, r)
			return
		}
		got := r.Header.Get("X-Token")
		if got == "" {
			got = r.URL.Query().Get("token")
		}
		if got != s.cfg.Token {
			writeErr(w, http.StatusUnauthorized, "token 无效")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func clientIP(r *http.Request) string {
	if ip := r.Header.Get("X-Forwarded-For"); ip != "" {
		return strings.Split(ip, ",")[0]
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// ---- JSON helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 1<<20) // 1MB
	return json.NewDecoder(r.Body).Decode(dst)
}

// ---- handlers ----

// excluded reports whether abs sits inside an excluded sub-path of its library.
func (s *Server) excluded(lib *Library, abs string) bool {
	return s.cfg.ExcludedAbs(lib, abs)
}

// handleHealth returns basic service info.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"ok":      true,
		"version": Version,
		"name":    "linNas",
	})
}

func (s *Server) handleListLibs(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.cfg.Libraries)
}

type addLibReq struct {
	Name     string   `json:"name"`
	Path     string   `json:"path"`
	ReadOnly bool     `json:"readOnly"`
	Exclude  []string `json:"exclude"`
}

func (s *Server) handleAddLib(w http.ResponseWriter, r *http.Request) {
	var req addLibReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	req.Path = strings.TrimSpace(req.Path)
	req.Name = strings.TrimSpace(req.Name)
	if req.Path == "" {
		writeErr(w, 400, "路径不能为空")
		return
	}
	info, err := os.Stat(req.Path)
	if err != nil || !info.IsDir() {
		writeErr(w, 400, "路径不存在或不是目录")
		return
	}
	abs, err := filepath.Abs(req.Path)
	if err != nil {
		writeErr(w, 400, "路径无效")
		return
	}
	for _, l := range s.cfg.Libraries {
		if strings.EqualFold(filepath.Clean(l.Path), filepath.Clean(abs)) {
			writeErr(w, 409, "该路径已注册")
			return
		}
	}
	if req.Name == "" {
		req.Name = filepath.Base(abs)
	}
	lib := Library{
		ID:       nextLibID(s.cfg),
		Name:     req.Name,
		Path:     abs,
		ReadOnly: req.ReadOnly,
		Enabled:  true,
		Exclude:  cleanExcludes(req.Exclude),
	}
	s.cfg.Libraries = append(s.cfg.Libraries, lib)
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	if s.index != nil {
		s.index.ScanAll(s.cfg)
	}
	writeJSON(w, 200, lib)
}

func nextLibID(cfg *Config) string {
	used := map[string]bool{}
	for _, l := range cfg.Libraries {
		used[l.ID] = true
	}
	for i := 1; ; i++ {
		id := "lib" + strconv.Itoa(i)
		if !used[id] {
			return id
		}
	}
}

func (s *Server) handleRemoveLib(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	var keep []Library
	found := false
	for _, l := range s.cfg.Libraries {
		if l.ID == id {
			found = true
			continue
		}
		keep = append(keep, l)
	}
	if !found {
		writeErr(w, 404, "存储库不存在")
		return
	}
	s.cfg.Libraries = keep
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	if s.index != nil {
		s.index.ScanAll(s.cfg)
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// lsResp is the directory listing response.
type lsResp struct {
	Lib     Library       `json:"lib"`
	Path    string        `json:"path"`
	Entries []ListEntries `json:"entries"`
	Exists  bool          `json:"exists"`
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	vp := r.URL.Query().Get("vpath")
	lib, abs, err := s.cfg.Resolve(vp)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	resp := lsResp{Lib: *lib, Path: vp}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」访问")
		return
	}
	if s.excluded(lib, abs) {
		writeErr(w, 403, "该目录已被排除")
		return
	}
	info, statErr := os.Stat(abs)
	if statErr != nil || !info.IsDir() {
		resp.Exists = false
		writeJSON(w, 200, resp)
		return
	}
	resp.Exists = true
	ents, err := s.dirEntriesFiltered(lib, abs)
	if err != nil {
		writeErr(w, 500, "读取目录失败: "+err.Error())
		return
	}
	resp.Entries = ents
	writeJSON(w, 200, resp)
}

// handleRaw streams a file with full Range support (206) — the core of
// in-browser video playback with seeking.
func (s *Server) handleRaw(w http.ResponseWriter, r *http.Request) {
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
		writeErr(w, 403, "该路径已被排除")
		return
	}
	if s.inTrash(abs) {
		writeErr(w, 403, "回收站中的文件请通过「回收站」访问")
		return
	}
	info, err := os.Stat(abs)
	if err != nil {
		writeErr(w, 404, "文件不存在")
		return
	}
	if info.IsDir() {
		writeErr(w, 400, "这是目录")
		return
	}
	f, err := os.Open(abs)
	if err != nil {
		writeErr(w, 500, "打开文件失败: "+err.Error())
		return
	}
	defer f.Close()

	if r.URL.Query().Get("dl") != "" {
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(BaseVirtual(vp)))
	}
	w.Header().Set("Accept-Ranges", "bytes")
	// ServeContent handles Range/If-Range/206 automatically.
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

func urlPathEscape(s string) string {
	// minimal RFC 5987 filename* escaping; browsers handle encoded UTF-8
	var b strings.Builder
	for _, c := range []byte(s) {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '.' || c == '_' || c == '~' {
			b.WriteByte(c)
		} else {
			const hexd = "0123456789ABCDEF"
			b.WriteByte('%')
			b.WriteByte(hexd[c>>4])
			b.WriteByte(hexd[c&0xf])
		}
	}
	return b.String()
}

func (s *Server) handleMkdir(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath string `json:"vpath"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib, abs, err := s.cfg.Resolve(req.VPath)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」操作")
		return
	}
	if lib.ReadOnly {
		writeErr(w, 403, errReadOnly.Error())
		return
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		writeErr(w, 500, "创建目录失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]string{"vpath": req.VPath})
}

func (s *Server) handleRename(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath   string `json:"vpath"`
		NewName string `json:"newName"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if !SafeName(req.NewName) {
		writeErr(w, 400, "名称不合法")
		return
	}
	lib, abs, err := s.cfg.Resolve(req.VPath)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」操作")
		return
	}
	if lib.ReadOnly {
		writeErr(w, 403, errReadOnly.Error())
		return
	}
	dst := filepath.Join(filepath.Dir(abs), req.NewName)
	if _, err := os.Stat(dst); err == nil {
		writeErr(w, 409, "同名文件已存在")
		return
	}
	if err := os.Rename(abs, dst); err != nil {
		writeErr(w, 500, "重命名失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]string{"vpath": JoinVirtual(DirVirtual(req.VPath), req.NewName)})
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath     string `json:"vpath"`
		Recursive bool   `json:"recursive"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib, abs, err := s.cfg.Resolve(req.VPath)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(abs) {
		writeErr(w, 403, "请通过「隐私保险箱」操作")
		return
	}
	if lib.ReadOnly {
		writeErr(w, 403, errReadOnly.Error())
		return
	}
	if filepath.Clean(abs) == filepath.Clean(lib.Path) {
		writeErr(w, 400, "不能删除存储库根目录")
		return
	}
	info, err := os.Stat(abs)
	if err != nil {
		writeErr(w, 404, "文件不存在")
		return
	}
	if s.inTrash(abs) {
		writeErr(w, 403, "回收站中的文件请通过「回收站」访问")
		return
	}
	var derr error
	if info.IsDir() {
		// 移入回收站(同一存储库内),失败再彻底删除
		if _, terr := s.moveToTrash(lib, abs); terr != nil {
			derr = os.RemoveAll(abs)
		}
	} else {
		if _, terr := s.moveToTrash(lib, abs); terr != nil {
			derr = os.Remove(abs)
		}
	}
	if derr != nil {
		writeErr(w, 500, "删除失败: "+derr.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
