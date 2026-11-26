package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestParseDiskHealthArray 数组形式:盘符/型号/健康状态/容量。
func TestParseDiskHealthArray(t *testing.T) {
	disks := []byte(`[
		{"DeviceId":"0","FriendlyName":"Samsung SSD 980 PRO 1TB","MediaType":4,"HealthStatus":0,"OperationalStatus":2,"Size":1000204886016},
		{"DeviceId":"1","FriendlyName":"WDC WD40EZAZ-00SF3B0","MediaType":3,"HealthStatus":1,"OperationalStatus":"Degraded","Size":4000787030016}
	]`)
	parts := []byte(`[
		{"DriveLetter":"C","DiskNumber":0},
		{"DriveLetter":"D","DiskNumber":1}
	]`)
	out, err := parseDiskHealth(disks, parts)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Fatalf("应解析出 2 块磁盘,得到 %d: %#v", len(out), out)
	}
	c, d := out[0], out[1]
	if c.Drive != "C:" || d.Drive != "D:" {
		t.Errorf("盘符不正确: %#v", out)
	}
	if c.Model != "Samsung SSD 980 PRO 1TB" || d.Model != "WDC WD40EZAZ-00SF3B0" {
		t.Errorf("型号不正确: %#v", out)
	}
	if c.MediaType != "SSD" || d.MediaType != "HDD" {
		t.Errorf("介质类型不正确: %#v", out)
	}
	if c.HealthStatus != "Healthy" || d.HealthStatus != "Warning" {
		t.Errorf("健康状态不正确: %#v", out)
	}
	if c.OperationalStatus != "OK" || d.OperationalStatus != "Degraded" {
		t.Errorf("运行状态不正确: %#v", out)
	}
	if c.SizeGB != 931 || d.SizeGB != 3726 {
		t.Errorf("容量(GB)不正确: C=%d D=%d", c.SizeGB, d.SizeGB)
	}
}

// TestParseDiskHealthSingleObject 单对象形式(PowerShell 只有一块盘时):
// 枚举可能已经是字符串、盘符可能是字符码、DiskNumber 可能是字符串。
func TestParseDiskHealthSingleObject(t *testing.T) {
	disks := []byte(`{"DeviceId":"0","FriendlyName":"Samsung SSD 980","MediaType":"SSD","HealthStatus":"Warning","OperationalStatus":["OK"],"Size":1000204886016}`)
	parts := []byte(`{"DriveLetter":67,"DiskNumber":"0"}`)
	out, err := parseDiskHealth(disks, parts)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 {
		t.Fatalf("应解析出 1 块磁盘,得到 %d: %#v", len(out), out)
	}
	e := out[0]
	if e.Drive != "C:" || e.Model != "Samsung SSD 980" {
		t.Errorf("盘符/型号不正确: %#v", e)
	}
	if e.MediaType != "SSD" || e.HealthStatus != "Warning" || e.OperationalStatus != "OK" {
		t.Errorf("状态字段不正确: %#v", e)
	}
	if e.SizeGB != 931 {
		t.Errorf("容量(GB) = %d, 应为 931", e.SizeGB)
	}
}

// TestParseDiskHealthEmptyAndInvalid 空分区/无匹配磁盘/非法 JSON 的处理。
func TestParseDiskHealthEmptyAndInvalid(t *testing.T) {
	disks := []byte(`[{"DeviceId":"0","FriendlyName":"Disk","MediaType":4,"HealthStatus":0,"OperationalStatus":2,"Size":1000}]`)
	// 没有分区数据 → 没有可展示的盘符条目,但不是错误。
	out, err := parseDiskHealth(disks, nil)
	if err != nil {
		t.Fatalf("空分区数据不应报错: %v", err)
	}
	if len(out) != 0 {
		t.Errorf("空分区数据应返回空列表,得到 %#v", out)
	}
	// 分区指向不存在的磁盘 → 跳过。
	out, err = parseDiskHealth(disks, []byte(`[{"DriveLetter":"E","DiskNumber":9}]`))
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 0 {
		t.Errorf("找不到物理磁盘的分区应跳过,得到 %#v", out)
	}
	// 非法 JSON → 返回错误。
	if _, err := parseDiskHealth([]byte(`{bad`), nil); err == nil {
		t.Error("非法物理磁盘 JSON 应返回错误")
	}
	if _, err := parseDiskHealth(disks, []byte(`[oops`)); err == nil {
		t.Error("非法分区 JSON 应返回错误")
	}
}

