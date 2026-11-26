package main

// ============================================================
//  D 安全:多用户 / 会话与自动锁定 / 操作日志 / 分享链接
//  设计要点:
//   - 未配置任何用户时,保持原有「全局访问口令」单用户行为不变。
//   - 一旦创建用户(users.json 非空),进入多用户模式:需登录拿会话 token,
//     会话闲置超时即视为「自动锁定」,再次访问需重新登录。
//   - 角色:admin(全部)/ user(可读写文件,不能改系统设置与用户)/ viewer(只读)。
//   - 全局访问口令(若设置)始终视为 admin,便于主人在忘记账号时兜底。
// ============================================================

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------------- 用户 ----------------

type User struct {
	ID        string `json:"id"`
	Username  string `json:"username"`
	Salt      string `json:"salt"`
	Hash      string `json:"hash"`
	Role      string `json:"role"` // admin | user | viewer
	CreatedAt int64  `json:"createdAt"`
	Disabled  bool   `json:"disabled"`
}

type usersDoc struct {
	Users []User `json:"users"`
}

// Session 是一个已登录会话(闲置超时后失效 = 自动锁定)。
// 会话会持久化到 DataDir/sessions.json,服务重启后仍然有效(不必重新登录)。
type Session struct {
	Token    string `json:"token"`
	UserID   string `json:"userId"`
	Username string `json:"username"`
	Role     string `json:"role"`
	IP       string `json:"ip"`
	Created  int64  `json:"created"`
	LastSeen int64  `json:"lastSeen"`
}

// sessionsDoc 是 sessions.json 的落盘结构。
type sessionsDoc struct {
	Sessions []Session `json:"sessions"`
}

// 登录防爆破:按「用户名 + 来源 IP」统计连续失败次数,超过阈值后指数退避锁定。
const (
	bruteMaxFails = 5                // 连续失败多少次后开始锁定
	bruteBaseLock = 30 * time.Second // 首次锁定时长,之后每次翻倍
	bruteMaxLock  = 15 * time.Minute // 单次锁定上限
	bruteIdleGC   = time.Hour        // 超过这么久没有新失败的记录可以清理
)

// bruteState 记录一个「用户名 + IP」的连续登录失败情况。
type bruteState struct {
	fails     int
	lockUntil time.Time
	last      time.Time
}

// LockoutError 表示登录被临时锁定。
type LockoutError struct {
	RetryAfter int // 剩余秒数
}

func (e *LockoutError) Error() string {
	return fmt.Sprintf("尝试次数过多,请在 %d 秒后重试", e.RetryAfter)
}

type authStore struct {
	mu          sync.Mutex
	users       []User
	sessions    map[string]*Session
	fails       map[string]*bruteState
	path        string
	sessPath    string
	sessSavedAt int64 // 上次把会话落盘的时间(Unix 秒),用于限流写盘
	idle        time.Duration
}

func newAuthStore(dataDir string, idleMinutes int) *authStore {
	a := &authStore{
		sessions: map[string]*Session{},
		fails:    map[string]*bruteState{},
		path:     filepath.Join(dataDir, "users.json"),
		sessPath: filepath.Join(dataDir, "sessions.json"),
	}
	if idleMinutes <= 0 {
		idleMinutes = 30
	}
	a.idle = time.Duration(idleMinutes) * time.Minute
	a.load()
	a.loadSessions()
	return a
}

func (a *authStore) load() {
	raw, err := os.ReadFile(a.path)
	if err != nil {
		return
	}
	var doc usersDoc
	if json.Unmarshal(raw, &doc) == nil {
		a.users = doc.Users
	}
}

func (a *authStore) saveLocked() error {
	raw, err := json.MarshalIndent(usersDoc{Users: a.users}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(a.path, raw, 0o600)
}

// loadSessions 把上次运行时的会话读回内存,让服务重启后客户端不必重新登录。
// 只有仍存在且未被禁用的用户会话会被恢复;停机时长不计入闲置超时(恢复时把 LastSeen 置为当前时间)。
func (a *authStore) loadSessions() {
	raw, err := os.ReadFile(a.sessPath)
	if err != nil {
		return
	}
	var doc sessionsDoc
	if json.Unmarshal(raw, &doc) != nil {
		return
	}
	now := time.Now().Unix()
	for _, s := range doc.Sessions {
		if s.Token == "" || s.UserID == "" {
			continue
		}
		u := a.findByID(s.UserID)
		if u == nil || u.Disabled {
			continue
		}
		sess := s
		sess.Username = u.Username
		sess.Role = u.Role
		sess.LastSeen = now
		a.sessions[sess.Token] = &sess
	}
	a.sessSavedAt = now
}

// saveSessionsLocked 把当前会话写盘(调用方需持有 a.mu)。忽略写盘错误,不影响登录。
func (a *authStore) saveSessionsLocked() {
	if a.sessPath == "" {
		return
	}
	list := make([]Session, 0, len(a.sessions))
	for _, s := range a.sessions {
		list = append(list, *s)
	}
	raw, err := json.MarshalIndent(sessionsDoc{Sessions: list}, "", "  ")
	if err != nil {
		return
	}
	if os.WriteFile(a.sessPath, raw, 0o600) == nil {
		a.sessSavedAt = time.Now().Unix()
	}
}

func (a *authStore) MultiUser() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.users) > 0
}

