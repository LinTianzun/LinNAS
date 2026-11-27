package main

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

// 开机自启:在 Windows 的启动文件夹里放一个 VBS 脚本,登录时以隐藏窗口
// 的方式启动 linNas(托盘模式),不会弹出黑色控制台。
//
// 与旧的 安装开机自启.bat 使用同一个文件名,二者可互换。
//
// 启动脚本里写的是**绝对路径**,所以 exe 改名或搬家后脚本会继续指向旧文件,
// 重开机就跑到旧版本上。因此服务启动时会把指向别处的脚本改写为当前 exe
// (autostartHeal),`GET /api/autostart` 也会回报 target 与 stale。

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

// autostartScript 生成启动文件夹里的脚本内容。
//
// VBScript 里字符串内的双引号要写成两个双引号,所以源码是
//
//	sh.Run """C:\path\linNas-server.exe"" -tray", 0, False
//
// 运行时传给 CreateProcess 的才是 "C:\path\linNas-server.exe" -tray
func autostartScript(exe string) string {
	return "Set sh = CreateObject(\"WScript.Shell\")\r\n" +
		"sh.Run \"\"\"" + exe + "\"\" -tray\", 0, False\r\n"
}

// autostartRunRe 匹配脚本里 sh.Run """<exe>"" -tray" 中的 exe 路径
// (开引号三个、闭引号两个,是 VBScript 对命令行首个参数加引号的写法)。
var autostartRunRe = regexp.MustCompile(`sh\.Run\s+"""([^"]+)""`)

// autostartExeFromScript 从脚本内容里取出被启动的可执行文件路径。
// 无法识别时返回空串(例如脚本被手改成别的写法)。
func autostartExeFromScript(script string) string {
	m := autostartRunRe.FindStringSubmatch(script)
	if m == nil {
		return ""
	}
	return strings.TrimSpace(m[1])
}

// autostartTargetAt 读脚本文件并返回它指向的可执行文件;读不到返回空串。
func autostartTargetAt(p string) string {
	if p == "" {
		return ""
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return autostartExeFromScript(string(b))
}

// autostartTarget 返回启动脚本当前指向的可执行文件。
func autostartTarget() string {
	return autostartTargetAt(autostartPath())
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

// samePath 比较两个路径是否指向同一个文件(大小写不敏感,清理分隔符)。
func samePath(a, b string) bool {
	ca := filepath.Clean(strings.TrimSpace(a))
	cb := filepath.Clean(strings.TrimSpace(b))
	if ca == "." || cb == "." {
		return false
	}
	return strings.EqualFold(ca, cb)
}

// autostartStaleTarget 若已开启的自启动指向**别的**可执行文件(通常是升级前
// 的旧 exe),返回那个路径。
func autostartStaleTarget() (string, bool) {
	cur := autostartExe()
	tgt := autostartTarget()
	if cur == "" || tgt == "" || samePath(cur, tgt) {
		return "", false
	}
	return tgt, true
}

// autostartSetAt 按给定脚本路径写入(或删除)开机自启;便于单测注入临时目录。
func autostartSetAt(p, exe string, on bool) error {
	if p == "" {
		return os.ErrInvalid
	}
	if !on {
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	if exe == "" {
		return os.ErrNotExist
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	return os.WriteFile(p, []byte(autostartScript(exe)), 0o644)
}

// autostartSet 以当前 exe 开启/关闭开机自启。
func autostartSet(on bool) error {
	return autostartSetAt(autostartPath(), autostartExe(), on)
}

// autostartHeal 把指向旧 exe 的启动脚本改写为当前 exe,返回被替换的旧路径。
// 自启未开启、脚本缺失或已指向当前 exe 时不做任何事。
func autostartHeal() (string, error) {
	old, stale := autostartStaleTarget()
	if !stale {
		return "", nil
	}
	if err := autostartSet(true); err != nil {
		return old, err
	}
	return old, nil
}

// handleAutostartGet GET /api/autostart — 开机自启状态
func (s *Server) handleAutostartGet(w http.ResponseWriter, r *http.Request) {
	target := autostartTarget()
	_, stale := autostartStaleTarget()
	writeJSON(w, 200, map[string]any{
		"supported": runtime.GOOS == "windows",
		"enabled":   autostartEnabled(),
		"path":      autostartPath(),
		"exe":       autostartExe(),
		"target":    target, // 启动脚本当前指向的 exe
		"stale":     stale,  // true = 指向旧版本,下次开机会跑起旧 exe
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
	writeJSON(w, 200, map[string]any{
		"ok":      true,
		"enabled": autostartEnabled(),
		"target":  autostartTarget(),
	})
}
