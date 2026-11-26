package main

// ============ 批次 4:自动整理规则 ============
// - 规则模型 organizeRule{id,name,enabled,libId,match,action},存 DataDir/organize.json。
// - 预览 POST /api/organize/preview:只读枚举索引,返回计划移动与跳过原因,不落盘。
// - 执行 POST /api/organize/run:单飞后台逐条 os.Rename(目标同名加 " (1)"),
//   journal DataDir/organize/journal-<jobID>.jsonl,完成后 Index.UpdatePaths 增量刷新。
// - 撤销 POST /api/organize/undo:按 journal 倒序回搬,原位置被占用时跳过并逐条报告。
// - 调度 v1 仅手动(intervalHours 预留,默认 0)。
// - 安全:全部接口仅管理员可用;不删除文件;跨库/只读库/保险箱/回收站/排除目录一律跳过。
// ============

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// organizeRunMax 是单次整理/撤销的移动上限。
	organizeRunMax = 5000
	// organizeJobsMax 是作业历史保留条数。
	organizeJobsMax = 50
	// organizeSkipListMax 是预览里逐条列出的跳过项上限(超出只计数)。
	organizeSkipListMax = 200
	// organizeRenameAttempts 是目标同名冲突时尝试 " (n)" 的最大次数。
	organizeRenameAttempts = 999
)

// organizeMatch 是规则的匹配条件(全部条件同时满足才命中;零值表示不限)。
type organizeMatch struct {
	// PathPrefix 是库内相对目录前缀(如 photos/2024)。
	PathPrefix string `json:"matchPathPrefix"`
	// Exts 是扩展名列表(小写含点,如 [".jpg"])。
	Exts []string `json:"exts"`
	// NameContains 是文件名必须包含的片段(不区分大小写)。
	NameContains string `json:"nameContains"`
	// Kind 是媒体类型:video|image|audio|other,空表示不限。
	Kind string `json:"kind"`
	// MinSizeMB / MaxSizeMB 是文件大小范围(MB,0 表示不限)。
	MinSizeMB float64 `json:"minSizeMB"`
	MaxSizeMB float64 `json:"maxSizeMB"`
	// OlderThanDays 只命中修改时间早于 N 天前的文件(0 表示不限)。
	OlderThanDays int `json:"olderThanDays"`
}

// organizeAction 是命中后的目标动作。
type organizeAction struct {
	// MoveTo 是库内目标目录(相对库根,slash 分隔,必填)。
	MoveTo string `json:"moveTo"`
	// GroupBy 是分组方式:ext|kind|year-month|none。
	GroupBy string `json:"groupBy"`
}

// organizeRule 是一条自动整理规则。
type organizeRule struct {
	ID      string         `json:"id"`
	Name    string         `json:"name"`
	Enabled bool           `json:"enabled"`
	LibID   string         `json:"libId"`
	Match   organizeMatch  `json:"match"`
	Action  organizeAction `json:"action"`
	// IntervalHours 是定时执行的预留字段(v1 仅手动,默认 0)。
	IntervalHours int `json:"intervalHours,omitempty"`
}

// organizeRulesDoc 是 DataDir/organize.json 的内容。
type organizeRulesDoc struct {
	Rules []organizeRule `json:"rules"`
}

// organizeRunState 是当前整理作业的实时状态(单飞,供 UI 轮询)。
type organizeRunState struct {
	Running    bool   `json:"running"`
	JobID      string `json:"jobId"`
	Kind       string `json:"kind"` // run|undo
	RuleID     string `json:"ruleId"`
	RuleName   string `json:"ruleName"`
	StartedAt  int64  `json:"startedAt"`
	FinishedAt int64  `json:"finishedAt"`
	Total      int    `json:"total"`
	Done       int    `json:"done"`
	Current    string `json:"current"`
	Moved      int    `json:"moved"`
	Failed     int    `json:"failed"`
	Skipped    int    `json:"skipped"`
	Cancel     bool   `json:"-"`
	LastError  string `json:"lastError"`
}

// organizeJob 是一次整理或撤销作业的摘要(保留最近 organizeJobsMax 条)。
type organizeJob struct {
	JobID       string `json:"jobId"`
	Kind        string `json:"kind"` // run|undo
	RuleID      string `json:"ruleId"`
	RuleName    string `json:"ruleName"`
	UndoOf      string `json:"undoOf,omitempty"`
	StartedAt   int64  `json:"startedAt"`
	FinishedAt  int64  `json:"finishedAt"`
	Total       int    `json:"total"`
	Moved       int    `json:"moved"`
	Failed      int    `json:"failed"`
	Skipped     int    `json:"skipped"`
	Cancelled   bool   `json:"cancelled"`
	DurationSec int64  `json:"durationSec"`
	CanUndo     bool   `json:"canUndo"`
}

// organizeJobsDoc 是 DataDir/organize/jobs.json 的内容。
type organizeJobsDoc struct {
	Items []organizeJob `json:"items"`
}

