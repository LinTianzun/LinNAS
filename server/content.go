package main

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
)

// ============================================================
//  批次 3:正文索引与内容搜索
//  - content / content_meta 表由 index.go 建表处创建。
//  - 文本类文档 UTF-8 优先、失败按 GB18030 兜底(兼容中文 Windows 的 GBK 文档);
//    docx/xlsx/pptx 用 archive/zip + encoding/xml 抽文本。
//  - 抽取在后台 goroutine(并发 2)执行,不阻塞索引扫描/对账与 HTTP。
//  - 单文件 ≤ 2MB、抽取文本截断 1MB、单文件 300ms 软超时、可取消。
//  - 单轮最多处理 contentRoundLimit 个文件,超出的下一轮(下次对账)继续。
// ============================================================

const (
	// contentMaxFileSize 单文件参与抽取的最大字节数(超出跳过,reason=too-large)。
	contentMaxFileSize = 2 * 1024 * 1024
	// contentMaxTextLen 抽取文本的最大长度(超出截断并标记 truncated)。
	contentMaxTextLen = 1 * 1024 * 1024
	// contentWorkers 并发抽取的 worker 数量。
	contentWorkers = 2
	// contentFileTimeout 单文件抽取的软超时。
	contentFileTimeout = 300 * time.Millisecond
	// contentRoundLimit 单轮最多入队的文件数。
	contentRoundLimit = 5000
	// contentSnippetWindow 关键词片段的上下文窗口(字符数,前后各 N)。
	contentSnippetWindow = 40
	// contentSnippetMax 每个文件最多返回的片段数。
	contentSnippetMax = 3
	// contentTotalCap 命中总数最多统计到该值,超过则 truncated=true。
	contentTotalCap = 1000
)

// contentTextExts 直读文本(UTF-8)的扩展名集合。
var contentTextExts = map[string]bool{
	"txt": true, "md": true, "log": true, "csv": true, "json": true,
	"xml": true, "html": true, "htm": true, "srt": true, "ass": true, "vtt": true,
}

// contentZipExts 需要用 archive/zip + encoding/xml 抽取的 Office 文档扩展名。
var contentZipExts = map[string]bool{"docx": true, "xlsx": true, "pptx": true}

// isContentExt 报告某个(小写)扩展名是否属于内容索引支持的类型。
func isContentExt(ext string) bool { return contentTextExts[ext] || contentZipExts[ext] }

// contentJob 是一条待抽取任务(路径为虚拟路径,如 /lib1/dir/a.md)。
type contentJob struct {
	Path string
}

// contentSnippet 是一段命中片段。Start/Len 为命中词在 Text 内的「字符(rune)」偏移,
// 便于前端用 <mark> 高亮;Text 已做换行归一化。
type contentSnippet struct {
	Text  string `json:"text"`
	Start int    `json:"start"`
	Len   int    `json:"len"`
}

// contentHit 是一个内容命中的文件(按文件聚合片段)。
type contentHit struct {
	LibID    string           `json:"libId"`
	Path     string           `json:"path"`
	Name     string           `json:"name"`
	Size     int64            `json:"size"`
	ModTime  int64            `json:"modTime"`
	Snippets []contentSnippet `json:"snippets"`
}

// contentSearchResp 是 GET /api/search/content 的响应。
type contentSearchResp struct {
	Hits      []contentHit `json:"hits"`
	Total     int          `json:"total"`
	Truncated bool         `json:"truncated"`
}

// contentStatusResp 是 GET /api/content/status 的响应。
type contentStatusResp struct {
	Enabled   bool   `json:"enabled"`
	Queued    int    `json:"queued"`
	Running   int    `json:"running"`
	Done      int64  `json:"done"`
	Skipped   int64  `json:"skipped"`
	Failed    int64  `json:"failed"`
	Truncated int64  `json:"truncated"`
	LastAt    int64  `json:"lastAt"`
	LastError string `json:"lastError"`
	Workers   int    `json:"workers"`
}