func (a *authStore) UserCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.users)
}

func subtleEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func hashPassword(pw string) (salt, hash string) {
	salt = randHex(16)
	key := pbkdf2Key([]byte(pw), []byte(salt), 120000, 32)
	return salt, hex.EncodeToString(key)
}

func checkPassword(u *User, pw string) bool {
	key := pbkdf2Key([]byte(pw), []byte(u.Salt), 120000, 32)
	return subtleEqual(hex.EncodeToString(key), u.Hash)
}

func (a *authStore) findByName(name string) *User {
	for i := range a.users {
		if strings.EqualFold(a.users[i].Username, name) {
			return &a.users[i]
		}
	}
	return nil
}

func (a *authStore) findByID(id string) *User {
	for i := range a.users {
		if a.users[i].ID == id {
			return &a.users[i]
		}
	}
	return nil
}

func (a *authStore) nextUserIDLocked() string {
	used := map[string]bool{}
	for _, u := range a.users {
		used[u.ID] = true
	}
	for i := 1; ; i++ {
		id := "u" + itoa(i)
		if !used[id] {
			return id
		}
	}
}

func (a *authStore) CreateUser(username, pw, role string) (*User, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	username = strings.TrimSpace(username)
	if username == "" {
		return nil, fmt.Errorf("用户名不能为空")
	}
	if len(pw) < 4 {
		return nil, fmt.Errorf("密码至少 4 位")
	}
	if a.findByName(username) != nil {
		return nil, fmt.Errorf("用户名已存在")
	}
	if role != "admin" && role != "user" && role != "viewer" {
		role = "user"
	}
	salt, hash := hashPassword(pw)
	u := User{ID: a.nextUserIDLocked(), Username: username, Salt: salt, Hash: hash, Role: role, CreatedAt: time.Now().Unix()}
	a.users = append(a.users, u)
	if err := a.saveLocked(); err != nil {
		return nil, err
	}
	return &u, nil
}

func (a *authStore) SetPassword(id, pw string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(pw) < 4 {
		return fmt.Errorf("密码至少 4 位")
	}
	u := a.findByID(id)
	if u == nil {
		return fmt.Errorf("用户不存在")
	}
	u.Salt, u.Hash = hashPassword(pw)
	return a.saveLocked()
}

func (a *authStore) SetRole(id, role string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	u := a.findByID(id)
	if u == nil {
		return fmt.Errorf("用户不存在")
	}
	if role != "admin" && role != "user" && role != "viewer" {
		return fmt.Errorf("角色无效")
	}
	u.Role = role
	return a.saveLocked()
}

func (a *authStore) DeleteUser(id string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	u := a.findByID(id)
	if u == nil {
		return fmt.Errorf("用户不存在")
	}
	// 不允许删除最后一个管理员
	if u.Role == "admin" {
		admins := 0
		for _, x := range a.users {
			if x.Role == "admin" {
				admins++
			}
		}
		if admins <= 1 {
			return fmt.Errorf("至少需要保留一个管理员")
		}
	}
	out := a.users[:0]
	for _, x := range a.users {
		if x.ID != id {
			out = append(out, x)
		}
	}
	a.users = out
	// 让该用户的会话失效
	for tok, s := range a.sessions {
		if s.UserID == id {
			delete(a.sessions, tok)
		}
	}
	a.saveSessionsLocked()
	return a.saveLocked()
}

// List 返回脱敏的用户列表。
func (a *authStore) List() []map[string]any {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]map[string]any, 0, len(a.users))
	for _, u := range a.users {
		out = append(out, map[string]any{
			"id": u.ID, "username": u.Username, "role": u.Role,
			"createdAt": u.CreatedAt, "disabled": u.Disabled,
		})
	}
	return out
}

// Login 校验用户名密码并创建会话。ip 用于登录防爆破计数。
func (a *authStore) Login(username, pw, ip string) (*Session, error) {
	key := bruteKey(username, ip)
	a.mu.Lock()
	defer a.mu.Unlock()
	if st := a.fails[key]; st != nil && time.Now().Before(st.lockUntil) {
		return nil, &LockoutError{RetryAfter: int(time.Until(st.lockUntil).Seconds()) + 1}
	}
	s, ok := a.authenticateLocked(username, pw)
	if !ok {
		a.noteFailLocked(key)
		if st := a.fails[key]; st != nil && time.Now().Before(st.lockUntil) {
			return nil, &LockoutError{RetryAfter: int(time.Until(st.lockUntil).Seconds()) + 1}
		}
		return nil, fmt.Errorf("用户名或密码错误")
	}
	delete(a.fails, key)
	s.Token = randHex(24)
	s.IP = ip
	s.Created = time.Now().Unix()
	s.LastSeen = s.Created
	a.sessions[s.Token] = s
	a.saveSessionsLocked()
	return s, nil
}