// organizeJournalLine 是 journal-<jobID>.jsonl 的一行;撤销按倒序回放成功行。
type organizeJournalLine struct {
	From string `json:"from"`
	To   string `json:"to"`
	Ts   int64  `json:"ts"`
	OK   bool   `json:"ok"`
	Err  string `json:"err,omitempty"`
}

// organizePlanItem 是预览/执行计划里的一条移动。
type organizePlanItem struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Reason string `json:"reason,omitempty"`
	Size   int64  `json:"size"`
}

// organizeSkipItem 是预览里被跳过的文件及原因。
type organizeSkipItem struct {
	From   string `json:"from"`
	Reason string `json:"reason"`
	Size   int64  `json:"size,omitempty"`
}

// organizePreviewResp 是 POST /api/organize/preview 的响应。
type organizePreviewResp struct {
	Items        []organizePlanItem `json:"items"`
	Skipped      []organizeSkipItem `json:"skipped"`
	Conflicts    int                `json:"conflicts"`
	TotalBytes   int64              `json:"totalBytes"`
	Total        int                `json:"total"`
	SkippedCount int                `json:"skippedCount"`
	Truncated    bool               `json:"truncated"`
}

// organizeCandidate 是从索引枚举出的候选文件。
type organizeCandidate struct {
	vpath   string // 虚拟路径 /libN/...
	rel     string // 库内相对路径(slash)
	name    string
	ext     string // 小写含点
	kind    string
	size    int64
	modTime int64
}

// organizeManager 保存自动整理的规则、单飞作业状态与作业历史。
type organizeManager struct {
	mu      sync.Mutex
	st      organizeRunState
	cancel  chan struct{}
	dataDir string
}

// newOrganizeManager 创建整理管理器;规则与作业文件按需读写。
func newOrganizeManager(dataDir string) *organizeManager {
	return &organizeManager{dataDir: dataDir}
}

// rulesPath 是规则文件路径(DataDir/organize.json)。
func (m *organizeManager) rulesPath() string { return filepath.Join(m.dataDir, "organize.json") }

// dir 是 journal 与作业历史所在目录(DataDir/organize/)。
func (m *organizeManager) dir() string { return filepath.Join(m.dataDir, "organize") }

// jobsPath 是作业历史文件路径。
func (m *organizeManager) jobsPath() string { return filepath.Join(m.dir(), "jobs.json") }

// journalPath 是某个作业的 journal 文件路径。
func (m *organizeManager) journalPath(jobID string) string {
	return filepath.Join(m.dir(), "journal-"+jobID+".jsonl")
}

// loadRules 读取规则文件;文件缺失或损坏时返回空规则集。
func (m *organizeManager) loadRules() organizeRulesDoc {
	raw, err := os.ReadFile(m.rulesPath())
	if err != nil {
		return organizeRulesDoc{Rules: []organizeRule{}}
	}
	var doc organizeRulesDoc
	if err := json.Unmarshal(raw, &doc); err != nil || doc.Rules == nil {
		return organizeRulesDoc{Rules: []organizeRule{}}
	}
	return doc
}

// saveRules 保存规则文件(先写临时文件再重命名,避免半写)。
func (m *organizeManager) saveRules(doc organizeRulesDoc) error {
	if doc.Rules == nil {
		doc.Rules = []organizeRule{}
	}
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(m.rulesPath()), 0o755); err != nil {
		return err
	}
	tmp := m.rulesPath() + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, m.rulesPath())
}

// loadJobs 读取作业历史;缺失或损坏时返回空列表。
func (m *organizeManager) loadJobs() organizeJobsDoc {
	raw, err := os.ReadFile(m.jobsPath())
	if err != nil {
		return organizeJobsDoc{Items: []organizeJob{}}
	}
	var doc organizeJobsDoc
	if err := json.Unmarshal(raw, &doc); err != nil || doc.Items == nil {
		return organizeJobsDoc{Items: []organizeJob{}}
	}
	return doc
}

// appendJobLocked 追加一条作业摘要,超出上限时截掉最旧的。调用方需持有 m.mu。
func (m *organizeManager) appendJobLocked(job organizeJob) {
	doc := m.loadJobs()
	doc.Items = append(doc.Items, job)
	if len(doc.Items) > organizeJobsMax {
		doc.Items = doc.Items[len(doc.Items)-organizeJobsMax:]
	}
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return
	}
	if err := os.MkdirAll(m.dir(), 0o755); err != nil {
		return
	}
	_ = os.WriteFile(m.jobsPath(), raw, 0o644)
}

// organizeJournal 是一次作业的移动记录写入器,每行一条 JSON。
type organizeJournal struct {
	f  *os.File
	bw *bufio.Writer
}