// contentIndexer 保存后台抽取队列与统计(受 mu 保护,后台写、HTTP 读)。
type contentIndexer struct {
	mu sync.Mutex

	queue   []contentJob
	pending map[string]bool // 已入队未处理的虚拟路径,避免重复入队
	queued  int             // 当前队列长度
	running int             // 正在抽取的 worker 数

	done      int64
	skipped   int64
	failed    int64
	truncated int64
	lastAt    int64
	lastError string

	active       bool
	loaded       bool
	cancel       chan struct{}
	cancelClosed bool
}

// ============================================================
//  后台抽取队列
// ============================================================

// enqueueContentIndex 清理已消失文件的内容行,并把 (mtime,size) 有变化的支持类型
// 文件增量入队。整个过程在索引扫描/对账之后由后台 hook 调用,本身立即返回。
func (s *Server) enqueueContentIndex() {
	if s.index == nil || s.content == nil {
		return
	}
	s.loadContentStats()

	if err := purgeMissingContent(s.index); err != nil {
		s.content.setLastError("清理内容索引失败: " + err.Error())
	}

	jobs, err := pendingContentJobs(s.index)
	if err != nil {
		s.content.setLastError("读取待索引内容失败: " + err.Error())
		return
	}
	if len(jobs) == 0 {
		return
	}

	ci := s.content
	ci.mu.Lock()
	ci.ensureCancelLocked()
	if ci.pending == nil {
		ci.pending = map[string]bool{}
	}
	for _, j := range jobs {
		if ci.pending[j.Path] {
			continue
		}
		ci.pending[j.Path] = true
		ci.queue = append(ci.queue, j)
	}
	ci.queued = len(ci.queue)
	start := !ci.active && len(ci.queue) > 0
	if start {
		ci.active = true
	}
	ci.mu.Unlock()
	if start {
		go s.contentProcessQueue()
	}
}

// contentProcessQueue 持续消费队列,每批用 contentWorkers 个 goroutine 并行抽取。
func (s *Server) contentProcessQueue() {
	ci := s.content
	for {
		ci.mu.Lock()
		if len(ci.queue) == 0 {
			ci.active = false
			ci.queued = 0
			ci.running = 0
			ci.mu.Unlock()
			s.saveContentStats()
			return
		}
		batch := ci.queue
		ci.queue = nil
		ci.queued = len(batch)
		cancel := ci.ensureCancelLocked()
		ci.mu.Unlock()

		s.contentRunBatch(batch, cancel)
	}
}

// contentRunBatch 用固定数量的 worker 处理一批任务。可被取消通道中断。
func (s *Server) contentRunBatch(jobs []contentJob, cancel chan struct{}) {
	ci := s.content
	ch := make(chan contentJob)
	var wg sync.WaitGroup

	for w := 0; w < contentWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range ch {
				ci.mu.Lock()
				ci.running++
				if ci.queued > 0 {
					ci.queued--
				}
				ci.mu.Unlock()

				out := s.contentExtractJob(j, cancel)

				ci.mu.Lock()
				ci.running--
				switch out.kind {
				case "done":
					ci.done++
				case "skipped":
					ci.skipped++
				case "failed":
					ci.failed++
				}
				if out.truncated {
					ci.truncated++
				}
				if out.errMsg != "" {
					ci.lastError = out.errMsg
				}
				ci.lastAt = time.Now().Unix()
				delete(ci.pending, j.Path)
				ci.mu.Unlock()
			}
		}()
	}

dispatch:
	for _, j := range jobs {
		select {
		case <-cancel:
			break dispatch
		case ch <- j:
		}
	}
	close(ch)
	wg.Wait()
}

// contentOutcome 是单文件抽取的结果分类。
type contentOutcome struct {
	kind      string // done | skipped | failed
	truncated bool
	errMsg    string
}

