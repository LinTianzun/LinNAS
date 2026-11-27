//go:build windows

package main

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

// TestHideVaultPathSetsAttributes 验证保险箱目录被打上 Windows 隐藏 + 系统属性。
func TestHideVaultPathSetsAttributes(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "vault")
	if err := os.MkdirAll(filepath.Join(dir, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := hideVaultPath(dir); err != nil {
		t.Skipf("hideVaultPath 不被当前环境允许: %v", err)
	}
	checkHiddenSystem(t, dir)
	s := &Server{}
	s.hideVaultTree(dir)
	attrs := fileAttrs(t, filepath.Join(dir, "sub"))
	if attrs&fileAttributeHidden == 0 || attrs&fileAttributeSystem == 0 {
		t.Fatalf("子目录属性 = %#x, 期望含 hidden|system", attrs)
	}
}

func checkHiddenSystem(t *testing.T, p string) {
	t.Helper()
	attrs := fileAttrs(t, p)
	if attrs&fileAttributeHidden == 0 || attrs&fileAttributeSystem == 0 {
		t.Fatalf("属性 = %#x, 期望含 hidden(0x2)|system(0x4)", attrs)
	}
}

func fileAttrs(t *testing.T, p string) uint32 {
	t.Helper()
	ptr, err := syscall.UTF16PtrFromString(p)
	if err != nil {
		t.Fatalf("UTF16PtrFromString(%q): %v", p, err)
	}
	attrs, err := syscall.GetFileAttributes(ptr)
	if err != nil {
		t.Fatalf("GetFileAttributes(%q): %v", p, err)
	}
	return attrs
}