// openJournal 新建(覆盖)某个作业的 journal 文件。
func (m *organizeManager) openJournal(jobID string) (*organizeJournal, error) {
	if err := os.MkdirAll(m.dir(), 0o755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(m.journalPath(jobID), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return nil, err
	}
	return &organizeJournal{f: f, bw: bufio.NewWriter(f)}, nil
}

// write 写入一行 journal(忽略编码错误)。
func (j *organizeJournal) write(line organizeJournalLine) {
	raw, err := json.Marshal(line)
	if err != nil {
		return
	}
	_, _ = j.bw.Write(raw)
	_ = j.bw.WriteByte('\n')
}

// close 刷新并关闭 journal 文件。
func (j *organizeJournal) close() {
	_ = j.bw.Flush()
	_ = j.f.Close()
}

// readJournal 读取作业 journal 的全部行(忽略无法解析的行)。
func (m *organizeManager) readJournal(jobID string) ([]organizeJournalLine, error) {
	f, err := os.Open(m.journalPath(jobID))
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []organizeJournalLine
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		var line organizeJournalLine
		if err := json.Unmarshal(sc.Bytes(), &line); err != nil {
			continue
		}
		out = append(out, line)
	}
	return out, sc.Err()
}

// cancelled 报告当前作业是否收到取消请求。
func (m *organizeManager) cancelled() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.st.Cancel
}

// progress 更新作业实时计数(供 UI 轮询)。
func (m *organizeManager) progress(done, moved, failed, skipped int, current string) {
	m.mu.Lock()
	m.st.Done = done
	m.st.Moved = moved
	m.st.Failed = failed
	m.st.Skipped = skipped
	m.st.Current = current
	m.mu.Unlock()
}

// finishOrganize 收尾:写作业摘要、清空单飞状态。
func (m *organizeManager) finishOrganize(job organizeJob, moved, failed, skipped int, cancelled bool, lastErr string) {
	now := time.Now().Unix()
	job.Moved, job.Failed, job.Skipped = moved, failed, skipped
	job.FinishedAt = now
	job.DurationSec = now - job.StartedAt
	job.Cancelled = cancelled
	job.CanUndo = moved > 0
	m.mu.Lock()
	m.appendJobLocked(job)
	m.st.Running = false
	m.st.FinishedAt = now
	m.st.Done = job.Total
	m.st.Moved, m.st.Failed, m.st.Skipped = moved, failed, skipped
	m.st.Current = ""
	m.st.LastError = lastErr
	m.cancel = nil
	m.mu.Unlock()
}

// organizeSeq 是规则/作业 ID 的进程内序号。
var organizeSeq atomic.Uint64

// newOrganizeID 生成规则或作业的短 ID。
func newOrganizeID(prefix string) string {
	n := organizeSeq.Add(1)
	return fmt.Sprintf("%s%d-%d", prefix, time.Now().UnixMilli(), n)
}

// exists 报告路径是否已经存在。
func exists(p string) bool {
	_, err := os.Lstat(p)
	return err == nil
}

// slashJoin 用 slash 连接两段相对路径。
func slashJoin(a, b string) string {
	if a == "" {
		return b
	}
	return a + "/" + b
}

