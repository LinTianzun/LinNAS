package main

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// writeTestZip 在 path 生成一个最小 zip(用于构造 docx/xlsx/pptx fixture)。
func writeTestZip(t *testing.T, path string, files map[string]string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	for name, content := range files {
		w, werr := zw.Create(name)
		if werr != nil {
			t.Fatal(werr)
		}
		if _, werr := w.Write([]byte(content)); werr != nil {
			t.Fatal(werr)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

// TestExtractContentTxtAndSnippets 覆盖 txt 抽取与关键词片段(含位置换算)。
func TestExtractContentTxtAndSnippets(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "note.txt")
	text := "开头说明\n这是关键词第一次出现，后面还有关键词第二次，再来关键词第三次，最后关键词第四次结束。"
	if err := os.WriteFile(p, []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}

	ex := extractContentFile(p, "txt")
	if ex.Reason != "" {
		t.Fatalf("txt 抽取应成功,reason=%q", ex.Reason)
	}
	if !strings.Contains(ex.Text, "关键词") {
		t.Fatalf("抽取文本未包含关键词: %q", ex.Text)
	}
	if ex.Size != int64(len(text)) {
		t.Errorf("size=%d, want %d", ex.Size, len(text))
	}

	sn := contentSnippets(ex.Text, "关键词")
	if len(sn) == 0 {
		t.Fatal("未生成关键词片段")
	}
	if len(sn) > contentSnippetMax {
		t.Errorf("片段数 %d 超过上限 %d", len(sn), contentSnippetMax)
	}
	s := sn[0]
	runes := []rune(s.Text)
	if s.Start < 0 || s.Len <= 0 || s.Start+s.Len > len(runes) {
		t.Fatalf("片段位置越界: start=%d len=%d textLen=%d", s.Start, s.Len, len(runes))
	}
	if got := string(runes[s.Start : s.Start+s.Len]); got != "关键词" {
		t.Errorf("片段命中位置不对,得到 %q", got)
	}
	if strings.ContainsAny(s.Text, "\r\n") {
		t.Errorf("片段未做换行归一化: %q", s.Text)
	}
}

// TestExtractContentOfficeDocs 用代码构造最小 docx/xlsx/pptx 校验抽取。
func TestExtractContentOfficeDocs(t *testing.T) {
	dir := t.TempDir()

	// docx:两个 run 拼成 HelloWorld,另一个段落「第二段」。
	docx := filepath.Join(dir, "a.docx")
	writeTestZip(t, docx, map[string]string{
		"word/document.xml": `<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t>World</w:t></w:r></w:p>
<w:p><w:r><w:t>第二段</w:t></w:r></w:p>
</w:body></w:document>`,
	})
	ex := extractContentFile(docx, "docx")
	if ex.Reason != "" {
		t.Fatalf("docx 抽取失败: %q", ex.Reason)
	}
	if !strings.Contains(ex.Text, "HelloWorld") {
		t.Errorf("docx 未按顺序拼接 <w:t>: %q", ex.Text)
	}
	if !strings.Contains(ex.Text, "第二段") {
		t.Errorf("docx 缺少第二段: %q", ex.Text)
	}
	if !strings.Contains(ex.Text, "\n") {
		t.Errorf("docx 段落未用换行分隔: %q", ex.Text)
	}

	// xlsx:sharedStrings 两条字符串。
	xlsx := filepath.Join(dir, "a.xlsx")
	writeTestZip(t, xlsx, map[string]string{
		"xl/sharedStrings.xml": `<?xml version="1.0" encoding="UTF-8"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Alice</t></si><si><t>Bob</t></si></sst>`,
	})
	ex = extractContentFile(xlsx, "xlsx")
	if ex.Reason != "" {
		t.Fatalf("xlsx 抽取失败: %q", ex.Reason)
	}
	if !strings.Contains(ex.Text, "Alice") || !strings.Contains(ex.Text, "Bob") {
		t.Errorf("xlsx 未抽取 sharedStrings: %q", ex.Text)
	}

	// pptx:两张幻灯片,按页码顺序拼接。
	pptx := filepath.Join(dir, "a.pptx")
	slide := func(s string) string {
		return `<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>` + s + `</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>`
	}
	writeTestZip(t, pptx, map[string]string{
		"ppt/slides/slide1.xml": slide("第一页"),
		"ppt/slides/slide2.xml": slide("第二页"),
	})
	ex = extractContentFile(pptx, "pptx")
	if ex.Reason != "" {
		t.Fatalf("pptx 抽取失败: %q", ex.Reason)
	}
	if !strings.Contains(ex.Text, "第一页") || !strings.Contains(ex.Text, "第二页") {
		t.Errorf("pptx 未抽取幻灯片文本: %q", ex.Text)
	}
	if strings.Index(ex.Text, "第一页") > strings.Index(ex.Text, "第二页") {
		t.Errorf("pptx 幻灯片顺序不正确: %q", ex.Text)
	}
}

// TestExtractContentSkips 覆盖 >2MB 跳过、GB18030 兜底解码、二进制跳过与 1MB 截断。
func TestExtractContentSkips(t *testing.T) {
	dir := t.TempDir()

	big := filepath.Join(dir, "big.txt")
	if err := os.WriteFile(big, make([]byte, contentMaxFileSize+1), 0o644); err != nil {
		t.Fatal(err)
	}
	if ex := extractContentFile(big, "txt"); ex.Reason != "too-large" {
		t.Errorf("超过 2MB 应 reason=too-large,得到 %q", ex.Reason)
	}

	bad := filepath.Join(dir, "bad.txt")
	if err := os.WriteFile(bad, []byte{0xff, 0xfe, 0x00, 0x41}, 0o644); err != nil {
		t.Fatal(err)
	}
	if ex := extractContentFile(bad, "txt"); ex.Reason != "encoding" {
		t.Errorf("非 UTF-8 应 reason=encoding,得到 %q", ex.Reason)
	}

	// GBK 编码的「关键词」(UTF-8 校验失败)→ 应按 GB18030 兜底解码成功。
	gbk := filepath.Join(dir, "gbk.txt")
	if err := os.WriteFile(gbk, []byte{0xB9, 0xD8, 0xBC, 0xFC, 0xB4, 0xCA}, 0o644); err != nil {
		t.Fatal(err)
	}
	if ex := extractContentFile(gbk, "txt"); ex.Reason != "" || !strings.Contains(ex.Text, "关键词") {
		t.Errorf("GBK 文本应按 GB18030 兜底,reason=%q text=%q", ex.Reason, ex.Text)
	}

	// 无 NUL 但整体是二进制字节:GB18030 解码后替换符过多 → 仍应跳过。
	bin := filepath.Join(dir, "bin.txt")
	if err := os.WriteFile(bin, bytes.Repeat([]byte{0xFF, 0xFE}, 64), 0o644); err != nil {
		t.Fatal(err)
	}
	if ex := extractContentFile(bin, "txt"); ex.Reason != "encoding" {
		t.Errorf("二进制应 reason=encoding,得到 %q", ex.Reason)
	}

	trunc := filepath.Join(dir, "trunc.md")
	if err := os.WriteFile(trunc, []byte(strings.Repeat("a", contentMaxTextLen+10)), 0o644); err != nil {
		t.Fatal(err)
	}
	ex := extractContentFile(trunc, "md")
	if ex.Reason != "" {
		t.Fatalf("截断文件抽取应成功,reason=%q", ex.Reason)
	}
	if !ex.Truncated {
		t.Error("超过 1MB 应标记 truncated")
	}
	if len(ex.Text) != contentMaxTextLen {
		t.Errorf("截断后长度=%d, want %d", len(ex.Text), contentMaxTextLen)
	}
}

// TestContentIncrementalJobs 覆盖增量入队:(mtime,size) 变化才重抽,并清理消失文件。
func TestContentIncrementalJobs(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	insertFile := func(vpath string, size, mt int64) {
		if _, err := ix.db.Exec(`INSERT OR REPLACE INTO files
			(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES ('lib1',?, '/lib1','x',0,?,?,'other')`,
			vpath, size, mt); err != nil {
			t.Fatal(err)
		}
	}
	insertContent := func(vpath string, size, mt int64) {
		if _, err := ix.db.Exec(`INSERT OR REPLACE INTO content
			(lib_id,path,mtime,size,text,indexed_at) VALUES ('lib1',?,?,?,'x',0)`,
			vpath, mt, size); err != nil {
			t.Fatal(err)
		}
	}

	// a.txt 已有同 (mtime,size) 的内容行 → 不需要重抽;b.md 尚无内容行 → 需要。
	insertFile("/lib1/a.txt", 5, 100)
	insertContent("/lib1/a.txt", 5, 100)
	insertFile("/lib1/b.md", 7, 100)
	// 不支持的类型不应入队。
	insertFile("/lib1/c.mp4", 9, 100)

	jobs, err := pendingContentJobs(ix)
	if err != nil {
		t.Fatal(err)
	}
	if len(jobs) != 1 || jobs[0].Path != "/lib1/b.md" {
		t.Fatalf("首轮增量任务 = %#v, 期望只有 /lib1/b.md", jobs)
	}

	// a.txt 的 mtime 变化 → 应重新入队。
	insertFile("/lib1/a.txt", 5, 200)
	jobs, err = pendingContentJobs(ix)
	if err != nil {
		t.Fatal(err)
	}
	if len(jobs) != 2 || jobs[0].Path != "/lib1/a.txt" || jobs[1].Path != "/lib1/b.md" {
		t.Fatalf("mtime 变化后任务 = %#v, 期望 a.txt 与 b.md", jobs)
	}

	// 已从索引消失的文件,其内容行应被清理。
	insertContent("/lib1/gone.txt", 3, 1)
	if err := purgeMissingContent(ix); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM content WHERE path = '/lib1/gone.txt'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Errorf("消失文件的内容行未被清理,剩余 %d 行", n)
	}
}

// TestSearchContent 覆盖命中 / 未命中 / 转义 % / 库过滤 / 空查询。
func TestSearchContent(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	ins := func(lib, vpath, text string) {
		if _, err := ix.db.Exec(`INSERT INTO content
			(lib_id,path,mtime,size,text,indexed_at) VALUES (?,?,0,0,?,0)`, lib, vpath, text); err != nil {
			t.Fatal(err)
		}
	}
	ins("lib1", "/lib1/a.txt", "hello world and 50% off")
	ins("lib1", "/lib1/b.txt", "50 items only")
	ins("lib1", "/lib1/c.md", "nothing to see")

	r, err := ix.SearchContent("hello", "", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Hits) != 1 || r.Hits[0].Name != "a.txt" {
		t.Fatalf("hello 应命中 a.txt,得到 %#v", r.Hits)
	}
	if len(r.Hits[0].Snippets) == 0 {
		t.Error("命中文件应带片段")
	}
	if r.Total != 1 || r.Truncated {
		t.Errorf("total=%d truncated=%v, want 1/false", r.Total, r.Truncated)
	}

	if r2, _ := ix.SearchContent("zzz-not-there", "", 50); len(r2.Hits) != 0 || r2.Total != 0 {
		t.Errorf("未命中应返回空,得到 %#v", r2)
	}

	// 转义 %:只有字面包含 "50%" 的 a.txt 命中,b.txt 的 "50 items" 不应命中。
	r3, err := ix.SearchContent("50%", "", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(r3.Hits) != 1 || r3.Hits[0].Name != "a.txt" {
		t.Fatalf("转义 %% 应只命中 a.txt,得到 %#v", r3.Hits)
	}

	// 库过滤:lib2 无内容。
	if r4, _ := ix.SearchContent("hello", "lib2", 50); len(r4.Hits) != 0 {
		t.Errorf("库过滤不生效,得到 %#v", r4.Hits)
	}

	// 空查询返回空 hits。
	if r5, _ := ix.SearchContent("   ", "", 50); len(r5.Hits) != 0 || r5.Total != 0 {
		t.Errorf("空查询应返回空,得到 %#v", r5)
	}
}