// Authenticate 只校验凭据、不创建会话(WebDAV Basic 认证用)。
func (a *authStore) Authenticate(username, pw, ip string) (*Session, bool) {
	key := bruteKey(username, ip)
	a.mu.Lock()
	defer a.mu.Unlock()
	if st := a.fails[key]; st != nil && time.Now().Before(st.lockUntil) {
		return nil, false
	}
	s, ok := a.authenticateLocked(username, pw)
	if !ok {
		a.noteFailLocked(key)
		return nil, false
	}
	delete(a.fails, key)
	return s, true
}

// bruteKey 拼出「用户名 + IP」计数键(用户名不区分大小写,避免绕过)。
func bruteKey(username, ip string) string {
	return strings.ToLower(strings.TrimSpace(username)) + "\x00" + strings.TrimSpace(ip)
}

// noteFailLocked 记录一次失败并按需锁定(调用方需持有 a.mu)。
func (a *authStore) noteFailLocked(key string) {
	now := time.Now()
	// 顺带清理长时间无活动的记录,避免 map 无限增长
	for k, st := range a.fails {
		if now.Sub(st.last) > bruteIdleGC && now.After(st.lockUntil) {
			delete(a.fails, k)
		}
	}
	st := a.fails[key]
	if st == nil {
		st = &bruteState{}
		a.fails[key] = st
	}
	st.fails++
	st.last = now
	if st.fails >= bruteMaxFails {
		over := st.fails - bruteMaxFails
		lock := bruteBaseLock << uint(over) // 30s, 60s, 120s ... 指数退避
		if lock > bruteMaxLock || lock <= 0 {
			lock = bruteMaxLock
		}
		st.lockUntil = now.Add(lock)
	}
}

// LockRemaining 返回某「用户名 + IP」还要等多少秒才能再试(0 = 未锁定)。
func (a *authStore) LockRemaining(username, ip string) int {
	a.mu.Lock()
	defer a.mu.Unlock()
	st := a.fails[bruteKey(username, ip)]
	if st == nil || time.Now().After(st.lockUntil) {
		return 0
	}
	return int(time.Until(st.lockUntil).Seconds()) + 1
}

func (a *authStore) authenticateLocked(username, pw string) (*Session, bool) {
	u := a.findByName(username)
	if u == nil || u.Disabled || !checkPassword(u, pw) {
		return nil, false
	}
	return &Session{UserID: u.ID, Username: u.Username, Role: u.Role}, true
}

func (a *authStore) Get(tok string) *Session {
	if tok == "" {
		return nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	s, ok := a.sessions[tok]
	if !ok {
		return nil
	}
	now := time.Now()
	if now.Sub(time.Unix(s.LastSeen, 0)) > a.idle {
		delete(a.sessions, tok)
		a.saveSessionsLocked()
		return nil
	}
	s.LastSeen = now.Unix()
	if u := a.findByID(s.UserID); u != nil {
		s.Role = u.Role
		s.Username = u.Username
	}
	// 限流:最多每 60 秒把 LastSeen 落盘一次,避免每个请求都写文件。
	if now.Unix()-a.sessSavedAt >= 60 {
		a.saveSessionsLocked()
	}
	return s
}

func (a *authStore) Logout(tok string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.sessions, tok)
	a.saveSessionsLocked()
}

// SessionInfo 是「活跃会话」列表里的一行(不暴露完整 token)。
type SessionInfo struct {
	ID       string `json:"id"` // token 前 6 位,用于吊销
	UserID   string `json:"userId"`
	Username string `json:"username"`
	Role     string `json:"role"`
	IP       string `json:"ip"`
	Created  int64  `json:"created"`
	LastSeen int64  `json:"lastSeen"`
	Current  bool   `json:"current"`
}

// Sessions 列出当前活跃会话(闲置超时的会顺带清掉)。callerToken 用于标记「当前会话」。
func (a *authStore) Sessions(callerToken string) []SessionInfo {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now()
	out := make([]SessionInfo, 0, len(a.sessions))
	for tok, s := range a.sessions {
		if now.Sub(time.Unix(s.LastSeen, 0)) > a.idle {
			delete(a.sessions, tok)
			continue
		}
		info := SessionInfo{
			ID: tok[:6], UserID: s.UserID, Username: s.Username, Role: s.Role,
			IP: s.IP, Created: s.Created, LastSeen: s.LastSeen, Current: tok == callerToken,
		}
		if u := a.findByID(s.UserID); u != nil {
			info.Username = u.Username
			info.Role = u.Role
		}
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastSeen > out[j].LastSeen })
	return out
}

