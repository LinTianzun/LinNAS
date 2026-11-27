package main

// vault_test.go 为隐私保险箱模块(vault.go)补齐单元测试。
// 所有测试只依赖 t.TempDir(),不访问真实磁盘/外网,不需管理员权限。

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// ---- helpers ----

// resetVaultSessions 清空全局保险箱会话与主密钥,避免测试之间互相污染。
func resetVaultSessions(t *testing.T) {
	t.Helper()
	clearVaultSessions()
	t.Cleanup(clearVaultSessions)
}

// newVaultTestServer 构造一个最小可用 *Server:一个临时 DataDir + 一个可写存储库。
func newVaultTestServer(t *testing.T) *Server {
	t.Helper()
	resetVaultSessions(t)
	root := t.TempDir()
	libDir := filepath.Join(root, "library")
	if err := os.MkdirAll(libDir, 0o755); err != nil {
		t.Fatalf("mkdir library: %v", err)
	}
	return &Server{
		cfg: &Config{
			DataDir: root,
			Libraries: []Library{{
				ID:      "lib1",
				Name:    "test",
				Path:    libDir,
				Enabled: true,
			}},
		},
	}
}

// callVaultHandler 用 httptest 直接调用某个 handler(body 非 nil 时序列化为 JSON)。
func callVaultHandler(t *testing.T, fn http.HandlerFunc, method, target string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var rdr io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal request body: %v", err)
		}
		rdr = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, target, rdr)
	rec := httptest.NewRecorder()
	fn(rec, req)
	return rec
}

// vaultError 取出 {"error": "..."} 响应体里的错误文案。
func vaultError(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var resp struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode error body %q: %v", rec.Body.String(), err)
	}
	return resp.Error
}

