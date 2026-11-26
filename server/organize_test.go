package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newOrganizeTestEnv 构造一个带单个可写存储库的整理测试环境。
func newOrganizeTestEnv(t *testing.T) (*Server, *Index, *Config, string) {
	t.Helper()
	dataDir := t.TempDir()
	ix, err := OpenIndex(dataDir)
	if err != nil {
		t.Fatalf("打开索引失败: %v", err)
	}
	t.Cleanup(func() { _ = ix.Close() })
	root := t.TempDir()
	cfg := &Config{
		DataDir:   dataDir,
		Libraries: []Library{{ID: "lib1", Name: "测试库", Path: root, Enabled: true}},
	}
	s := &Server{cfg: cfg, index: ix, organize: newOrganizeManager(dataDir)}
	return s, ix, cfg, root
}

// waitIndexHas 轮询等待索引中某个虚拟路径出现/消失(UpdatePaths 是后台执行)。
func waitIndexHas(t *testing.T, ix *Index, vpath string, want bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var n int
		if err := ix.db.QueryRow(`SELECT COUNT(*) FROM files WHERE path=?`, vpath).Scan(&n); err != nil {
			t.Fatalf("查询索引失败: %v", err)
		}
		if (n > 0) == want {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("索引未在超时内更新: %s 期望存在=%v", vpath, want)
}

// startOrganizeJob 把管理器置为单飞运行态,模拟 handleOrganizeRun 的入队部分。
func startOrganizeJob(s *Server, job organizeJob) {
	s.organize.mu.Lock()
	s.organize.st = organizeRunState{
		Running:   true,
		JobID:     job.JobID,
		Kind:      job.Kind,
		RuleID:    job.RuleID,
		RuleName:  job.RuleName,
		StartedAt: job.StartedAt,
		Total:     job.Total,
	}
	s.organize.cancel = make(chan struct{})
	s.organize.mu.Unlock()
}

func TestNormalizeOrganizeRule(t *testing.T) {
	cfg := &Config{Libraries: []Library{
		{ID: "lib1", Name: "库", Path: t.TempDir(), Enabled: true},
	}}
	rule := organizeRule{
		Name:    "图片按年归档",
		Enabled: true,
		LibID:   "lib1",
		Match:   organizeMatch{Exts: []string{"JPG", ".png", "jpg"}, Kind: "image", MinSizeMB: 1, MaxSizeMB: 100},
		Action:  organizeAction{MoveTo: "photos/", GroupBy: "year-month"},
	}
	if msg := normalizeOrganizeRule(cfg, &rule); msg != "" {
		t.Fatalf("合法规则被拒绝: %s", msg)
	}
	if rule.Action.MoveTo != "photos" {
		t.Errorf("目标目录未清洗: %q", rule.Action.MoveTo)
	}
	if len(rule.Match.Exts) != 2 || rule.Match.Exts[0] != ".jpg" || rule.Match.Exts[1] != ".png" {
		t.Errorf("扩展名未归一或未去重: %#v", rule.Match.Exts)
	}

	bad := []struct {
		name string
		rule organizeRule
		want string
	}{
		{"空名称", organizeRule{LibID: "lib1", Action: organizeAction{MoveTo: "x", GroupBy: "none"}}, "规则名称"},
		{"库不存在", organizeRule{Name: "x", LibID: "nope", Action: organizeAction{MoveTo: "x", GroupBy: "none"}}, "存储库不存在"},
		{"目标为空", organizeRule{Name: "x", LibID: "lib1"}, "目标目录不能为空"},
		{"目标越界", organizeRule{Name: "x", LibID: "lib1", Action: organizeAction{MoveTo: "../escape", GroupBy: "none"}}, "库内相对路径"},
		{"目标为保险箱", organizeRule{Name: "x", LibID: "lib1", Action: organizeAction{MoveTo: ".linNasVault", GroupBy: "none"}}, "保险箱或回收站"},
		{"分组非法", organizeRule{Name: "x", LibID: "lib1", Action: organizeAction{MoveTo: "x", GroupBy: "day"}}, "分组方式"},
		{"类型非法", organizeRule{Name: "x", LibID: "lib1", Match: organizeMatch{Kind: "doc"}, Action: organizeAction{MoveTo: "x", GroupBy: "none"}}, "类型必须是"},
		{"大小区间颠倒", organizeRule{Name: "x", LibID: "lib1", Match: organizeMatch{MinSizeMB: 10, MaxSizeMB: 1}, Action: organizeAction{MoveTo: "x", GroupBy: "none"}}, "最小大小"},
		{"目标嵌套在前缀内", organizeRule{Name: "x", LibID: "lib1", Match: organizeMatch{PathPrefix: "docs"}, Action: organizeAction{MoveTo: "docs/2025", GroupBy: "none"}}, "匹配前缀内"},
		{"前缀越界", organizeRule{Name: "x", LibID: "lib1", Match: organizeMatch{PathPrefix: "../out"}, Action: organizeAction{MoveTo: "x", GroupBy: "none"}}, "匹配前缀"},
	}
	for _, tc := range bad {
		r := tc.rule
		if msg := normalizeOrganizeRule(cfg, &r); !strings.Contains(msg, tc.want) {
			t.Errorf("%s: 期望错误包含 %q,得到 %q", tc.name, tc.want, msg)
		}
	}
}

func TestOrganizeMatchAndTarget(t *testing.T) {
	now := time.Now().Unix()
	rule := organizeRule{
		Match:  organizeMatch{PathPrefix: "photos", Exts: []string{".jpg"}, Kind: "image", MinSizeMB: 0.001, OlderThanDays: 1},
		Action: organizeAction{MoveTo: "archive", GroupBy: "ext"},
	}
	cand := organizeCandidate{
		name: "a.jpg", ext: ".jpg", rel: "photos/2026/a.jpg", kind: "image",
		size: 4096, modTime: now - 3*86400,
	}
	if !rule.matchOrganize(cand, now) {
		t.Fatal("合法候选未命中")
	}
	if got := rule.targetRel(cand); got != "archive/jpg/a.jpg" {
		t.Errorf("ext 分组目标错误: %q", got)
	}

	// 前缀必须是目录边界
	outside := cand
	outside.rel = "photos2/a.jpg"
	if rule.matchOrganize(outside, now) {
		t.Error("前缀 photos 不应命中 photos2")
	}
	// 类型不符
	notImage := cand
	notImage.kind = "video"
	if rule.matchOrganize(notImage, now) {
		t.Error("类型不符不应命中")
	}
	// 太新
	fresh := cand
	fresh.modTime = now
	if rule.matchOrganize(fresh, now) {
		t.Error("未超过天数限制不应命中")
	}
	// 太小
	tiny := cand
	tiny.size = 1
	if rule.matchOrganize(tiny, now) {
		t.Error("小于最小大小不应命中")
	}
	// 大小写扩展名
	upper := cand
	upper.ext = ".jpg"
	upper.name = "B.JPG"
	if !rule.matchOrganize(upper, now) {
		t.Error("扩展名应大小写不敏感")
	}

	// 其它分组方式
	rule.Action.GroupBy = "kind"
	if got := rule.targetRel(cand); got != "archive/image/a.jpg" {
		t.Errorf("kind 分组目标错误: %q", got)
	}
	rule.Action.GroupBy = "year-month"
	if got := rule.targetRel(cand); !strings.HasPrefix(got, "archive/20") || !strings.HasSuffix(got, "/a.jpg") {
		t.Errorf("year-month 分组目标错误: %q", got)
	}
	rule.Action.GroupBy = "none"
	if got := rule.targetRel(cand); got != "archive/a.jpg" {
		t.Errorf("none 分组目标错误: %q", got)
	}
	// 无扩展名归入 noext
	noExt := cand
	noExt.ext = ""
	noExt.name = "README"
	rule.Action.GroupBy = "ext"
	if got := rule.targetRel(noExt); got != "archive/noext/README" {
		t.Errorf("noext 分组目标错误: %q", got)
	}
}

func TestOrganizePlanRunUndo(t *testing.T) {
	s, ix, cfg, root := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]

	mustWrite(t, filepath.Join(root, "a.txt"), "aaa")
	mustWrite(t, filepath.Join(root, "b.txt"), "bbb")
	mustWrite(t, filepath.Join(root, "sub", "c.txt"), "ccc")
	mustWrite(t, filepath.Join(root, "sub", "d.jpg"), "jjj")
	if err := ix.scanLib(cfg, *lib, ""); err != nil {
		t.Fatalf("扫描失败: %v", err)
	}

	rule := organizeRule{
		ID: "r1", Name: "文本归档", Enabled: true, LibID: lib.ID,
		Match:  organizeMatch{Exts: []string{".txt"}},
		Action: organizeAction{MoveTo: "texts", GroupBy: "none"},
	}
	if msg := normalizeOrganizeRule(cfg, &rule); msg != "" {
		t.Fatalf("规则校验失败: %s", msg)
	}
	resp, msg := s.organizePlan(rule, organizeRunMax)
	if msg != "" {
		t.Fatalf("预览失败: %s", msg)
	}
	if resp.Total != 3 || len(resp.Items) != 3 {
		t.Fatalf("应命中 3 个 txt,得到 %d: %#v", resp.Total, resp.Items)
	}
	for _, it := range resp.Items {
		if !strings.HasPrefix(it.To, "/"+lib.ID+"/texts/") || !strings.HasSuffix(it.To, ".txt") {
			t.Errorf("目标路径不符: %s -> %s", it.From, it.To)
		}
	}
	if resp.Conflicts != 0 || resp.Truncated {
		t.Errorf("不应有冲突或截断: %#v", resp)
	}

	job := organizeJob{JobID: "job-test-1", Kind: "run", RuleID: rule.ID, RuleName: rule.Name, StartedAt: time.Now().Unix(), Total: len(resp.Items)}
	startOrganizeJob(s, job)
	s.runOrganize(job, resp.Items)

	for _, rel := range []string{"a.txt", "b.txt", "sub/c.txt"} {
		if exists(filepath.Join(root, filepath.FromSlash(rel))) {
			t.Errorf("源文件未移动: %s", rel)
		}
		if !exists(filepath.Join(root, "texts", filepath.Base(rel))) {
			t.Errorf("目标文件缺失: %s", rel)
		}
	}
	if !exists(filepath.Join(root, "sub", "d.jpg")) {
		t.Error("非 txt 文件不应移动")
	}
	jobs := s.organize.loadJobs()
	if len(jobs.Items) != 1 || jobs.Items[0].Moved != 3 || !jobs.Items[0].CanUndo {
		t.Fatalf("作业记录异常: %#v", jobs.Items)
	}
	lines, err := s.organize.readJournal(job.JobID)
	if err != nil || len(lines) != 3 || !lines[0].OK || lines[0].To == "" {
		t.Fatalf("journal 异常: err=%v lines=%#v", err, lines)
	}

	// 执行后索引即时可见
	waitIndexHas(t, ix, "/"+lib.ID+"/texts/a.txt", true)
	waitIndexHas(t, ix, "/"+lib.ID+"/a.txt", false)

	// 撤销:按 journal 倒序回搬
	plan := make([]organizeJournalLine, 0, len(lines))
	for _, line := range lines {
		if line.OK && line.From != "" && line.To != "" {
			plan = append(plan, line)
		}
	}
	undoJob := organizeJob{JobID: "undo-test-1", Kind: "undo", UndoOf: job.JobID, StartedAt: time.Now().Unix(), Total: len(plan)}
	startOrganizeJob(s, undoJob)
	s.runOrganizeUndo(undoJob, plan)

	for _, rel := range []string{"a.txt", "b.txt", "sub/c.txt"} {
		if !exists(filepath.Join(root, filepath.FromSlash(rel))) {
			t.Errorf("撤销后源文件未回位: %s", rel)
		}
		if exists(filepath.Join(root, "texts", filepath.Base(rel))) {
			t.Errorf("撤销后目标位置仍有文件: %s", rel)
		}
	}
	waitIndexHas(t, ix, "/"+lib.ID+"/a.txt", true)
	waitIndexHas(t, ix, "/"+lib.ID+"/texts/a.txt", false)

	jobs = s.organize.loadJobs()
	if len(jobs.Items) != 2 || jobs.Items[1].Kind != "undo" || jobs.Items[1].UndoOf != job.JobID || jobs.Items[1].Moved != 3 {
		t.Fatalf("撤销作业记录异常: %#v", jobs.Items)
	}
	if st := s.organize.st; st.Running {
		t.Errorf("作业结束后仍标记运行中: %#v", st)
	}
}