// Revoke 按 token 前缀吊销会话。非管理员只能吊销自己名下的会话。
// 返回被吊销的会话数量。
func (a *authStore) Revoke(idPrefix string, caller *Session) (int, error) {
	idPrefix = strings.TrimSpace(idPrefix)
	if len(idPrefix) < 6 {
		return 0, fmt.Errorf("会话标识无效")
	}
	isAdmin := caller != nil && caller.Role == "admin"
	a.mu.Lock()
	defer a.mu.Unlock()
	removed := 0
	denied := false
	for tok, s := range a.sessions {
		if !strings.HasPrefix(tok, idPrefix) {
			continue
		}
		if !isAdmin && (caller == nil || s.UserID != caller.UserID) {
			denied = true
			continue
		}
		delete(a.sessions, tok)
		removed++
	}
	if removed == 0 {
		if denied {
			return 0, fmt.Errorf("只能吊销自己的会话")
		}
		return 0, fmt.Errorf("未找到该会话")
	}
	a.saveSessionsLocked()
	return removed, nil
}

func (a *authStore) IdleMinutes() int {
	return int(a.idle / time.Minute)
}

// ---------------- 请求上下文里的当前用户 ----------------

type ctxKeyUser struct{}

func withUser(ctx context.Context, s *Session) context.Context {
	return context.WithValue(ctx, ctxKeyUser{}, s)
}

func userFrom(r *http.Request) *Session {
	v, _ := r.Context().Value(ctxKeyUser{}).(*Session)
	return v
}

func currentUserName(r *http.Request) string {
	if u := userFrom(r); u != nil {
		return u.Username
	}
	return ""
}

// ---------------- 操作日志 ----------------

type AuditEntry struct {
	Time   int64  `json:"time"`
	User   string `json:"user"`
	IP     string `json:"ip"`
	Action string `json:"action"`
	Method string `json:"method"`
	Path   string `json:"path"`
	Target string `json:"target"`
	Status int    `json:"status"`
}

type auditLog struct {
	mu      sync.Mutex
	entries []AuditEntry
	path    string
}

const auditMax = 5000

func newAuditLog(dataDir string) *auditLog {
	l := &auditLog{path: filepath.Join(dataDir, "audit.log")}
	if f, err := os.Open(l.path); err == nil {
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
		for sc.Scan() {
			var e AuditEntry
			if json.Unmarshal(sc.Bytes(), &e) == nil {
				l.entries = append(l.entries, e)
			}
		}
		_ = f.Close()
		if len(l.entries) > auditMax {
			l.entries = l.entries[len(l.entries)-auditMax:]
		}
	}
	return l
}

func (l *auditLog) Add(e AuditEntry) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.entries = append(l.entries, e)
	if len(l.entries) > auditMax {
		l.entries = l.entries[len(l.entries)-auditMax:]
	}
	if raw, err := json.Marshal(e); err == nil {
		if f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
			_, _ = f.Write(raw)
			_, _ = f.Write([]byte("\n"))
			_ = f.Close()
		}
	}
}

func (l *auditLog) Recent(limit int) []AuditEntry {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := len(l.entries)
	if limit <= 0 || limit > n {
		limit = n
	}
	out := make([]AuditEntry, 0, limit)
	for i := n - 1; i >= n-limit && i >= 0; i-- {
		out = append(out, l.entries[i])
	}
	return out
}

var auditActions = []struct{ prefix, action string }{
	{"/api/users", "用户管理"},
	{"/api/auth/revoke", "吊销会话"},
	{"/api/maintenance/cleanup", "维护清理"},
	{"/api/wol", "网络唤醒"},
	{"/api/shares", "分享"},
	{"/api/libs/update", "编辑存储库"},
	{"/api/libs", "存储库"},
	{"/api/settings", "修改设置"},
	{"/api/mkdir", "新建文件夹"},
	{"/api/rename", "改名"},
	{"/api/delete", "删除"},
	{"/api/move", "移动"},
	{"/api/copy", "复制"},
	{"/api/upload/complete", "上传"},
	{"/api/upload/abort", "取消上传"},
	{"/api/reindex", "重建索引"},
	{"/api/trash", "回收站"},
	{"/api/vault", "保险箱"},
	{"/api/devices/clear", "清空设备"},
}

func auditAction(p string) string {
	for _, m := range auditActions {
		if strings.HasPrefix(p, m.prefix) {
			return m.action
		}
	}
	return ""
}

