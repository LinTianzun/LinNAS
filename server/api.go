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

	"golang.org/x/net/webdav"
)

// Server ties config and handlers together.
type Server struct {
	cfg      *Config
	mux      *http.ServeMux
	index    *Index
	uploads  *uploadSessionStore
	davLocks webdav.LockSystem
	devices  *deviceRegistry
	auth     *authStore
	audit    *auditLog
	shares   *shareStore
	dup      *dupScanState
}

// NewServer creates the HTTP mux with all routes registered.
func NewServer(cfg *Config) *Server {
	s := &Server{
		cfg:      cfg,
		mux:      http.NewServeMux(),
		uploads:  newUploadSessionStore(),
		davLocks: webdav.NewMemLS(),
		devices:  newDeviceRegistry(),
		auth:     newAuthStore(cfg.DataDir, cfg.IdleLockMinutes),
		audit:    newAuditLog(cfg.DataDir),
		shares:   newShareStore(cfg.DataDir),
		dup:      newDupScanState(),
	}

	api := http.NewServeMux()
	api.HandleFunc("GET /api/health", s.handleHealth)
	api.HandleFunc("GET /api/libs", s.handleListLibs)
	api.HandleFunc("POST /api/libs", s.handleAddLib)
	api.HandleFunc("DELETE /api/libs", s.handleRemoveLib)
	api.HandleFunc("GET /api/disks", s.handleDisks)
	// 存储空间分析:汇总 / 大文件 / 重复文件候选
	api.HandleFunc("GET /api/storage/summary", s.handleStorageSummary)
	api.HandleFunc("GET /api/storage/large", s.handleStorageLarge)
	api.HandleFunc("POST /api/storage/duplicates/scan", s.handleStorageDupScan)
	api.HandleFunc("GET /api/storage/duplicates", s.handleStorageDupStatus)
	api.HandleFunc("GET /api/ls", s.handleList)
	api.HandleFunc("GET /api/size", s.handleSize)
	api.HandleFunc("GET /api/info", s.handleInfo)
	api.HandleFunc("GET /api/subtitle", s.handleSubtitleList)
	api.HandleFunc("GET /api/subtitle/content", s.handleSubtitleContent)
	api.HandleFunc("GET /api/raw", s.handleRaw)
	api.HandleFunc("GET /api/stream", s.handleStream)
	api.HandleFunc("GET /api/thumb", s.handleThumb)
	api.HandleFunc("GET /api/search", s.handleSearch)
	api.HandleFunc("GET /api/media", s.handleMedia)
	api.HandleFunc("GET /api/index/status", s.handleIndexStatus)
	api.HandleFunc("POST /api/reindex", s.handleReindex)
	api.HandleFunc("POST /api/index/refresh", s.handleIndexRefresh)
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
	api.HandleFunc("POST /api/libs/media-hide", s.handleMediaHide)
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

	// 安全:多用户 / 会话 / 操作日志 / 分享链接
	api.HandleFunc("GET /api/auth/status", s.handleAuthStatus)
	api.HandleFunc("POST /api/auth/login", s.handleAuthLogin)
	api.HandleFunc("GET /api/auth/me", s.handleAuthMe)
	api.HandleFunc("POST /api/auth/logout", s.handleAuthLogout)
	api.HandleFunc("GET /api/auth/sessions", s.handleAuthSessions)
	api.HandleFunc("POST /api/auth/revoke", s.handleAuthRevoke)
	api.HandleFunc("GET /api/users", s.handleUsersList)
	api.HandleFunc("POST /api/users", s.handleUsersCreate)
	api.HandleFunc("DELETE /api/users", s.handleUsersDelete)
	api.HandleFunc("POST /api/users/update", s.handleUsersUpdate)
	api.HandleFunc("POST /api/users/password", s.handleUsersPassword)
	api.HandleFunc("GET /api/audit", s.handleAuditList)
	api.HandleFunc("POST /api/shares", s.handleSharesCreate)
	api.HandleFunc("GET /api/shares", s.handleSharesList)
	api.HandleFunc("DELETE /api/shares", s.handleSharesDelete)

	// 远程访问(Tailscale)与开机自启
	api.HandleFunc("GET /api/remote", s.handleRemoteInfo)
	api.HandleFunc("POST /api/remote/serve", s.handleRemoteServe)
	api.HandleFunc("GET /api/autostart", s.handleAutostartGet)
	api.HandleFunc("POST /api/autostart", s.handleAutostartSet)

	// 回收站
	api.HandleFunc("GET /api/trash", s.handleTrashList)
	api.HandleFunc("GET /api/trash/thumb", s.handleTrashThumb)
	api.HandleFunc("POST /api/trash/restore", s.handleTrashRestore)
	api.HandleFunc("POST /api/trash/delete", s.handleTrashDelete)
	api.HandleFunc("POST /api/trash/empty", s.handleTrashEmpty)

	// 维护(立即清理回收站/转码缓存,管理员)
	api.HandleFunc("POST /api/maintenance/cleanup", s.handleMaintenanceCleanup)

	// 打包下载(文件夹/多选 → zip 流)
	api.HandleFunc("GET /api/zip", s.handleZip)

	// Android 客户端在线更新(v1.8.0)
	api.HandleFunc("GET /api/app/version", s.handleAppVersion)
	api.HandleFunc("GET /api/app/apk", s.handleAppApk)

	// Wake-on-LAN 网络唤醒
	api.HandleFunc("GET /api/wol", s.handleWolList)
	api.HandleFunc("POST /api/wol", s.handleWolAdd)
	api.HandleFunc("DELETE /api/wol", s.handleWolDelete)
	api.HandleFunc("POST /api/wol/send", s.handleWolSend)

	// 移动热点(手机不在同一网络时,由电脑开热点让手机接入)
	api.HandleFunc("GET /api/hotspot", s.handleHotspotStatus)
	api.HandleFunc("POST /api/hotspot", s.handleHotspotAction)
	// 任意文本二维码(热点 Wi-Fi 扫码加入等)
	api.HandleFunc("GET /api/qr", s.handleQRText)
	// 在服务端所在电脑打开系统设置页
	api.HandleFunc("POST /api/system/open", s.handleOpenSettings)

	s.mux.Handle("/api/", s.withToken(s.auditLog(s.recordDevice(s.logRequests(api)))))
	s.mux.Handle("/dav/", s.withWebDAVAuth(s.auditWebDAV(s.recordDevice(s.logRequests(http.HandlerFunc(s.handleWebDAV))))))
	// 分享链接公开访问(免登录),更具体的路径优先于 /api/
	s.mux.HandleFunc("GET /api/share/{id}", s.handleShareInfo)
	s.mux.HandleFunc("GET /api/share/{id}/raw", s.handleShareRaw)
	s.mux.HandleFunc("GET /s/{id}", s.handleSharePage)
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

// withToken enforces authentication and role-based authorization.
// 未配置任何用户且未设口令时开放访问(局域网单机默认行为)。
// 否则接受两种凭据:全局访问口令(视为 admin 兜底)或用户登录会话 token。
func (s *Server) withToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == "/api/health" || p == "/api/auth/status" || p == "/api/auth/login" {
			next.ServeHTTP(w, r)
			return
		}
		if s.cfg.Token == "" && !s.auth.MultiUser() {
			next.ServeHTTP(w, r)
			return
		}
		got := r.Header.Get("X-Token")
		if got == "" {
			got = r.URL.Query().Get("token")
		}
		var sess *Session
		if s.cfg.Token != "" && got == s.cfg.Token {
			sess = &Session{Username: "admin", Role: "admin", Token: got}
		} else if ss := s.auth.Get(got); ss != nil {
			sess = ss
		}
		if sess == nil {
			writeErr(w, http.StatusUnauthorized, "未登录或登录已过期")
			return
		}
		if !s.authorize(sess, r) {
			writeErr(w, http.StatusForbidden, "无权限执行此操作")
			return
		}
		next.ServeHTTP(w, r.WithContext(withUser(r.Context(), sess)))
	})
}