func TestOrganizeConflictSuffix(t *testing.T) {
	s, ix, cfg, root := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]

	mustWrite(t, filepath.Join(root, "a.txt"), "new")
	mustWrite(t, filepath.Join(root, "texts", "a.txt"), "old")
	if err := ix.scanLib(cfg, *lib, ""); err != nil {
		t.Fatalf("扫描失败: %v", err)
	}

	rule := organizeRule{
		ID: "r1", Name: "文本归档", Enabled: true, LibID: lib.ID,
		Match:  organizeMatch{Exts: []string{".txt"}},
		Action: organizeAction{MoveTo: "texts", GroupBy: "none"},
	}
	resp, msg := s.organizePlan(rule, organizeRunMax)
	if msg != "" {
		t.Fatalf("预览失败: %s", msg)
	}
	if resp.Conflicts != 1 || resp.Total != 1 {
		t.Fatalf("应只有 1 条计划且 1 个冲突: %#v", resp)
	}
	if len(resp.Skipped) != 1 || !strings.Contains(resp.Skipped[0].Reason, "已在目标位置") {
		t.Fatalf("已在目标位置的文件应跳过: %#v", resp.Skipped)
	}

	job := organizeJob{JobID: "job-conflict", Kind: "run", RuleID: rule.ID, RuleName: rule.Name, StartedAt: time.Now().Unix(), Total: len(resp.Items)}
	startOrganizeJob(s, job)
	s.runOrganize(job, resp.Items)

	if !exists(filepath.Join(root, "texts", "a.txt")) {
		t.Error("原有目标文件不应被覆盖")
	}
	conflictPath := filepath.Join(root, "texts", "a (1).txt")
	if !exists(conflictPath) {
		t.Fatalf("同名目标应自动重命名为 a (1).txt; 实际目录: %v", listNames(t, filepath.Join(root, "texts")))
	}
	raw, err := os.ReadFile(conflictPath)
	if err != nil || string(raw) != "new" {
		t.Errorf("重命名后的内容异常: %q err=%v", raw, err)
	}
	waitIndexHas(t, ix, "/"+lib.ID+"/texts/a (1).txt", true)
}

