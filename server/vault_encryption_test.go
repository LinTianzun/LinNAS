package main

import (
	"bytes"
	"encoding/base64"
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

// ============================================================
//  M2:保险箱静态加密接入业务路径的回归测试
//  覆盖:上传即加密、列表读 header、下载/Range 解密、改密码不重加密、
//        v1 配置自动升级、旧明文兼容、导入/导出加解密、会话密钥生命周期。
// ============================================================

func uploadVaultFile(t *testing.T, s *Server, tok, dir, name string, data []byte) {
	t.Helper()
	rec := httptest.NewRecorder()
	s.handleVaultUpload(rec, httptest.NewRequest(http.MethodPost,
		"/api/vault/upload?name="+name+"&path="+dir+"&vt="+tok, bytes.NewReader(data)))
	if rec.Code != http.StatusOK {
		t.Fatalf("upload %s status = %d, want 200; body: %s", name, rec.Code, rec.Body.String())
	}
}

func fetchVaultRaw(t *testing.T, s *Server, tok, rel string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	s.handleVaultRaw(rec, httptest.NewRequest(http.MethodGet, "/api/vault/raw?path="+rel+"&vt="+tok, nil))
	return rec
}

func vaultListEntries(t *testing.T, s *Server, tok, dir string) []vaultEntry {
	t.Helper()
	rec := callVaultHandler(t, s.handleVaultList, http.MethodGet, "/api/vault/ls?vt="+tok+"&path="+dir, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("ls %q status = %d, want 200; body: %s", dir, rec.Code, rec.Body.String())
	}
	var ents []vaultEntry
	if err := json.Unmarshal(rec.Body.Bytes(), &ents); err != nil {
		t.Fatalf("decode ls %q: %v", dir, err)
	}
	return ents
}

func readVaultConfigFile(t *testing.T, s *Server) vaultConfig {
	t.Helper()
	raw, err := os.ReadFile(s.vaultCfgPath())
	if err != nil {
		t.Fatalf("read vault.json: %v", err)
	}
	var vc vaultConfig
	if err := json.Unmarshal(raw, &vc); err != nil {
		t.Fatalf("decode vault.json: %v", err)
	}
	return vc
}

// TestVaultUploadEncryptsAtRest 上传后磁盘只有密文,列表/下载/Range 都还原明文。
func TestVaultUploadEncryptsAtRest(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "at-rest-pass")
	tok := mustUnlockVault(t, s, "at-rest-pass")

	plain := make([]byte, 200*1024+37)
	fillPattern(plain)
	uploadVaultFile(t, s, tok, "media", "clip.mp4", plain)

	// 1) 磁盘上是 token 文件名的 .lvault 容器,且不包含明文、不暴露文件名
	mk := vaultTestKey(t, tok)
	abs, isEnc := vaultTestDiskPath(t, s, mk, "media/clip.mp4")
	if !isEnc {
		t.Fatalf("uploaded file is not an encrypted container")
	}
	ents2, derr := os.ReadDir(filepath.Join(s.vaultDir(), "media"))
	if derr != nil || len(ents2) != 1 {
		t.Fatalf("read media dir: ents=%d err=%v", len(ents2), derr)
	}
	diskName := ents2[0].Name()
	if strings.Contains(diskName, "clip") || !strings.HasSuffix(diskName, vaultExt) {
		t.Fatalf("disk file name must be an opaque token, got %q", diskName)
	}
	raw, err := os.ReadFile(abs)
	if err != nil {
		t.Fatalf("encrypted container missing on disk: %v", err)
	}
	if !bytes.HasPrefix(raw, []byte(lvaultMagic)) {
		t.Fatalf("container does not start with lvault magic")
	}
	if bytes.Contains(raw, plain[:512]) {
		t.Fatalf("container leaks plaintext bytes")
	}
	if _, err := os.Stat(filepath.Join(s.vaultDir(), "media", "clip.mp4")); !os.IsNotExist(err) {
		t.Fatalf("plaintext copy must not exist in vault, stat err = %v", err)
	}

	// 2) 列表:显示名去掉后缀、大小取容器头 plainSize、标记加密
	ents := vaultListEntries(t, s, tok, "media")
	if len(ents) != 1 {
		t.Fatalf("ls media = %+v, want exactly 1 entry", ents)
	}
	if e := ents[0]; e.Name != "clip.mp4" || e.Size != int64(len(plain)) || !e.Encrypted || e.IsDir {
		t.Fatalf("ls entry = %+v, want clip.mp4 size %d encrypted", e, len(plain))
	}
	if ents[0].Path != "media/clip.mp4" {
		t.Fatalf("ls path = %q, want %q", ents[0].Path, "media/clip.mp4")
	}

	// 3) 全量下载还原明文
	rec := fetchVaultRaw(t, s, tok, "media/clip.mp4")
	if rec.Code != http.StatusOK {
		t.Fatalf("raw status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	if !bytes.Equal(rec.Body.Bytes(), plain) {
		t.Fatalf("raw body mismatch: got %d bytes", rec.Body.Len())
	}

	// 4) Range 跨块拖动仍正确(模拟视频 seek)
	rangeReq := httptest.NewRequest(http.MethodGet, "/api/vault/raw?path=media/clip.mp4&vt="+tok, nil)
	rangeReq.Header.Set("Range", "bytes=65530-65550")
	rangeRec := httptest.NewRecorder()
	s.handleVaultRaw(rangeRec, rangeReq)
	if rangeRec.Code != http.StatusPartialContent {
		t.Fatalf("range status = %d, want 206; body: %s", rangeRec.Code, rangeRec.Body.String())
	}
	if !bytes.Equal(rangeRec.Body.Bytes(), plain[65530:65551]) {
		t.Fatalf("range body mismatch: %v", rangeRec.Body.Bytes()[:16])
	}

	// 5) 锁定后不能再读
	_ = callVaultHandler(t, s.handleVaultLock, http.MethodPost, "/api/vault/lock?vt="+tok, nil)
	if rec := fetchVaultRaw(t, s, tok, "media/clip.mp4"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("raw after lock status = %d, want 401", rec.Code)
	}
}

// TestVaultChangePasswordKeepsEncryptedFiles 改密码只重新包裹 VMK,不重写容器。
func TestVaultChangePasswordKeepsEncryptedFiles(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "oldpw123")
	oldTok := mustUnlockVault(t, s, "oldpw123")

	plain := make([]byte, 300*1024+11)
	fillPattern(plain)
	uploadVaultFile(t, s, oldTok, "", "movie.mkv", plain)
	abs, _ := vaultTestDiskPath(t, s, vaultTestKey(t, oldTok), "movie.mkv")
	before, err := os.ReadFile(abs)
	if err != nil {
		t.Fatalf("read container before change: %v", err)
	}

	if rec := changeVaultPassword(t, s, "oldpw123", "newpw456"); rec.Code != http.StatusOK {
		t.Fatalf("change password status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}

	// 容器字节完全没变(内容未重加密),且旧会话 / 旧密码都失效
	after, err := os.ReadFile(abs)
	if err != nil {
		t.Fatalf("read container after change: %v", err)
	}
	if !bytes.Equal(before, after) {
		t.Fatalf("change password re-encrypted the container (content changed)")
	}
	if authedWithToken(oldTok) {
		t.Fatalf("old token still valid after password change")
	}
	rec := callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
		map[string]string{"password": "oldpw123"})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("old password unlock status = %d, want 403", rec.Code)
	}

	newTok := mustUnlockVault(t, s, "newpw456")
	got := fetchVaultRaw(t, s, newTok, "movie.mkv")
	if got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), plain) {
		t.Fatalf("file not readable after password change: status=%d", got.Code)
	}

	// vault.json 不再保存 PBKDF2 派生值(它以前就是明文主密钥)
	cfg := readVaultConfigFile(t, s)
	if cfg.Hash != "" {
		t.Fatalf("vault.json still stores legacy hash field: %+v", cfg)
	}
	if cfg.WrappedKey == "" || cfg.KeyNonce == "" || cfg.Version != vaultKeyVersion {
		t.Fatalf("vault.json not upgraded to v2: %+v", cfg)
	}
	vmk, ok := vaultKeyForToken(newTok)
	if !ok {
		t.Fatalf("session has no key after unlock")
	}
	kek := pbkdf2Key([]byte("newpw456"), []byte(cfg.Salt), cfg.Iter, 32)
	if bytes.Equal(vmk, kek) {
		t.Fatalf("VMK equals password-derived key; vault.json would expose the master key")
	}
}