func shouldAudit(r *http.Request) bool {
	if r.Method != "POST" && r.Method != "DELETE" {
		return false
	}
	p := r.URL.Path
	if p == "/api/upload/chunk" || p == "/api/upload/status" || p == "/api/auth/status" || p == "/api/auth/login" {
		return false
	}
	return auditAction(p) != ""
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(c int) {
	s.status = c
	s.ResponseWriter.WriteHeader(c)
}
func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = 200
	}
	return s.ResponseWriter.Write(b)
}
func (s *statusRecorder) Flush() {
	if f, ok := s.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (s *Server) auditLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !shouldAudit(r) {
			next.ServeHTTP(w, r)
			return
		}
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		target := r.URL.Query().Get("vpath")
		if target == "" {
			target = r.URL.Query().Get("id")
		}
		if target == "" && r.URL.RawQuery != "" {
			target = r.URL.RawQuery
		}
		s.audit.Add(AuditEntry{
			Time: time.Now().Unix(), User: currentUserName(r), IP: clientIP(r),
			Action: auditAction(r.URL.Path), Method: r.Method, Path: r.URL.Path,
			Target: target, Status: rec.status,
		})
	})
}

// ---------------- 分享链接 ----------------

type Share struct {
	ID            string `json:"id"`
	VPath         string `json:"vpath"`
	Name          string `json:"name"`
	IsDir         bool   `json:"isDir"`
	CreatedBy     string `json:"createdBy"`
	CreatedAt     int64  `json:"createdAt"`
	ExpiresAt     int64  `json:"expiresAt"` // 0 = 永久
	PasswordSalt  string `json:"passwordSalt,omitempty"`
	PasswordHash  string `json:"passwordHash,omitempty"`
	AllowDownload bool   `json:"allowDownload"`
}

func (sh *Share) checkPw(pw string) bool {
	if sh.PasswordHash == "" {
		return true
	}
	key := pbkdf2Key([]byte(pw), []byte(sh.PasswordSalt), 120000, 32)
	return subtleEqual(hex.EncodeToString(key), sh.PasswordHash)
}

type sharesDoc struct {
	Shares []Share `json:"shares"`
}

type shareStore struct {
	mu    sync.Mutex
	items []Share
	path  string
}

func newShareStore(dataDir string) *shareStore {
	st := &shareStore{path: filepath.Join(dataDir, "shares.json")}
	if raw, err := os.ReadFile(st.path); err == nil {
		var doc sharesDoc
		if json.Unmarshal(raw, &doc) == nil {
			st.items = doc.Shares
		}
	}
	return st
}

func (st *shareStore) saveLocked() error {
	raw, err := json.MarshalIndent(sharesDoc{Shares: st.items}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(st.path, raw, 0o600)
}

func (st *shareStore) Add(sh Share) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.items = append(st.items, sh)
	return st.saveLocked()
}

// Get 返回未过期的分享副本;过期返回 nil。
func (st *shareStore) Get(id string) *Share {
	st.mu.Lock()
	defer st.mu.Unlock()
	for i := range st.items {
		if st.items[i].ID == id {
			sh := st.items[i]
			if sh.ExpiresAt > 0 && time.Now().Unix() > sh.ExpiresAt {
				return nil
			}
			return &sh
		}
	}
	return nil
}

func (st *shareStore) raw(id string) *Share {
	for i := range st.items {
		if st.items[i].ID == id {
			sh := st.items[i]
			return &sh
		}
	}
	return nil
}

func (st *shareStore) Delete(id string) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	out := st.items[:0]
	found := false
	for _, sh := range st.items {
		if sh.ID == id {
			found = true
			continue
		}
		out = append(out, sh)
	}
	st.items = out
	if found {
		_ = st.saveLocked()
	}
	return found
}

func (st *shareStore) List() []map[string]any {
	st.mu.Lock()
	defer st.mu.Unlock()
	now := time.Now().Unix()
	out := make([]map[string]any, 0, len(st.items))
	for _, sh := range st.items {
		expired := sh.ExpiresAt > 0 && now > sh.ExpiresAt
		out = append(out, map[string]any{
			"id": sh.ID, "name": sh.Name, "vpath": sh.VPath, "isDir": sh.IsDir,
			"createdBy": sh.CreatedBy, "createdAt": sh.CreatedAt, "expiresAt": sh.ExpiresAt,
			"hasPassword": sh.PasswordHash != "", "allowDownload": sh.AllowDownload,
			"expired": expired, "url": "/s/" + sh.ID,
		})
	}
	return out
}

// ---------------- auth / 用户 / 日志 / 分享 的 HTTP 处理器 ----------------

func (s *Server) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"multiUser":       s.auth.MultiUser(),
		"userCount":       s.auth.UserCount(),
		"idleLockMinutes": s.auth.IdleMinutes(),
		"version":         Version,
	})
}

func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	sess, err := s.auth.Login(req.Username, req.Password, clientIP(r))
	if err != nil {
		status := http.StatusUnauthorized
		action := "登录失败"
		if le, ok := err.(*LockoutError); ok {
			status = http.StatusTooManyRequests
			action = "登录锁定"
			w.Header().Set("Retry-After", strconv.Itoa(le.RetryAfter))
		}
		s.audit.Add(AuditEntry{Time: time.Now().Unix(), User: strings.TrimSpace(req.Username), IP: clientIP(r), Action: action, Method: "POST", Path: "/api/auth/login", Status: status})
		writeErr(w, status, err.Error())
		return
	}
	s.audit.Add(AuditEntry{Time: time.Now().Unix(), User: sess.Username, IP: clientIP(r), Action: "登录", Method: "POST", Path: "/api/auth/login", Status: 200})
	writeJSON(w, 200, map[string]any{
		"token": sess.Token, "username": sess.Username, "role": sess.Role,
		"idleLockMinutes": s.auth.IdleMinutes(), "expiresIn": int(s.auth.idle / time.Second),
	})
}

