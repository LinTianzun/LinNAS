package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// sha256FileHex 计算整个文件的 SHA-256(测试用参照实现)。
func sha256FileHex(p string) (string, error) {
	raw, err := os.ReadFile(p)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:]), nil
}

// newPatrolTestServer 构造带索引与巡检管理器的测试服务(单库 lib1,路径 root/lib)。
func newPatrolTestServer(t *testing.T) (*Server, *Index, string) {
	t.Helper()
	root := t.TempDir()
	libDir := filepath.Join(root, "lib")
	if err := os.MkdirAll(libDir, 0o755); err != nil {
		t.Fatal(err)
	}
	ix, err := OpenIndex(filepath.Join(root, "index"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ix.Close() })
	s := &Server{
		cfg: &Config{
			DataDir:   root,
			Libraries: []Library{{ID: "lib1", Name: "测试库", Path: libDir, Enabled: true}},
		},
		index:  ix,
		patrol: newPatrolManager(root),
	}
	return s, ix, libDir
}

// seedPatrolFile 在磁盘写一个文件并在索引里插入对应行(is_dir=0)。
func seedPatrolFile(t *testing.T, ix *Index, libID, libDir, name, content string) {
	t.Helper()
	p := filepath.Join(libDir, name)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	vpath := "/" + libID + "/" + filepath.ToSlash(name)
	if _, err := ix.db.Exec(`INSERT OR REPLACE INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,0,?,?,?)`,
		libID, vpath, "/"+libID, filepath.Base(name), info.Size(), info.ModTime().Unix(), "other"); err != nil {
		t.Fatal(err)
	}
}

// TestQuickSigContentAndMtime quick 签名:内容变化敏感、仅 mtime 变化不敏感。
func TestQuickSigContentAndMtime(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "f.bin")
	mustWrite(t, p, "abcdefghij")
	info, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	s1, err := quickSig(p, info.Size())
	if err != nil {
		t.Fatal(err)
	}
	// 仅改变 mtime:签名不变。
	newT := info.ModTime().Add(2 * time.Hour)
	if err := os.Chtimes(p, newT, newT); err != nil {
		t.Fatal(err)
	}
	s2, err := quickSig(p, info.Size())
	if err != nil {
		t.Fatal(err)
	}
	if s1 != s2 {
		t.Errorf("仅 mtime 变化不应改变 quick 签名: %s vs %s", s1, s2)
	}
	// 改 1 字节(同长度):签名必须变化。
	mustWrite(t, p, "abcdefghiJ")
	s3, err := quickSig(p, info.Size())
	if err != nil {
		t.Fatal(err)
	}
	if s3 == s1 {
		t.Errorf("内容变化(1 字节)应改变 quick 签名: %s", s3)
	}
	// 不存在的文件应报错。
	if _, err := quickSig(filepath.Join(dir, "missing.bin"), 1); err == nil {
		t.Error("不存在文件的 quick 签名应返回错误")
	}
}

// TestDeepSigRespectsCancel 已取消的深哈希应立即返回取消错误。
func TestDeepSigRespectsCancel(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "big.bin")
	if err := os.WriteFile(p, bytes.Repeat([]byte("a"), 4<<20), 0o644); err != nil {
		t.Fatal(err)
	}
	cancel := make(chan struct{})
	close(cancel)
	if _, err := deepSig(p, cancel); !errors.Is(err, errPatrolCancelled) {
		t.Fatalf("已取消的 deep 应返回 errPatrolCancelled,得到 %v", err)
	}
}

// TestDeepSigMatchesFullHash deep 签名应等于整文件 SHA-256(无取消)。
func TestDeepSigMatchesFullHash(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "s.bin")
	mustWrite(t, p, "hello deep patrol")
	got, err := deepSig(p, make(chan struct{}))
	if err != nil {
		t.Fatal(err)
	}
	want, err := sha256FileHex(p)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Errorf("deep 签名 = %s, want %s", got, want)
	}
}

