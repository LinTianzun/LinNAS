package main

import (
	"os"
	"path/filepath"
	"testing"
)

// mustWrite 写入一个测试文件(字节内容)。
func mustWrite(t *testing.T, p, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestSampleHashOfFile(t *testing.T) {
	dir := t.TempDir()
	a := filepath.Join(dir, "a.bin")
	b := filepath.Join(dir, "b.bin")
	c := filepath.Join(dir, "c.bin")
	mustWrite(t, a, "hello world 1234")
	mustWrite(t, b, "hello world 1234")
	mustWrite(t, c, "HELLO WORLD 5678") // 同长度不同内容

	ha, err := sampleHashOfFile(a, 16)
	if err != nil {
		t.Fatal(err)
	}
	hb, err := sampleHashOfFile(b, 16)
	if err != nil {
		t.Fatal(err)
	}
	hc, err := sampleHashOfFile(c, 16)
	if err != nil {
		t.Fatal(err)
	}
	if ha != hb {
		t.Errorf("相同内容抽样哈希不一致: %s vs %s", ha, hb)
	}
	if ha == hc {
		t.Errorf("不同内容抽样哈希相同(不应): %s", ha)
	}
	if _, err := sampleHashOfFile(filepath.Join(dir, "missing.bin"), 1); err == nil {
		t.Error("对不存在的文件应返回错误")
	}
}

func TestGroupDuplicatesBySizeAndContent(t *testing.T) {
	dir := t.TempDir()
	a1 := filepath.Join(dir, "a1.dat")
	a2 := filepath.Join(dir, "a2.dat")
	b := filepath.Join(dir, "b.dat")
	u := filepath.Join(dir, "unique.dat")
	mustWrite(t, a1, "hello world 1234") // 16 字节
	mustWrite(t, a2, "hello world 1234") // 与 a1 同大小同内容
	mustWrite(t, b, "HELLO WORLD 5678")  // 与 a1 同大小不同内容
	mustWrite(t, u, "xyz")               // 独一无二的大小

	files := []dupCandidate{
		{LibID: "lib1", VPath: "/lib1/a1.dat", Name: "a1.dat", Size: 16, Kind: "other", Abs: a1},
		{LibID: "lib1", VPath: "/lib1/a2.dat", Name: "a2.dat", Size: 16, Kind: "other", Abs: a2},
		{LibID: "lib1", VPath: "/lib1/b.dat", Name: "b.dat", Size: 16, Kind: "other", Abs: b},
		{LibID: "lib1", VPath: "/lib1/unique.dat", Name: "unique.dat", Size: 3, Kind: "other", Abs: u},
	}

	var lastDone, lastTotal int
	groups := groupDuplicates(files, func(done, total int) { lastDone, lastTotal = done, total })

	if len(groups) != 1 {
		t.Fatalf("应只有 1 个重复组,得到 %d: %#v", len(groups), groups)
	}
	g := groups[0]
	if g.Size != 16 {
		t.Errorf("组大小 = %d, want 16", g.Size)
	}
	if len(g.Files) != 2 {
		t.Fatalf("组内文件数 = %d, want 2", len(g.Files))
	}
	got := map[string]bool{}
	for _, f := range g.Files {
		got[f.Name] = true
	}
	if !got["a1.dat"] || !got["a2.dat"] {
		t.Errorf("重复组应包含 a1.dat 与 a2.dat,得到 %#v", g.Files)
	}
	if got["b.dat"] {
		t.Error("同大小不同内容的 b.dat 不应进入重复组")
	}
	if lastDone != lastTotal {
		t.Errorf("进度回调最终 done=%d total=%d,应相等", lastDone, lastTotal)
	}
}

func TestGroupDuplicatesSkipsUnreadable(t *testing.T) {
	dir := t.TempDir()
	a1 := filepath.Join(dir, "a1.dat")
	a2 := filepath.Join(dir, "a2.dat")
	mustWrite(t, a1, "abcdefghijklmnop")
	mustWrite(t, a2, "abcdefghijklmnop")
	files := []dupCandidate{
		{LibID: "lib1", VPath: "/lib1/a1.dat", Name: "a1.dat", Size: 16, Abs: a1},
		{LibID: "lib1", VPath: "/lib1/a2.dat", Name: "a2.dat", Size: 16, Abs: a2},
		{LibID: "lib1", VPath: "/lib1/missing.dat", Name: "missing.dat", Size: 16, Abs: filepath.Join(dir, "nope.dat")},
	}
	groups := groupDuplicates(files, nil)
	if len(groups) != 1 || len(groups[0].Files) != 2 {
		t.Fatalf("读失败的文件应被跳过,仍应得到 a1+a2 一组,得到 %#v", groups)
	}
}
