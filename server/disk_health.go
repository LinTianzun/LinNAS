package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os/exec"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
//  磁盘健康:通过 PowerShell(Get-PhysicalDisk / Get-Partition)
//  读取物理磁盘型号、介质类型与健康状态;结果缓存 5 分钟。
//  非 Windows 或命令失败时返回 supported:false,不返回 500。
// ============================================================

// diskHealthEntry 是一块物理磁盘(按盘符输出)。
type diskHealthEntry struct {
	Drive             string `json:"drive"`             // "C:"
	Model             string `json:"model"`             // 型号,如 Samsung SSD 980
	MediaType         string `json:"mediaType"`         // SSD / HDD / Unspecified
	HealthStatus      string `json:"healthStatus"`      // Healthy / Warning / Unhealthy
	OperationalStatus string `json:"operationalStatus"` // OK / Degraded / ...
	SizeGB            int64  `json:"sizeGB"`            // 物理磁盘总容量(GB)
	TotalGB           int64  `json:"totalGB"`           // 该盘符对应卷的总容量(GB),来自 Win32_LogicalDisk
	FreeGB            int64  `json:"freeGB"`            // 该卷的可用容量(GB)
}

// diskHealthResp 是 GET /api/disks/health 的响应。
// Note 用于「按存储库过滤」时该库所在磁盘不在物理磁盘列表内的提示。
type diskHealthResp struct {
	Supported bool              `json:"supported"`
	Reason    string            `json:"reason,omitempty"`
	Note      string            `json:"note,omitempty"`
	At        int64             `json:"at"`
	Disks     []diskHealthEntry `json:"disks"`
}

// diskHealthTTL 是磁盘健康结果的缓存时长。
const diskHealthTTL = 5 * time.Minute

// diskHealthCmdTimeout 是单条 PowerShell 命令的超时。
const diskHealthCmdTimeout = 20 * time.Second

// PowerShell 采集脚本(与契约一致);前缀把输出编码固定为 UTF-8,避免中文型号乱码。
const (
	psDiskHealthScript = `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ` +
		`Get-PhysicalDisk | Select-Object DeviceId,FriendlyName,MediaType,HealthStatus,OperationalStatus,Size | ConvertTo-Json -Compress`
	psDiskPartitionScript = `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ` +
		`Get-Partition | Where-Object { $_.DriveLetter } | Select-Object DriveLetter,DiskNumber | ConvertTo-Json -Compress`
	// 按盘符读取卷的总容量与可用容量(逻辑卷,而非物理磁盘),用于进度条与用量展示。
	psDiskVolumeScript = `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ` +
		`Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | Select-Object DeviceID,Size,FreeSpace | ConvertTo-Json -Compress`
)

// diskHealthCache 缓存最近一次采集结果(受 mu 保护,后台采集、HTTP 读)。
type diskHealthCache struct {
	mu     sync.Mutex
	at     time.Time
	resp   diskHealthResp
	filled bool
}

// get 返回缓存结果;超过 5 分钟或尚未采集时 ok=false。
func (c *diskHealthCache) get() (diskHealthResp, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.filled && time.Since(c.at) < diskHealthTTL {
		return c.resp, true
	}
	return diskHealthResp{}, false
}

// set 写入最新采集结果。
func (c *diskHealthCache) set(resp diskHealthResp) {
	c.mu.Lock()
	c.at = time.Now()
	c.resp = resp
	c.filled = true
	c.mu.Unlock()
}

// peek 返回当前缓存磁盘列表的副本;不会触发采集(供总览页读取)。
func (c *diskHealthCache) peek() []diskHealthEntry {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.filled || len(c.resp.Disks) == 0 {
		return nil
	}
	out := make([]diskHealthEntry, len(c.resp.Disks))
	copy(out, c.resp.Disks)
	return out
}