// TestPatrolBaselineThenChangedThenMissing 首轮基线不报变化;改内容报变化;删除报缺失。
func TestPatrolBaselineThenChangedThenMissing(t *testing.T) {
	s, ix, libDir := newPatrolTestServer(t)
	seedPatrolFile(t, ix, "lib1", libDir, "a.txt", "hello")
	seedPatrolFile(t, ix, "lib1", libDir, "b.txt", "world")

	// 首轮:基线,不报 Changed。
	res := s.patrolOnce("quick", "lib1", nil, nil)
	if res.Total != 2 {
		t.Fatalf("total = %d, want 2", res.Total)
	}
	if len(res.Changed) != 0 {
		t.Errorf("首轮基线不应报变化,得到 %#v", res.Changed)
	}
	if len(res.Missing) != 0 || len(res.Failed) != 0 {
		t.Errorf("首轮不应有缺失/失败: missing=%#v failed=%#v", res.Missing, res.Failed)
	}
	// 基线应已入库。
	var sigCount int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM file_sig WHERE lib_id = 'lib1'`).Scan(&sigCount); err != nil {
		t.Fatal(err)
	}
	if sigCount != 2 {
		t.Fatalf("首轮后应写入 2 条基线,得到 %d", sigCount)
	}

	// 改内容(同长度)→ 第二轮报 1 个变化。
	if err := os.WriteFile(filepath.Join(libDir, "a.txt"), []byte("HELLO"), 0o644); err != nil {
		t.Fatal(err)
	}
	res = s.patrolOnce("quick", "lib1", nil, nil)
	if len(res.Changed) != 1 || res.Changed[0].Path != "/lib1/a.txt" {
		t.Fatalf("改内容后应报 1 个变化,得到 %#v", res.Changed)
	}
	if res.Changed[0].Reason != "内容已变化" {
		t.Errorf("变化 reason = %q, want 内容已变化", res.Changed[0].Reason)
	}

	// 再跑一次:签名已更新,不应重复报变化。
	res = s.patrolOnce("quick", "lib1", nil, nil)
	if len(res.Changed) != 0 {
		t.Errorf("基线更新后不应重复报变化: %#v", res.Changed)
	}

	// 删除 b.txt → Missing。
	if err := os.Remove(filepath.Join(libDir, "b.txt")); err != nil {
		t.Fatal(err)
	}
	res = s.patrolOnce("quick", "lib1", nil, nil)
	if len(res.Missing) != 1 || res.Missing[0].Path != "/lib1/b.txt" {
		t.Fatalf("删除后应报 1 个缺失,得到 %#v", res.Missing)
	}
	if res.Missing[0].Reason != "文件缺失" {
		t.Errorf("缺失 reason = %q, want 文件缺失", res.Missing[0].Reason)
	}
}

// TestPatrolHistoryCap 历史落盘只保留最近 20 条,readHistory 最新在前。
func TestPatrolHistoryCap(t *testing.T) {
	dir := t.TempDir()
	m := newPatrolManager(dir)
	for i := 1; i <= 25; i++ {
		m.appendHistory(patrolHistoryItem{
			StartedAt: int64(i), FinishedAt: int64(i + 1), Mode: "quick", Total: i,
		})
	}
	doc := m.loadHistory()
	if len(doc.Items) != patrolHistoryMax {
		t.Fatalf("历史应保留 %d 条,得到 %d", patrolHistoryMax, len(doc.Items))
	}
	if doc.Items[0].StartedAt != 6 || doc.Items[len(doc.Items)-1].StartedAt != 25 {
		t.Errorf("应保留最近 20 条(6..25),得到首=%d 末=%d",
			doc.Items[0].StartedAt, doc.Items[len(doc.Items)-1].StartedAt)
	}
	items := m.readHistory(3)
	if len(items) != 3 || items[0].StartedAt != 25 || items[2].StartedAt != 23 {
		t.Errorf("readHistory 应最新在前,得到 %#v", items)
	}
	// 落盘 JSON 可被解析且条数正确。
	raw, err := os.ReadFile(m.path)
	if err != nil {
		t.Fatal(err)
	}
	var doc2 patrolHistoryDoc
	if err := json.Unmarshal(raw, &doc2); err != nil {
		t.Fatalf("patrol.json 不是合法 JSON: %v", err)
	}
	if len(doc2.Items) != patrolHistoryMax {
		t.Errorf("落盘历史条数 = %d, want %d", len(doc2.Items), patrolHistoryMax)
	}
}

// TestPatrolStatusShape 未运行时的状态字段齐全,数组字段为 []。
func TestPatrolStatusShape(t *testing.T) {
	s, _, _ := newPatrolTestServer(t)
	rec := httptest.NewRecorder()
	s.handlePatrolStatus(rec, httptest.NewRequest(http.MethodGet, "/api/storage/patrol/status", nil))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, want 200", rec.Code)
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	for _, k := range []string{
		"running", "mode", "libId", "startedAt", "finishedAt",
		"done", "total", "current", "changed", "missing", "failed", "lastError",
	} {
		if _, ok := raw[k]; !ok {
			t.Errorf("status 缺少字段 %q: %s", k, rec.Body.String())
		}
	}
	for _, k := range []string{"changed", "missing", "failed"} {
		if _, ok := raw[k].([]any); !ok {
			t.Errorf("%s 应为数组: %s", k, rec.Body.String())
		}
	}
}

// TestPatrolCancelNoTask 无任务时取消也返回 200 ok。
func TestPatrolCancelNoTask(t *testing.T) {
	s, _, _ := newPatrolTestServer(t)
	rec := httptest.NewRecorder()
	s.handlePatrolCancel(rec, httptest.NewRequest(http.MethodPost, "/api/storage/patrol/cancel", nil))
	if rec.Code != 200 {
		t.Fatalf("无任务取消应 200,得到 %d", rec.Code)
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatal(err)
	}
	if raw["ok"] != true {
		t.Errorf("取消响应应为 ok:true,得到 %s", rec.Body.String())
	}
}

// TestPatrolStartValidationAndConflict 非法 mode→400;正常启动→200;运行中→409;完成后落 last。
func TestPatrolStartValidationAndConflict(t *testing.T) {
	s, ix, libDir := newPatrolTestServer(t)
	seedPatrolFile(t, ix, "lib1", libDir, "a.txt", "x")

	// 非法 mode → 400
	rec := httptest.NewRecorder()
	s.handlePatrolStart(rec, httptest.NewRequest(http.MethodPost, "/api/storage/patrol/start?mode=bogus&lib=lib1", nil))
	if rec.Code != 400 {
		t.Fatalf("非法 mode 应 400,得到 %d: %s", rec.Code, rec.Body.String())
	}

	// 正常启动
	rec = httptest.NewRecorder()
	s.handlePatrolStart(rec, httptest.NewRequest(http.MethodPost, "/api/storage/patrol/start?mode=quick&lib=lib1", nil))
	if rec.Code != 200 {
		t.Fatalf("启动应 200,得到 %d: %s", rec.Code, rec.Body.String())
	}
	var startResp map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &startResp)
	if startResp["ok"] != true || startResp["mode"] != "quick" {
		t.Errorf("启动响应不正确: %s", rec.Body.String())
	}

	// 等待巡检完成(小文件应很快)。
	deadline := time.Now().Add(5 * time.Second)
	for {
		s.patrol.mu.Lock()
		running := s.patrol.st.Running
		s.patrol.mu.Unlock()
		if !running {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("巡检未在预期时间内完成")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// 完成后 last 应有 1 条 quick 记录。
	rec = httptest.NewRecorder()
	s.handlePatrolLast(rec, httptest.NewRequest(http.MethodGet, "/api/storage/patrol/last?limit=20", nil))
	var lastResp struct {
		Items []patrolHistoryItem `json:"items"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &lastResp); err != nil {
		t.Fatalf("last 响应不是合法 JSON: %v", err)
	}
	if len(lastResp.Items) != 1 {
		t.Fatalf("last 应有 1 条,得到 %d", len(lastResp.Items))
	}
	if lastResp.Items[0].Mode != "quick" || lastResp.Items[0].Cancelled {
		t.Errorf("last 记录不正确: %#v", lastResp.Items[0])
	}

	// 运行中再次启动 → 409,运行中取消 → 200。
	s.patrol.mu.Lock()
	s.patrol.st.Running = true
	s.patrol.mu.Unlock()
	rec = httptest.NewRecorder()
	s.handlePatrolStart(rec, httptest.NewRequest(http.MethodPost, "/api/storage/patrol/start?mode=quick&lib=lib1", nil))
	if rec.Code != 409 {
		t.Fatalf("运行中启动应 409,得到 %d: %s", rec.Code, rec.Body.String())
	}
	var errResp map[string]string
	_ = json.Unmarshal(rec.Body.Bytes(), &errResp)
	if errResp["error"] != "patrol already running" {
		t.Errorf("冲突错误信息 = %q, want patrol already running", errResp["error"])
	}

	rec = httptest.NewRecorder()
	s.handlePatrolCancel(rec, httptest.NewRequest(http.MethodPost, "/api/storage/patrol/cancel", nil))
	if rec.Code != 200 {
		t.Fatalf("运行中取消应 200,得到 %d", rec.Code)
	}
	if !s.patrol.st.Cancel {
		t.Errorf("取消后应置 Cancel=true")
	}
	// 复位(测试收尾,避免遗留假运行状态)。
	s.patrol.mu.Lock()
	s.patrol.st.Running = false
	s.patrol.st.Cancel = false
	s.patrol.cancelCh = nil
	s.patrol.mu.Unlock()
}