// contentExtractJob 处理一条任务:解析路径 → 抽取(软超时)→ 写库/删旧行。
func (s *Server) contentExtractJob(job contentJob, cancel chan struct{}) contentOutcome {
	if s.index == nil || s.cfg == nil {
		return contentOutcome{kind: "skipped"}
	}
	lib, abs, err := s.cfg.Resolve(job.Path)
	if err != nil {
		return contentOutcome{kind: "failed", errMsg: "路径无效: " + err.Error()}
	}
	// 保险箱 / 回收站不索引(索引本身已排除,这里再做一次兜底)。
	if s.inVault(abs) || s.inTrash(abs) {
		s.deleteContentRow(lib.ID, job.Path)
		return contentOutcome{kind: "skipped"}
	}
	ext := fileExt(abs)
	if !isContentExt(ext) {
		s.deleteContentRow(lib.ID, job.Path)
		return contentOutcome{kind: "skipped"}
	}

	type wrapped struct{ ex contentExtract }
	ch := make(chan contentExtract, 1)
	go func() { ch <- extractContentFile(abs, ext) }()

	var ex contentExtract
	select {
	case ex = <-ch:
	case <-time.After(contentFileTimeout):
		s.deleteContentRow(lib.ID, job.Path)
		return contentOutcome{kind: "skipped"}
	case <-cancel:
		return contentOutcome{kind: "skipped"}
	}

	switch ex.Reason {
	case "":
		if _, werr := s.index.db.Exec(`INSERT OR REPLACE INTO content
			(lib_id,path,mtime,size,text,indexed_at) VALUES (?,?,?,?,?,?)`,
			lib.ID, job.Path, ex.ModTime, ex.Size, ex.Text, time.Now().Unix()); werr != nil {
			return contentOutcome{kind: "failed", errMsg: "写入内容索引失败: " + werr.Error()}
		}
		return contentOutcome{kind: "done", truncated: ex.Truncated}
	case "too-large", "encoding", "timeout", "cancelled":
		// 跳过类:清掉可能存在的旧内容行,避免检索到过期正文。
		s.deleteContentRow(lib.ID, job.Path)
		return contentOutcome{kind: "skipped"}
	default:
		s.deleteContentRow(lib.ID, job.Path)
		return contentOutcome{kind: "failed", errMsg: ex.Reason}
	}
}

// deleteContentRow 删除某个文件的内容行(抽取失败/跳过时调用)。
func (s *Server) deleteContentRow(libID, vpath string) {
	if s.index == nil {
		return
	}
	_, _ = s.index.db.Exec(`DELETE FROM content WHERE lib_id = ? AND path = ?`, libID, vpath)
}

// purgeMissingContent 删除索引(files 表)中已不存在的文件对应的 content 行。
func purgeMissingContent(ix *Index) error {
	_, err := ix.db.Exec(`DELETE FROM content WHERE NOT EXISTS (
		SELECT 1 FROM files f WHERE f.lib_id = content.lib_id AND f.path = content.path)`)
	return err
}

