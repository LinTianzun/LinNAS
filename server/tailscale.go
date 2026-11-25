package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// 远程访问:Tailscale 接入。
//
// linNas 自己不做组网,只是把本机 Tailscale 的状态读出来(调用官方 CLI),
// 在界面上告诉你该用哪个地址从外面访问,并可选地用 `tailscale serve`
// 把服务发布成本 tailnet 内的 HTTPS 站点。

// tailscaleInfo 是给前端用的 Tailscale 状态。
type tailscaleInfo struct {
	Installed   bool     `json:"installed"`
	Exe         string   `json:"exe"`
	State       string   `json:"state"` // not-installed | stopped | needs-login | starting | running | error
	Ready       bool     `json:"ready"`
	Version     string   `json:"version"`
	Host        string   `json:"host"`
	DNSName     string   `json:"dnsName"`
	TailnetName string   `json:"tailnetName"`
	IPs         []string `json:"ips"`
	URL         string   `json:"url"`     // http://<tailscale-ip>:port/ui/
	DNSURL      string   `json:"dnsUrl"`  // http://<magicdns-name>:port/ui/
	ServeOn     bool     `json:"serveOn"`
	ServeURL    string   `json:"serveUrl"` // https://<magicdns-name>/
	Hint        string   `json:"hint"`
	Error       string   `json:"error"`
}

// remoteInfo 是「远程访问」页面的全部数据。
type remoteInfo struct {
	Port       int           `json:"port"`
	PrimaryURL string        `json:"primaryUrl"`
	LANURLs    []string      `json:"lanUrls"`
	Tray       bool          `json:"tray"`
	Autostart  bool          `json:"autostart"`
	Tailscale  tailscaleInfo `json:"tailscale"`
}

// tsJSON 只取我们需要的字段,其余忽略。
type tsJSON struct {
	Version        string   `json:"Version"`
	BackendState   string   `json:"BackendState"`
	TailscaleIPs   []string `json:"TailscaleIPs"`
	MagicDNSSuffix string   `json:"MagicDNSSuffix"`
	Self           struct {
		HostName     string   `json:"HostName"`
		DNSName      string   `json:"DNSName"`
		TailscaleIPs []string `json:"TailscaleIPs"`
	} `json:"Self"`
	CurrentTailnet struct {
		Name           string `json:"Name"`
		MagicDNSSuffix string `json:"MagicDNSSuffix"`
	} `json:"CurrentTailnet"`
}

// knownTailscalePaths 是 Windows 上 Tailscale 的常见安装位置。
func knownTailscalePaths() []string {
	var out []string
	if la := os.Getenv("LOCALAPPDATA"); la != "" {
		out = append(out, filepath.Join(la, "Tailscale", "tailscale.exe"))
	}
	out = append(out,
		`C:\Program Files\Tailscale\tailscale.exe`,
		`C:\Program Files (x86)\Tailscale\tailscale.exe`,
	)
	return out
}

// findTailscale 定位 tailscale 命令行;找不到返回空串。
func findTailscale() string {
	if p, err := exec.LookPath("tailscale"); err == nil {
		return p
	}
	for _, p := range knownTailscalePaths() {
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			return p
		}
	}
	return ""
}

// runTailscale 执行 tailscale 命令,超时返回错误。
func runTailscale(timeout time.Duration, args ...string) (string, error) {
	exe := findTailscale()
	if exe == "" {
		return "", os.ErrNotExist
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, exe, args...)
	cmd.SysProcAttr = hiddenProcAttr()
	out, err := cmd.CombinedOutput()
	text := strings.TrimSpace(string(out))
	if ctx.Err() == context.DeadlineExceeded {
		return text, context.DeadlineExceeded
	}
	return text, err
}

// tailscaleState 缓存,避免每次刷新都去跑 CLI。
var (
	tsCacheMu   sync.Mutex
	tsCacheAt   time.Time
	tsCacheInfo tailscaleInfo
)

const tsCacheTTL = 8 * time.Second

// detectTailscale 读取本机 Tailscale 状态(带短缓存)。
func detectTailscale(port int, force bool) tailscaleInfo {
	tsCacheMu.Lock()
	defer tsCacheMu.Unlock()
	if !force && time.Since(tsCacheAt) < tsCacheTTL {
		info := tsCacheInfo
		info.URL = joinURL("http://", firstIPv4(info.IPs), port, "/ui/")
		info.DNSURL = joinURL("http://", info.DNSName, port, "/ui/")
		return info
	}
	info := probeTailscale(port)
	tsCacheInfo = info
	tsCacheAt = time.Now()
	return info
}

func joinURL(scheme, host string, port int, path string) string {
	if host == "" {
		return ""
	}
	if port <= 0 {
		return scheme + host + path
	}
	return scheme + host + ":" + itoa(port) + path
}

func firstIPv4(ips []string) string {
	for _, ip := range ips {
		if strings.Count(ip, ".") == 3 && !strings.Contains(ip, ":") {
			return ip
		}
	}
	return ""
}

