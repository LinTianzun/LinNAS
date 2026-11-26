package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// GET /api/trash/thumb 缺参数应返回 400。
func TestHandleTrashThumbMissingParams(t *testing.T) {
	s := &Server{cfg: &Config{}}
	for _, q := range []string{"", "?id=x", "?lib=lib1"} {
		rec := httptest.NewRecorder()
		s.handleTrashThumb(rec, httptest.NewRequest(http.MethodGet, "/api/trash/thumb"+q, nil))
		if rec.Code != http.StatusBadRequest {
			t.Errorf("query %q: code = %d, want 400", q, rec.Code)
		}
	}
}

// 未知存储库应返回 404。
func TestHandleTrashThumbUnknownLibrary(t *testing.T) {
	s := &Server{cfg: &Config{}}
	rec := httptest.NewRecorder()
	s.handleTrashThumb(rec, httptest.NewRequest(http.MethodGet, "/api/trash/thumb?id=a&lib=nope", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("code = %d, want 404", rec.Code)
	}
}

// 回收站里不存在的条目应返回 404;文件夹应返回 400(没有缩略图)。
func TestHandleTrashThumbItemStates(t *testing.T) {
	dir := t.TempDir()
	s := &Server{cfg: &Config{Libraries: []Library{{ID: "lib1", Path: dir}}}}

	rec := httptest.NewRecorder()
	s.handleTrashThumb(rec, httptest.NewRequest(http.MethodGet, "/api/trash/thumb?id=nope&lib=lib1", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("unknown item: code = %d, want 404", rec.Code)
	}

	trashDir := filepath.Join(dir, ".linNasTrash")
	if err := os.MkdirAll(filepath.Join(trashDir, "20260101-000000-folder__folder"), 0o755); err != nil {
		t.Fatal(err)
	}
	rec = httptest.NewRecorder()
	s.handleTrashThumb(rec, httptest.NewRequest(http.MethodGet, "/api/trash/thumb?id=20260101-000000-folder&lib=lib1", nil))
	if rec.Code != http.StatusBadRequest {
		t.Errorf("dir item: code = %d, want 400", rec.Code)
	}
}
