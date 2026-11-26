package main

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"
)

// 磁盘目录含 index.html 时应使用 os.DirFS 并标明来源 disk。
func TestResolveUIFS_Disk(t *testing.T) {
	dir := t.TempDir()
	content := []byte("<!doctype html><title>disk ui</title>")
	if err := os.WriteFile(filepath.Join(dir, "index.html"), content, 0o644); err != nil {
		t.Fatalf("写入临时 index.html 失败: %v", err)
	}

	got, source, err := resolveUIFS(dir)
	if err != nil {
		t.Fatalf("期望成功,实际返回 error: %v", err)
	}
	if source != "disk" {
		t.Fatalf("来源 = %q, 期望 disk", source)
	}
	data, err := fs.ReadFile(got, "index.html")
	if err != nil {
		t.Fatalf("从磁盘 UI 读取 index.html 失败: %v", err)
	}
	if string(data) != string(content) {
		t.Fatalf("磁盘 index.html 内容不匹配: got %q want %q", data, content)
	}
}

// 空串应回退到内嵌 UI,来源 embedded,且能读到内嵌 index.html。
func TestResolveUIFS_Embedded(t *testing.T) {
	got, source, err := resolveUIFS("")
	if err != nil {
		t.Fatalf("空目录应回退内嵌 UI 且无 error,实际: %v", err)
	}
	if source != "embedded" {
		t.Fatalf("来源 = %q, 期望 embedded", source)
	}
	if _, err := fs.ReadFile(got, "index.html"); err != nil {
		t.Fatalf("读取内嵌 index.html 失败: %v", err)
	}
}

// 不存在的目录应回退 embedded、返回可用的内嵌 fs.FS,并给出 error 说明原因。
func TestResolveUIFS_MissingDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "does-not-exist")

	got, source, err := resolveUIFS(dir)
	if source != "embedded" {
		t.Fatalf("不存在目录应回退 embedded,实际来源 = %q", source)
	}
	if err == nil {
		t.Fatalf("不存在目录应返回非 nil error")
	}
	if got == nil {
		t.Fatalf("回退时应返回可用的内嵌 fs.FS,实际为 nil")
	}
	if _, readErr := fs.ReadFile(got, "index.html"); readErr != nil {
		t.Fatalf("回退内嵌 UI 后读取 index.html 失败: %v", readErr)
	}
}