// handleDisksHealth GET /api/disks/health?lib=<libs|all|库ID> — 物理磁盘健康状态(缓存 5 分钟)。
// lib 语义:
//   - 不传 / lib=libs(默认):只返回承载所有已添加存储库的磁盘(各库路径盘符并集)。
//   - lib=all:返回全部物理磁盘。
//   - lib=<库ID>:只返回承载该库路径的磁盘(在缓存副本上过滤)。
//   - lib=<不存在>:404 library not found。
func (s *Server) handleDisksHealth(w http.ResponseWriter, r *http.Request) {
	var resp diskHealthResp
	if c := s.diskHealth; c == nil {
		// 没有缓存容器(例如测试里手工构造 Server):直接采集一次,不缓存。
		resp = collectDiskHealth()
	} else if cached, ok := c.get(); ok {
		resp = cached
	} else {
		resp = collectDiskHealth()
		c.set(resp)
	}

	libID := strings.TrimSpace(r.URL.Query().Get("lib"))
	switch {
	case libID == "" || strings.EqualFold(libID, "libs"):
		// 默认:各已添加存储库的盘符并集。
		var libs []Library
		if s.cfg != nil {
			libs = s.cfg.Libraries
		}
		drives := drivesOfLibraries(libs)
		if len(drives) == 0 {
			resp.Disks = []diskHealthEntry{}
			resp.Note = "没有承载已添加存储库的磁盘"
			break
		}
		filtered := filterDisksByDrives(resp.Disks, drives)
		if len(filtered) == 0 {
			resp.Disks = []diskHealthEntry{}
			resp.Note = "已添加存储库所在磁盘不在本机物理磁盘列表内"
		} else {
			resp.Disks = filtered
		}
	case strings.EqualFold(libID, "all"):
		// 全部物理磁盘:保持原行为。
	default:
		var lib *Library
		if s.cfg != nil {
			lib = s.cfg.LibraryByID(libID)
		}
		if lib == nil {
			writeJSON(w, 404, map[string]string{"error": "library not found"})
			return
		}
		drive := driveOfPath(lib.Path)
		filtered := filterDisksByDrive(resp.Disks, drive)
		if drive == "" || len(filtered) == 0 {
			filtered = []diskHealthEntry{}
			resp.Note = "该存储库所在磁盘不在本机物理磁盘列表内"
		}
		resp.Disks = filtered
	}
	writeJSON(w, 200, resp)
}

// drivesOfLibraries 返回一组存储库路径涉及的盘符并集(去重、升序)。
// 非盘符路径(UNC/相对路径等)被忽略;没有任何盘符时返回空切片(非 nil)。
func drivesOfLibraries(libs []Library) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, l := range libs {
		d := driveOfPath(l.Path)
		if d == "" || seen[d] {
			continue
		}
		seen[d] = true
		out = append(out, d)
	}
	sort.Strings(out)
	return out
}

// filterDisksByDrives 按一组盘符过滤磁盘列表(大小写不敏感),结果按 Drive 升序。
func filterDisksByDrives(disks []diskHealthEntry, drives []string) []diskHealthEntry {
	out := []diskHealthEntry{}
	if len(drives) == 0 {
		return out
	}
	want := make(map[string]bool, len(drives))
	for _, d := range drives {
		d = strings.ToUpper(strings.TrimSpace(d))
		if d != "" {
			want[d] = true
		}
	}
	for _, disk := range disks {
		if want[strings.ToUpper(strings.TrimSpace(disk.Drive))] {
			out = append(out, disk)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Drive < out[j].Drive })
	return out
}

// driveOfPath 返回 Windows 路径的盘符(如 "E:");非盘符路径(UNC/相对路径等)返回 ""。
// 例:E:\a\b → "E:";E:/a → "E:";\\server\share → "";relative\path → ""。
func driveOfPath(p string) string {
	p = strings.TrimSpace(p)
	if len(p) < 2 {
		return ""
	}
	c := p[0]
	if !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
		return ""
	}
	if p[1] != ':' {
		return ""
	}
	// 盘符之后必须是路径分隔符或字符串结束(如 "E:\"、"E:/"、"E:")。
	if len(p) > 2 && p[2] != '\\' && p[2] != '/' {
		return ""
	}
	return strings.ToUpper(string(c)) + ":"
}

// filterDisksByDrive 在全量磁盘列表里按盘符过滤(大小写不敏感)。
// drive 为空或没有匹配时返回空切片(非 nil),便于直接 JSON 序列化为 []。
func filterDisksByDrive(disks []diskHealthEntry, drive string) []diskHealthEntry {
	out := []diskHealthEntry{}
	drive = strings.TrimSpace(drive)
	if drive == "" {
		return out
	}
	for _, d := range disks {
		if strings.EqualFold(strings.TrimSpace(d.Drive), drive) {
			out = append(out, d)
		}
	}
	return out
}

// collectDiskHealth 调用 PowerShell 采集并按盘符组装结果;任何失败都降级为 supported:false。
func collectDiskHealth() diskHealthResp {
	resp := diskHealthResp{At: time.Now().Unix(), Disks: []diskHealthEntry{}}
	if runtime.GOOS != "windows" {
		resp.Reason = "仅 Windows 支持读取物理磁盘健康状态"
		return resp
	}
	disksJSON, err := runPowershellJSON(psDiskHealthScript)
	if err != nil {
		resp.Reason = "读取物理磁盘失败: " + err.Error()
		return resp
	}
	partsJSON, err := runPowershellJSON(psDiskPartitionScript)
	if err != nil {
		resp.Reason = "读取分区信息失败: " + err.Error()
		return resp
	}
	entries, err := parseDiskHealth(disksJSON, partsJSON)
	if err != nil {
		resp.Reason = "解析磁盘信息失败: " + err.Error()
		return resp
	}
	if len(entries) == 0 {
		resp.Reason = "未读取到带盘符的物理磁盘"
		return resp
	}
	// 卷容量(总量/可用)用于进度条;读取失败不影响磁盘健康本身,只是容量留空。
	if volsJSON, verr := runPowershellJSON(psDiskVolumeScript); verr == nil {
		entries = attachVolumes(entries, volsJSON)
	}
	resp.Supported = true
	resp.Disks = entries
	return resp
}