// TestVaultLegacyConfigUpgrade 旧 v1 配置(明文派生值)解锁时自动升级为 v2。
func TestVaultLegacyConfigUpgrade(t *testing.T) {
	s := newVaultTestServer(t)
	salt := "00112233445566778899aabbccddeeff"
	iter := 120000
	derived := pbkdf2Key([]byte("legacypw"), []byte(salt), iter, 32)
	legacy, _ := json.Marshal(map[string]any{"salt": salt, "hash": hex.EncodeToString(derived), "iter": iter})
	if err := os.WriteFile(s.vaultCfgPath(), legacy, 0o600); err != nil {
		t.Fatalf("write legacy vault.json: %v", err)
	}
	if err := os.MkdirAll(s.vaultDir(), 0o755); err != nil {
		t.Fatalf("mkdir vault: %v", err)
	}

	// 错误密码仍然拒绝
	rec := callVaultHandler(t, s.handleVaultUnlock, http.MethodPost, "/api/vault/unlock",
		map[string]string{"password": "wrong"})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("legacy wrong password status = %d, want 403", rec.Code)
	}

	tok := mustUnlockVault(t, s, "legacypw")
	cfg := readVaultConfigFile(t, s)
	if cfg.Hash != "" || cfg.WrappedKey == "" || cfg.Version != vaultKeyVersion {
		t.Fatalf("legacy config was not upgraded: %+v", cfg)
	}

	// 升级后上传 / 读取正常
	plain := bytes.Repeat([]byte("legacy-upgraded"), 1000)
	uploadVaultFile(t, s, tok, "", "after-upgrade.txt", plain)
	got := fetchVaultRaw(t, s, tok, "after-upgrade.txt")
	if got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), plain) {
		t.Fatalf("read after legacy upgrade: status=%d", got.Code)
	}
}