// authorize 根据角色限制访问:admin 全部;user 可读写文件但不能改系统/用户;viewer 只读。
func (s *Server) authorize(sess *Session, r *http.Request) bool {
	role := sess.Role
	if role == "admin" {
		return true
	}
	p := r.URL.Path
	m := r.Method
	// 任何登录用户都可以修改自己的密码
	if p == "/api/users/password" {
		return true
	}
	// 管理员专属
	if strings.HasPrefix(p, "/api/users") || strings.HasPrefix(p, "/api/audit") {
		return false
	}
	if p == "/api/settings" && m != "GET" {
		return false
	}
	if (p == "/api/libs" || p == "/api/libs/update") && m != "GET" {
		return false
	}
	if p == "/api/devices/clear" {
		return false
	}
	// 远程访问与开机自启属于系统级设置,只有管理员能动
	if (p == "/api/remote/serve" || p == "/api/autostart") && m != "GET" {
		return false
	}
	// 维护操作(清理回收站/转码缓存)仅管理员
	if p == "/api/maintenance/cleanup" {
		return false
	}
	// Wake-on-LAN 的增删与发送属于系统级操作,仅管理员
	if strings.HasPrefix(p, "/api/wol") && m != "GET" {
		return false
	}
	// 只读角色禁止一切写操作(读、登录、退出、解锁除外)
	if role == "viewer" && (m == "POST" || m == "DELETE") {
		switch {
		case strings.HasPrefix(p, "/api/auth"):
			return true
		case p == "/api/vault/unlock" || p == "/api/vault/lock":
			return true
		}
		return false
	}
	return true
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
		s.index.RescanLib(s.cfg, lib.ID)
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
		s.index.RemoveLibRows(id)
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
	s.indexUpdate(IndexChange{Add: req.VPath})
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
	newVPath := JoinVirtual(DirVirtual(req.VPath), req.NewName)
	s.indexUpdate(IndexChange{Remove: req.VPath}, IndexChange{Add: newVPath})
	writeJSON(w, 200, map[string]string{"vpath": newVPath})
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
	s.indexUpdate(IndexChange{Remove: req.VPath})
	writeJSON(w, 200, map[string]bool{"ok": true})
}