// probeTailscale 真正去调用 tailscale CLI。
func probeTailscale(port int) tailscaleInfo {
	info := tailscaleInfo{State: "not-installed"}
	exe := findTailscale()
	if exe == "" {
		info.Hint = "本机还没有安装 Tailscale。安装并登录后,就能在家里以外的任何网络访问 linNas。"
		return info
	}
	info.Installed = true
	info.Exe = exe

	out, err := runTailscale(8*time.Second, "status", "--json")
	var st tsJSON
	parseErr := json.Unmarshal([]byte(out), &st)
	if parseErr != nil {
		// CLI 存在但守护进程没起来时,输出通常不是 JSON
		info.State = "stopped"
		info.Error = strings.TrimSpace(firstLine(out))
		if err != nil && info.Error == "" {
			info.Error = err.Error()
		}
		info.Hint = "Tailscale 已安装但没有运行。打开「开始菜单 → Tailscale」登录并开启连接。"
		return info
	}

	info.Version = st.Version
	info.Host = st.Self.HostName
	info.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
	info.TailnetName = st.CurrentTailnet.Name
	if len(st.Self.TailscaleIPs) > 0 {
		info.IPs = st.Self.TailscaleIPs
	} else if len(st.TailscaleIPs) > 0 {
		info.IPs = st.TailscaleIPs
	}

	switch st.BackendState {
	case "Running":
		info.State = "running"
		info.Ready = true
		info.Hint = "已连接 Tailscale,可以直接用下面的地址在外网访问。"
	case "Starting":
		info.State = "starting"
		info.Hint = "Tailscale 正在连接,稍等几秒再刷新。"
	case "NeedsLogin", "NeedsMachineAuth":
		info.State = "needs-login"
		info.Hint = "Tailscale 已安装但还没登录。打开「开始菜单 → Tailscale」用你的账号登录。"
	case "Stopped", "NoState":
		info.State = "stopped"
		info.Hint = "Tailscale 处于停止状态,打开 Tailscale 客户端点「Connect」即可。"
	default:
		info.State = "error"
		info.Error = st.BackendState
		info.Hint = "Tailscale 状态异常:" + st.BackendState
	}

	info.URL = joinURL("http://", firstIPv4(info.IPs), port, "/ui/")
	info.DNSURL = joinURL("http://", info.DNSName, port, "/ui/")

	// tailscale serve 状态(只有已连接时才有意义)
	if info.Ready {
		if sOut, sErr := runTailscale(6*time.Second, "serve", "status"); sErr == nil || sOut != "" {
			for _, line := range strings.Split(sOut, "\n") {
				line = strings.TrimSpace(line)
				if strings.HasPrefix(line, "https://") {
					info.ServeOn = true
					info.ServeURL = strings.Fields(line)[0]
					break
				}
			}
		}
	}
	return info
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

// handleRemoteInfo GET /api/remote?refresh=1 — 远程访问总览
func (s *Server) handleRemoteInfo(w http.ResponseWriter, r *http.Request) {
	force := r.URL.Query().Get("refresh") != ""
	ts := detectTailscale(s.cfg.Port, force)
	info := remoteInfo{
		Port:       s.cfg.Port,
		PrimaryURL: "http://" + primaryIP() + ":" + itoa(s.cfg.Port) + "/ui/",
		Tray:       trayRunning(),
		Autostart:  autostartEnabled(),
		Tailscale:  ts,
	}
	for _, ip := range localIPs() {
		info.LANURLs = append(info.LANURLs, "http://"+ip+":"+itoa(s.cfg.Port)+"/ui/")
	}
	writeJSON(w, 200, info)
}

// handleRemoteServe POST /api/remote/serve {enable:bool}
// 用 `tailscale serve` 把 linNas 发布成本 tailnet 内的 HTTPS 站点。
func (s *Server) handleRemoteServe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enable *bool `json:"enable"`
	}
	if err := readJSON(r, &req); err != nil || req.Enable == nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if findTailscale() == "" {
		writeErr(w, 400, "本机没有安装 Tailscale")
		return
	}
	local := "http://127.0.0.1:" + itoa(s.cfg.Port)
	var out string
	var err error
	if *req.Enable {
		out, err = runTailscale(45*time.Second, "serve", "--bg", local)
	} else {
		out, err = runTailscale(20*time.Second, "serve", "reset")
	}
	tsCacheMu.Lock()
	tsCacheAt = time.Time{} // 让下次读取重新探测
	tsCacheMu.Unlock()
	if err != nil {
		msg := strings.TrimSpace(out)
		if msg == "" {
			msg = err.Error()
		}
		writeErr(w, 500, "tailscale serve 执行失败: "+msg)
		return
	}
	ts := detectTailscale(s.cfg.Port, true)
	writeJSON(w, 200, map[string]any{
		"ok":      true,
		"output":  strings.TrimSpace(out),
		"serveOn": ts.ServeOn,
		"url":     ts.ServeURL,
	})
}