// TestVaultLegacyPlaintextCompat 迁移前遗留的明文文件仍可列表/下载/导出。
func TestVaultLegacyPlaintextCompat(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "compat-pass")
	tok := mustUnlockVault(t, s, "compat-pass")

	legacyPath := filepath.Join(s.vaultDir(), "old.txt")
	if err := os.WriteFile(legacyPath, []byte("legacy-plain"), 0o644); err != nil {
		t.Fatalf("write legacy plaintext: %v", err)
	}
	ents := vaultListEntries(t, s, tok, "")
	if len(ents) != 1 || ents[0].Name != "old.txt" || ents[0].Encrypted || ents[0].Size != int64(len("legacy-plain")) {
		t.Fatalf("ls legacy = %+v, want old.txt plaintext size %d", ents, len("legacy-plain"))
	}
	if got := fetchVaultRaw(t, s, tok, "old.txt"); got.Code != http.StatusOK || got.Body.String() != "legacy-plain" {
		t.Fatalf("raw legacy status = %d body = %q", got.Code, got.Body.String())
	}

	libDir := s.cfg.Libraries[0].Path
	rec := callVaultHandler(t, s.handleVaultExport, http.MethodPost, "/api/vault/export?vt="+tok,
		map[string]any{"paths": []string{"old.txt"}, "dest": "/lib1"})
	if rec.Code != http.StatusOK {
		t.Fatalf("export legacy status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	got, err := os.ReadFile(filepath.Join(libDir, "old.txt"))
	if err != nil || string(got) != "legacy-plain" {
		t.Fatalf("exported legacy content = %q err = %v, want legacy-plain", got, err)
	}
	if _, err := os.Stat(legacyPath); !os.IsNotExist(err) {
		t.Fatalf("legacy file should be removed after export, stat err = %v", err)
	}
}

// TestVaultExportDecryptsContainer 加密文件导出到存储库时还原为明文。
func TestVaultExportDecryptsContainer(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "export-enc")
	tok := mustUnlockVault(t, s, "export-enc")

	plain := make([]byte, 70*1024+9)
	fillPattern(plain)
	uploadVaultFile(t, s, tok, "", "photo.jpg", plain)
	if rec := callVaultHandler(t, s.handleVaultExport, http.MethodPost, "/api/vault/export?vt="+tok,
		map[string]any{"paths": []string{"photo.jpg"}, "dest": "/lib1"}); rec.Code != http.StatusOK {
		t.Fatalf("export status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	libDir := s.cfg.Libraries[0].Path
	got, err := os.ReadFile(filepath.Join(libDir, "photo.jpg"))
	if err != nil {
		t.Fatalf("exported file missing: %v", err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("exported content mismatch: got %d bytes", len(got))
	}
	if _, _, _, err := s.vaultLookup("photo.jpg", vaultTestKey(t, tok)); err == nil {
		t.Fatalf("container should be removed after export")
	}
	if ents := vaultListEntries(t, s, tok, ""); len(ents) != 0 {
		t.Fatalf("vault should be empty after export, got %+v", ents)
	}
}

// TestVaultImportDirectoryEncrypts 目录递归导入:每个文件都变成容器。
func TestVaultImportDirectoryEncrypts(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "import-dir")
	tok := mustUnlockVault(t, s, "import-dir")

	libDir := s.cfg.Libraries[0].Path
	album := filepath.Join(libDir, "album")
	if err := os.MkdirAll(filepath.Join(album, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(album, "a.txt"), []byte("AAA"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(album, "sub", "b.bin"), bytes.Repeat([]byte{9}, 1000), 0o644); err != nil {
		t.Fatal(err)
	}

	if rec := callVaultHandler(t, s.handleVaultImport, http.MethodPost, "/api/vault/import?vt="+tok,
		map[string]any{"vpaths": []string{"/lib1/album"}, "dest": ""}); rec.Code != http.StatusOK {
		t.Fatalf("import status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
	mk := vaultTestKey(t, tok)
	for _, tc := range []struct{ rel, want string }{
		{"album/a.txt", "AAA"},
		{"album/sub/b.bin", ""},
	} {
		if _, isEnc := vaultTestDiskPath(t, s, mk, tc.rel); !isEnc {
			t.Fatalf("%s is not encrypted after import", tc.rel)
		}
		if _, err := os.Stat(filepath.Join(s.vaultDir(), filepath.FromSlash(tc.rel))); !os.IsNotExist(err) {
			t.Fatalf("%s must not be plaintext in vault, stat err = %v", tc.rel, err)
		}
	}
	if got := fetchVaultRaw(t, s, tok, "album/a.txt"); got.Code != http.StatusOK || got.Body.String() != "AAA" {
		t.Fatalf("raw imported a.txt status = %d body = %q", got.Code, got.Body.String())
	}
	if got := fetchVaultRaw(t, s, tok, "album/sub/b.bin"); got.Code != http.StatusOK || got.Body.Len() != 1000 {
		t.Fatalf("raw imported b.bin status = %d len = %d", got.Code, got.Body.Len())
	}
	if _, err := os.Stat(album); !os.IsNotExist(err) {
		t.Fatalf("source album should be removed after import, stat err = %v", err)
	}
}

// TestVaultStreamingContainer 流式写入的容器布局(固定头区)与 ReadSeeker 语义。
func TestVaultStreamingContainer(t *testing.T) {
	plain := make([]byte, 1024*1024+123)
	fillPattern(plain)
	dst := filepath.Join(t.TempDir(), "big.lvault")
	mk := testMasterKey(t)
	n, err := writeEncryptedStream(dst, bytes.NewReader(plain), mk)
	if err != nil {
		t.Fatalf("writeEncryptedStream: %v", err)
	}
	if n != int64(len(plain)) {
		t.Fatalf("writeEncryptedStream n = %d, want %d", n, len(plain))
	}
	f, err := os.Open(dst)
	if err != nil {
		t.Fatalf("open container: %v", err)
	}
	h, dataStart, err := readLvaultHeader(f)
	_ = f.Close()
	if err != nil {
		t.Fatalf("readLvaultHeader: %v", err)
	}
	if dataStart != lvaultHeaderReserve {
		t.Fatalf("dataStart = %d, want reserved %d", dataStart, lvaultHeaderReserve)
	}
	if h.PlainSize != int64(len(plain)) || h.BlockSize != lvaultBlockSize {
		t.Fatalf("header = %+v, want plainSize %d blockSize %d", h, len(plain), lvaultBlockSize)
	}

	ef, err := openEncryptedFile(dst, mk)
	if err != nil {
		t.Fatalf("openEncryptedFile: %v", err)
	}
	defer func() { _ = ef.Close() }()
	if off, serr := ef.Seek(0, io.SeekEnd); serr != nil || off != int64(len(plain)) {
		t.Fatalf("Seek(0,End) = (%d,%v), want (%d,nil)", off, serr, len(plain))
	}
	if off, serr := ef.Seek(-10, io.SeekEnd); serr != nil || off != int64(len(plain))-10 {
		t.Fatalf("Seek(-10,End) = (%d,%v), want %d", off, serr, len(plain)-10)
	}
	if _, serr := ef.Seek(0, io.SeekStart); serr != nil {
		t.Fatalf("Seek(0,Start): %v", serr)
	}
	got, err := io.ReadAll(ef)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("sequential read mismatch: got %d bytes", len(got))
	}
}

// TestVaultSessionKeyLifecycle 会话主密钥随解锁/锁定/过期正确建立与清除。
func TestVaultSessionKeyLifecycle(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "session-key")
	tok := mustUnlockVault(t, s, "session-key")

	key, ok := vaultKeyForToken(tok)
	if !ok || len(key) != 32 {
		t.Fatalf("vaultKeyForToken = (%d bytes, %v), want 32-byte key", len(key), ok)
	}
	if _, ok := vaultKeyForToken("deadbeef"); ok {
		t.Fatalf("bogus token must not have a key")
	}

	dropVaultSession(tok)
	if _, ok := vaultKeyForToken(tok); ok {
		t.Fatalf("key still available after dropVaultSession")
	}

	setVaultSession("expired", time.Now().Add(-time.Second).Unix(), testMasterKey(t))
	if _, ok := vaultKeyForToken("expired"); ok {
		t.Fatalf("expired session still yields a key")
	}

	vault.mu.Lock()
	vault.sessions["nokey"] = time.Now().Add(time.Hour).Unix()
	vault.mu.Unlock()
	if _, ok := vaultKeyForToken("nokey"); ok {
		t.Fatalf("session without key must be treated as locked")
	}

	setVaultSession("expired2", time.Now().Add(-time.Second).Unix(), testMasterKey(t))
	if removed := pruneVaultSessions(); removed != 1 {
		t.Fatalf("pruneVaultSessions removed = %d, want 1", removed)
	}
	vault.mu.Lock()
	_, hasKey := vault.keys["expired2"]
	vault.mu.Unlock()
	if hasKey {
		t.Fatalf("pruned session key was not cleared")
	}
}

// TestVaultThumb 校验保险箱缩略图:未解锁 401、非媒体 415、图片返回 JPEG。
func TestVaultThumb(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "thumb-pass")
	tok := mustUnlockVault(t, s, "thumb-pass")

	rec := httptest.NewRecorder()
	s.handleVaultThumb(rec, httptest.NewRequest(http.MethodGet, "/api/vault/thumb?path=a.png", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauth thumb status = %d, want 401", rec.Code)
	}

	uploadVaultFile(t, s, tok, "", "note.txt", []byte("hello"))
	rec = httptest.NewRecorder()
	s.handleVaultThumb(rec, httptest.NewRequest(http.MethodGet, "/api/vault/thumb?path=note.txt&vt="+tok, nil))
	if rec.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("non-media thumb status = %d, want 415; body: %s", rec.Code, rec.Body.String())
	}

	if findFFmpeg() == "" {
		t.Skip("ffmpeg 不可用,跳过缩略图生成断言")
	}
	uploadVaultFile(t, s, tok, "", "tiny.png", tinyPNGForThumb)
	rec = httptest.NewRecorder()
	s.handleVaultThumb(rec, httptest.NewRequest(http.MethodGet, "/api/vault/thumb?path=tiny.png&vt="+tok, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("thumb status = %d; body: %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "image/jpeg" {
		t.Fatalf("thumb content-type = %q, want image/jpeg", ct)
	}
	if rec.Body.Len() == 0 {
		t.Fatalf("thumb body is empty")
	}

	// 二次请求命中内存缓存
	rec2 := httptest.NewRecorder()
	s.handleVaultThumb(rec2, httptest.NewRequest(http.MethodGet, "/api/vault/thumb?path=tiny.png&vt="+tok, nil))
	if rec2.Code != http.StatusOK || rec2.Body.Len() != rec.Body.Len() {
		t.Fatalf("cached thumb = (%d, %d bytes), want 200 and same size", rec2.Code, rec2.Body.Len())
	}

	// 锁定后清空缓存且拒绝访问
	_ = callVaultHandler(t, s.handleVaultLock, http.MethodPost, "/api/vault/lock?vt="+tok, nil)
	rec3 := httptest.NewRecorder()
	s.handleVaultThumb(rec3, httptest.NewRequest(http.MethodGet, "/api/vault/thumb?path=tiny.png&vt="+tok, nil))
	if rec3.Code != http.StatusUnauthorized {
		t.Fatalf("thumb after lock status = %d, want 401", rec3.Code)
	}
}

// tinyPNGForThumb 是 1x1 透明 PNG,用于缩略图测试。
var tinyPNGForThumb, _ = base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

// vaultTestKey 取解锁会话的主密钥(测试内部使用)。
func vaultTestKey(t *testing.T, tok string) []byte {
	t.Helper()
	mk, ok := vaultKeyForToken(tok)
	if !ok || len(mk) != 32 {
		t.Fatalf("session %q has no 32-byte key", tok)
	}
	return mk
}

// vaultTestDiskPath 按显示路径解析磁盘真实路径,失败即终止测试。
func vaultTestDiskPath(t *testing.T, s *Server, mk []byte, rel string) (string, bool) {
	t.Helper()
	abs, enc, _, err := s.vaultLookup(rel, mk)
	if err != nil {
		t.Fatalf("vaultLookup(%q): %v", rel, err)
	}
	return abs, enc
}

// assertNoDisplayNameOnDisk 断言目录里没有以显示名命名的磁盘文件(文件名已加密)。
func assertNoDisplayNameOnDisk(t *testing.T, dir, display string) {
	t.Helper()
	ents, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read dir %q: %v", dir, err)
	}
	for _, e := range ents {
		if e.Name() == display || strings.HasPrefix(e.Name(), display) {
			t.Fatalf("display name %q leaked into disk file name %q", display, e.Name())
		}
	}
}

// TestVaultSealsDisplayName 验证显示名的加解密与 header 落盘。
func TestVaultSealsDisplayName(t *testing.T) {
	mk := testMasterKey(t)
	name := "机密 视频 2024.mp4"
	enc, nonce, err := sealVaultName(mk, name)
	if err != nil {
		t.Fatalf("sealVaultName: %v", err)
	}
	if enc == "" || nonce == "" {
		t.Fatalf("sealVaultName returned empty values")
	}
	got, err := openVaultName(mk, enc, nonce)
	if err != nil || got != name {
		t.Fatalf("openVaultName = (%q,%v), want %q", got, err, name)
	}
	wrong := testMasterKey(t)
	wrong[0] ^= 0xFF
	if _, err := openVaultName(wrong, enc, nonce); err == nil {
		t.Fatalf("openVaultName with wrong key should fail")
	}

	// 写一个带显示名的容器:header 里有 name,磁盘文件名不含显示名
	dir := t.TempDir()
	dst := filepath.Join(dir, "token.lvault")
	plain := bytes.Repeat([]byte("z"), 1000)
	if _, err := writeEncryptedStreamNamed(dst, bytes.NewReader(plain), mk, name); err != nil {
		t.Fatalf("writeEncryptedStreamNamed: %v", err)
	}
	h, err := vaultContainerHeader(dst)
	if err != nil {
		t.Fatalf("vaultContainerHeader: %v", err)
	}
	if h.Name == "" || h.NameNonce == "" {
		t.Fatalf("header has no encrypted name: %+v", h)
	}
	disp, err := openVaultName(mk, h.Name, h.NameNonce)
	if err != nil || disp != name {
		t.Fatalf("header name = (%q,%v), want %q", disp, err, name)
	}
	raw, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("read container: %v", err)
	}
	if raw[8] != lvaultVersion {
		t.Fatalf("container version = %d, want %d", raw[8], lvaultVersion)
	}
	if !bytes.Contains(raw, []byte(name)) {
		// 显示名本身也不该以明文出现在文件里 —— header 只应有密文。
	} else {
		t.Fatalf("container leaks plaintext display name")
	}
}

// TestVaultMigration 覆盖旧明文文件 + 旧格式容器的迁移(干跑 + 确认执行)。
func TestVaultMigration(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "migrate-pass")
	tok := mustUnlockVault(t, s, "migrate-pass")
	mk := vaultTestKey(t, tok)

	// 旧明文文件
	plainContent := []byte("old plaintext content")
	if err := os.WriteFile(filepath.Join(s.vaultDir(), "old.txt"), plainContent, 0o644); err != nil {
		t.Fatalf("write legacy plaintext: %v", err)
	}
	// 旧格式容器(header 无 name 字段)
	legacyContent := bytes.Repeat([]byte("legacy"), 200)
	legacyPath := filepath.Join(s.vaultDir(), "legacy.bin"+vaultExt)
	if _, err := writeEncryptedToFile(legacyPath, bytes.NewReader(legacyContent), mk); err != nil {
		t.Fatalf("write legacy container: %v", err)
	}

	// 干跑:只统计,不动文件
	rec := callVaultHandler(t, s.handleVaultMigrate, http.MethodPost, "/api/vault/migrate?vt="+tok,
		map[string]any{"confirm": false})
	if rec.Code != http.StatusOK {
		t.Fatalf("dry-run status = %d; body: %s", rec.Code, rec.Body.String())
	}
	var dry struct {
		Files   int   `json:"files"`
		Bytes   int64 `json:"bytes"`
		Plain   int   `json:"plaintext"`
		Legacy  int   `json:"legacyContainers"`
		Confirm bool  `json:"confirm"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &dry); err != nil {
		t.Fatalf("decode dry-run: %v", err)
	}
	if dry.Confirm || dry.Files != 2 || dry.Plain != 1 || dry.Legacy != 1 {
		t.Fatalf("dry-run = %+v, want files=2 plaintext=1 legacy=1 confirm=false", dry)
	}
	if dry.Bytes != int64(len(plainContent)+len(legacyContent)) {
		t.Fatalf("dry-run bytes = %d, want %d", dry.Bytes, len(plainContent)+len(legacyContent))
	}
	if _, err := os.Stat(filepath.Join(s.vaultDir(), "old.txt")); err != nil {
		t.Fatalf("dry-run must not touch files: %v", err)
	}

	// 确认执行
	rec = callVaultHandler(t, s.handleVaultMigrate, http.MethodPost, "/api/vault/migrate?vt="+tok,
		map[string]any{"confirm": true})
	if rec.Code != http.StatusOK {
		t.Fatalf("migrate status = %d; body: %s", rec.Code, rec.Body.String())
	}
	var res struct {
		OK       bool     `json:"ok"`
		Migrated int      `json:"migrated"`
		Failed   int      `json:"failed"`
		Errors   []string `json:"errors"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("decode migrate: %v", err)
	}
	if !res.OK || res.Migrated != 2 || res.Failed != 0 {
		t.Fatalf("migrate = %+v, want ok migrated=2", res)
	}

	// 旧文件消失,列表显示原名,下载内容正确
	for _, p := range []string{"old.txt", "legacy.bin" + vaultExt} {
		if _, err := os.Stat(filepath.Join(s.vaultDir(), p)); !os.IsNotExist(err) {
			t.Fatalf("legacy file %q should be removed, stat err = %v", p, err)
		}
	}
	ents := vaultListEntries(t, s, tok, "")
	gotNames := map[string]vaultEntry{}
	for _, e := range ents {
		gotNames[e.Name] = e
	}
	if e, ok := gotNames["old.txt"]; !ok || !e.Encrypted || e.Size != int64(len(plainContent)) {
		t.Fatalf("list old.txt = %+v ok=%v, want encrypted size %d", e, ok, len(plainContent))
	}
	if e, ok := gotNames["legacy.bin"]; !ok || !e.Encrypted || e.Size != int64(len(legacyContent)) {
		t.Fatalf("list legacy.bin = %+v ok=%v, want encrypted size %d", e, ok, len(legacyContent))
	}
	if got := fetchVaultRaw(t, s, tok, "old.txt"); got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), plainContent) {
		t.Fatalf("raw old.txt status=%d", got.Code)
	}
	if got := fetchVaultRaw(t, s, tok, "legacy.bin"); got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), legacyContent) {
		t.Fatalf("raw legacy.bin status=%d", got.Code)
	}
	assertNoDisplayNameOnDisk(t, s.vaultDir(), "old.txt")
	assertNoDisplayNameOnDisk(t, s.vaultDir(), "legacy.bin")

	// 再次干跑应为 0(幂等)
	rec = callVaultHandler(t, s.handleVaultMigrate, http.MethodPost, "/api/vault/migrate?vt="+tok,
		map[string]any{"confirm": false})
	if err := json.Unmarshal(rec.Body.Bytes(), &dry); err != nil {
		t.Fatalf("decode second dry-run: %v", err)
	}
	if dry.Files != 0 {
		t.Fatalf("second dry-run files = %d, want 0", dry.Files)
	}
}

// TestVaultMigrationNoop 新格式容器无需迁移。
func TestVaultMigrationNoop(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "migrate-noop")
	tok := mustUnlockVault(t, s, "migrate-noop")
	uploadVaultFile(t, s, tok, "", "new.bin", []byte("already encrypted"))

	rec := callVaultHandler(t, s.handleVaultMigrate, http.MethodPost, "/api/vault/migrate?vt="+tok, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d; body: %s", rec.Code, rec.Body.String())
	}
	var dry struct {
		Files int `json:"files"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &dry); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if dry.Files != 0 {
		t.Fatalf("dry-run files = %d, want 0", dry.Files)
	}
}

// TestVaultMigrationRequiresUnlock 未解锁不能迁移。
func TestVaultMigrationRequiresUnlock(t *testing.T) {
	s := newVaultTestServer(t)
	mustSetupVault(t, s, "migrate-locked")
	rec := callVaultHandler(t, s.handleVaultMigrate, http.MethodPost, "/api/vault/migrate", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}
