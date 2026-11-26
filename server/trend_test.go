package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// seedTrendFile 往 files 表塞一行(供 Snapshot 聚合)。
func seedTrendFile(t *testing.T, ix *Index, libID, name string, size int64) {
	t.Helper()
	if _, err := ix.db.Exec(`INSERT INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,0,?,?,?)`,
		libID, "/"+libID+"/"+name, "/"+libID, name, size, 1, "other"); err != nil {
		t.Fatal(err)
	}
}

// seedTrendSnapshot 直接写一条快照(绕过「整点」限制,便于构造历史)。
func seedTrendSnapshot(t *testing.T, ix *Index, libID string, ts, bytes int64, files int) {
	t.Helper()
	if _, err := ix.db.Exec(`INSERT OR REPLACE INTO storage_snapshots
		(lib_id,ts,bytes,files) VALUES (?,?,?,?)`, libID, ts, bytes, files); err != nil {
		t.Fatal(err)
	}
}

// TestSnapshotWritesHourlyPointAndCleansOld 同一小时只保留一条,并清理 400 天前的数据。
func TestSnapshotWritesHourlyPointAndCleansOld(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	seedTrendFile(t, ix, "lib1", "a.mp4", 100)
	seedTrendFile(t, ix, "lib1", "b.mp4", 200)
	seedTrendFile(t, ix, "lib2", "c.jpg", 300)
	if err := ix.Snapshot(); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM storage_snapshots`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("首次快照应有 2 条(每库一条),得到 %d", n)
	}
	// 同一小时再快照一次:应覆盖而不是新增。
	if err := ix.Snapshot(); err != nil {
		t.Fatal(err)
	}
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM storage_snapshots`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("同一小时再次快照后应仍为 2 条,得到 %d", n)
	}
	// 聚合值:lib1 = 300 字节 / 2 个文件。
	var bytes int64
	var files int
	ts := time.Now().Truncate(time.Hour).Unix()
	if err := ix.db.QueryRow(`SELECT bytes, files FROM storage_snapshots WHERE lib_id = 'lib1' AND ts = ?`,
		ts).Scan(&bytes, &files); err != nil {
		t.Fatal(err)
	}
	if bytes != 300 || files != 2 {
		t.Errorf("lib1 快照聚合不正确: bytes=%d files=%d", bytes, files)
	}
	// 400 天前的快照应在下次快照时被清理。
	seedTrendSnapshot(t, ix, "lib1", time.Now().Add(-401*24*time.Hour).Unix(), 1, 1)
	if err := ix.Snapshot(); err != nil {
		t.Fatal(err)
	}
	var old int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM storage_snapshots WHERE ts < ?`,
		time.Now().Unix()-400*86400).Scan(&old); err != nil {
		t.Fatal(err)
	}
	if old != 0 {
		t.Errorf("400 天前的快照应被清理,还剩 %d 条", old)
	}
}

// TestTrendEmptyReturnsArrays 没有快照时返回空数组(不能是 null)。
func TestTrendEmptyReturnsArrays(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	tr, err := ix.Trend(30, "")
	if err != nil {
		t.Fatal(err)
	}
	if tr.Libs == nil || len(tr.Libs) != 0 {
		t.Errorf("无数据时 Libs 应为空数组,得到 %#v", tr.Libs)
	}
	if tr.Total.Points == nil || len(tr.Total.Points) != 0 {
		t.Errorf("无数据时 Total.Points 应为空数组,得到 %#v", tr.Total.Points)
	}

	s := &Server{cfg: &Config{}, index: ix}
	rec := httptest.NewRecorder()
	s.handleStorageTrend(rec, httptest.NewRequest(http.MethodGet, "/api/storage/trend", nil))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, 应为 200: %s", rec.Code, rec.Body.String())
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if raw["days"] != float64(30) {
		t.Errorf("days 缺省应为 30,得到 %v", raw["days"])
	}
	libs, ok := raw["libs"].([]any)
	if !ok || len(libs) != 0 {
		t.Errorf("libs 应为空数组,得到 %#v", raw["libs"])
	}
	total, _ := raw["total"].(map[string]any)
	points, ok := total["points"].([]any)
	if !ok || len(points) != 0 {
		t.Errorf("total.points 应为空数组,得到 %#v", total["points"])
	}
}

// TestTrendPointsDeltaAndWeek 采样点、Delta 与 weekDelta 的计算。
func TestTrendPointsDeltaAndWeek(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	now := time.Now().Truncate(time.Hour).Unix()
	p1 := now - 14*86400
	p2 := now - 7*86400
	p3 := now
	seedTrendSnapshot(t, ix, "lib1", p1, 1000, 1)
	seedTrendSnapshot(t, ix, "lib1", p2, 2000, 2)
	seedTrendSnapshot(t, ix, "lib1", p3, 3000, 3)
	seedTrendSnapshot(t, ix, "lib2", p3, 500, 1)

	s := &Server{
		cfg: &Config{Libraries: []Library{
			{ID: "lib1", Name: "H盘"},
			{ID: "lib2", Name: "I盘"},
		}},
		index: ix,
	}
	rec := httptest.NewRecorder()
	s.handleStorageTrend(rec, httptest.NewRequest(http.MethodGet, "/api/storage/trend?days=30", nil))
	if rec.Code != 200 {
		t.Fatalf("状态码 = %d, 应为 200: %s", rec.Code, rec.Body.String())
	}
	var resp trendResp
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if resp.Days != 30 {
		t.Errorf("days = %d, 应为 30", resp.Days)
	}
	if len(resp.Libs) != 2 {
		t.Fatalf("应有 2 个库的趋势,得到 %d: %s", len(resp.Libs), rec.Body.String())
	}
	// 库名按配置补全,并按配置顺序排列。
	if resp.Libs[0].ID != "lib1" || resp.Libs[0].Name != "H盘" {
		t.Errorf("libs[0] 应为 lib1/H盘,得到 %#v", resp.Libs[0])
	}
	if resp.Libs[1].ID != "lib2" || resp.Libs[1].Name != "I盘" {
		t.Errorf("libs[1] 应为 lib2/I盘,得到 %#v", resp.Libs[1])
	}
	// lib1 的三个点:Delta 首点 0,之后 1000 / 1000。
	p := resp.Libs[0].Points
	if len(p) != 3 {
		t.Fatalf("lib1 应有 3 个采样点,得到 %d", len(p))
	}
	if p[0].Delta != 0 || p[1].Delta != 1000 || p[2].Delta != 1000 {
		t.Errorf("lib1 Delta 不正确: %#v", p)
	}
	if p[0].T >= p[1].T || p[1].T >= p[2].T {
		t.Errorf("采样点应按 ts 升序: %#v", p)
	}
	// lib2 只有一个点,Delta 为 0。
	if len(resp.Libs[1].Points) != 1 || resp.Libs[1].Points[0].Delta != 0 {
		t.Errorf("lib2 单点 Delta 应为 0: %#v", resp.Libs[1].Points)
	}
	// 总序列:1000 / 2000 / 3500(同 ts 各库求和)。
	total := resp.Total.Points
	if len(total) != 3 {
		t.Fatalf("总序列应有 3 个点,得到 %d", len(total))
	}
	wantBytes := []int64{1000, 2000, 3500}
	wantDelta := []int64{0, 1000, 1500}
	for i := range total {
		if total[i].Bytes != wantBytes[i] || total[i].Delta != wantDelta[i] {
			t.Errorf("total.points[%d] = %#v, 期望 bytes=%d delta=%d", i, total[i], wantBytes[i], wantDelta[i])
		}
	}
	// 本周变化:3500 - 2000(7 天前的点) = 1500;百分比 75.0。
	if resp.Total.WeekDeltaBytes != 1500 {
		t.Errorf("weekDeltaBytes = %d, 应为 1500", resp.Total.WeekDeltaBytes)
	}
	if resp.Total.WeekDeltaPct != 75.0 {
		t.Errorf("weekDeltaPct = %v, 应为 75", resp.Total.WeekDeltaPct)
	}

	// lib 过滤:只看 lib2,总序列也只含该库。
	rec = httptest.NewRecorder()
	s.handleStorageTrend(rec, httptest.NewRequest(http.MethodGet, "/api/storage/trend?days=30&lib=lib2", nil))
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if len(resp.Libs) != 1 || resp.Libs[0].ID != "lib2" {
		t.Errorf("lib 过滤后应只剩 lib2: %#v", resp.Libs)
	}
	if len(resp.Total.Points) != 1 || resp.Total.Points[0].Bytes != 500 {
		t.Errorf("lib 过滤后总序列应只有 lib2 的点: %#v", resp.Total.Points)
	}
}

// TestTrendShortSpanUsesEarliest 不足 7 天时与最早一点比较。
func TestTrendShortSpanUsesEarliest(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	now := time.Now().Truncate(time.Hour).Unix()
	seedTrendSnapshot(t, ix, "lib1", now-3*86400, 100, 1)
	seedTrendSnapshot(t, ix, "lib1", now, 300, 2)

	tr, err := ix.Trend(30, "")
	if err != nil {
		t.Fatal(err)
	}
	if tr.Total.WeekDeltaBytes != 200 {
		t.Errorf("weekDeltaBytes = %d, 应为 200(与最早点比)", tr.Total.WeekDeltaBytes)
	}
	if tr.Total.WeekDeltaPct != 200.0 {
		t.Errorf("weekDeltaPct = %v, 应为 200", tr.Total.WeekDeltaPct)
	}
}

// TestTrendDaysClamp days 缺省 30,显式值 clamp 1..400。
func TestTrendDaysClamp(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	s := &Server{cfg: &Config{}, index: ix}

	cases := []struct {
		query string
		want  int
	}{
		{"", 30},
		{"?days=abc", 30},
		{"?days=0", 1},
		{"?days=7", 7},
		{"?days=999", 400},
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		s.handleStorageTrend(rec, httptest.NewRequest(http.MethodGet, "/api/storage/trend"+c.query, nil))
		var raw struct {
			Days int `json:"days"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
			t.Fatalf("query %q 响应不是合法 JSON: %v", c.query, err)
		}
		if raw.Days != c.want {
			t.Errorf("query %q days = %d, 应为 %d", c.query, raw.Days, c.want)
		}
	}
}
