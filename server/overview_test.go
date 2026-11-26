package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// newOverviewTestServer 构造一个带索引 / 审计 / 分享 / 设备的测试服务。
func newOverviewTestServer(t *testing.T) (*Server, *Index) {
	t.Helper()
	root := t.TempDir()
	libDir := filepath.Join(root, "library")
	if err := os.MkdirAll(libDir, 0o755); err != nil {
		t.Fatal(err)
	}
	ix, err := OpenIndex(filepath.Join(root, "index"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ix.Close() })
	s := &Server{
		cfg: &Config{
			DataDir:              root,
			IndexRefreshSeconds:  120,
			SpaceWarnPercent:     10,
			SpaceCriticalPercent: 3,
			Libraries: []Library{{
				ID: "lib1", Name: "测试库", Path: libDir, Enabled: true,
			}},
		},
		index:   ix,
		audit:   newAuditLog(root),
		shares:  newShareStore(root),
		devices: newDeviceRegistry(),
		alerts:  newAlertStore(root),
	}
	return s, ix
}

// seedOverviewIndex 往索引里塞几行不同类型的文件。
func seedOverviewIndex(t *testing.T, ix *Index, libID string) {
	t.Helper()
	rows := []struct {
		vpath, name, kind string
		size              int64
		isDir             int
	}{
		{"/" + libID + "/a.mp4", "a.mp4", "video", 100, 0},
		{"/" + libID + "/b.mp4", "b.mp4", "video", 200, 0},
		{"/" + libID + "/c.jpg", "c.jpg", "image", 300, 0},
		{"/" + libID + "/d.mp3", "d.mp3", "audio", 400, 0},
		{"/" + libID + "/e.txt", "e.txt", "other", 500, 0},
		{"/" + libID + "/dir", "dir", "dir", 0, 1},
	}
	for _, r := range rows {
		if _, err := ix.db.Exec(`INSERT INTO files
			(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`,
			libID, r.vpath, "/"+libID, r.name, r.isDir, r.size, 1, r.kind); err != nil {
			t.Fatal(err)
		}
	}
	if err := ix.refreshCount(); err != nil {
		t.Fatal(err)
	}
}

// overviewRequest 构造一个带指定会话的 GET /api/overview 请求。
func overviewRequest(user, role string) *http.Request {
	r := httptest.NewRequest(http.MethodGet, "/api/overview", nil)
	if user != "" {
		r = r.WithContext(withUser(r.Context(), &Session{Username: user, Role: role}))
	}
	return r
}

// TestOverviewResponseFields 校验响应可序列化且字段齐全(空集合必须是数组而不是 null)。
func TestOverviewResponseFields(t *testing.T) {
	s, ix := newOverviewTestServer(t)
	seedOverviewIndex(t, ix, "lib1")

	rec := httptest.NewRecorder()
	s.handleOverview(rec, overviewRequest("admin", "admin"))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, 应为 200: %s", rec.Code, rec.Body.String())
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v (%s)", err, rec.Body.String())
	}
	for _, key := range []string{
		"version", "libs", "totals", "kinds", "index",
		"trash", "shares", "devices", "recent", "alerts",
	} {
		if _, ok := raw[key]; !ok {
			t.Errorf("响应缺少字段 %q: %s", key, rec.Body.String())
		}
	}
	if raw["version"] != Version {
		t.Errorf("version = %v, 应为 %q", raw["version"], Version)
	}
	// 空集合必须是空数组(不能是 null)。
	for _, key := range []string{"libs", "recent", "alerts"} {
		v, ok := raw[key].([]any)
		if !ok {
			t.Errorf("%s 应为数组,实际为 %#v", key, raw[key])
			continue
		}
		if v == nil {
			t.Errorf("%s 为 null,应为 []", key)
		}
	}
	// kinds 五个键齐全。
	kinds, _ := raw["kinds"].(map[string]any)
	for _, k := range []string{"video", "image", "audio", "other", "dir"} {
		if _, ok := kinds[k]; !ok {
			t.Errorf("kinds 缺少 %q: %#v", k, kinds)
		}
	}
	if kinds["video"] != float64(2) || kinds["image"] != float64(1) ||
		kinds["audio"] != float64(1) || kinds["other"] != float64(1) || kinds["dir"] != float64(1) {
		t.Errorf("kinds 统计不正确: %#v", kinds)
	}
	// 索引信息齐全。
	idx, _ := raw["index"].(map[string]any)
	if idx == nil {
		t.Fatalf("index 不应为 null: %s", rec.Body.String())
	}
	for _, k := range []string{"fileCount", "lastScan", "scanning", "refreshSeconds"} {
		if _, ok := idx[k]; !ok {
			t.Errorf("index 缺少字段 %q: %#v", k, idx)
		}
	}
	if idx["fileCount"] != float64(5) {
		t.Errorf("index.fileCount = %v, 应为 5(只算非目录文件)", idx["fileCount"])
	}
	if idx["refreshSeconds"] != float64(120) {
		t.Errorf("index.refreshSeconds = %v, 应为 120", idx["refreshSeconds"])
	}
	// 存储库与合计。
	libs, _ := raw["libs"].([]any)
	if len(libs) != 1 {
		t.Fatalf("libs 应有 1 个库,得到 %d: %s", len(libs), rec.Body.String())
	}
	lib0, _ := libs[0].(map[string]any)
	for _, k := range []string{"id", "name", "path", "freeMB", "totalMB", "files", "bytes"} {
		if _, ok := lib0[k]; !ok {
			t.Errorf("libs[0] 缺少字段 %q: %#v", k, lib0)
		}
	}
	if lib0["files"] != float64(5) {
		t.Errorf("libs[0].files = %v, 应为 5", lib0["files"])
	}
	if lib0["bytes"] != float64(1500) {
		t.Errorf("libs[0].bytes = %v, 应为 1500", lib0["bytes"])
	}
	totals, _ := raw["totals"].(map[string]any)
	for _, k := range []string{"freeMB", "totalMB", "files", "bytes", "usedPct"} {
		if _, ok := totals[k]; !ok {
			t.Errorf("totals 缺少字段 %q: %#v", k, totals)
		}
	}
	if totals["files"] != float64(5) || totals["bytes"] != float64(1500) {
		t.Errorf("totals 汇总不正确: %#v", totals)
	}
}

