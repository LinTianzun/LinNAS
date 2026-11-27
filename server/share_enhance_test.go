package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newShareTestServer 构造一个带单存储库的分享测试环境。
func newShareTestServer(t *testing.T) (*Server, *Config, string) {
	t.Helper()
	dataDir := t.TempDir()
	root := t.TempDir()
	cfg := &Config{
		DataDir:   dataDir,
		Libraries: []Library{{ID: "lib1", Name: "test", Path: root, Enabled: true}},
	}
	s := &Server{cfg: cfg, shares: newShareStore(dataDir)}
	return s, cfg, root
}

func TestShareDownloadLimit(t *testing.T) {
	s, _, root := newShareTestServer(t)
	name := "a.txt"
	if err := os.WriteFile(filepath.Join(root, name), []byte("hello"), 0o644); err != nil {
		t.Fatalf("写入测试文件失败: %v", err)
	}
	sh := Share{ID: "s1", VPath: "/lib1/" + name, Name: name, AllowDownload: true, MaxDownloads: 2}
	if err := s.shares.Add(sh); err != nil {
		t.Fatalf("添加分享失败: %v", err)
	}
	if ok, rem := s.shares.ConsumeDownload("s1"); !ok || rem != 1 {
		t.Fatalf("第一次下载应成功,剩余 1; ok=%v rem=%d", ok, rem)
	}
	if ok, rem := s.shares.ConsumeDownload("s1"); !ok || rem != 0 {
		t.Fatalf("第二次下载应成功,剩余 0; ok=%v rem=%d", ok, rem)
	}
	if ok, _ := s.shares.ConsumeDownload("s1"); ok {
		t.Fatal("达到上限后不应再允许下载")
	}
	// 达到上限后链接整体失效。
	if s.shares.Get("s1") != nil {
		t.Fatal("达到下载上限后分享应失效")
	}
}

func TestShareOneTimeLink(t *testing.T) {
	s, _, _ := newShareTestServer(t)
	if err := s.shares.Add(Share{ID: "s2", VPath: "/lib1/a.txt", Name: "a.txt", AllowDownload: true, OneTime: true}); err != nil {
		t.Fatalf("添加分享失败: %v", err)
	}
	if ok, _ := s.shares.ConsumeDownload("s2"); !ok {
		t.Fatal("单次链接第一次下载应成功")
	}
	if ok, _ := s.shares.ConsumeDownload("s2"); ok {
		t.Fatal("单次链接第二次下载应被拒绝")
	}
	if s.shares.Get("s2") != nil {
		t.Fatal("单次链接使用后应失效")
	}
}

func TestShareVisitAndStats(t *testing.T) {
	s, _, _ := newShareTestServer(t)
	if err := s.shares.Add(Share{ID: "s3", VPath: "/lib1/a.txt", Name: "a.txt", AllowDownload: true}); err != nil {
		t.Fatalf("添加分享失败: %v", err)
	}
	s.shares.RecordVisit("s3", "192.168.1.9")
	s.shares.RecordVisit("s3", "192.168.1.9")
	list := s.shares.List()
	if len(list) != 1 {
		t.Fatalf("列表长度 = %d", len(list))
	}
	if v, _ := list[0]["visits"].(int64); v != 2 {
		t.Fatalf("visits = %v", list[0]["visits"])
	}
	if v, _ := list[0]["lastVisitIp"].(string); v != "192.168.1.9" {
		t.Fatalf("lastVisitIp = %v", list[0]["lastVisitIp"])
	}
}

func TestShareBatchDelete(t *testing.T) {
	s, _, _ := newShareTestServer(t)
	for _, id := range []string{"b1", "b2", "b3"} {
		if err := s.shares.Add(Share{ID: id, VPath: "/lib1/a.txt", Name: id, CreatedBy: "alice"}); err != nil {
			t.Fatalf("添加分享失败: %v", err)
		}
	}
	// 非管理员且不是创建者:不能撤销。
	req := httptest.NewRequest(http.MethodDelete, "/api/shares?ids=b1,b2", nil)
	req = req.WithContext(withUser(req.Context(), &Session{Username: "bob", Role: "user"}))
	rec := httptest.NewRecorder()
	s.handleSharesDelete(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("他人分享不应可撤销, status=%d", rec.Code)
	}
	// 管理员:批量撤销。
	req2 := httptest.NewRequest(http.MethodDelete, "/api/shares?ids=b1,b2", nil)
	req2 = req2.WithContext(withUser(req2.Context(), &Session{Username: "root", Role: "admin"}))
	rec2 := httptest.NewRecorder()
	s.handleSharesDelete(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("批量撤销失败 status=%d body=%s", rec2.Code, rec2.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rec2.Body.Bytes(), &out); err != nil {
		t.Fatalf("响应不是 JSON: %v", err)
	}
	if v, _ := out["deleted"].(float64); v != 2 {
		t.Fatalf("deleted = %v", out["deleted"])
	}
	if len(s.shares.List()) != 1 {
		t.Fatalf("应只剩 1 条分享, 实际 %d", len(s.shares.List()))
	}
}

func TestShareRawRejectsExhaustedLink(t *testing.T) {
	s, _, root := newShareTestServer(t)
	if err := os.WriteFile(filepath.Join(root, "b.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatalf("写入测试文件失败: %v", err)
	}
	if err := s.shares.Add(Share{ID: "s4", VPath: "/lib1/b.txt", Name: "b.txt", AllowDownload: true, OneTime: true}); err != nil {
		t.Fatalf("添加分享失败: %v", err)
	}
	// 首次下载:通过。
	req := httptest.NewRequest(http.MethodGet, "/api/share/s4/raw?dl=1", nil)
	req.SetPathValue("id", "s4")
	rec := httptest.NewRecorder()
	s.handleShareRaw(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("首次下载应成功 status=%d", rec.Code)
	}
	// 二次下载:拒绝。
	req2 := httptest.NewRequest(http.MethodGet, "/api/share/s4/raw?dl=1", nil)
	req2.SetPathValue("id", "s4")
	rec2 := httptest.NewRecorder()
	s.handleShareRaw(rec2, req2)
	if rec2.Code != http.StatusForbidden {
		t.Fatalf("单次链接二次下载应被拒 status=%d", rec2.Code)
	}
}

func TestShareExpiredStillDenied(t *testing.T) {
	s, _, _ := newShareTestServer(t)
	past := time.Now().Add(-time.Hour).Unix()
	if err := s.shares.Add(Share{ID: "s5", VPath: "/lib1/a.txt", Name: "a.txt", AllowDownload: true, ExpiresAt: past}); err != nil {
		t.Fatalf("添加分享失败: %v", err)
	}
	if ok, _ := s.shares.ConsumeDownload("s5"); ok {
		t.Fatal("过期分享不应允许下载")
	}
	if s.shares.Get("s5") != nil {
		t.Fatal("过期分享 Get 应返回 nil")
	}
}

func TestSplitIDs(t *testing.T) {
	got := splitIDs(" a , b ,, a ")
	want := []string{"a", "b"}
	if len(got) != len(want) {
		t.Fatalf("splitIDs = %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("splitIDs = %v", got)
		}
	}
	if len(splitIDs("")) != 0 {
		t.Fatal("空串应返回空列表")
	}
	if !strings.Contains("abc", "b") {
		t.Fatal("sanity")
	}
}