func TestOrganizeSkipsProtected(t *testing.T) {
	s, _, cfg, root := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]
	rule := organizeRule{ID: "r1", Name: "x", Enabled: true, LibID: lib.ID, Action: organizeAction{MoveTo: "texts", GroupBy: "none"}}

	trash := organizeCandidate{vpath: "/lib1/.linNasTrash/x.txt", rel: ".linNasTrash/x.txt", name: "x.txt", ext: ".txt", kind: "other", size: 1}
	if got := s.organizeSkipReason(lib, trash, rule); !strings.Contains(got, "保险箱、回收站或排除目录") {
		t.Errorf("回收站文件应跳过,得到 %q", got)
	}
	vault := organizeCandidate{vpath: "/lib1/.linNasVault/x.txt", rel: ".linNasVault/x.txt", name: "x.txt", ext: ".txt", kind: "other", size: 1}
	if got := s.organizeSkipReason(lib, vault, rule); !strings.Contains(got, "保险箱、回收站或排除目录") {
		t.Errorf("保险箱文件应跳过,得到 %q", got)
	}

	cfg.Libraries[0].Exclude = []string{"skipdir"}
	excluded := organizeCandidate{vpath: "/lib1/skipdir/y.txt", rel: "skipdir/y.txt", name: "y.txt", ext: ".txt", kind: "other", size: 1}
	if got := s.organizeSkipReason(lib, excluded, rule); !strings.Contains(got, "保险箱、回收站或排除目录") {
		t.Errorf("排除目录文件应跳过,得到 %q", got)
	}

	roLib := &Library{ID: "lib1", Name: "只读", Path: root, Enabled: true, ReadOnly: true}
	normal := organizeCandidate{vpath: "/lib1/a.txt", rel: "a.txt", name: "a.txt", ext: ".txt", kind: "other", size: 1}
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := s.organizeSkipReason(roLib, normal, rule); got != "存储库只读" {
		t.Errorf("只读库应跳过,得到 %q", got)
	}
}