// TestOverviewWithoutIndexDegrades 索引不可用时只降级,接口仍是 200 且 index 为 null。
func TestOverviewWithoutIndexDegrades(t *testing.T) {
	s, _ := newOverviewTestServer(t)
	s.index = nil

	rec := httptest.NewRecorder()
	s.handleOverview(rec, overviewRequest("admin", "admin"))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, 应为 200: %s", rec.Code, rec.Body.String())
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if raw["index"] != nil {
		t.Errorf("索引不可用时 index 应为 null,实际 %#v", raw["index"])
	}
	alerts, _ := raw["alerts"].([]any)
	if len(alerts) != 1 {
		t.Fatalf("索引不可用时应有 1 条 info 告警,得到 %d", len(alerts))
	}
	a0, _ := alerts[0].(map[string]any)
	if a0["level"] != "info" || a0["text"] != "媒体索引未启用" {
		t.Errorf("告警内容不正确: %#v", a0)
	}
}

// TestOverviewRecentFiltersByUser 非管理员只能看到自己的审计记录。
func TestOverviewRecentFiltersByUser(t *testing.T) {
	s, _ := newOverviewTestServer(t)
	s.audit.Add(AuditEntry{Time: time.Now().Unix(), User: "alice", Action: "上传", Method: "POST", Path: "/api/upload/complete", Target: "/lib1/a.mp4", Status: 200})
	s.audit.Add(AuditEntry{Time: time.Now().Unix(), User: "bob", Action: "删除", Method: "POST", Path: "/api/delete", Target: "/lib1/b.mp4", Status: 200})
	s.audit.Add(AuditEntry{Time: time.Now().Unix(), User: "alice", Action: "改名", Method: "POST", Path: "/api/rename", Target: "/lib1/c.mp4", Status: 200})

	// 非管理员(bob):只剩自己的一条。
	rec := httptest.NewRecorder()
	s.handleOverview(rec, overviewRequest("bob", "user"))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, 应为 200", rec.Code)
	}
	var raw struct {
		Recent []overviewRecent `json:"recent"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if len(raw.Recent) != 1 {
		t.Fatalf("非管理员应只看到 1 条自己的记录,得到 %d: %#v", len(raw.Recent), raw.Recent)
	}
	if raw.Recent[0].User != "bob" || raw.Recent[0].Action != "删除" {
		t.Errorf("记录内容不正确: %#v", raw.Recent[0])
	}

	// 管理员:看到全部三条。
	rec = httptest.NewRecorder()
	s.handleOverview(rec, overviewRequest("admin", "admin"))
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if len(raw.Recent) != 3 {
		t.Fatalf("管理员应看到 3 条记录,得到 %d", len(raw.Recent))
	}
}

// TestFilterRecentForUser 纯函数:角色过滤与字段兜底。
func TestFilterRecentForUser(t *testing.T) {
	entries := []AuditEntry{
		{Time: 3, User: "alice", Action: "上传", Target: "/lib1/a.mp4", Status: 200},
		{Time: 2, User: "bob", Action: "", Method: "POST", Path: "/api/delete", Status: 500},
		{Time: 1, User: "bob", Action: "", Method: "", Path: "/api/ls", Status: 200},
	}
	// 管理员:全部保留,顺序与输入一致(Recent 已倒序)。
	all := filterRecentForUser(entries, "alice", true)
	if len(all) != 3 {
		t.Fatalf("管理员应看到全部 3 条,得到 %d", len(all))
	}
	if all[0].Time != 3 {
		t.Errorf("顺序应保持倒序,得到 %d", all[0].Time)
	}
	// action 兜底:method → "访问"。
	if all[1].Action != "POST" {
		t.Errorf("action 为空时应回退到 method,得到 %q", all[1].Action)
	}
	if all[2].Action != "访问" {
		t.Errorf("action 与 method 都为空时应回退到 \"访问\",得到 %q", all[2].Action)
	}
	// target 为空时回退到 path。
	if all[1].Target != "/api/delete" {
		t.Errorf("target 为空时应回退到 path,得到 %q", all[1].Target)
	}
	// 非管理员:只剩自己的。
	mine := filterRecentForUser(entries, "bob", false)
	if len(mine) != 2 {
		t.Fatalf("非管理员应只看到 2 条,得到 %d", len(mine))
	}
	for _, r := range mine {
		if r.User != "bob" {
			t.Errorf("非管理员不应看到他人记录: %#v", r)
		}
	}
	// 空结果也要是空数组(不是 nil)。
	if got := filterRecentForUser(nil, "bob", false); got == nil || len(got) != 0 {
		t.Errorf("无记录时应返回空数组,得到 %#v", got)
	}
}

// TestBuildOverviewAlertsSpace 纯函数:低可用空间给出 warn 告警。
func TestBuildOverviewAlertsSpace(t *testing.T) {
	now := time.Now()
	// 可用 5% → 不足 10%。
	alerts := buildOverviewAlerts([]overviewLib{
		{ID: "lib1", Name: "H盘", FreeMB: 50, TotalMB: 1000},
	}, nil, now, 120, 10, 3)
	if len(alerts) != 2 { // 空间告警 + 索引未启用
		t.Fatalf("应有 2 条告警(空间 + 索引未启用),得到 %d: %#v", len(alerts), alerts)
	}
	if alerts[0].Level != "warn" || alerts[0].Text != "「H盘」可用空间不足 10%" {
		t.Errorf("空间告警不正确: %#v", alerts[0])
	}
	// 可用 2% → 不足 3%(更严重)。
	alerts = buildOverviewAlerts([]overviewLib{
		{ID: "lib1", Name: "H盘", FreeMB: 20, TotalMB: 1000},
	}, nil, now, 120, 10, 3)
	if alerts[0].Level != "warn" || alerts[0].Text != "「H盘」可用空间不足 3%" {
		t.Errorf("严重空间告警不正确: %#v", alerts[0])
	}
	// 可用 50% → 无空间告警。
	alerts = buildOverviewAlerts([]overviewLib{
		{ID: "lib1", Name: "H盘", FreeMB: 500, TotalMB: 1000},
	}, &overviewIndexState{LastScan: now.Unix(), RefreshSeconds: 120}, now, 120, 10, 3)
	if len(alerts) != 0 {
		t.Errorf("空间充足且索引新鲜时不应有告警,得到 %#v", alerts)
	}
	if alerts == nil {
		t.Error("无告警时应返回空数组而不是 nil")
	}
}

// TestBuildOverviewAlertsIndex 纯函数:索引未启用 / 久未更新。
func TestBuildOverviewAlertsIndex(t *testing.T) {
	now := time.Now()
	// 索引未启用。
	alerts := buildOverviewAlerts(nil, nil, now, 120, 10, 3)
	if len(alerts) != 1 || alerts[0].Level != "info" || alerts[0].Text != "媒体索引未启用" {
		t.Fatalf("索引未启用告警不正确: %#v", alerts)
	}
	// 索引 2 小时未更新(refreshSeconds=120 → 阈值 max(10 分钟, 360 秒) = 10 分钟)。
	alerts = buildOverviewAlerts(nil, &overviewIndexState{
		LastScan: now.Add(-2 * time.Hour).Unix(), RefreshSeconds: 120,
	}, now, 120, 10, 3)
	if len(alerts) != 1 || alerts[0].Level != "info" {
		t.Fatalf("索引过期告警不正确: %#v", alerts)
	}
	if alerts[0].Text != "索引已 120 分钟未更新" {
		t.Errorf("索引过期文案不正确: %q", alerts[0].Text)
	}
	// 索引刚更新过 → 无告警。
	alerts = buildOverviewAlerts(nil, &overviewIndexState{
		LastScan: now.Add(-time.Minute).Unix(), RefreshSeconds: 120,
	}, now, 120, 10, 3)
	if len(alerts) != 0 {
		t.Errorf("索引新鲜时不应有告警,得到 %#v", alerts)
	}
	// refreshSeconds = 0(关闭对账)→ 不报过期。
	alerts = buildOverviewAlerts(nil, &overviewIndexState{
		LastScan: now.Add(-72 * time.Hour).Unix(), RefreshSeconds: 0,
	}, now, 0, 10, 3)
	if len(alerts) != 0 {
		t.Errorf("关闭自动对账时不应报索引过期,得到 %#v", alerts)
	}
	// 告警条数上限 5 条。
	many := make([]overviewLib, 0, 8)
	for i := 0; i < 8; i++ {
		many = append(many, overviewLib{ID: "lib", Name: "库", FreeMB: 1, TotalMB: 1000})
	}
	alerts = buildOverviewAlerts(many, nil, now, 120, 10, 3)
	if len(alerts) > overviewMaxAlerts {
		t.Errorf("告警条数应不超过 %d,得到 %d", overviewMaxAlerts, len(alerts))
	}
}