// pendingContentJobs 返回需要(重新)抽取的虚拟路径:files 表里支持类型、且与
// content 表 (mtime,size) 不一致或尚无内容行的文件;单轮最多 contentRoundLimit 个。
func pendingContentJobs(ix *Index) ([]contentJob, error) {
	rows, err := ix.db.Query(`SELECT f.lib_id, f.path FROM files f
		LEFT JOIN content c ON c.lib_id = f.lib_id AND c.path = f.path
		WHERE f.is_dir = 0 AND (c.path IS NULL OR c.mtime <> f.mod_time OR c.size <> f.size)
		ORDER BY f.lib_id, f.path`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	jobs := make([]contentJob, 0, 64)
	for rows.Next() {
		var libID, vpath string
		if err := rows.Scan(&libID, &vpath); err != nil {
			return nil, err
		}
		if !isContentExt(fileExt(vpath)) {
			continue
		}
		jobs = append(jobs, contentJob{Path: vpath})
		if len(jobs) >= contentRoundLimit {
			break
		}
	}
	return jobs, rows.Err()
}

// ============================================================
//  统计 / 状态
// ============================================================

func (ci *contentIndexer) setLastError(msg string) {
	ci.mu.Lock()
	ci.lastError = msg
	ci.mu.Unlock()
}

func (ci *contentIndexer) ensureCancelLocked() chan struct{} {
	if ci.cancel == nil || ci.cancelClosed {
		ci.cancel = make(chan struct{})
		ci.cancelClosed = false
	}
	return ci.cancel
}

// Cancel 请求取消正在进行的抽取(供停机/测试使用)。
func (s *Server) CancelContentIndexing() {
	if s.content == nil {
		return
	}
	ci := s.content
	ci.mu.Lock()
	if ci.cancel != nil && !ci.cancelClosed {
		close(ci.cancel)
		ci.cancelClosed = true
	}
	ci.mu.Unlock()
}

// loadContentStats 首次使用时从 content_meta 恢复累计统计。
func (s *Server) loadContentStats() {
	if s.index == nil || s.content == nil {
		return
	}
	ci := s.content
	ci.mu.Lock()
	defer ci.mu.Unlock()
	if ci.loaded {
		return
	}
	ci.loaded = true
	read := func(k string) string {
		var v string
		if err := s.index.db.QueryRow(`SELECT v FROM content_meta WHERE k = ?`, k).Scan(&v); err == nil {
			return v
		}
		return ""
	}
	if n, err := strconv.ParseInt(read("content_done"), 10, 64); err == nil {
		ci.done = n
	}
	if n, err := strconv.ParseInt(read("content_skipped"), 10, 64); err == nil {
		ci.skipped = n
	}
	if n, err := strconv.ParseInt(read("content_failed"), 10, 64); err == nil {
		ci.failed = n
	}
	if n, err := strconv.ParseInt(read("content_truncated"), 10, 64); err == nil {
		ci.truncated = n
	}
	if n, err := strconv.ParseInt(read("content_last_at"), 10, 64); err == nil {
		ci.lastAt = n
	}
	ci.lastError = read("content_last_error")
}

// saveContentStats 把累计统计落 content_meta(每批结束后保存一次)。
func (s *Server) saveContentStats() {
	if s.index == nil || s.content == nil {
		return
	}
	ci := s.content
	ci.mu.Lock()
	vals := map[string]string{
		"content_done":       strconv.FormatInt(ci.done, 10),
		"content_skipped":    strconv.FormatInt(ci.skipped, 10),
		"content_failed":     strconv.FormatInt(ci.failed, 10),
		"content_truncated":  strconv.FormatInt(ci.truncated, 10),
		"content_last_at":    strconv.FormatInt(ci.lastAt, 10),
		"content_last_error": ci.lastError,
	}
	ci.mu.Unlock()
	for k, v := range vals {
		_, _ = s.index.db.Exec(`INSERT OR REPLACE INTO content_meta(k, v) VALUES (?, ?)`, k, v)
	}
}

// handleContentStatus GET /api/content/status — 内容索引状态。
func (s *Server) handleContentStatus(w http.ResponseWriter, r *http.Request) {
	resp := contentStatusResp{Enabled: s.index != nil, Workers: contentWorkers}
	if s.index != nil && s.content != nil {
		s.loadContentStats()
		ci := s.content
		ci.mu.Lock()
		resp.Queued = ci.queued
		resp.Running = ci.running
		resp.Done = ci.done
		resp.Skipped = ci.skipped
		resp.Failed = ci.failed
		resp.Truncated = ci.truncated
		resp.LastAt = ci.lastAt
		resp.LastError = ci.lastError
		ci.mu.Unlock()
	}
	writeJSON(w, 200, resp)
}

// ============================================================
//  内容抽取
// ============================================================

// contentExtract 是单文件抽取的结果(Reason 为空表示成功)。
type contentExtract struct {
	Text      string
	Truncated bool
	Reason    string
	Size      int64
	ModTime   int64
}

// extractContentFile 按扩展名抽取一个文件的正文。纯函数(只读磁盘),便于单测。
func extractContentFile(abs, ext string) contentExtract {
	info, err := os.Stat(abs)
	if err != nil {
		return contentExtract{Reason: "read-error: " + err.Error()}
	}
	if info.IsDir() {
		return contentExtract{Reason: "is-dir"}
	}
	res := contentExtract{Size: info.Size(), ModTime: info.ModTime().Unix()}
	if info.Size() > contentMaxFileSize {
		res.Reason = "too-large"
		return res
	}

	var text string
	switch {
	case contentTextExts[ext]:
		raw, rerr := os.ReadFile(abs)
		if rerr != nil {
			res.Reason = "read-error: " + rerr.Error()
			return res
		}
		decoded, ok := decodeTextBytes(raw)
		if !ok {
			res.Reason = "encoding"
			return res
		}
		text = decoded
	case contentZipExts[ext]:
		t, zerr := extractZipText(abs, ext)
		if zerr != nil {
			res.Reason = "read-error: " + zerr.Error()
			return res
		}
		if !utf8.ValidString(t) {
			res.Reason = "encoding"
			return res
		}
		text = t
	default:
		res.Reason = "unsupported"
		return res
	}

	if len(text) > contentMaxTextLen {
		text = text[:contentMaxTextLen]
		// 避免截断到多字节字符中间。
		for len(text) > 0 {
			r, size := utf8.DecodeLastRuneInString(text)
			if r == utf8.RuneError && size <= 1 {
				text = text[:len(text)-1]
				continue
			}
			break
		}
		res.Truncated = true
	}
	res.Text = text
	return res
}

// decodeTextBytes 把文件字节解码为文本:UTF-8 优先;校验失败时按 GB18030 兜底
// (兼容 GBK/GB2312 文档)。含 NUL 字节、或解码结果中替换符超过 0.5% 时视为
// 二进制,返回 ok=false(调用方按 reason=encoding 跳过)。
func decodeTextBytes(raw []byte) (string, bool) {
	if bytes.IndexByte(raw, 0x00) >= 0 {
		return "", false
	}
	if utf8.Valid(raw) {
		return string(raw), true
	}
	decoded, err := simplifiedchinese.GB18030.NewDecoder().Bytes(raw)
	if err != nil {
		return "", false
	}
	s := string(decoded)
	n := utf8.RuneCountInString(s)
	if n == 0 {
		return "", true
	}
	bad := strings.Count(s, "\uFFFD")
	if bad*1000 > n*5 { // 替换符超过 0.5% → 大概率不是文本
		return "", false
	}
	return s, true
}

// extractZipText 用 archive/zip 打开 docx/xlsx/pptx 并抽取其中的文本。
func extractZipText(abs, ext string) (string, error) {
	zr, err := zip.OpenReader(abs)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	switch ext {
	case "docx":
		for _, f := range zr.File {
			if f.Name == "word/document.xml" {
				return xmlTextFromZipFile(f, "t", "p")
			}
		}
		return "", nil
	case "xlsx":
		for _, f := range zr.File {
			if f.Name == "xl/sharedStrings.xml" {
				return xmlTextFromZipFile(f, "t", "si")
			}
		}
		return "", nil
	case "pptx":
		type slideFile struct {
			f   *zip.File
			num int
		}
		var slides []slideFile
		for _, f := range zr.File {
			name := f.Name
			if !strings.HasPrefix(name, "ppt/slides/slide") || !strings.HasSuffix(name, ".xml") {
				continue
			}
			mid := strings.TrimSuffix(strings.TrimPrefix(name, "ppt/slides/slide"), ".xml")
			if n, perr := strconv.Atoi(mid); perr == nil {
				slides = append(slides, slideFile{f: f, num: n})
			}
		}
		sort.Slice(slides, func(i, j int) bool { return slides[i].num < slides[j].num })
		var b strings.Builder
		for i, s := range slides {
			t, xerr := xmlTextFromZipFile(s.f, "t", "p")
			if xerr != nil {
				return "", xerr
			}
			if i > 0 {
				b.WriteByte('\n')
			}
			b.WriteString(t)
		}
		return b.String(), nil
	}
	return "", nil
}

// xmlTextFromZipFile 打开 zip 内的一个 XML 条目并抽取文本。
func xmlTextFromZipFile(f *zip.File, textLocal, paraLocal string) (string, error) {
	rc, err := f.Open()
	if err != nil {
		return "", err
	}
	defer rc.Close()
	return xmlCollectText(rc, textLocal, paraLocal)
}

// xmlCollectText 收集 XML 中指定局部名元素的文本,并在段落结束元素处追加换行。
// 用 Name.Local 匹配,忽略命名空间前缀(w:t / a:t / 默认命名空间的 t 都能命中),
// 这正是 docx/xlsx/pptx 抽取能稳定工作的关键。
func xmlCollectText(r io.Reader, textLocal, paraLocal string) (string, error) {
	dec := xml.NewDecoder(r)
	var b strings.Builder
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if t.Name.Local == textLocal {
				var s string
				if derr := dec.DecodeElement(&s, &t); derr != nil {
					return "", derr
				}
				b.WriteString(s)
			}
		case xml.EndElement:
			if paraLocal != "" && t.Name.Local == paraLocal {
				b.WriteByte('\n')
			}
		}
	}
	return b.String(), nil
}