// normalizeOrganizeRule 校验并清洗规则字段;返回错误消息(空串表示通过)。
func normalizeOrganizeRule(cfg *Config, r *organizeRule) string {
	r.Name = strings.TrimSpace(r.Name)
	if r.Name == "" {
		return "规则名称不能为空"
	}
	if len([]rune(r.Name)) > 60 {
		return "规则名称过长(最多 60 字)"
	}
	lib := cfg.LibraryByID(r.LibID)
	if lib == nil {
		return "存储库不存在"
	}

	// 目标目录:库内相对路径
	moveTo := strings.Trim(strings.ReplaceAll(r.Action.MoveTo, "\\", "/"), "/")
	if moveTo == "" {
		return "目标目录不能为空"
	}
	if strings.Contains(moveTo, "..") || filepath.IsAbs(moveTo) || strings.Contains(moveTo, ":") {
		return "目标目录必须是库内相对路径"
	}
	moveTo = path.Clean(moveTo)
	if moveTo == "." || moveTo == ".." || strings.HasPrefix(moveTo, "../") {
		return "目标目录必须是库内相对路径"
	}
	for _, seg := range strings.Split(moveTo, "/") {
		if !SafeName(seg) {
			return "目标目录包含非法字符或保留名"
		}
	}
	lower := strings.ToLower(moveTo)
	if lower == ".linnasvault" || strings.HasPrefix(lower, ".linnasvault/") ||
		lower == ".linnastrash" || strings.HasPrefix(lower, ".linnastrash/") {
		return "目标目录不能是保险箱或回收站"
	}
	r.Action.MoveTo = moveTo

	switch r.Action.GroupBy {
	case "":
		r.Action.GroupBy = "none"
	case "none", "ext", "kind", "year-month":
	default:
		return "分组方式必须是 ext、kind、year-month 或 none"
	}

	// 匹配条件
	m := &r.Match
	m.PathPrefix = strings.Trim(strings.ReplaceAll(m.PathPrefix, "\\", "/"), "/")
	if m.PathPrefix != "" {
		cp := path.Clean(m.PathPrefix)
		if cp == "." || cp == ".." || strings.HasPrefix(cp, "../") || strings.Contains(cp, ":") {
			return "匹配前缀必须是库内相对路径"
		}
		for _, seg := range strings.Split(cp, "/") {
			if !SafeName(seg) {
				return "匹配前缀包含非法字符或保留名"
			}
		}
		m.PathPrefix = cp
		// 目标不能落在匹配前缀内(否则整理结果会被下次规则再次命中)
		if cp == r.Action.MoveTo || strings.HasPrefix(r.Action.MoveTo, cp+"/") {
			return "目标目录不能位于匹配前缀内"
		}
	}

	var exts []string
	seen := make(map[string]bool, len(m.Exts))
	for _, e := range m.Exts {
		e = strings.TrimSpace(strings.ToLower(e))
		if e == "" {
			continue
		}
		if !strings.HasPrefix(e, ".") {
			e = "." + e
		}
		if seen[e] {
			continue
		}
		seen[e] = true
		exts = append(exts, e)
		if len(exts) > 32 {
			return "扩展名数量过多(最多 32 个)"
		}
	}
	m.Exts = exts

	m.NameContains = strings.TrimSpace(m.NameContains)
	switch m.Kind {
	case "", "video", "image", "audio", "other":
	default:
		return "类型必须是 video、image、audio 或 other"
	}
	if m.MinSizeMB < 0 || m.MaxSizeMB < 0 {
		return "大小限制不能为负数"
	}
	if m.MinSizeMB > 0 && m.MaxSizeMB > 0 && m.MinSizeMB > m.MaxSizeMB {
		return "最小大小不能大于最大大小"
	}
	if m.OlderThanDays < 0 {
		return "天数限制不能为负数"
	}
	if m.OlderThanDays > 3650 {
		m.OlderThanDays = 3650
	}
	return ""
}

// matchOrganize 判断候选文件是否命中规则(全部条件同时满足)。
func (r *organizeRule) matchOrganize(c organizeCandidate, now int64) bool {
	m := r.Match
	if m.PathPrefix != "" && c.rel != m.PathPrefix && !strings.HasPrefix(c.rel, m.PathPrefix+"/") {
		return false
	}
	if len(m.Exts) > 0 {
		hit := false
		for _, e := range m.Exts {
			if e == c.ext {
				hit = true
				break
			}
		}
		if !hit {
			return false
		}
	}
	if m.NameContains != "" && !strings.Contains(strings.ToLower(c.name), strings.ToLower(m.NameContains)) {
		return false
	}
	if m.Kind != "" && c.kind != m.Kind {
		return false
	}
	const mb = int64(1024 * 1024)
	if m.MinSizeMB > 0 && float64(c.size) < m.MinSizeMB*float64(mb) {
		return false
	}
	if m.MaxSizeMB > 0 && float64(c.size) > m.MaxSizeMB*float64(mb) {
		return false
	}
	if m.OlderThanDays > 0 && c.modTime > now-int64(m.OlderThanDays)*86400 {
		return false
	}
	return true
}

// targetRel 计算命中文件在库内的目标相对路径(含分组子目录)。
func (r *organizeRule) targetRel(c organizeCandidate) string {
	dir := r.Action.MoveTo
	switch r.Action.GroupBy {
	case "ext":
		g := strings.TrimPrefix(c.ext, ".")
		if g == "" {
			g = "noext"
		}
		dir = slashJoin(dir, g)
	case "kind":
		g := c.kind
		if g == "" {
			g = "other"
		}
		dir = slashJoin(dir, g)
	case "year-month":
		t := time.Unix(c.modTime, 0)
		dir = slashJoin(dir, fmt.Sprintf("%04d-%02d", t.Year(), int(t.Month())))
	}
	return slashJoin(dir, c.name)
}

// organizeProtectedAbs 报告 abs 是否位于保险箱、回收站或该库的排除目录内。
func (s *Server) organizeProtectedAbs(lib *Library, abs string) bool {
	if lib == nil {
		return true
	}
	if s.cfg.ExcludedAbs(lib, abs) {
		return true
	}
	if vd := vaultDirForConfig(s.cfg); vd != "" && withinRoot(vd, abs) {
		return true
	}
	return withinRoot(trashDirFor(lib), abs)
}