// TestPatrolModeSwitchNoFalseChanged quick 建立基线后切 deep、再切回 quick,
// 都不得把文件误报为「内容已变化」;切模式后真实的改动仍要能检测到。
func TestPatrolModeSwitchNoFalseChanged(t *testing.T) {
	s, ix, libDir := newPatrolTestServer(t)
	seedPatrolFile(t, ix, "lib1", libDir, "a.txt", "hello")
	seedPatrolFile(t, ix, "lib1", libDir, "b.txt", "world")

	if res := s.patrolOnce("quick", "lib1", nil, nil); len(res.Changed) != 0 {
		t.Fatalf("quick 首轮不应报变化: %#v", res.Changed)
	}
	if res := s.patrolOnce("deep", "lib1", nil, nil); len(res.Changed) != 0 {
		t.Fatalf("切换 deep 后不应误报变化: %#v", res.Changed)
	}
	if res := s.patrolOnce("quick", "lib1", nil, nil); len(res.Changed) != 0 {
		t.Fatalf("切回 quick 后不应误报变化: %#v", res.Changed)
	}

	// 切模式后真实的改动仍能被检测到。
	if err := os.WriteFile(filepath.Join(libDir, "a.txt"), []byte("HELLO"), 0o644); err != nil {
		t.Fatal(err)
	}
	res := s.patrolOnce("quick", "lib1", nil, nil)
	if len(res.Changed) != 1 || res.Changed[0].Path != "/lib1/a.txt" {
		t.Fatalf("切模式后真实改动应报 1 个变化,得到 %#v", res.Changed)
	}
}