// runPowershellJSON 执行一条 PowerShell 命令并返回原始 stdout(带超时)。
func runPowershellJSON(script string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), diskHealthCmdTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
	raw, err := cmd.Output()
	if ctx.Err() == context.DeadlineExceeded {
		return nil, fmt.Errorf("PowerShell 命令超时")
	}
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok && len(ee.Stderr) > 0 {
			return nil, fmt.Errorf("%v: %s", err, strings.TrimSpace(string(ee.Stderr)))
		}
		return nil, err
	}
	return raw, nil
}

// psPhysicalDisk 是 Get-PhysicalDisk 序列化出来的一行(字段用 RawMessage,兼容
// PowerShell 5.1 / 7 对枚举、字符的不同序列化形式)。
type psPhysicalDisk struct {
	DeviceID          json.RawMessage `json:"DeviceId"`
	FriendlyName      json.RawMessage `json:"FriendlyName"`
	MediaType         json.RawMessage `json:"MediaType"`
	HealthStatus      json.RawMessage `json:"HealthStatus"`
	OperationalStatus json.RawMessage `json:"OperationalStatus"`
	Size              json.RawMessage `json:"Size"`
}

// psPartition 是 Get-Partition 序列化出来的一行。
type psPartition struct {
	DriveLetter json.RawMessage `json:"DriveLetter"`
	DiskNumber  json.RawMessage `json:"DiskNumber"`
}

// parseDiskHealth 解析两份 PowerShell JSON(单对象与数组都支持),把
// DiskNumber ↔ DriveLetter 关联后按盘符输出。供单测用固定样本覆盖。
func parseDiskHealth(disksJSON, partsJSON []byte) ([]diskHealthEntry, error) {
	disks, err := decodeJSONList[psPhysicalDisk](disksJSON)
	if err != nil {
		return nil, fmt.Errorf("解析物理磁盘 JSON 失败: %w", err)
	}
	parts, err := decodeJSONList[psPartition](partsJSON)
	if err != nil {
		return nil, fmt.Errorf("解析分区 JSON 失败: %w", err)
	}

	type diskInfo struct {
		model   string
		media   string
		health  string
		operate string
		sizeGB  int64
	}
	byNumber := make(map[int]diskInfo, len(disks))
	for _, d := range disks {
		byNumber[psInt(d.DeviceID)] = diskInfo{
			model:   psString(d.FriendlyName),
			media:   normalizePSEnum(psEnumName(d.MediaType, diskMediaTypeNames), "Unspecified"),
			health:  normalizePSEnum(psEnumName(d.HealthStatus, diskHealthNames), "Unknown"),
			operate: normalizePSEnum(psEnumName(d.OperationalStatus, diskOperationalNames), "Unknown"),
			sizeGB:  psInt64(d.Size) / (1 << 30),
		}
	}

	out := []diskHealthEntry{}
	seen := map[string]bool{}
	for _, p := range parts {
		letter := psDriveLetter(p.DriveLetter)
		if letter == "" {
			continue
		}
		drive := letter + ":"
		if seen[drive] {
			continue
		}
		seen[drive] = true
		info, ok := byNumber[psInt(p.DiskNumber)]
		if !ok {
			continue // 找不到对应物理磁盘就跳过
		}
		out = append(out, diskHealthEntry{
			Drive:             drive,
			Model:             info.model,
			MediaType:         info.media,
			HealthStatus:      info.health,
			OperationalStatus: info.operate,
			SizeGB:            info.sizeGB,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Drive < out[j].Drive })
	return out, nil
}

// psVolume 是 Get-CimInstance Win32_LogicalDisk 序列化出来的一行(按盘符给出卷容量)。
type psVolume struct {
	DeviceID  json.RawMessage `json:"DeviceID"`
	Size      json.RawMessage `json:"Size"`
	FreeSpace json.RawMessage `json:"FreeSpace"`
}

// attachVolumes 把卷的总容量/可用容量(GB)按盘符补进磁盘列表(大小写不敏感)。
// 找不到对应卷或字段缺失时保持 0,UI 会显示为「—」。返回新的切片。
func attachVolumes(entries []diskHealthEntry, volsJSON []byte) []diskHealthEntry {
	vols, err := decodeJSONList[psVolume](volsJSON)
	if err != nil || len(vols) == 0 {
		return entries
	}
	type volInfo struct{ totalGB, freeGB int64 }
	byDrive := make(map[string]volInfo, len(vols))
	for _, v := range vols {
		letter := psDriveLetter(v.DeviceID)
		if letter == "" {
			continue
		}
		byDrive[letter+":"] = volInfo{
			totalGB: psInt64(v.Size) / (1 << 30),
			freeGB:  psInt64(v.FreeSpace) / (1 << 30),
		}
	}
	out := make([]diskHealthEntry, len(entries))
	copy(out, entries)
	for i := range out {
		if v, ok := byDrive[strings.ToUpper(strings.TrimSpace(out[i].Drive))]; ok {
			out[i].TotalGB = v.totalGB
			out[i].FreeGB = v.freeGB
		}
	}
	return out
}

// decodeJSONList 兼容 PowerShell 的两种输出:单个对象或对象数组;空输出视为空列表。
func decodeJSONList[T any](raw []byte) ([]T, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return nil, nil
	}
	var arr []T
	if err := json.Unmarshal(raw, &arr); err == nil {
		return arr, nil
	}
	var one T
	if err := json.Unmarshal(raw, &one); err != nil {
		return nil, err
	}
	return []T{one}, nil
}