func mustSetupVault(t *testing.T, s *Server, password string) {
	t.Helper()
	rec := callVaultHandler(t, s.handleVaultSetup, http.MethodPost, "/api/vault/setup",
		map[string]string{"password": password})
	if rec.Code != http.StatusOK {
		t.Fatalf("setup status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
}

// unlockVault 解锁并返回 token 与过期时间。
func unlockVault(t *testing.T, s *Server, password string) (string, int64) {
	t.Helper()
	rec := callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
		map[string]string{"password": password})
	if rec.Code != http.StatusOK {
		t.Fatalf("unlock status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Token   string `json:"token"`
		Expires int64  `json:"expires"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode unlock body %q: %v", rec.Body.String(), err)
	}
	if resp.Token == "" {
		t.Fatalf("unlock returned empty token")
	}
	return resp.Token, resp.Expires
}

func mustUnlockVault(t *testing.T, s *Server, password string) string {
	t.Helper()
	tok, _ := unlockVault(t, s, password)
	return tok
}

func changeVaultPassword(t *testing.T, s *Server, oldPassword, newPassword string) *httptest.ResponseRecorder {
	t.Helper()
	return callVaultHandler(t, s.handleVaultChangePassword, http.MethodPost, "/api/vault/change-password",
		map[string]string{"oldPassword": oldPassword, "newPassword": newPassword})
}

func authedWithToken(tok string) bool {
	return vaultAuthed(httptest.NewRequest(http.MethodGet, "/api/vault/ls?vt="+tok, nil))
}

// ---- 纯函数 ----

func TestVaultDirForConfig(t *testing.T) {
	root := t.TempDir()
	libWritable := filepath.Join(root, "writable")
	libReadOnly := filepath.Join(root, "readonly")
	dataDir := filepath.Join(root, "data")

	tests := []struct {
		name string
		cfg  *Config
		want string
	}{
		{
			name: "显式 VaultDir 优先并 Clean",
			cfg:  &Config{DataDir: dataDir, VaultDir: filepath.Join(root, "custom") + string(filepath.Separator)},
			want: filepath.Join(root, "custom"),
		},
		{
			name: "可写存储库 -> .linNasVault",
			cfg:  &Config{DataDir: dataDir, Libraries: []Library{{ID: "a", Path: libWritable, Enabled: true}}},
			want: filepath.Join(libWritable, ".linNasVault"),
		},
		{
			name: "跳过只读,选择后面的可写库",
			cfg: &Config{DataDir: dataDir, Libraries: []Library{
				{ID: "a", Path: libReadOnly, Enabled: true, ReadOnly: true},
				{ID: "b", Path: libWritable, Enabled: true},
			}},
			want: filepath.Join(libWritable, ".linNasVault"),
		},
		{
			name: "忽略未启用的库",
			cfg: &Config{DataDir: dataDir, Libraries: []Library{
				{ID: "a", Path: libReadOnly, Enabled: false},
				{ID: "b", Path: libWritable, Enabled: true},
			}},
			want: filepath.Join(libWritable, ".linNasVault"),
		},
		{
			name: "全部只读时回退到第一个启用库",
			cfg: &Config{DataDir: dataDir, Libraries: []Library{
				{ID: "a", Path: libReadOnly, Enabled: true, ReadOnly: true},
				{ID: "b", Path: libWritable, Enabled: true, ReadOnly: true},
			}},
			want: filepath.Join(libReadOnly, ".linNasVault"),
		},
		{
			name: "无存储库 -> DataDir/vault",
			cfg:  &Config{DataDir: dataDir},
			want: filepath.Join(dataDir, "vault"),
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := vaultDirForConfig(tc.cfg); got != tc.want {
				t.Fatalf("vaultDirForConfig = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestJoinRel(t *testing.T) {
	tests := []struct{ dir, name, want string }{
		{"", "a", "a"},
		{"/", "a", "a"},
		{"sub", "a", "sub/a"},
		{"/sub/", "a", "sub/a"},
		{"sub/deep", "a", "sub/deep/a"},
	}
	for _, tc := range tests {
		if got := joinRel(tc.dir, tc.name); got != tc.want {
			t.Errorf("joinRel(%q, %q) = %q, want %q", tc.dir, tc.name, got, tc.want)
		}
	}
}

func TestPbkdf2Key(t *testing.T) {
	const iter = 1000
	k1 := pbkdf2Key([]byte("password"), []byte("salt"), iter, 32)
	k2 := pbkdf2Key([]byte("password"), []byte("salt"), iter, 32)
	if !bytes.Equal(k1, k2) {
		t.Fatalf("pbkdf2Key is not deterministic for identical inputs")
	}
	if len(k1) != 32 {
		t.Fatalf("pbkdf2Key length = %d, want 32", len(k1))
	}
	if bytes.Equal(k1, pbkdf2Key([]byte("password"), []byte("other-salt"), iter, 32)) {
		t.Fatalf("different salt produced identical key")
	}
	if bytes.Equal(k1, pbkdf2Key([]byte("other-pass"), []byte("salt"), iter, 32)) {
		t.Fatalf("different password produced identical key")
	}
}

func TestRandHex(t *testing.T) {
	a := randHex(16)
	b := randHex(16)
	if len(a) != 32 {
		t.Fatalf("randHex(16) length = %d, want 32 hex chars", len(a))
	}
	if a == b {
		t.Fatalf("randHex returned identical values twice: %q", a)
	}
	if _, err := hex.DecodeString(a); err != nil {
		t.Fatalf("randHex produced non-hex output %q: %v", a, err)
	}
}

// ---- setup / status ----

func TestVaultSetupAndStatus(t *testing.T) {
	s := newVaultTestServer(t)

	// 未创建时 status.exists=false / unlocked=false
	rec := callVaultHandler(t, s.handleVaultStatus, http.MethodGet, "/api/vault/status", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	var st struct {
		Exists   bool `json:"exists"`
		Unlocked bool `json:"unlocked"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if st.Exists || st.Unlocked {
		t.Fatalf("status before setup = %+v, want exists=false unlocked=false", st)
	}

	// 密码过短 -> 400
	rec = callVaultHandler(t, s.handleVaultSetup, http.MethodPost, "/api/vault/setup",
		map[string]string{"password": "abc"})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("short password status = %d, want 400; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "密码至少 4 位" {
		t.Fatalf("short password message = %q, want %q", msg, "密码至少 4 位")
	}
	if _, err := os.Stat(filepath.Join(s.cfg.DataDir, "vault.json")); !os.IsNotExist(err) {
		t.Fatalf("vault.json should not exist after rejected setup, stat err = %v", err)
	}

	// 正常 setup
	mustSetupVault(t, s, "secret1")
	if _, err := os.Stat(filepath.Join(s.cfg.DataDir, "vault.json")); err != nil {
		t.Fatalf("vault.json missing after setup: %v", err)
	}
	vd := s.vaultDir()
	if info, err := os.Stat(vd); err != nil || !info.IsDir() {
		t.Fatalf("vault dir %q not created as directory: err=%v", vd, err)
	}
	if !s.inVault(vd) {
		t.Fatalf("vault dir %q not reported inside vault", vd)
	}

	// setup 后 status.exists=true,仍未解锁
	rec = callVaultHandler(t, s.handleVaultStatus, http.MethodGet, "/api/vault/status", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if !st.Exists || st.Unlocked {
		t.Fatalf("status after setup = %+v, want exists=true unlocked=false", st)
	}

	// 重复 setup -> 409
	rec = callVaultHandler(t, s.handleVaultSetup, http.MethodPost, "/api/vault/setup",
		map[string]string{"password": "another"})
	if rec.Code != http.StatusConflict {
		t.Fatalf("repeat setup status = %d, want 409; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "保险箱已存在,请使用「修改密码」" {
		t.Fatalf("repeat setup message = %q, want %q", msg, "保险箱已存在,请使用「修改密码」")
	}
}

// ---- unlock ----

func TestVaultUnlock(t *testing.T) {
	t.Run("未创建返回 404", func(t *testing.T) {
		s := newVaultTestServer(t)
		rec := callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
			map[string]string{"password": "whatever"})
		if rec.Code != http.StatusNotFound {
			t.Fatalf("status = %d, want 404; body: %s", rec.Code, rec.Body.String())
		}
		if msg := vaultError(t, rec); msg != "保险箱尚未创建" {
			t.Fatalf("message = %q, want %q", msg, "保险箱尚未创建")
		}
	})

	t.Run("密码错误 403 / 正确返回可用 token", func(t *testing.T) {
		s := newVaultTestServer(t)
		mustSetupVault(t, s, "goodpass")

		rec := callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
			map[string]string{"password": "badpass"})
		if rec.Code != http.StatusForbidden {
			t.Fatalf("wrong password status = %d, want 403; body: %s", rec.Code, rec.Body.String())
		}
		if msg := vaultError(t, rec); msg != "密码不正确" {
			t.Fatalf("wrong password message = %q, want %q", msg, "密码不正确")
		}

		tok, expires := unlockVault(t, s, "goodpass")
		if expires <= time.Now().Unix() {
			t.Fatalf("expires = %d, want a future unix time", expires)
		}
		if !authedWithToken(tok) {
			t.Fatalf("vaultAuthed=false for freshly issued token")
		}
		// 通过 X-Vault-Token 头也应视为已解锁
		req := httptest.NewRequest(http.MethodGet, "/api/vault/ls", nil)
		req.Header.Set("X-Vault-Token", tok)
		if !vaultAuthed(req) {
			t.Fatalf("vaultAuthed=false for token in X-Vault-Token header")
		}
		// 无效/缺失 token 视为未解锁
		if vaultAuthed(httptest.NewRequest(http.MethodGet, "/api/vault/ls?vt=deadbeef", nil)) {
			t.Fatalf("vaultAuthed=true for bogus token")
		}
		if vaultAuthed(httptest.NewRequest(http.MethodGet, "/api/vault/ls", nil)) {
			t.Fatalf("vaultAuthed=true without any token")
		}
		// status 通过 vt 反映已解锁
		rec = callVaultHandler(t, s.handleVaultStatus, http.MethodGet, "/api/vault/status?vt="+tok, nil)
		var st struct {
			Unlocked bool `json:"unlocked"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
			t.Fatalf("decode status: %v", err)
		}
		if !st.Unlocked {
			t.Fatalf("status.unlocked=false for unlocked session")
		}
	})
}

// ---- change-password ----

func TestVaultChangePassword(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "oldPassw0rd")
	oldTok := mustUnlockVault(t, s, "oldPassw0rd")

	// 原密码错 -> 403
	rec := changeVaultPassword(t, s, "wrongpass", "newpass2")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("wrong old status = %d, want 403; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "原密码不正确" {
		t.Fatalf("wrong old message = %q, want %q", msg, "原密码不正确")
	}

	// 新密码过短 -> 400
	rec = changeVaultPassword(t, s, "oldPassw0rd", "abc")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("short new status = %d, want 400; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "新密码至少 4 位" {
		t.Fatalf("short new message = %q, want %q", msg, "新密码至少 4 位")
	}

	// 成功改密
	rec = changeVaultPassword(t, s, "oldPassw0rd", "newpass2")
	if rec.Code != http.StatusOK {
		t.Fatalf("change status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}

	// 旧会话 token 失效
	if authedWithToken(oldTok) {
		t.Fatalf("old token still valid after password change")
	}

	// 新密码可解锁
	newTok := mustUnlockVault(t, s, "newpass2")
	if !authedWithToken(newTok) {
		t.Fatalf("new token invalid after password change")
	}

	// 旧密码解锁失败
	rec = callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
		map[string]string{"password": "oldPassw0rd"})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("old password unlock status = %d, want 403; body: %s", rec.Code, rec.Body.String())
	}
}

// ---- lock ----

func TestVaultLock(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "lockpass")

	// 通过查询参数 vt 锁定
	tok := mustUnlockVault(t, s, "lockpass")
	if !authedWithToken(tok) {
		t.Fatalf("token should be valid before lock")
	}
	rec := callVaultHandler(t, s.handleVaultLock, http.MethodPost, "/api/vault/lock?vt="+tok, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("lock status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	if authedWithToken(tok) {
		t.Fatalf("token still valid after lock")
	}

	// 通过 JSON body token 锁定
	tok2 := mustUnlockVault(t, s, "lockpass")
	rec = callVaultHandler(t, s.handleVaultLock, http.MethodPost, "/api/vault/lock",
		map[string]string{"token": tok2})
	if rec.Code != http.StatusOK {
		t.Fatalf("lock(body) status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	if authedWithToken(tok2) {
		t.Fatalf("token still valid after lock via body")
	}
}

// ---- 文件操作往返 ----

func TestVaultFileRoundTrip(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "roundpass")
	tok := mustUnlockVault(t, s, "roundpass")

	// 未解锁 -> 401
	rec := callVaultHandler(t, s.handleVaultList, http.MethodGet, "/api/vault/ls", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("ls without token status = %d, want 401; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "保险箱未解锁" {
		t.Fatalf("ls without token message = %q, want %q", msg, "保险箱未解锁")
	}

	// 空根目录
	rec = callVaultHandler(t, s.handleVaultList, http.MethodGet, "/api/vault/ls?vt="+tok, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("ls root status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	var entries []vaultEntry
	if err := json.Unmarshal(rec.Body.Bytes(), &entries); err != nil {
		t.Fatalf("decode ls: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("root should be empty, got %+v", entries)
	}

	// mkdir
	rec = callVaultHandler(t, s.handleVaultMkdir, http.MethodPost, "/api/vault/mkdir?vt="+tok,
		map[string]string{"path": "sub"})
	if rec.Code != http.StatusOK {
		t.Fatalf("mkdir status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(filepath.Join(s.vaultDir(), "sub")); err != nil {
		t.Fatalf("mkdir did not create dir: %v", err)
	}

	// upload 原始字节流
	content := []byte{0x00, 0x01, 0x02, 0x03, 'h', 'e', 'l', 'l', 'o', 0xff}
	uploadURL := "/api/vault/upload?name=hello.bin&path=sub&vt=" + tok
	upReq := httptest.NewRequest(http.MethodPost, uploadURL, bytes.NewReader(content))
	upRec := httptest.NewRecorder()
	s.handleVaultUpload(upRec, upReq)
	if upRec.Code != http.StatusOK {
		t.Fatalf("upload status = %d, want 200; body: %s", upRec.Code, upRec.Body.String())
	}
	var up struct {
		Name string `json:"name"`
		Path string `json:"path"`
		Size int64  `json:"size"`
	}
	if err := json.Unmarshal(upRec.Body.Bytes(), &up); err != nil {
		t.Fatalf("decode upload: %v", err)
	}
	if up.Name != "hello.bin" || up.Path != "sub/hello.bin" || up.Size != int64(len(content)) {
		t.Fatalf("upload response = %+v, want name=hello.bin path=sub/hello.bin size=%d", up, len(content))
	}
	if _, err := os.Stat(filepath.Join(s.vaultDir(), "sub", "hello.bin")); !os.IsNotExist(err) {
		t.Fatalf("vault file must not be stored as plaintext, stat err = %v", err)
	}
	encPath, isEnc := vaultTestDiskPath(t, s, vaultTestKey(t, tok), "sub/hello.bin")
	if !isEnc {
		t.Fatalf("uploaded file is not an encrypted container")
	}
	if _, err := os.Stat(encPath); err != nil {
		t.Fatalf("uploaded encrypted container missing on disk: %v", err)
	}

	// ls 子目录能看到
	rec = callVaultHandler(t, s.handleVaultList, http.MethodGet, "/api/vault/ls?vt="+tok+"&path=sub", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &entries); err != nil {
		t.Fatalf("decode ls sub: %v", err)
	}
	if len(entries) != 1 || entries[0].Name != "hello.bin" || entries[0].Size != int64(len(content)) || entries[0].IsDir {
		t.Fatalf("ls sub = %+v, want single hello.bin size %d", entries, len(content))
	}

	// raw 取回字节一致
	rawURL := "/api/vault/raw?path=sub/hello.bin&vt=" + tok
	rawRec := httptest.NewRecorder()
	s.handleVaultRaw(rawRec, httptest.NewRequest(http.MethodGet, rawURL, nil))
	if rawRec.Code != http.StatusOK {
		t.Fatalf("raw status = %d, want 200; body: %s", rawRec.Code, rawRec.Body.String())
	}
	if !bytes.Equal(rawRec.Body.Bytes(), content) {
		t.Fatalf("raw body = %v, want %v", rawRec.Body.Bytes(), content)
	}

	// raw 支持 Range
	rangeReq := httptest.NewRequest(http.MethodGet, rawURL, nil)
	rangeReq.Header.Set("Range", "bytes=0-2")
	rangeRec := httptest.NewRecorder()
	s.handleVaultRaw(rangeRec, rangeReq)
	if rangeRec.Code != http.StatusPartialContent {
		t.Fatalf("range status = %d, want 206; body: %s", rangeRec.Code, rangeRec.Body.String())
	}
	if !bytes.Equal(rangeRec.Body.Bytes(), content[:3]) {
		t.Fatalf("range body = %v, want %v", rangeRec.Body.Bytes(), content[:3])
	}

	// delete 后消失
	rec = callVaultHandler(t, s.handleVaultDelete, http.MethodPost, "/api/vault/delete?vt="+tok,
		map[string]any{"paths": []string{"sub/hello.bin"}})
	if rec.Code != http.StatusOK {
		t.Fatalf("delete status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	var del struct {
		OK    bool `json:"ok"`
		Count int  `json:"count"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &del); err != nil {
		t.Fatalf("decode delete: %v", err)
	}
	if !del.OK || del.Count != 1 {
		t.Fatalf("delete response = %+v, want ok=true count=1", del)
	}

	rec = callVaultHandler(t, s.handleVaultList, http.MethodGet, "/api/vault/ls?vt="+tok+"&path=sub", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &entries); err != nil {
		t.Fatalf("decode ls sub after delete: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("sub should be empty after delete, got %+v", entries)
	}
	rawRec = httptest.NewRecorder()
	s.handleVaultRaw(rawRec, httptest.NewRequest(http.MethodGet, rawURL, nil))
	if rawRec.Code != http.StatusNotFound {
		t.Fatalf("raw after delete status = %d, want 404", rawRec.Code)
	}
}

// ---- import ----

func TestVaultImport(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "importpw")
	tok := mustUnlockVault(t, s, "importpw")

	libDir := s.cfg.Libraries[0].Path
	if err := os.MkdirAll(filepath.Join(libDir, "a"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(libDir, "a", "b.txt"), []byte("nested"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(libDir, "top.txt"), []byte("topdata"), 0o644); err != nil {
		t.Fatal(err)
	}

	rec := callVaultHandler(t, s.handleVaultImport, http.MethodPost, "/api/vault/import?vt="+tok,
		map[string]any{
			"vpaths": []string{"/lib1/a/b.txt", "/lib1/top.txt"},
			"dest":   "imp",
		})
	if rec.Code != http.StatusOK {
		t.Fatalf("import status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		OK     bool     `json:"ok"`
		Count  int      `json:"count"`
		Errors []string `json:"errors"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode import: %v", err)
	}
	if !resp.OK || resp.Count != 2 || len(resp.Errors) != 0 {
		t.Fatalf("import response = %+v, want ok=true count=2 no errors", resp)
	}

	// 保留了相对存储库根的目录结构;导入后落盘为加密容器
	for _, tc := range []struct{ rel, want string }{
		{"imp/a/b.txt", "nested"},
		{"imp/top.txt", "topdata"},
	} {
		encPath, isEnc := vaultTestDiskPath(t, s, vaultTestKey(t, tok), tc.rel)
		if !isEnc {
			t.Fatalf("imported file %s is not encrypted", tc.rel)
		}
		if _, err := os.Stat(encPath); err != nil {
			t.Fatalf("imported encrypted container missing for %s: %v", tc.rel, err)
		}
		if _, err := os.Stat(filepath.Join(s.vaultDir(), filepath.FromSlash(tc.rel))); !os.IsNotExist(err) {
			t.Fatalf("imported file %s must not be stored as plaintext, stat err = %v", tc.rel, err)
		}
		rawRec := httptest.NewRecorder()
		s.handleVaultRaw(rawRec, httptest.NewRequest(http.MethodGet, "/api/vault/raw?path="+tc.rel+"&vt="+tok, nil))
		if rawRec.Code != http.StatusOK {
			t.Fatalf("raw %s status = %d, want 200; body: %s", tc.rel, rawRec.Code, rawRec.Body.String())
		}
		if rawRec.Body.String() != tc.want {
			t.Fatalf("decrypted content of %s = %q, want %q", tc.rel, rawRec.Body.String(), tc.want)
		}
	}

	// 原文件已从存储库移走
	if _, err := os.Stat(filepath.Join(libDir, "a", "b.txt")); !os.IsNotExist(err) {
		t.Fatalf("source nested file should be gone, stat err = %v", err)
	}
	if _, err := os.Stat(filepath.Join(libDir, "top.txt")); !os.IsNotExist(err) {
		t.Fatalf("source top file should be gone, stat err = %v", err)
	}
}

// 只读存储库不允许「导入保险箱」(导入会移走/删除源文件),必须被拒绝且源文件不受影响。
func TestVaultImportRejectsReadOnlyLibrary(t *testing.T) {
	s := newVaultTestServer(t)
	s.cfg.Libraries[0].ReadOnly = true
	mustSetupVault(t, s, "roimport")
	tok := mustUnlockVault(t, s, "roimport")

	libDir := s.cfg.Libraries[0].Path
	src := filepath.Join(libDir, "keep.txt")
	if err := os.WriteFile(src, []byte("keepme"), 0o644); err != nil {
		t.Fatal(err)
	}

	rec := callVaultHandler(t, s.handleVaultImport, http.MethodPost, "/api/vault/import?vt="+tok,
		map[string]any{"vpaths": []string{"/lib1/keep.txt"}, "dest": ""})
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (逐个报错而不是整请求失败); body: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		OK     bool     `json:"ok"`
		Count  int      `json:"count"`
		Errors []string `json:"errors"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode import: %v", err)
	}
	if resp.OK || resp.Count != 0 {
		t.Fatalf("response = %+v, want ok=false count=0", resp)
	}
	if len(resp.Errors) == 0 || !strings.Contains(resp.Errors[0], "只读") {
		t.Fatalf("errors = %v, want 含「只读」的说明", resp.Errors)
	}
	if _, err := os.Stat(src); err != nil {
		t.Fatalf("只读库源文件必须保留, stat err = %v", err)
	}
	if _, err := os.Stat(filepath.Join(s.vaultDir(), "keep.txt")); !os.IsNotExist(err) {
		t.Fatalf("保险箱内不应出现该文件, stat err = %v", err)
	}
}

// ---- export ----

func TestVaultExport(t *testing.T) {
	t.Run("导出到存储库并从保险箱移除", func(t *testing.T) {
		s := newVaultTestServer(t)
		mustSetupVault(t, s, "exportpw")
		tok := mustUnlockVault(t, s, "exportpw")
		libDir := s.cfg.Libraries[0].Path

		if err := os.WriteFile(filepath.Join(s.vaultDir(), "move.txt"), []byte("exportme"), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Join(libDir, "outdir"), 0o755); err != nil {
			t.Fatal(err)
		}

		rec := callVaultHandler(t, s.handleVaultExport, http.MethodPost, "/api/vault/export?vt="+tok,
			map[string]any{"paths": []string{"move.txt"}, "dest": "/lib1/outdir"})
		if rec.Code != http.StatusOK {
			t.Fatalf("export status = %d, want 200; body: %s", rec.Code, rec.Body.String())
		}
		var resp struct {
			OK     bool     `json:"ok"`
			Count  int      `json:"count"`
			Errors []string `json:"errors"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("decode export: %v", err)
		}
		if !resp.OK || resp.Count != 1 || len(resp.Errors) != 0 {
			t.Fatalf("export response = %+v, want ok=true count=1 no errors", resp)
		}

		got, err := os.ReadFile(filepath.Join(libDir, "outdir", "move.txt"))
		if err != nil {
			t.Fatalf("exported file missing in library: %v", err)
		}
		if string(got) != "exportme" {
			t.Fatalf("exported content = %q, want %q", got, "exportme")
		}
		if _, err := os.Stat(filepath.Join(s.vaultDir(), "move.txt")); !os.IsNotExist(err) {
			t.Fatalf("file should be removed from vault, stat err = %v", err)
		}
	})

	t.Run("目标存储库只读 -> 403", func(t *testing.T) {
		s := newVaultTestServer(t)
		mustSetupVault(t, s, "exportpw")
		tok := mustUnlockVault(t, s, "exportpw")
		s.cfg.Libraries[0].ReadOnly = true

		rec := callVaultHandler(t, s.handleVaultExport, http.MethodPost, "/api/vault/export?vt="+tok,
			map[string]any{"paths": []string{"x.txt"}, "dest": "/lib1"})
		if rec.Code != http.StatusForbidden {
			t.Fatalf("readonly export status = %d, want 403; body: %s", rec.Code, rec.Body.String())
		}
		if msg := vaultError(t, rec); msg != "目标存储库为只读" {
			t.Fatalf("readonly message = %q, want %q", msg, "目标存储库为只读")
		}
	})

	t.Run("目标目录不存在 -> 404", func(t *testing.T) {
		s := newVaultTestServer(t)
		mustSetupVault(t, s, "exportpw")
		tok := mustUnlockVault(t, s, "exportpw")

		rec := callVaultHandler(t, s.handleVaultExport, http.MethodPost, "/api/vault/export?vt="+tok,
			map[string]any{"paths": []string{"x.txt"}, "dest": "/lib1/nope"})
		if rec.Code != http.StatusNotFound {
			t.Fatalf("missing dest status = %d, want 404; body: %s", rec.Code, rec.Body.String())
		}
		if msg := vaultError(t, rec); msg != "目标目录不存在" {
			t.Fatalf("missing dest message = %q, want %q", msg, "目标目录不存在")
		}
	})
}

// ---- 安全:路径穿越 / inVault ----

func TestVaultInVault(t *testing.T) {
	s := newVaultTestServer(t)
	s.cfg.VaultDir = filepath.Join(s.cfg.DataDir, "VaultRoot")
	vd := s.vaultDir()

	cases := []struct {
		name string
		path string
		want bool
	}{
		{"保险箱根目录", vd, true},
		{"保险箱内部文件", filepath.Join(vd, "sub", "f.txt"), true},
		{"DataDir 其它目录", filepath.Join(s.cfg.DataDir, "other"), false},
		{"同前缀的兄弟目录", vd + "Extra", false},
		{"存储库根", s.cfg.Libraries[0].Path, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := s.inVault(tc.path); got != tc.want {
				t.Fatalf("inVault(%q) = %v, want %v", tc.path, got, tc.want)
			}
		})
	}
}

func TestVaultAbsContainment(t *testing.T) {
	s := newVaultTestServer(t)
	s.cfg.VaultDir = filepath.Join(s.cfg.DataDir, "vaultroot")
	vaultDir := s.vaultDir()
	if err := os.MkdirAll(vaultDir, 0o755); err != nil {
		t.Fatal(err)
	}

	// 拟穿越输入:无论怎么构造,解析结果都必须留在保险箱内(不逃逸)。
	hostile := []string{
		"../x",
		"..",
		"../..",
		"a/../../x",
		`..\..\x`,
		"foo/../bar",
		"C:/evil",
		"/etc/passwd",
	}
	for _, rel := range hostile {
		abs, err := s.vaultAbs(rel)
		if err != nil {
			t.Fatalf("vaultAbs(%q) unexpected error: %v", rel, err)
		}
		if !withinRoot(vaultDir, abs) {
			t.Fatalf("vaultAbs(%q) = %q escapes vault %q", rel, abs, vaultDir)
		}
	}

	// 相邻的机密文件绝不能通过穿越读到。
	secret := filepath.Join(s.cfg.DataDir, "secret.txt")
	if err := os.WriteFile(secret, []byte("TOP-SECRET"), 0o644); err != nil {
		t.Fatal(err)
	}
	abs, err := s.vaultAbs("../secret.txt")
	if err != nil {
		t.Fatalf("vaultAbs(../secret.txt) error: %v", err)
	}
	if filepath.Clean(abs) == filepath.Clean(secret) {
		t.Fatalf("vaultAbs resolved to the adjacent secret file %q", secret)
	}

	// handler 层:带 token 请求 /api/vault/raw?path=../secret.txt 不能返回机密内容。
	mustSetupVault(t, s, "secpass")
	tok := mustUnlockVault(t, s, "secpass")
	rec := httptest.NewRecorder()
	s.handleVaultRaw(rec, httptest.NewRequest(http.MethodGet, "/api/vault/raw?path=../secret.txt&vt="+tok, nil))
	if rec.Code == http.StatusOK && bytes.Contains(rec.Body.Bytes(), []byte("TOP-SECRET")) {
		t.Fatalf("traversal leaked secret content: status=%d body=%q", rec.Code, rec.Body.String())
	}
	if rec.Code != http.StatusNotFound {
		t.Fatalf("traversal raw status = %d, want 404; body: %s", rec.Code, rec.Body.String())
	}

	// mkdir 的根目录守卫:path "." 解析为保险箱根,应被拒绝。
	rec = callVaultHandler(t, s.handleVaultMkdir, http.MethodPost, "/api/vault/mkdir?vt="+tok,
		map[string]string{"path": "."})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("mkdir root status = %d, want 400; body: %s", rec.Code, rec.Body.String())
	}
	if msg := vaultError(t, rec); msg != "路径无效" {
		t.Fatalf("mkdir root message = %q, want %q", msg, "路径无效")
	}
}

// ---- pruneVaultSessions ----

func TestPruneVaultSessions(t *testing.T) {
	resetVaultSessions(t)
	now := time.Now().Unix()

	vault.mu.Lock()
	vault.sessions["expired1"] = now - 10
	vault.sessions["expired2"] = now - 1
	vault.sessions["alive"] = now + 3600
	vault.mu.Unlock()

	removed := pruneVaultSessions()
	if removed != 2 {
		t.Fatalf("pruneVaultSessions removed = %d, want 2", removed)
	}

	vault.mu.Lock()
	_, expired1Exists := vault.sessions["expired1"]
	_, expired2Exists := vault.sessions["expired2"]
	_, aliveExists := vault.sessions["alive"]
	remaining := len(vault.sessions)
	vault.mu.Unlock()

	if expired1Exists || expired2Exists {
		t.Fatalf("expired sessions not pruned: expired1=%v expired2=%v", expired1Exists, expired2Exists)
	}
	if !aliveExists {
		t.Fatalf("unexpired session was incorrectly pruned")
	}
	if remaining != 1 {
		t.Fatalf("remaining sessions = %d, want 1", remaining)
	}
}