// handleAuthSessions GET /api/auth/sessions  当前活跃会话列表
func (s *Server) handleAuthSessions(w http.ResponseWriter, r *http.Request) {
	tok := r.Header.Get("X-Token")
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	writeJSON(w, 200, map[string]any{"sessions": s.auth.Sessions(tok)})
}

// handleAuthRevoke POST /api/auth/revoke {id}  吊销某个会话(非管理员仅限本人)
func (s *Server) handleAuthRevoke(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID string `json:"id"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	caller := userFrom(r)
	n, err := s.auth.Revoke(req.ID, caller)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "count": n})
}

func (s *Server) handleAuthMe(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if u == nil {
		writeJSON(w, 200, map[string]any{"username": "", "role": "", "multiUser": s.auth.MultiUser()})
		return
	}
	writeJSON(w, 200, map[string]any{"username": u.Username, "role": u.Role, "multiUser": s.auth.MultiUser(), "idleLockMinutes": s.auth.IdleMinutes()})
}

func (s *Server) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	tok := r.Header.Get("X-Token")
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	s.auth.Logout(tok)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) handleUsersList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.auth.List())
}

func (s *Server) handleUsersCreate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Role     string `json:"role"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	u, err := s.auth.CreateUser(req.Username, req.Password, req.Role)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "id": u.ID, "username": u.Username, "role": u.Role})
}

func (s *Server) handleUsersDelete(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if err := s.auth.DeleteUser(id); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) handleUsersUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID       string `json:"id"`
		Password string `json:"password"`
		Role     string `json:"role"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if req.Password != "" {
		if err := s.auth.SetPassword(req.ID, req.Password); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	if req.Role != "" {
		if err := s.auth.SetRole(req.ID, req.Role); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handleUsersPassword 修改当前登录用户自己的密码(任何已登录用户)。
func (s *Server) handleUsersPassword(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if u == nil || u.UserID == "" {
		writeErr(w, 400, "当前为全局口令登录,没有可修改的用户账号")
		return
	}
	var req struct {
		OldPassword string `json:"oldPassword"`
		NewPassword string `json:"newPassword"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	// 校验旧密码
	var uname string
	for _, mu := range s.auth.List() {
		if mu["id"] == u.UserID {
			uname, _ = mu["username"].(string)
		}
	}
	if !s.auth.CheckUserPassword(uname, req.OldPassword) {
		writeErr(w, 400, "原密码不正确")
		return
	}
	if err := s.auth.SetPassword(u.UserID, req.NewPassword); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// CheckUserPassword 校验某用户名+密码(用于改密前验证旧密码)。
func (a *authStore) CheckUserPassword(username, pw string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	u := a.findByName(username)
	return u != nil && !u.Disabled && checkPassword(u, pw)
}

func atoiSafe(s string) int {
	n := 0
	if s == "" {
		return -1
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return -1
		}
		n = n*10 + int(c-'0')
	}
	return n
}

func (s *Server) handleAuditList(w http.ResponseWriter, r *http.Request) {
	limit := 100
	if v := r.URL.Query().Get("limit"); v != "" {
		if n := atoiSafe(v); n > 0 {
			limit = n
		}
	}
	writeJSON(w, 200, s.audit.Recent(limit))
}

func (s *Server) handleSharesCreate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath         string `json:"vpath"`
		ExpiresHours  int    `json:"expiresHours"`
		Password      string `json:"password"`
		AllowDownload bool   `json:"allowDownload"`
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
		writeErr(w, 403, "保险箱内容不支持生成分享链接")
		return
	}
	if s.excluded(lib, abs) || s.inTrash(abs) {
		writeErr(w, 403, "该路径不可分享")
		return
	}
	info, err := os.Stat(abs)
	if err != nil {
		writeErr(w, 404, "文件不存在")
		return
	}
	sh := Share{
		ID: randHex(6), VPath: req.VPath, Name: BaseVirtual(req.VPath), IsDir: info.IsDir(),
		CreatedBy: currentUserName(r), CreatedAt: time.Now().Unix(), AllowDownload: req.AllowDownload,
	}
	if req.ExpiresHours > 0 {
		sh.ExpiresAt = time.Now().Unix() + int64(req.ExpiresHours)*3600
	}
	if strings.TrimSpace(req.Password) != "" {
		sh.PasswordSalt, sh.PasswordHash = hashPassword(req.Password)
	}
	if err := s.shares.Add(sh); err != nil {
		writeErr(w, 500, "保存分享失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"id": sh.ID, "url": "/s/" + sh.ID, "name": sh.Name, "isDir": sh.IsDir, "expiresAt": sh.ExpiresAt, "hasPassword": sh.PasswordHash != ""})
}

func (s *Server) handleSharesList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.shares.List())
}

