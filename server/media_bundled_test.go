package main

import (
	"os"
	"path/filepath"
	"testing"
)

// TestBundledBinPath 验证「自包含发布目录」的依赖查找:
// 当 exe 同目录下存在 bin/<name> 时优先返回它,否则返回空串。
func TestBundledBinPath(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatalf("mkdir bin: %v", err)
	}
	ff := filepath.Join(bin, "ffmpeg.exe")
	if err := os.WriteFile(ff, []byte("stub"), 0o755); err != nil {
		t.Fatalf("write stub: %v", err)
	}

	if got := bundledBinPathFrom(dir, "ffmpeg.exe"); got != ff {
		t.Fatalf("bundledBinPathFrom = %q, want %q", got, ff)
	}
	if got := bundledBinPathFrom(dir, "missing.exe"); got != "" {
		t.Fatalf("bundledBinPathFrom(missing) = %q, want empty", got)
	}
	if got := bundledBinPathFrom("", "ffmpeg.exe"); got != "" {
		t.Fatalf("bundledBinPathFrom(empty dir) = %q, want empty", got)
	}
}

// TestBundledBinPathDirectoryIgnored 确保同名目录不会被当作可执行文件。
func TestBundledBinPathDirectoryIgnored(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "bin")
	if err := os.MkdirAll(filepath.Join(bin, "ffmpeg.exe"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if got := bundledBinPathFrom(dir, "ffmpeg.exe"); got != "" {
		t.Fatalf("bundledBinPathFrom = %q, want empty (dir should be ignored)", got)
	}
}