func TestOrganizeRulesHandlers(t *testing.T) {
	s, _, cfg, _ := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]

	body := fmt.Sprintf(`{"name":"图片归档","enabled":true,"libId":%q,"match":{"exts":["JPG",".png"]},"action":{"moveTo":"photos","groupBy":"year-month"}}`, lib.ID)
	w := httptest.NewRecorder()
	s.handleOrganizeRules(w, httptest.NewRequest(http.MethodPost, "/api/organize/rules", strings.NewReader(body)))
	if w.Code != http.StatusOK {
		t.Fatalf("新增规则状态 %d: %s", w.Code, w.Body.String())
	}

	w = httptest.NewRecorder()
	s.handleOrganizeRules(w, httptest.NewRequest(http.MethodGet, "/api/organize/rules", nil))
	var got struct {
		Rules []organizeRule `json:"rules"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || len(got.Rules) != 1 {
		t.Fatalf("读取规则异常: err=%v body=%s", err, w.Body.String())
	}
	if got.Rules[0].Action.MoveTo != "photos" || len(got.Rules[0].Match.Exts) != 2 || got.Rules[0].Match.Exts[0] != ".jpg" {
		t.Fatalf("规则字段未按预期保存: %#v", got.Rules[0])
	}

	update := fmt.Sprintf(`{"id":%q,"name":"改名","enabled":false,"libId":%q,"match":{},"action":{"moveTo":"pics","groupBy":"none"}}`, got.Rules[0].ID, lib.ID)
	w = httptest.NewRecorder()
	s.handleOrganizeRules(w, httptest.NewRequest(http.MethodPut, "/api/organize/rules", strings.NewReader(update)))
	if w.Code != http.StatusOK {
		t.Fatalf("修改规则状态 %d: %s", w.Code, w.Body.String())
	}
	if rules := s.organize.loadRules(); len(rules.Rules) != 1 || rules.Rules[0].Name != "改名" || rules.Rules[0].Action.MoveTo != "pics" {
		t.Fatalf("修改未生效: %#v", rules.Rules)
	}

	w = httptest.NewRecorder()
	s.handleOrganizeRuleDelete(w, httptest.NewRequest(http.MethodDelete, "/api/organize/rules?id="+got.Rules[0].ID, nil))
	if w.Code != http.StatusOK {
		t.Fatalf("删除规则状态 %d: %s", w.Code, w.Body.String())
	}
	if rules := s.organize.loadRules(); len(rules.Rules) != 0 {
		t.Fatalf("删除后仍有规则: %#v", rules.Rules)
	}

	// 非法规则应 400
	w = httptest.NewRecorder()
	s.handleOrganizeRules(w, httptest.NewRequest(http.MethodPost, "/api/organize/rules", strings.NewReader(`{"name":"x","libId":"lib1","action":{"moveTo":"../bad","groupBy":"none"}}`)))
	if w.Code != http.StatusBadRequest {
		t.Errorf("非法规则应返回 400,得到 %d: %s", w.Code, w.Body.String())
	}
}

func TestOrganizeRunHandlerConflictAndStatus(t *testing.T) {
	s, ix, cfg, root := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]
	mustWrite(t, filepath.Join(root, "a.txt"), "aaa")
	if err := ix.scanLib(cfg, *lib, ""); err != nil {
		t.Fatal(err)
	}
	rule := organizeRule{ID: "r1", Name: "文本归档", Enabled: true, LibID: lib.ID, Match: organizeMatch{Exts: []string{".txt"}}, Action: organizeAction{MoveTo: "texts", GroupBy: "none"}}
	doc := s.organize.loadRules()
	doc.Rules = append(doc.Rules, rule)
	if err := s.organize.saveRules(doc); err != nil {
		t.Fatal(err)
	}

	// 手动置为运行中 -> 再次启动应 409
	s.organize.mu.Lock()
	s.organize.st = organizeRunState{Running: true}
	s.organize.mu.Unlock()
	w := httptest.NewRecorder()
	s.handleOrganizeRun(w, httptest.NewRequest(http.MethodPost, "/api/organize/run", strings.NewReader(`{"ruleId":"r1"}`)))
	if w.Code != http.StatusConflict {
		t.Fatalf("运行中再启动应 409,得到 %d: %s", w.Code, w.Body.String())
	}

	// 取消后状态复位,可正常启动
	s.organize.mu.Lock()
	s.organize.st = organizeRunState{}
	s.organize.mu.Unlock()
	w = httptest.NewRecorder()
	s.handleOrganizeRun(w, httptest.NewRequest(http.MethodPost, "/api/organize/run", strings.NewReader(`{"ruleId":"r1"}`)))
	if w.Code != http.StatusOK {
		t.Fatalf("启动整理失败 %d: %s", w.Code, w.Body.String())
	}
	// 等待后台作业结束
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		s.organize.mu.Lock()
		running := s.organize.st.Running
		s.organize.mu.Unlock()
		if !running {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !exists(filepath.Join(root, "texts", "a.txt")) {
		t.Errorf("后台作业未移动文件; 目录: %v", listNames(t, filepath.Join(root, "texts")))
	}
	waitIndexHas(t, ix, "/"+lib.ID+"/texts/a.txt", true)

	// 作业列表应有 1 条且可撤销
	w = httptest.NewRecorder()
	s.handleOrganizeJobs(w, httptest.NewRequest(http.MethodGet, "/api/organize/jobs", nil))
	var jobs struct {
		Items []organizeJob `json:"items"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &jobs); err != nil || len(jobs.Items) != 1 {
		t.Fatalf("作业列表异常: err=%v body=%s", err, w.Body.String())
	}
	if jobs.Items[0].Moved != 1 || !jobs.Items[0].CanUndo {
		t.Fatalf("作业摘要异常: %#v", jobs.Items[0])
	}
}