// organizeSkipReason 校验单个候选文件能否安全移动;返回跳过原因(空串表示可以)。
func (s *Server) organizeSkipReason(lib *Library, cand organizeCandidate, rule organizeRule) string {
	if lib.ReadOnly {
		return "存储库只读"
	}
	fromLib, fromAbs, err := s.cfg.Resolve(cand.vpath)
	if err != nil {
		return "源路径校验失败"
	}
	if fromLib.ID != lib.ID {
		return "跨库移动"
	}
	if s.organizeProtectedAbs(fromLib, fromAbs) {
		return "位于保险箱、回收站或排除目录"
	}
	toRel := rule.targetRel(cand)
	toAbs := filepath.Join(lib.Path, filepath.FromSlash(toRel))
	if !withinRoot(lib.Path, toAbs) {
		return "目标路径越界"
	}
	if s.organizeProtectedAbs(lib, filepath.Dir(toAbs)) {
		return "目标目录位于保险箱、回收站或排除目录"
	}
	return ""
}

// organizePlan 枚举索引中命中规则的文件并生成移动计划(只读,不落盘)。
func (s *Server) organizePlan(rule organizeRule, limit int) (organizePreviewResp, string) {
	resp := organizePreviewResp{Items: []organizePlanItem{}, Skipped: []organizeSkipItem{}}
	if s.index == nil {
		return resp, "索引未启用"
	}
	lib := s.cfg.LibraryByID(rule.LibID)
	if lib == nil {
		return resp, "存储库不存在"
	}
	rows, err := s.index.db.Query(
		`SELECT lib_id, path, name, size, mod_time, kind FROM files WHERE is_dir=0 AND lib_id=? ORDER BY path`,
		rule.LibID)
	if err != nil {
		return resp, "查询索引失败: " + err.Error()
	}
	defer rows.Close()

	now := time.Now().Unix()
	extra := 0
	for rows.Next() {
		var libID, vpath, name, kind string
		var size, modTime int64
		if err := rows.Scan(&libID, &vpath, &name, &size, &modTime, &kind); err != nil {
			return resp, "读取索引失败: " + err.Error()
		}
		rel := strings.TrimPrefix(vpath, "/"+libID+"/")
		if rel == vpath {
			rel = strings.TrimPrefix(strings.TrimPrefix(vpath, "/"+libID), "/")
		}
		cand := organizeCandidate{
			vpath:   vpath,
			rel:     rel,
			name:    name,
			ext:     strings.ToLower(filepath.Ext(name)),
			kind:    kind,
			size:    size,
			modTime: modTime,
		}
		if !rule.matchOrganize(cand, now) {
			continue
		}
		if len(resp.Items) >= limit {
			extra++
			continue
		}
		if reason := s.organizeSkipReason(lib, cand, rule); reason != "" {
			resp.SkippedCount++
			if len(resp.Skipped) < organizeSkipListMax {
				resp.Skipped = append(resp.Skipped, organizeSkipItem{From: vpath, Reason: reason, Size: size})
			}
			continue
		}
		toRel := rule.targetRel(cand)
		toVP := "/" + lib.ID + "/" + toRel
		if strings.EqualFold(toVP, vpath) {
			resp.SkippedCount++
			if len(resp.Skipped) < organizeSkipListMax {
				resp.Skipped = append(resp.Skipped, organizeSkipItem{From: vpath, Reason: "已在目标位置", Size: size})
			}
			continue
		}
		item := organizePlanItem{From: vpath, To: toVP, Size: size}
		if exists(filepath.Join(lib.Path, filepath.FromSlash(toRel))) {
			resp.Conflicts++
			item.Reason = "目标同名,执行时自动重命名"
		}
		resp.Items = append(resp.Items, item)
		resp.TotalBytes += size
	}
	if err := rows.Err(); err != nil {
		return resp, "读取索引失败: " + err.Error()
	}
	resp.Total = len(resp.Items)
	resp.Truncated = extra > 0
	omitted := resp.SkippedCount - len(resp.Skipped)
	if extra > 0 {
		resp.SkippedCount += extra
		if omitted > 0 {
			resp.Skipped = append(resp.Skipped, organizeSkipItem{From: "(更多)", Reason: fmt.Sprintf("另有 %d 个文件被跳过未列出", omitted)})
		}
		resp.Skipped = append(resp.Skipped, organizeSkipItem{From: "(更多文件)", Reason: fmt.Sprintf("另有 %d 个文件超出单次上限 %d,本次不执行", extra, limit)})
	} else if omitted > 0 {
		resp.Skipped = append(resp.Skipped, organizeSkipItem{From: "(更多)", Reason: fmt.Sprintf("另有 %d 个文件被跳过未列出", omitted)})
	}
	return resp, ""
}

// uniqueTargetPath 在目标已被占用时按 "name (1).ext" 递增直到可用。
func uniqueTargetPath(abs string) (string, bool) {
	if !exists(abs) {
		return abs, true
	}
	dir := filepath.Dir(abs)
	base := filepath.Base(abs)
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	for i := 1; i <= organizeRenameAttempts; i++ {
		cand := filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
		if !exists(cand) {
			return cand, true
		}
	}
	return "", false
}