func (s *Server) handleSharesDelete(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	sh := s.shares.raw(id)
	if sh == nil {
		writeErr(w, 404, "分享不存在")
		return
	}
	u := userFrom(r)
	admin := u != nil && u.Role == "admin"
	if !admin && (u == nil || sh.CreatedBy != u.Username) {
		writeErr(w, 403, "只能删除自己创建的分享")
		return
	}
	s.shares.Delete(id)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---------------- 分享的公开访问(免登录) ----------------

func (s *Server) shareAbs(sh *Share, rel string) (string, error) {
	_, abs, err := s.cfg.Resolve(sh.VPath)
	if err != nil {
		return "", err
	}
	if !sh.IsDir {
		return abs, nil
	}
	rel = strings.ReplaceAll(rel, "\\", "/")
	clean := path.Clean("/" + rel)
	full := filepath.Join(abs, filepath.FromSlash(clean))
	if !withinRoot(abs, full) {
		return "", fmt.Errorf("越权路径")
	}
	return full, nil
}

func (s *Server) handleShareInfo(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	sh := s.shares.Get(id)
	if sh == nil {
		writeErr(w, 404, "分享不存在或已过期")
		return
	}
	pw := r.URL.Query().Get("pw")
	if !sh.checkPw(pw) {
		writeJSON(w, 200, map[string]any{"needPassword": true, "name": sh.Name, "isDir": sh.IsDir})
		return
	}
	resp := map[string]any{
		"name": sh.Name, "isDir": sh.IsDir, "allowDownload": sh.AllowDownload,
		"expiresAt": sh.ExpiresAt, "password": sh.PasswordHash != "",
	}
	if sh.IsDir {
		full, err := s.shareAbs(sh, "")
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		des, err := os.ReadDir(full)
		if err != nil {
			writeErr(w, 500, "读取目录失败")
			return
		}
		type ent struct {
			Name    string `json:"name"`
			IsDir   bool   `json:"isDir"`
			Size    int64  `json:"size"`
			ModTime int64  `json:"modTime"`
			Rel     string `json:"rel"`
		}
		ents := make([]ent, 0, len(des))
		for _, de := range des {
			if strings.HasPrefix(de.Name(), ".") {
				continue
			}
			fi, err := de.Info()
			if err != nil {
				continue
			}
			ents = append(ents, ent{Name: de.Name(), IsDir: de.IsDir(), Size: fi.Size(), ModTime: fi.ModTime().Unix(), Rel: de.Name()})
		}
		sort.Slice(ents, func(i, j int) bool {
			if ents[i].IsDir != ents[j].IsDir {
				return ents[i].IsDir
			}
			return strings.ToLower(ents[i].Name) < strings.ToLower(ents[j].Name)
		})
		resp["entries"] = ents
	}
	writeJSON(w, 200, resp)
}

func (s *Server) handleShareRaw(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	sh := s.shares.Get(id)
	if sh == nil {
		writeErr(w, 404, "分享不存在或已过期")
		return
	}
	if !sh.checkPw(r.URL.Query().Get("pw")) {
		writeErr(w, 403, "需要密码")
		return
	}
	full, err := s.shareAbs(sh, r.URL.Query().Get("p"))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	info, err := os.Stat(full)
	if err != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	f, err := os.Open(full)
	if err != nil {
		writeErr(w, 500, "打开失败")
		return
	}
	defer f.Close()
	if sh.AllowDownload && r.URL.Query().Get("dl") != "" {
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(info.Name()))
	}
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

func (s *Server) handleSharePage(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// 页面本身只作为静态壳,数据由同源 /api/share/{id} 拉取(便于密码/目录处理)。
	page := strings.ReplaceAll(sharePageHTML, "__ID__", html.EscapeString(id))
	_, _ = w.Write([]byte(page))
}

const sharePageHTML = `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>linNas 分享</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,"Segoe UI",Roboto,sans-serif;background:#0b0d0c;color:#e9ece8;min-height:100vh}
a{color:#e8a33d}
.wrap{max-width:920px;margin:0 auto;padding:24px 16px 60px}
.head{display:flex;align-items:center;gap:10px;margin-bottom:18px}
.logo{width:30px;height:30px;border-radius:8px;background:#e8a33d;color:#0b0d0c;display:flex;align-items:center;justify-content:center;font-weight:700}
.card{background:#141816;border:1px solid #232823;border-radius:14px;padding:18px}
.name{font-size:18px;font-weight:600;word-break:break-all;margin-bottom:10px}
.muted{color:#9aa39c;font-size:13px}
video,img{max-width:100%;border-radius:10px;background:#000}
audio{width:100%}
.btn{display:inline-flex;align-items:center;gap:6px;background:#e8a33d;color:#0b0d0c;border:0;border-radius:9px;padding:9px 14px;font:inherit;font-weight:600;cursor:pointer;text-decoration:none}
.btn.ghost{background:#1d221f;color:#e9ece8;border:1px solid #2c332d}
input{background:#0f1311;border:1px solid #2c332d;color:#e9ece8;border-radius:9px;padding:10px 12px;font:inherit;width:100%}
.list{display:flex;flex-direction:column;gap:6px;margin-top:12px}
.item{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px;background:#101412;border:1px solid #1d221f;text-decoration:none;color:inherit}
.item:hover{background:#161b18}
.ic{width:22px;text-align:center;opacity:.8}
.grow{flex:1;min-width:0;word-break:break-all}
.sz{color:#9aa39c;font-size:12px}
.row{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px}
</style></head><body><div class="wrap">
<div class="head"><div class="logo">L</div><div style="font-weight:700">linNas 分享</div></div>
<div id="app" class="card"><div class="muted">加载中…</div></div>
</div>
<script>
const ID="__ID__";
const app=document.getElementById("app");
let pw=new URLSearchParams(location.search).get("pw")||"";
function fmt(n){if(n==null)return"";const u=["B","KB","MB","GB","TB"];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return (n>=100||i===0?Math.round(n):n.toFixed(1))+" "+u[i]}
function esc(s){return s.replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]))}
function ext(n){const i=n.lastIndexOf(".");return i<0?"":n.slice(i+1).toLowerCase()}
const VIDEO=["mp4","webm","mkv","mov","m4v","avi","flv","wmv","ts"];
const AUDIO=["mp3","aac","m4a","flac","wav","ogg","opus"];
const IMAGE=["jpg","jpeg","png","gif","webp","bmp","avif"];
async function load(p){
  const u="/api/share/"+ID+"?pw="+encodeURIComponent(pw)+(p?("&p="+encodeURIComponent(p)):"");
  const r=await fetch(u); const d=await r.json();
  if(d.needPassword){ askPw(); return; }
  if(d.error){ app.innerHTML='<div class="muted">'+esc(d.error)+'</div>'; return; }
  if(d.isDir){ renderDir(d,p||""); } else { renderFile(d,p||""); }
}
function askPw(){ app.innerHTML='<div class="name">需要密码</div><input id="pw" type="password" placeholder="请输入分享密码" autofocus><div class="row"><button class="btn" id="go">进入</button></div>'; document.getElementById("go").onclick=()=>{pw=document.getElementById("pw").value;load("");}; document.getElementById("pw").addEventListener("keydown",e=>{if(e.key==="Enter"){pw=e.target.value;load("")}}); }
function q(pw0){return "pw="+encodeURIComponent(pw)+(pw0?"&p="+encodeURIComponent(pw0):"")}
function renderFile(d,p){
  const e=ext(d.name); let media="";
  const src="/api/share/"+ID+"/raw?"+q(p);
  if(VIDEO.includes(e)) media='<video controls autoplay src="'+src+'"></video>';
  else if(AUDIO.includes(e)) media='<audio controls src="'+src+'"></audio>';
  else if(IMAGE.includes(e)) media='<img src="'+src+'">';
  let dl = d.allowDownload ? '<a class="btn ghost" href="'+src+'&dl=1">下载</a>' : '';
  app.innerHTML='<div class="name">'+esc(d.name)+'</div>'+media+'<div class="row"><a class="btn ghost" href="'+src+'" target="_blank">直接打开</a>'+dl+'</div>'+ (d.expiresAt? '<div class="muted" style="margin-top:10px">链接有效期至 '+new Date(d.expiresAt*1000).toLocaleString()+'</div>':'<div class="muted" style="margin-top:10px">长期有效</div>');
}
function renderDir(d,p){
  let h='<div class="name">'+esc(d.name||"分享目录")+'</div><div class="muted">'+ (d.entries?d.entries.length:0)+' 项</div><div class="list">';
  if(p){ const up=p.split("/").slice(0,-1).join("/"); h+='<a class="item" href="#" onclick="load(\''+up+'\');return false"><span class="ic">↩</span><span class="grow">上一级</span></a>'; }
  for(const it of (d.entries||[])){
    const np=p?p+"/"+it.name:it.name;
    if(it.isDir){ h+='<a class="item" href="#" onclick="load(\''+esc(np)+'\');return false"><span class="ic">📁</span><span class="grow">'+esc(it.name)+'</span></a>'; }
    else { const src="/api/share/"+ID+"/raw?"+q(np); h+='<a class="item" href="'+src+'" target="_blank"><span class="ic">📄</span><span class="grow">'+esc(it.name)+'</span><span class="sz">'+fmt(it.size)+'</span></a>'; }
  }
  h+='</div>'; app.innerHTML=h;
}
load("");
</script></body></html>`
