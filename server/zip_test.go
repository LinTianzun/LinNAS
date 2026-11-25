package main

import (
	"archive/zip"
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func newZipTestServer(t *testing.T) (*Server, string) {
	t.Helper()
	root := t.TempDir()
	libDir := filepath.Join(root, "library")
	mustMkdir := func(p string) {
		if err := os.MkdirAll(p, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	mustWrite := func(p, content string) {
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	mustMkdir(filepath.Join(libDir, "sub", "nested"))
	mustWrite(filepath.Join(libDir, "sub", "a.txt"), "hello a")
	mustWrite(filepath.Join(libDir, "sub", "nested", "b.txt"), "hello b")
	mustWrite(filepath.Join(libDir, "sub", "movie.mp4"), "fake video")
	mustMkdir(filepath.Join(libDir, "secret"))
	mustWrite(filepath.Join(libDir, "secret", "hidden.txt"), "should not appear")
	mustMkdir(filepath.Join(libDir, ".linNasTrash", ".meta"))
	mustWrite(filepath.Join(libDir, ".linNasTrash", "x__trash.txt"), "trash")
	mustWrite(filepath.Join(libDir, "sub", "partial.part"), "partial")
	mustWrite(filepath.Join(libDir, "single.txt"), "single file")

	s := &Server{cfg: &Config{
		DataDir: root,
		Libraries: []Library{{
			ID: "lib1", Name: "测试库", Path: libDir, Enabled: true,
			Exclude: []string{"secret"},
		}},
	}}
	return s, libDir
}

func doZip(t *testing.T, s *Server, vpaths []string, name string) *httptest.ResponseRecorder {
	t.Helper()
	q := url.Values{}
	for _, v := range vpaths {
		q.Add("vpaths", v)
	}
	if name != "" {
		q.Set("name", name)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/zip?"+q.Encode(), nil)
	rec := httptest.NewRecorder()
	s.handleZip(rec, req)
	return rec
}

func zipEntryNames(t *testing.T, data []byte) []string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("读取 zip 失败: %v", err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	sort.Strings(names)
	return names
}

func readZipEntry(t *testing.T, data []byte, name string) string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("读取 zip 失败: %v", err)
	}
	for _, f := range zr.File {
		if f.Name == name {
			rc, err := f.Open()
			if err != nil {
				t.Fatal(err)
			}
			defer rc.Close()
			b, _ := io.ReadAll(rc)
			return string(b)
		}
	}
	return ""
}

func TestHandleZipDirSkipsHiddenExcludedPart(t *testing.T) {
	s, _ := newZipTestServer(t)
	rec := doZip(t, s, []string{"/lib1/sub"}, "打包")
	if rec.Code != 200 {
		t.Fatalf("状态码 %d: %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/zip" {
		t.Errorf("Content-Type = %q", ct)
	}
	if cd := rec.Header().Get("Content-Disposition"); cd == "" {
		t.Errorf("缺少 Content-Disposition")
	}
	names := zipEntryNames(t, rec.Body.Bytes())
	want := map[string]bool{
		"sub/a.txt":        true,
		"sub/nested/b.txt": true,
		"sub/movie.mp4":    true,
	}
	for _, n := range names {
		if !want[n] && n != "sub/nested/" {
			t.Errorf("zip 中不应出现的条目: %q(全部: %v)", n, names)
		}
	}
	for n := range want {
		if readZipEntry(t, rec.Body.Bytes(), n) == "" && n != "sub/movie.mp4" {
			t.Errorf("条目 %q 内容为空或缺失", n)
		}
	}
	if got := readZipEntry(t, rec.Body.Bytes(), "sub/a.txt"); got != "hello a" {
		t.Errorf("a.txt 内容 = %q", got)
	}
}

func TestHandleZipSingleFileAndDefaultName(t *testing.T) {
	s, _ := newZipTestServer(t)
	rec := doZip(t, s, []string{"/lib1/single.txt"}, "")
	if rec.Code != 200 {
		t.Fatalf("状态码 %d: %s", rec.Code, rec.Body.String())
	}
	names := zipEntryNames(t, rec.Body.Bytes())
	if len(names) != 1 || names[0] != "single.txt" {
		t.Fatalf("期望仅 single.txt,得到 %v", names)
	}
	if got := readZipEntry(t, rec.Body.Bytes(), "single.txt"); got != "single file" {
		t.Errorf("内容 = %q", got)
	}
	if cd := rec.Header().Get("Content-Disposition"); cd == "" {
		t.Errorf("缺少 Content-Disposition")
	}
}

func TestHandleZipRejectsExcludedAndEmpty(t *testing.T) {
	s, _ := newZipTestServer(t)
	if rec := doZip(t, s, []string{"/lib1/secret"}, ""); rec.Code != 403 {
		t.Errorf("排除目录应返回 403,得到 %d", rec.Code)
	}
	if rec := doZip(t, s, nil, ""); rec.Code != 400 {
		t.Errorf("空选择应返回 400,得到 %d", rec.Code)
	}
	if rec := doZip(t, s, []string{"/lib1"}, ""); rec.Code != 200 {
		t.Errorf("整库打包应成功,得到 %d", rec.Code)
	} else {
		// 整库打包时,顶层目录应使用库名而不是库 ID
		for _, name := range zipEntryNames(t, rec.Body.Bytes()) {
			if !strings.HasPrefix(name, "测试库/") {
				t.Errorf("整库打包条目应以库名开头,得到 %q", name)
			}
		}
	}
}
