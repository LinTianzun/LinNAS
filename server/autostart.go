package main

import (
	"net/http"
	"os"
	"path/filepath"
	"runtime"
)

// 开机自启:在 Windows 的启动文件夹里放一个 VBS 脚本,登录时以隐藏窗口
// 的方式启动 linNas(托盘模式),不会弹出黑色控制台。
//
// 与旧的 安装开机自启.bat 使用同一个文件名,二者可互换。

const autostartFileName = "linNas-autostart.vbs"

// autostartPath 返回启动文件夹里脚本的完整路径;非 Windows 返回空串。
func autostartPath() string {
	if runtime.GOOS != "windows" {
		return ""
	}
	appdata := os.Getenv("APPDATA")
	if appdata == "" {
		return ""
	}
	return filepath.Join(appdata, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", autostartFileName)
}

// autostartExe 返回当前可执行文件的绝对路径。
func autostartExe() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	if abs, absErr := filepath.Abs(exe); absErr == nil {
		return abs
	}
	return exe
}

// autostartEnabled 判断当前是否已设置开机自启。
func autostartEnabled() bool {
	p := autostartPath()
	if p == "" {
		return false
	}
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// autostartSet 开启/关闭开机自启。
func autostartSet(on bool) error {
	p := autostartPath()
	if p == "" {
		return os.ErrInvalid
	}
	if !on {
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	exe := autostartExe()
	if exe == "" {
		return os.ErrNotExist
	}
	// VBScript 里字符串内的双引号要写成两个双引号,所以源码是
	//   sh.Run """C:\path\linNas-server.exe"" -tray", 0, False
	// 运行时传给 CreateProcess 的才是 "C:\path\linNas-server.exe" -tray
	script := "Set sh = CreateObject(\"WScript.Shell\")\r\n" +
		"sh.Run \"\"\"" + exe + "\"\" -tray\", 0, False\r\n"
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	return os.WriteFile(p, []byte(script), 0o644)
}

// handleAutostartGet GET /api/autostart — 开机自启状态
func (s *Server) handleAutostartGet(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{
		"supported": runtime.GOOS == "windows",
		"enabled":   autostartEnabled(),
		"path":      autostartPath(),
		"exe":       autostartExe(),
		"tray":      trayRunning(),
	})
}

// handleAutostartSet POST /api/autostart {enable:true|false}
func (s *Server) handleAutostartSet(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enable *bool `json:"enable"`
	}
	if err := readJSON(r, &req); err != nil || req.Enable == nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if err := autostartSet(*req.Enable); err != nil {
		writeErr(w, 500, "设置开机自启失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "enabled": autostartEnabled()})
}
