package main

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// ============================================================
//  Android 客户端在线更新(v1.8.0):
//    GET /api/app/version?token=...  → {versionName,versionCode,url,notes}
//    GET /api/app/apk?file=xxx.apk   → 下载 APK 文件
//  版本信息来源:release/android-version.json;缺失时回退为扫描
//  release 目录下的 linNas-*.apk 文件名(versionCode 记为 0,由客户端按名称比较)。
// ============================================================

// releaseDir 依次尝试 LINNAS_RELEASE_DIR、可执行文件同级 release、当前目录 release
// 与上级目录 release,返回第一个存在的目录;都不存在返回空串。
func releaseDir() string {
	if d := strings.TrimSpace(os.Getenv("LINNAS_RELEASE_DIR")); d != "" {
		if st, err := os.Stat(d); err == nil && st.IsDir() {
			return d
		}
	}
	var candidates []string
	if exe, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(exe), "release"))
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates,
			filepath.Join(wd, "release"),
			filepath.Join(wd, "..", "release"),
		)
	}
	for _, c := range candidates {
		if st, err := os.Stat(c); err == nil && st.IsDir() {
			return c
		}
	}
	return ""
}

type appVersionInfo struct {
	VersionName string `json:"versionName"`
	VersionCode int64  `json:"versionCode"`
	File        string `json:"file"`
	Notes       string `json:"notes"`
}

// readAppVersion 读取 release/android-version.json,失败时回退为扫描 APK 文件名。
func readAppVersion(dir string) (appVersionInfo, bool) {
	if dir == "" {
		return appVersionInfo{}, false
	}
	if raw, err := os.ReadFile(filepath.Join(dir, "android-version.json")); err == nil {
		var v appVersionInfo
		if json.Unmarshal(raw, &v) == nil && strings.TrimSpace(v.VersionName) != "" {
			if v.File != "" {
				if _, serr := os.Stat(filepath.Join(dir, v.File)); serr != nil {
					v.File = ""
				}
			}
			if v.File == "" {
				v.File = findAPK(dir)
			}
			return v, true
		}
	}
	// 回退:扫描 linNas-<版本>-*.apk,取版本号最大者。
	name := findAPK(dir)
	if name == "" {
		return appVersionInfo{}, false
	}
	ver := versionFromAPKName(name)
	return appVersionInfo{VersionName: ver, VersionCode: 0, File: name}, true
}

// findAPK 返回目录内版本号最大的 linNas-*.apk(按文件名版本段排序)。
func findAPK(dir string) string {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	var names []string
	for _, e := range ents {
		n := e.Name()
		low := strings.ToLower(n)
		if !e.IsDir() && strings.HasPrefix(low, "linnas-") && strings.HasSuffix(low, ".apk") {
			names = append(names, n)
		}
	}
	if len(names) == 0 {
		return ""
	}
	sort.Slice(names, func(i, j int) bool {
		return compareVersion(versionFromAPKName(names[i]), versionFromAPKName(names[j])) > 0
	})
	return names[0]
}

// versionFromAPKName 从 linNas-1.8.0-debug.apk 提取 "1.8.0"。
func versionFromAPKName(name string) string {
	base := strings.TrimSuffix(name, filepath.Ext(name))
	parts := strings.Split(base, "-")
	if len(parts) >= 2 {
		return parts[1]
	}
	return ""
}

// compareVersion 比较形如 1.2.3 的版本号,返回 -1/0/1。
func compareVersion(a, b string) int {
	as := strings.Split(a, ".")
	bs := strings.Split(b, ".")
	n := len(as)
	if len(bs) > n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		var x, y int
		if i < len(as) {
			x = atoiSafe(as[i])
		}
		if i < len(bs) {
			y = atoiSafe(bs[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

// handleAppVersion 返回 Android 客户端最新版本信息。
func (s *Server) handleAppVersion(w http.ResponseWriter, r *http.Request) {
	dir := releaseDir()
	info, ok := readAppVersion(dir)
	if !ok {
		writeErr(w, 404, "服务端未配置 Android 版本信息(缺少 release/android-version.json 或 APK)")
		return
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	link := scheme + "://" + r.Host + "/api/app/apk?file=" + url.QueryEscape(info.File)
	// 让浏览器/下载器也能通过鉴权:沿用请求里的 token(如有)。
	if tk := r.URL.Query().Get("token"); tk != "" {
		link += "&token=" + url.QueryEscape(tk)
	}
	writeJSON(w, 200, map[string]any{
		"versionName": info.VersionName,
		"versionCode": info.VersionCode,
		"url":         link,
		"notes":       info.Notes,
	})
}

// handleAppApk 下载 release 目录中的 APK,禁止路径穿越。
func (s *Server) handleAppApk(w http.ResponseWriter, r *http.Request) {
	dir := releaseDir()
	if dir == "" {
		writeErr(w, 404, "未找到 release 目录")
		return
	}
	name := strings.TrimSpace(r.URL.Query().Get("file"))
	if name == "" {
		name = findAPK(dir)
	}
	name = filepath.Base(name)
	if !strings.HasSuffix(strings.ToLower(name), ".apk") {
		writeErr(w, 400, "只允许下载 APK 文件")
		return
	}
	full := filepath.Join(dir, name)
	f, err := os.Open(full)
	if err != nil {
		writeErr(w, 404, "APK 不存在: "+name)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeErr(w, 500, "读取 APK 失败")
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(name))
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, name, st.ModTime(), f)
}