func TestOrganizeUndoHandlerGuards(t *testing.T) {
	s, ix, cfg, root := newOrganizeTestEnv(t)
	lib := &cfg.Libraries[0]
	mustWrite(t, filepath.Join(root, "a.txt"), "aaa")
	mustWrite(t, filepath.Join(root, "b.txt"), "bbb")
	if err := ix.scanLib(cfg, *lib, ""); err != nil {
		t.Fatal(err)
	}
	rule := organizeRule{ID: "r1", Name: "文本归档", Enabled: true, LibID: lib.ID, Match: organizeMatch{Exts: []string{".txt"}}, Action: organizeAction{MoveTo: "texts", GroupBy: "none"}}
	doc := s.organize.loadRules()
	doc.Rules = append(doc.Rules, rule)
	if err := s.organize.saveRules(doc); err != nil {
		t.Fatal(err)
	}
	waitIdle := func() {
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			s.organize.mu.Lock()
			running := s.organize.st.Running
			s.organize.mu.Unlock()
			if !running {
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	readJobs := func() []organizeJob {
		w := httptest.NewRecorder()
		s.handleOrganizeJobs(w, httptest.NewRequest(http.MethodGet, "/api/organize/jobs", nil))
		var out struct {
			Items []organizeJob `json:"items"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatalf("作业列表解析失败: %v", err)
		}
		return out.Items
	}

	// 执行一次整理
	w := httptest.NewRecorder()
	s.handleOrganizeRun(w, httptest.NewRequest(http.MethodPost, "/api/organize/run", strings.NewReader(`{"ruleId":"r1"}`)))
	if w.Code != http.StatusOK {
		t.Fatalf("启动整理失败 %d: %s", w.Code, w.Body.String())
	}
	waitIdle()
	runJobID := readJobs()[0].JobID
	if !readJobs()[0].CanUndo {
		t.Fatal("整理作业应可撤销")
	}

	// 撤销该作业
	w = httptest.NewRecorder()
	s.handleOrganizeUndo(w, httptest.NewRequest(http.MethodPost, "/api/organize/undo", strings.NewReader(`{"jobId":"`+runJobID+`"}`)))
	if w.Code != http.StatusOK {
		t.Fatalf("撤销失败 %d: %s", w.Code, w.Body.String())
	}
	waitIdle()
	if exists(filepath.Join(root, "texts", "a.txt")) || !exists(filepath.Join(root, "a.txt")) {
		t.Error("撤销后文件应回到原位置")
	}

	// 撤销后：整理作业与撤销作业都不应再提供撤销
	for _, j := range readJobs() {
		if j.CanUndo {
			t.Errorf("作业 %s(kind=%s) 撤销后不应再可撤销", j.JobID, j.Kind)
		}
	}

	// 再次撤销同一整理作业应被拒绝
	w = httptest.NewRecorder()
	s.handleOrganizeUndo(w, httptest.NewRequest(http.MethodPost, "/api/organize/undo", strings.NewReader(`{"jobId":"`+runJobID+`"}`)))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("重复撤销应 400,得到 %d: %s", w.Code, w.Body.String())
	}

	// 对撤销作业本身发起撤销也应被拒绝
	undoJobID := readJobs()[0].JobID
	w = httptest.NewRecorder()
	s.handleOrganizeUndo(w, httptest.NewRequest(http.MethodPost, "/api/organize/undo", strings.NewReader(`{"jobId":"`+undoJobID+`"}`)))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("撤销撤销作业应 400,得到 %d: %s", w.Code, w.Body.String())
	}
}

func TestOrganizeAuthorizeAdminOnly(t *testing.T) {
	s := &Server{}
	req := httptest.NewRequest(http.MethodGet, "/api/organize/rules", nil)
	if s.authorize(&Session{Role: "user"}, req) {
		t.Error("普通用户不应访问自动整理")
	}
	if s.authorize(&Session{Role: "viewer"}, req) {
		t.Error("只读用户不应访问自动整理")
	}
	if !s.authorize(&Session{Role: "admin"}, req) {
		t.Error("管理员应可访问自动整理")
	}
}

// listNames 返回目录下的文件名(便于失败时排查)。
func listNames(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return []string{err.Error()}
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	return names
}