// runOrganize 后台执行整理作业:逐条移动、写 journal、完成后增量刷新索引。
func (s *Server) runOrganize(job organizeJob, items []organizePlanItem) {
	m := s.organize
	journal, err := m.openJournal(job.JobID)
	if err != nil {
		m.finishOrganize(job, 0, 0, 0, false, "创建 journal 失败: "+err.Error())
		return
	}
	moved, failed, skipped := 0, 0, 0
	cancelled := false
	changes := make([]IndexChange, 0, len(items))
	for i, it := range items {
		if m.cancelled() {
			cancelled = true
			break
		}
		fromLib, fromAbs, err := s.cfg.Resolve(it.From)
		if err != nil {
			skipped++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "源路径校验失败"})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		toRel := strings.TrimPrefix(it.To, "/"+fromLib.ID+"/")
		toAbs := filepath.Join(fromLib.Path, filepath.FromSlash(toRel))
		if !withinRoot(fromLib.Path, toAbs) {
			skipped++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "目标路径越界"})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		if fromLib.ReadOnly || s.organizeProtectedAbs(fromLib, fromAbs) ||
			s.organizeProtectedAbs(fromLib, filepath.Dir(toAbs)) {
			skipped++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "位于保险箱、回收站或排除目录"})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		if _, statErr := os.Stat(fromAbs); statErr != nil {
			skipped++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "源文件不存在(索引过期)"})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		if err := os.MkdirAll(filepath.Dir(toAbs), 0o755); err != nil {
			failed++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "创建目标目录失败: " + err.Error()})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		target, ok := uniqueTargetPath(toAbs)
		if !ok {
			failed++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: "无法生成不冲突的目标文件名"})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		if err := os.Rename(fromAbs, target); err != nil {
			failed++
			journal.write(organizeJournalLine{From: it.From, To: it.To, Ts: time.Now().Unix(), Err: err.Error()})
			m.progress(i+1, moved, failed, skipped, it.From)
			continue
		}
		moved++
		actual := VirtualPath(fromLib, target)
		changes = append(changes, IndexChange{Remove: it.From, Add: actual})
		journal.write(organizeJournalLine{From: it.From, To: actual, Ts: time.Now().Unix(), OK: true})
		m.progress(i+1, moved, failed, skipped, it.From)
	}
	journal.close()
	m.finishOrganize(job, moved, failed, skipped, cancelled, "")
	if len(changes) > 0 && s.index != nil {
		s.index.UpdatePaths(s.cfg, changes...)
	}
}

// runOrganizeUndo 按 journal 倒序回搬;原位置被占用时跳过并逐条记录。
func (s *Server) runOrganizeUndo(job organizeJob, plan []organizeJournalLine) {
	m := s.organize
	journal, err := m.openJournal(job.JobID)
	if err != nil {
		m.finishOrganize(job, 0, 0, 0, false, "创建 journal 失败: "+err.Error())
		return
	}
	moved, failed, skipped := 0, 0, 0
	cancelled := false
	changes := make([]IndexChange, 0, len(plan))
	for i := len(plan) - 1; i >= 0; i-- {
		line := plan[i]
		if m.cancelled() {
			cancelled = true
			break
		}
		done := len(plan) - i
		fromLib, fromAbs, err := s.cfg.Resolve(line.To)
		if err != nil {
			skipped++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: "当前路径校验失败"})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		toLib, toAbs, err := s.cfg.Resolve(line.From)
		if err != nil || toLib.ID != fromLib.ID {
			skipped++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: "原路径校验失败"})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		if fromLib.ReadOnly || s.organizeProtectedAbs(fromLib, fromAbs) ||
			s.organizeProtectedAbs(toLib, filepath.Dir(toAbs)) {
			skipped++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: "位于保险箱、回收站或排除目录"})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		if exists(toAbs) {
			skipped++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: "原位置已被占用"})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		if err := os.MkdirAll(filepath.Dir(toAbs), 0o755); err != nil {
			failed++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: "创建目录失败: " + err.Error()})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		if err := os.Rename(fromAbs, toAbs); err != nil {
			failed++
			journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), Err: err.Error()})
			m.progress(done, moved, failed, skipped, line.From)
			continue
		}
		moved++
		changes = append(changes, IndexChange{Remove: line.To, Add: line.From})
		journal.write(organizeJournalLine{From: line.To, To: line.From, Ts: time.Now().Unix(), OK: true})
		m.progress(done, moved, failed, skipped, line.From)
	}
	journal.close()
	m.finishOrganize(job, moved, failed, skipped, cancelled, "")
	if len(changes) > 0 && s.index != nil {
		s.index.UpdatePaths(s.cfg, changes...)
	}
}

// organizePreviewReq 是 POST /api/organize/preview 的请求体。
type organizePreviewReq struct {
	RuleID string        `json:"ruleId"`
	Rule   *organizeRule `json:"rule"`
}

