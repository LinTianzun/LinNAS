package main

import "testing"

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