// ============================================================
//  内容检索
// ============================================================

// SearchContent 按正文关键词检索 content 表。q 为空返回空结果。
func (ix *Index) SearchContent(q, libID string, limit int) (contentSearchResp, error) {
	resp := contentSearchResp{Hits: []contentHit{}, Total: 0, Truncated: false}
	q = strings.TrimSpace(q)
	if q == "" {
		return resp, nil
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 500 {
		limit = 500
	}

	pattern := "%" + likeEscape(q) + "%"
	where := `text LIKE ? ESCAPE '\'`
	args := []any{pattern}
	if libID != "" {
		where += " AND lib_id = ?"
		args = append(args, libID)
	}

	// 统计命中总数(最多统计到 contentTotalCap + 1,超出即 truncated)。
	var count int
	countSQL := `SELECT COUNT(*) FROM (SELECT 1 FROM content WHERE ` + where +
		` LIMIT ` + strconv.Itoa(contentTotalCap+1) + `)`
	if err := ix.db.QueryRow(countSQL, args...).Scan(&count); err != nil {
		return resp, err
	}
	if count > contentTotalCap {
		resp.Total = contentTotalCap
		resp.Truncated = true
	} else {
		resp.Total = count
	}

	rows, err := ix.db.Query(`SELECT lib_id,path,size,mtime,text FROM content WHERE `+where+
		` ORDER BY lib_id, path LIMIT ?`, append(append([]any{}, args...), limit)...)
	if err != nil {
		return resp, err
	}
	defer rows.Close()
	for rows.Next() {
		var lib, vpath, text string
		var size, mt int64
		if err := rows.Scan(&lib, &vpath, &size, &mt, &text); err != nil {
			return resp, err
		}
		resp.Hits = append(resp.Hits, contentHit{
			LibID:    lib,
			Path:     vpath,
			Name:     BaseVirtual(vpath),
			Size:     size,
			ModTime:  mt,
			Snippets: contentSnippets(text, q),
		})
	}
	return resp, rows.Err()
}

// handleSearchContent GET /api/search/content?q=&lib=&limit=50 — 正文搜索。
func (s *Server) handleSearchContent(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	lib := strings.TrimSpace(r.URL.Query().Get("lib"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	resp, err := s.index.SearchContent(q, lib, limit)
	if err != nil {
		writeErr(w, 500, "搜索失败: "+err.Error())
		return
	}
	writeJSON(w, 200, resp)
}

// contentSnippets 生成最多 contentSnippetMax 个片段:前后各 contentSnippetWindow 个字符窗口,
// 换行归一化为空格;Start/Len 为命中词在片段内的字符偏移。
func contentSnippets(text, q string) []contentSnippet {
	out := []contentSnippet{}
	if q == "" {
		return out
	}
	norm := normalizeSnippetText(text)
	lowerNorm := strings.ToLower(norm)
	lowerQ := strings.ToLower(q)
	if lowerQ == "" {
		return out
	}
	from := 0
	for len(out) < contentSnippetMax {
		idx := strings.Index(lowerNorm[from:], lowerQ)
		if idx < 0 {
			break
		}
		idx += from

		ws := idx - contentSnippetWindow
		if ws < 0 {
			ws = 0
		}
		we := idx + len(q) + contentSnippetWindow
		if we > len(norm) {
			we = len(norm)
		}
		for ws > 0 && ws < len(norm) && !utf8.RuneStart(norm[ws]) {
			ws--
		}
		for we < len(norm) && !utf8.RuneStart(norm[we]) {
			we++
		}

		seg := norm[ws:we]
		start := utf8.RuneCountInString(norm[ws:idx])
		length := utf8.RuneCountInString(norm[idx : idx+len(q)])
		out = append(out, contentSnippet{Text: seg, Start: start, Len: length})

		from = idx + len(q)
		if from >= len(norm) {
			break
		}
	}
	return out
}

// normalizeSnippetText 把各类换行统一为单个空格,便于片段展示。
func normalizeSnippetText(s string) string {
	replacer := strings.NewReplacer("\r\n", " ", "\r", " ", "\n", " ", "\t", " ")
	return replacer.Replace(s)
}
