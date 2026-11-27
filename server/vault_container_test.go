package main

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

// roundTrip 用临时文件做一次完整「加密写入 → 解密读回」往返,断言字节一致。
func roundTrip(t *testing.T, plain []byte) {
	t.Helper()
	dir := t.TempDir()
	dst := filepath.Join(dir, "a.lvault")
	mk := testMasterKey(t)

	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), mk); err != nil {
		t.Fatalf("writeEncryptedToFile: %v", err)
	}
	ef, err := openEncryptedFile(dst, mk)
	if err != nil {
		t.Fatalf("openEncryptedFile: %v", err)
	}
	defer func() { _ = ef.Close() }()

	if got := ef.PlainSize(); got != int64(len(plain)) {
		t.Fatalf("PlainSize = %d, want %d", got, len(plain))
	}
	got := make([]byte, len(plain))
	if _, err := ef.ReadAt(got, 0); err != nil && !errors.Is(err, io.EOF) {
		t.Fatalf("ReadAt full: %v", err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("round-trip mismatch: got %d bytes, want %d equal bytes", len(got), len(plain))
	}
}

func TestVaultRoundTripSizes(t *testing.T) {
	for _, size := range []int{0, 1, 100, 64*1024 - 1, 64 * 1024, 64*1024 + 1, 200 * 1024} {
		plain := make([]byte, size)
		fillPattern(plain)
		t.Run("size", func(t *testing.T) { roundTrip(t, plain) })
	}
}

// TestVaultRandomSeekRead 验证随机偏移读取(模拟视频拖动 / Range),含跨块边界。
func TestVaultRandomSeekRead(t *testing.T) {
	plain := make([]byte, 300*1024)
	fillPattern(plain)
	dir := t.TempDir()
	dst := filepath.Join(dir, "v.lvault")
	mk := testMasterKey(t)
	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), mk); err != nil {
		t.Fatalf("write: %v", err)
	}
	ef, err := openEncryptedFile(dst, mk)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer func() { _ = ef.Close() }()

	for _, off := range []int64{0, 1, 65535, 65536, 65537, 131072, 200000, int64(len(plain)) - 1} {
		want := 100
		if remaining := int64(len(plain)) - off; remaining < int64(want) {
			want = int(remaining)
		}
		if want <= 0 {
			continue
		}
		buf := make([]byte, want)
		if _, err := ef.ReadAt(buf, off); err != nil {
			t.Fatalf("ReadAt(%d): %v", off, err)
		}
		if !bytes.Equal(buf, plain[off:off+int64(want)]) {
			t.Fatalf("ReadAt(%d) mismatch", off)
		}
	}
}

// TestVaultReadAtBeyondEOF 验证越界读取返回 io.EOF。
func TestVaultReadAtBeyondEOF(t *testing.T) {
	plain := []byte("hello")
	dir := t.TempDir()
	dst := filepath.Join(dir, "e.lvault")
	mk := testMasterKey(t)
	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), mk); err != nil {
		t.Fatalf("write: %v", err)
	}
	ef, err := openEncryptedFile(dst, mk)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer func() { _ = ef.Close() }()

	buf := make([]byte, 4)
	if _, err := ef.ReadAt(buf, 10); !errors.Is(err, io.EOF) {
		t.Fatalf("ReadAt beyond EOF err = %v, want io.EOF", err)
	}
	if n, err := ef.ReadAt(buf[:0], int64(len(plain))); err != nil || n != 0 {
		t.Fatalf("ReadAt at EOF 0 bytes = (%d,%v), want (0,nil)", n, err)
	}
}

// TestVaultWrongKeyFails 验证错误主密钥无法解包 CEK。
func TestVaultWrongKeyFails(t *testing.T) {
	plain := []byte("secret data")
	dir := t.TempDir()
	dst := filepath.Join(dir, "s.lvault")
	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), testMasterKey(t)); err != nil {
		t.Fatalf("write: %v", err)
	}
	wrong := testMasterKey(t)
	wrong[0] ^= 0xFF
	if _, err := openEncryptedFile(dst, wrong); !errors.Is(err, lvaultErrWrap) {
		t.Fatalf("openEncryptedFile with wrong key err = %v, want lvaultErrWrap", err)
	}
}

// TestVaultCorruptBlockFails 验证密文被篡改后解密报错(防篡改)。
func TestVaultCorruptBlockFails(t *testing.T) {
	plain := bytes.Repeat([]byte("A"), 64*1024+10)
	dir := t.TempDir()
	dst := filepath.Join(dir, "c.lvault")
	mk := testMasterKey(t)
	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), mk); err != nil {
		t.Fatalf("write: %v", err)
	}
	raw, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("read raw: %v", err)
	}
	_, dataStart, err := readLvaultHeader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("header: %v", err)
	}
	raw[dataStart+20] ^= 0xFF
	if err := os.WriteFile(dst, raw, 0o644); err != nil {
		t.Fatalf("write raw: %v", err)
	}
	ef, err := openEncryptedFile(dst, mk)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer func() { _ = ef.Close() }()
	buf := make([]byte, 10)
	if _, err := ef.ReadAt(buf, 0); err == nil {
		t.Fatal("ReadAt on corrupted block should fail, got nil")
	}
}

// TestVaultHeaderSizeCorrect 验证 PlainSize 被正确回填。
func TestVaultHeaderSizeCorrect(t *testing.T) {
	plain := bytes.Repeat([]byte("x"), 1000)
	dir := t.TempDir()
	dst := filepath.Join(dir, "h.lvault")
	if _, err := writeEncryptedToFile(dst, bytes.NewReader(plain), testMasterKey(t)); err != nil {
		t.Fatalf("write: %v", err)
	}
	raw, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	h, _, err := readLvaultHeader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("header: %v", err)
	}
	if h.PlainSize != int64(len(plain)) {
		t.Fatalf("header.PlainSize = %d, want %d", h.PlainSize, len(plain))
	}
	if h.BlockSize != lvaultBlockSize {
		t.Fatalf("header.BlockSize = %d, want %d", h.BlockSize, lvaultBlockSize)
	}
}

// TestEnsureCEKLen 校验主密钥长度约束。
func TestEnsureCEKLen(t *testing.T) {
	if err := ensureCEKLen(make([]byte, 32)); err != nil {
		t.Fatalf("32 bytes should be valid: %v", err)
	}
	if err := ensureCEKLen(make([]byte, 16)); err == nil {
		t.Fatal("16 bytes should be invalid")
	}
}

// ---- helpers ----

func testMasterKey(t *testing.T) []byte {
	t.Helper()
	mk := make([]byte, 32)
	for i := range mk {
		mk[i] = byte(i)
	}
	return mk
}

// fillPattern 用确定性伪随机模式填充,避免全零的平凡情况。
func fillPattern(b []byte) {
	x := uint32(0x12345678)
	for i := range b {
		x = x*1664525 + 1013904223
		b[i] = byte(x >> 24)
	}
}