// psString 把 PowerShell 字段归一化为字符串:兼容字符串、数字与字符串数组
// (如 OperationalStatus 可能序列化为 ["OK"])。
func psString(raw json.RawMessage) string {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		return ""
	}
	if strings.HasPrefix(s, "[") {
		var arr []json.RawMessage
		if json.Unmarshal(raw, &arr) != nil {
			return ""
		}
		parts := make([]string, 0, len(arr))
		for _, item := range arr {
			if v := psString(item); v != "" {
				parts = append(parts, v)
			}
		}
		return strings.Join(parts, ", ")
	}
	var str string
	if json.Unmarshal(raw, &str) == nil {
		return strings.TrimSpace(str)
	}
	var num json.Number
	if json.Unmarshal(raw, &num) == nil {
		return num.String()
	}
	return ""
}

// psInt 把 PowerShell 字段归一化为整数;失败返回 -1。
func psInt(raw json.RawMessage) int {
	n, err := strconv.Atoi(strings.TrimSpace(psString(raw)))
	if err != nil {
		return -1
	}
	return n
}

// psInt64 把 PowerShell 字段归一化为 int64;失败返回 0。
func psInt64(raw json.RawMessage) int64 {
	f, err := strconv.ParseFloat(strings.TrimSpace(psString(raw)), 64)
	if err != nil {
		return 0
	}
	return int64(f)
}

// psDriveLetter 归一化盘符:兼容 "C"、数字字符码 67、带冒号的 "C:"。
func psDriveLetter(raw json.RawMessage) string {
	s := psString(raw)
	if s == "" {
		return ""
	}
	if n, err := strconv.Atoi(s); err == nil {
		if n <= 0 || n > 0x10FFFF {
			return ""
		}
		s = string(rune(n))
	}
	s = strings.ToUpper(strings.TrimSpace(s))
	s = strings.TrimSuffix(s, ":")
	if len(s) != 1 || s[0] < 'A' || s[0] > 'Z' {
		return ""
	}
	return s
}

// psEnumName 把 PowerShell 枚举字段归一化为名称:数字按映射表翻译,字符串原样返回。
func psEnumName(raw json.RawMessage, names map[int]string) string {
	s := psString(raw)
	if s == "" {
		return ""
	}
	if n, err := strconv.Atoi(strings.TrimSpace(s)); err == nil {
		if v, ok := names[n]; ok {
			return v
		}
		return "Unknown"
	}
	return s
}

// normalizePSEnum 为空字段补默认名称。
func normalizePSEnum(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

// 各枚举的数值 → 名称映射(取自 MSFT_PhysicalDisk)。
var diskMediaTypeNames = map[int]string{
	0: "Unspecified",
	3: "HDD",
	4: "SSD",
	5: "SCM",
}

var diskHealthNames = map[int]string{
	0: "Healthy",
	1: "Warning",
	2: "Unhealthy",
}

var diskOperationalNames = map[int]string{
	0:  "Unknown",
	1:  "Other",
	2:  "OK",
	3:  "Degraded",
	4:  "Stressed",
	5:  "PredictiveFailure",
	6:  "Error",
	7:  "NonRecoverableError",
	8:  "Starting",
	9:  "Stopping",
	10: "Stopped",
	11: "InService",
	12: "NoContact",
	13: "LostCommunication",
	14: "Aborted",
	15: "Dormant",
	16: "SupportingEntityInError",
	17: "Completed",
	18: "PowerMode",
}