// organizeRunReq 是 POST /api/organize/run 的请求体。
type organizeRunReq struct {
	RuleID string `json:"ruleId"`
}

// organizeUndoReq 是 POST /api/organize/undo 的请求体。
type organizeUndoReq struct {
	JobID string `json:"jobId"`
}

// handleOrganizeRules 处理规则的读取(GET)、新增(POST)与修改(PUT)。
func (s *Server) handleOrganizeRules(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	switch r.Method {
	case http.MethodGet:
		doc := m.loadRules()
		writeJSON(w, http.StatusOK, map[string]any{"rules": doc.Rules})
	case http.MethodPost:
		var rule organizeRule
		if err := readJSON(r, &rule); err != nil {
			writeErr(w, http.StatusBadRequest, "请求格式错误")
			return
		}
		rule.ID = newOrganizeID("rule-")
		if msg := normalizeOrganizeRule(s.cfg, &rule); msg != "" {
			writeErr(w, http.StatusBadRequest, msg)
			return
		}
		doc := m.loadRules()
		doc.Rules = append(doc.Rules, rule)
		if err := m.saveRules(doc); err != nil {
			writeErr(w, http.StatusInternalServerError, "保存规则失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "rule": rule})
	case http.MethodPut:
		var rule organizeRule
		if err := readJSON(r, &rule); err != nil || rule.ID == "" {
			writeErr(w, http.StatusBadRequest, "请求格式错误")
			return
		}
		if msg := normalizeOrganizeRule(s.cfg, &rule); msg != "" {
			writeErr(w, http.StatusBadRequest, msg)
			return
		}
		doc := m.loadRules()
		found := false
		for i := range doc.Rules {
			if doc.Rules[i].ID == rule.ID {
				doc.Rules[i] = rule
				found = true
				break
			}
		}
		if !found {
			writeErr(w, http.StatusNotFound, "规则不存在")
			return
		}
		if err := m.saveRules(doc); err != nil {
			writeErr(w, http.StatusInternalServerError, "保存规则失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "rule": rule})
	default:
		writeErr(w, http.StatusMethodNotAllowed, "不支持的请求方法")
	}
}

// handleOrganizeRuleDelete 删除一条规则(?id=<规则ID>)。
func (s *Server) handleOrganizeRuleDelete(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	id := r.URL.Query().Get("id")
	if id == "" {
		writeErr(w, http.StatusBadRequest, "缺少规则 ID")
		return
	}
	doc := m.loadRules()
	out := make([]organizeRule, 0, len(doc.Rules))
	found := false
	for _, rule := range doc.Rules {
		if rule.ID == id {
			found = true
			continue
		}
		out = append(out, rule)
	}
	if !found {
		writeErr(w, http.StatusNotFound, "规则不存在")
		return
	}
	doc.Rules = out
	if err := m.saveRules(doc); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存规则失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// handleOrganizePreview 预览规则命中的文件与目标(只读,不落盘)。
func (s *Server) handleOrganizePreview(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil || s.index == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	var req organizePreviewReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	var rule *organizeRule
	if req.RuleID != "" {
		doc := m.loadRules()
		for i := range doc.Rules {
			if doc.Rules[i].ID == req.RuleID {
				rule = &doc.Rules[i]
				break
			}
		}
		if rule == nil {
			writeErr(w, http.StatusNotFound, "规则不存在")
			return
		}
	} else if req.Rule != nil {
		draft := *req.Rule
		if msg := normalizeOrganizeRule(s.cfg, &draft); msg != "" {
			writeErr(w, http.StatusBadRequest, msg)
			return
		}
		rule = &draft
	} else {
		writeErr(w, http.StatusBadRequest, "缺少 ruleId 或 rule")
		return
	}
	resp, msg := s.organizePlan(*rule, organizeRunMax)
	if msg != "" {
		writeErr(w, http.StatusBadRequest, msg)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleOrganizeRun 启动一次整理作业(单飞,后台执行)。
func (s *Server) handleOrganizeRun(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil || s.index == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	var req organizeRunReq
	if err := readJSON(r, &req); err != nil || req.RuleID == "" {
		writeErr(w, http.StatusBadRequest, "缺少规则 ID")
		return
	}
	doc := m.loadRules()
	var rule *organizeRule
	for i := range doc.Rules {
		if doc.Rules[i].ID == req.RuleID {
			rule = &doc.Rules[i]
			break
		}
	}
	if rule == nil {
		writeErr(w, http.StatusNotFound, "规则不存在")
		return
	}
	if !rule.Enabled {
		writeErr(w, http.StatusBadRequest, "规则已停用")
		return
	}
	resp, msg := s.organizePlan(*rule, organizeRunMax)
	if msg != "" {
		writeErr(w, http.StatusBadRequest, msg)
		return
	}

	m.mu.Lock()
	if m.st.Running {
		m.mu.Unlock()
		writeErr(w, http.StatusConflict, "已有整理作业在运行")
		return
	}
	job := organizeJob{
		JobID:     newOrganizeID("job-"),
		Kind:      "run",
		RuleID:    rule.ID,
		RuleName:  rule.Name,
		StartedAt: time.Now().Unix(),
		Total:     len(resp.Items),
	}
	m.st = organizeRunState{
		Running:   true,
		JobID:     job.JobID,
		Kind:      job.Kind,
		RuleID:    job.RuleID,
		RuleName:  job.RuleName,
		StartedAt: job.StartedAt,
		Total:     job.Total,
	}
	m.cancel = make(chan struct{})
	m.mu.Unlock()

	go s.runOrganize(job, resp.Items)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "jobId": job.JobID, "total": len(resp.Items)})
}

// handleOrganizeCancel 请求取消当前整理作业。
func (s *Server) handleOrganizeCancel(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	m.mu.Lock()
	if m.st.Running {
		m.st.Cancel = true
		if m.cancel != nil {
			close(m.cancel)
			m.cancel = nil
		}
	}
	m.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// handleOrganizeStatus 返回当前(或最近一次)整理作业的实时状态。
func (s *Server) handleOrganizeStatus(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil {
		writeJSON(w, http.StatusOK, organizeRunState{})
		return
	}
	m.mu.Lock()
	st := m.st
	m.mu.Unlock()
	writeJSON(w, http.StatusOK, st)
}

// handleOrganizeJobs 列出最近的整理/撤销作业(最新在前)。
func (s *Server) handleOrganizeJobs(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil {
		writeJSON(w, http.StatusOK, map[string]any{"items": []organizeJob{}})
		return
	}
	limit := organizeJobsMax
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 200 {
			limit = n
		}
	}
	doc := m.loadJobs()
	items := doc.Items
	if len(items) > limit {
		items = items[len(items)-limit:]
	}
	out := make([]organizeJob, 0, len(items))
	for i := len(items) - 1; i >= 0; i-- {
		out = append(out, items[i])
	}
	// 只有「整理」作业可撤销；已被撤销过的整理作业不再提供撤销。
	undone := make(map[string]bool)
	for _, it := range items {
		if it.Kind == "undo" && it.UndoOf != "" {
			undone[it.UndoOf] = true
		}
	}
	for i := range out {
		out[i].CanUndo = out[i].Kind == "run" && out[i].Moved > 0 && !undone[out[i].JobID]
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

// handleOrganizeUndo 按作业 journal 倒序回搬(单飞,后台执行)。
func (s *Server) handleOrganizeUndo(w http.ResponseWriter, r *http.Request) {
	m := s.organize
	if m == nil || s.index == nil {
		writeErr(w, http.StatusServiceUnavailable, "自动整理未启用")
		return
	}
	var req organizeUndoReq
	if err := readJSON(r, &req); err != nil || req.JobID == "" {
		writeErr(w, http.StatusBadRequest, "缺少作业 ID")
		return
	}
	var target *organizeJob
	doc := m.loadJobs()
	for i := range doc.Items {
		if doc.Items[i].JobID == req.JobID {
			target = &doc.Items[i]
			break
		}
	}
	if target == nil {
		writeErr(w, http.StatusNotFound, "作业不存在")
		return
	}
	if target.Kind != "run" {
		writeErr(w, http.StatusBadRequest, "只有整理作业可撤销")
		return
	}
	for _, it := range doc.Items {
		if it.Kind == "undo" && it.UndoOf == target.JobID {
			writeErr(w, http.StatusBadRequest, "该作业已撤销")
			return
		}
	}
	lines, err := m.readJournal(req.JobID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "作业记录不可用")
		return
	}
	plan := make([]organizeJournalLine, 0, len(lines))
	for _, line := range lines {
		if line.OK && line.From != "" && line.To != "" {
			plan = append(plan, line)
		}
	}
	if len(plan) == 0 {
		writeErr(w, http.StatusBadRequest, "该作业没有可撤销的移动")
		return
	}

	m.mu.Lock()
	if m.st.Running {
		m.mu.Unlock()
		writeErr(w, http.StatusConflict, "已有整理作业在运行")
		return
	}
	job := organizeJob{
		JobID:     newOrganizeID("undo-"),
		Kind:      "undo",
		RuleID:    target.RuleID,
		RuleName:  target.RuleName,
		UndoOf:    target.JobID,
		StartedAt: time.Now().Unix(),
		Total:     len(plan),
	}
	m.st = organizeRunState{
		Running:   true,
		JobID:     job.JobID,
		Kind:      job.Kind,
		RuleID:    job.RuleID,
		RuleName:  job.RuleName,
		StartedAt: job.StartedAt,
		Total:     job.Total,
	}
	m.cancel = make(chan struct{})
	m.mu.Unlock()

	go s.runOrganizeUndo(job, plan)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "jobId": job.JobID, "total": len(plan)})
}
