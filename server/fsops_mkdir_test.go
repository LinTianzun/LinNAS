package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestHandleFsMkdir 覆盖:空路径/相对路径/盘符根拒绝、正常创建、父目录自动补建、已存在幂等。
func TestHandleFsMkdir(t *testing.T) {
	s := &Server{}
	root := t.TempDir()

	post := func(path string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]string{"path": path})
		rec := httptest.NewRecorder()
		s.handleFsMkdir(rec, httptest.NewRequest(http.MethodPost, "/api/fs/mkdir", strings.NewReader(string(body))))
		return rec
	}

	// 空路径 → 400
	if rec := post(""); rec.Code != 400 {
		t.Fatalf("空路径应 400,得到 %d", rec.Code)
	}
	// 相对路径 → 400
	if rec := post("media"); rec.Code != 400 {
		t.Fatalf("相对路径应 400,得到 %d", rec.Code)
	}
	// 盘符根 → 400
	if rec := post(`C:\`); rec.Code != 400 {
		t.Fatalf("盘符根应 400,得到 %d", rec.Code)
	}

	// 正常创建(单层)
	one := filepath.Join(root, "movies")
	if rec := post(one); rec.Code != 200 {
		t.Fatalf("创建 %s 应 200,得到 %d: %s", one, rec.Code, rec.Body.String())
	}
	if fi, err := os.Stat(one); err != nil || !fi.IsDir() {
		t.Fatalf("%s 应已创建为目录", one)
	}

	// 多层:父目录不存在 → MkdirAll 一并补建
	nested := filepath.Join(root, "a", "b", "c")
	rec := post(nested)
	if rec.Code != 200 {
		t.Fatalf("嵌套创建应 200,得到 %d: %s", rec.Code, rec.Body.String())
	}
	if fi, err := os.Stat(nested); err != nil || !fi.IsDir() {
		t.Fatalf("%s 应已创建为目录", nested)
	}
	var resp map[string]string
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp["path"] != filepath.Clean(nested) {
		t.Errorf("响应 path=%q,期望 %q", resp["path"], filepath.Clean(nested))
	}

	// 已存在 → 幂等 200
	if rec := post(one); rec.Code != 200 {
		t.Fatalf("重复创建应幂等 200,得到 %d: %s", rec.Code, rec.Body.String())
	}
}