// TestDriveOfPath 覆盖盘符提取的各种路径形式。
func TestDriveOfPath(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{`E:\a\b`, "E:"},
		{`E:/a`, "E:"},
		{`e:\x`, "E:"},
		{`E:\`, "E:"},
		{`E:`, "E:"},
		{`  F:\media  `, "F:"},
		{`\\server\share`, ""}, // UNC 网络路径
		{`relative\path`, ""},  // 相对路径
		{`E:relative`, ""},     // 盘符后不是分隔符
		{`1:\x`, ""},           // 非字母盘符
		{``, ""},               // 空
		{`/unix/path`, ""},     // 类 Unix 绝对路径
	}
	for _, c := range cases {
		if got := driveOfPath(c.in); got != c.want {
			t.Errorf("driveOfPath(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestFilterDisksByDrive 覆盖命中/未命中/大小写。
func TestFilterDisksByDrive(t *testing.T) {
	disks := []diskHealthEntry{
		{Drive: "C:", Model: "sys"},
		{Drive: "E:", Model: "media"},
		{Drive: "F:", Model: "other"},
	}
	if got := filterDisksByDrive(disks, "E:"); len(got) != 1 || got[0].Model != "media" {
		t.Errorf("命中 E: 过滤不正确: %#v", got)
	}
	if got := filterDisksByDrive(disks, "e:"); len(got) != 1 || got[0].Model != "media" {
		t.Errorf("大小写不敏感过滤不正确: %#v", got)
	}
	if got := filterDisksByDrive(disks, "G:"); len(got) != 0 || got == nil {
		t.Errorf("未命中应返回空切片(非 nil),得到 %#v", got)
	}
	if got := filterDisksByDrive(disks, ""); len(got) != 0 || got == nil {
		t.Errorf("空盘符应返回空切片(非 nil),得到 %#v", got)
	}
	// 过滤结果不应修改原切片。
	if len(disks) != 3 {
		t.Errorf("原切片被修改: %#v", disks)
	}
}

// newDiskHealthTestCache 预填一个全量磁盘健康缓存(绕过 PowerShell)。
func newDiskHealthTestCache() *diskHealthCache {
	c := &diskHealthCache{}
	c.set(diskHealthResp{
		Supported: true,
		At:        1,
		Disks: []diskHealthEntry{
			{Drive: "C:", Model: "sys"},
			{Drive: "E:", Model: "media"},
			{Drive: "F:", Model: "other"},
		},
	})
	return c
}

// TestHandleDisksHealthLibFilter 覆盖 handler 层 ?lib= 过滤、404 与 note。
func TestHandleDisksHealthLibFilter(t *testing.T) {
	s := &Server{
		diskHealth: newDiskHealthTestCache(),
		cfg: &Config{Libraries: []Library{
			{ID: "libE", Name: "媒体", Path: `E:\media`, Enabled: true},
			{ID: "libUNC", Name: "网络", Path: `\\nas\share`, Enabled: true},
			{ID: "libG", Name: "无盘", Path: `G:\x`, Enabled: true},
		}},
	}

	// ?lib=libE → 只返回 E:
	rec := httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health?lib=libE", nil))
	if rec.Code != 200 {
		t.Fatalf("libE 应 200,得到 %d: %s", rec.Code, rec.Body.String())
	}
	var r1 diskHealthResp
	if err := json.Unmarshal(rec.Body.Bytes(), &r1); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if len(r1.Disks) != 1 || r1.Disks[0].Drive != "E:" {
		t.Fatalf("libE 应只返回 E:,得到 %#v", r1.Disks)
	}
	if r1.Note != "" {
		t.Errorf("libE 不应带 note: %q", r1.Note)
	}

	// ?lib=all → 全部 3 块
	rec = httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health?lib=all", nil))
	var rAll diskHealthResp
	_ = json.Unmarshal(rec.Body.Bytes(), &rAll)
	if len(rAll.Disks) != 3 {
		t.Errorf("all 应返回 3 块盘,得到 %d", len(rAll.Disks))
	}

	// 无 lib → 全部 3 块(向后兼容)
	rec = httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health", nil))
	var rNone diskHealthResp
	_ = json.Unmarshal(rec.Body.Bytes(), &rNone)
	if len(rNone.Disks) != 3 {
		t.Errorf("无 lib 应返回 3 块盘,得到 %d", len(rNone.Disks))
	}

	// 不存在的库 → 404 且错误信息为 library not found
	rec = httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health?lib=nope", nil))
	if rec.Code != 404 {
		t.Fatalf("不存在的库应 404,得到 %d: %s", rec.Code, rec.Body.String())
	}
	var errResp map[string]string
	_ = json.Unmarshal(rec.Body.Bytes(), &errResp)
	if errResp["error"] != "library not found" {
		t.Errorf("错误信息 = %q, want library not found", errResp["error"])
	}

	// UNC 路径 → 200 + 空 disks + note
	rec = httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health?lib=libUNC", nil))
	var rUNC diskHealthResp
	_ = json.Unmarshal(rec.Body.Bytes(), &rUNC)
	if rec.Code != 200 || len(rUNC.Disks) != 0 || rUNC.Disks == nil {
		t.Errorf("UNC 库应 200 且 disks 为空数组,得到 %d %#v", rec.Code, rUNC.Disks)
	}
	if rUNC.Note == "" {
		t.Errorf("UNC 库应带 note")
	}

	// 有盘符但不在物理磁盘列表 → 200 + 空 + note
	rec = httptest.NewRecorder()
	s.handleDisksHealth(rec, httptest.NewRequest(http.MethodGet, "/api/disks/health?lib=libG", nil))
	var rG diskHealthResp
	_ = json.Unmarshal(rec.Body.Bytes(), &rG)
	if rec.Code != 200 || len(rG.Disks) != 0 {
		t.Errorf("G 库应 200 且 disks 为空,得到 %d %#v", rec.Code, rG.Disks)
	}
	if rG.Note == "" {
		t.Errorf("G 库应带 note")
	}
}
