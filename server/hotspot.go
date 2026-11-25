package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// 热点(Wi-Fi 移动热点)支持:用于「手机不在同一网络」时,让电脑开一个热点,
// 手机连上后再扫码配对。底层调用 Windows WinRT
// NetworkOperatorTetheringManager(即系统「移动热点」开关所用的同一套 API)。
//
// 说明:该能力依赖电脑具备可用的无线网卡,且无线开关需打开;
// 不具备条件时这里会返回 supported=false + reason,前端据此展示引导文案。

const hotspotPS = `param([string]$Action = "status", [string]$Ssid = "", [string]$Password = "")
$ErrorActionPreference = "Stop"
function Emit($o) { $o | ConvertTo-Json -Compress -Depth 4 | Write-Output }
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
  [Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null

  $asTaskOp = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*' })[0]
  $asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]
  function AwaitOp($op, $t) {
    $task = $asTaskOp.MakeGenericMethod($t).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    return $task.Result
  }
  # NOTE: ConfigureAccessPointAsync returns IAsyncAction (not IAsyncOperation).
  # Must await it with AsTask(IAsyncAction); the generic overload throws, which
  # previously meant SSID/passphrase/band were never actually applied.
  function ApplyApConfig($ssid, $password, $band) {
    $ap = $mgr.GetCurrentAccessPointConfiguration()
    if ($ssid -ne "") { $ap.Ssid = $ssid }
    if ($password.Length -ge 8) { $ap.Passphrase = $password }
    # band as int: 0=Auto 1=2.4GHz 2=5GHz 3=6GHz
    $ap.Band = [Windows.Networking.NetworkOperators.TetheringWiFiBand]$band
    try {
      $t = $asTaskAction.Invoke($null, @($mgr.ConfigureAccessPointAsync($ap)))
      $t.Wait(-1) | Out-Null
      return "ok"
    } catch { return "err: $($_.Exception.InnerException.Message)" }
  }

  $cp = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  if ($null -eq $cp) { Emit @{ ok = $true; capability = "NoInternet"; state = "Unknown"; error = "no-internet-profile" }; exit 0 }

  $cap = "Unknown"
  try { $cap = "$([Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::GetTetheringCapabilityFromConnectionProfile($cp))" } catch { $cap = "Unknown" }

  $mgr = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($cp)

  $cfg = ""
  if ($Action -eq "start") {
    # try bands in order: Auto -> 2.4GHz -> 5GHz; keep switching only while
    # the failure is SelectedNetworkBandNotAvailable
    foreach ($band in @(0, 1, 2)) {
      $cfg = ApplyApConfig $Ssid $Password $band
      $r = AwaitOp ($mgr.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
      $result = "$($r.Status)"; $perr = "$($r.AdditionalErrorMessage)"
      if ($result -eq "Success" -or $result -eq "AlreadyOn") { break }
      if ($result -ne "SelectedNetworkBandNotAvailable") { break }
    }
  } elseif ($Action -eq "stop") {
    $r = AwaitOp ($mgr.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
    $result = "$($r.Status)"; $perr = "$($r.AdditionalErrorMessage)"
  } else { $result = ""; $perr = "" }

  $ap2 = $mgr.GetCurrentAccessPointConfiguration()
  $clients = 0
  try { $clients = [int]$mgr.GetTetheringClients().Count } catch { $clients = 0 }

  Emit @{
    ok = $true
    capability = $cap
    state = "$($mgr.TetheringOperationalState)"
    ssid = "$($ap2.Ssid)"
    password = "$($ap2.Passphrase)"
    band = "$($ap2.Band)"
    auth = "$($ap2.AuthenticationKind)"
    maxClients = [int]$mgr.MaxClientCount
    clients = $clients
    action = $Action
    result = $result
    configure = $cfg
    error = $perr
  }
} catch {
  Emit @{ ok = $false; error = "$($_.Exception.Message)" }
}
`

var hotspotMu sync.Mutex

func hotspotScriptPath() (string, error) {
	p := filepath.Join(os.TempDir(), "linnas-hotspot.ps1")
	if b, err := os.ReadFile(p); err != nil || string(b) != hotspotPS {
		if err := os.WriteFile(p, []byte(hotspotPS), 0o600); err != nil {
			return "", err
		}
	}
	return p, nil
}

// runHotspotScript 执行 PowerShell 脚本并解析它输出的单行 JSON。
func runHotspotScript(action, ssid, password string) (map[string]any, error) {
	hotspotMu.Lock()
	defer hotspotMu.Unlock()

	p, err := hotspotScriptPath()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	args := []string{"-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", p, "-Action", action}
	if ssid != "" {
		args = append(args, "-Ssid", ssid)
	}
	if password != "" {
		args = append(args, "-Password", password)
	}
	cmd := exec.CommandContext(ctx, "powershell.exe", args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	out, err := cmd.Output()
	if err != nil && len(out) == 0 {
		return map[string]any{"ok": false, "error": err.Error()}, err
	}
	line := ""
	for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if strings.HasPrefix(strings.TrimSpace(l), "{") {
			line = strings.TrimSpace(l)
		}
	}
	if line == "" {
		return map[string]any{"ok": false, "error": "no-output", "raw": strings.TrimSpace(string(out))}, nil
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(line), &m); err != nil {
		return map[string]any{"ok": false, "error": "bad-json", "raw": line}, nil
	}
	return m, nil
}

// canControlSystem:管理员或单机开放模式下允许;其余角色禁止操作热点/系统设置。
func canControlSystem(r *http.Request) bool {
	u := userFrom(r)
	if u == nil {
		return true // 无多用户且未设口令的开放模式
	}
	return u.Role == "admin"
}

// normalizeHotspot 把脚本原始输出整理成前端友好的结构。
func normalizeHotspot(raw map[string]any) map[string]any {
	okv, _ := raw["ok"].(bool)
	if !okv {
		return map[string]any{"supported": false, "running": false, "reason": "error", "error": str(raw["error"])}
	}
	cap := str(raw["capability"])
	state := str(raw["state"])
	reason := ""
	supported := cap == "Enabled"
	if !supported {
		switch cap {
		case "Disabled":
			reason = "disabled"
		case "NoInternet":
			reason = "no-internet"
		case "Unknown":
			reason = "unknown"
		default:
			reason = strings.ToLower(cap)
		}
	}
	// 启动失败时把 WinRT 结果码翻译成可读原因
	result := str(raw["result"])
	if result != "" && result != "Success" {
		switch result {
		case "WiFiDeviceOff":
			reason = "wifi-off"
		case "NetworkLimitedConnectivity", "NetworkConnectivityFailure":
			reason = "no-internet"
		case "SelectedNetworkBandNotAvailable":
			reason = "band-unavailable"
		case "AlreadyOn":
			reason = ""
		default:
			reason = "op-" + strings.ToLower(result)
		}
	}
	return map[string]any{
		"supported":  supported,
		"running":    state == "On",
		"capability": cap,
		"state":      state,
		"ssid":       str(raw["ssid"]),
		"password":   str(raw["password"]),
		"band":       str(raw["band"]),
		"auth":       str(raw["auth"]),
		"maxClients": raw["maxClients"],
		"clients":    raw["clients"],
		"result":     result,
		"configure":  str(raw["configure"]),
		"reason":     reason,
		"error":      str(raw["error"]),
	}
}

func str(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	b, _ := json.Marshal(v)
	return string(b)
}

// GET /api/hotspot —— 查询热点能力与状态
func (s *Server) handleHotspotStatus(w http.ResponseWriter, r *http.Request) {
	if !canControlSystem(r) {
		writeErr(w, http.StatusForbidden, "仅管理员可查看热点状态")
		return
	}
	raw, err := runHotspotScript("status", "", "")
	if err != nil {
		writeJSON(w, 200, map[string]any{"supported": false, "running": false, "reason": "error", "error": err.Error()})
		return
	}
	writeJSON(w, 200, normalizeHotspot(raw))
}

// POST /api/hotspot {action:"start"|"stop", ssid?, password?}
func (s *Server) handleHotspotAction(w http.ResponseWriter, r *http.Request) {
	if !canControlSystem(r) {
		writeErr(w, http.StatusForbidden, "仅管理员可操作热点")
		return
	}
	var req struct {
		Action   string `json:"action"`
		Ssid     string `json:"ssid"`
		Password string `json:"password"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	action := strings.ToLower(strings.TrimSpace(req.Action))
	if action != "start" && action != "stop" {
		writeErr(w, http.StatusBadRequest, "action 只能是 start 或 stop")
		return
	}
	if req.Password != "" && len(req.Password) < 8 {
		writeErr(w, http.StatusBadRequest, "热点密码至少 8 位")
		return
	}
	raw, err := runHotspotScript(action, strings.TrimSpace(req.Ssid), req.Password)
	if err != nil {
		writeJSON(w, 200, map[string]any{"supported": false, "running": false, "reason": "error", "error": err.Error()})
		return
	}
	writeJSON(w, 200, normalizeHotspot(raw))
}

// GET /api/qr?text=...&size=... —— 把任意文本渲染成二维码 PNG(用于 Wi-Fi 扫码加入热点)
func (s *Server) handleQRText(w http.ResponseWriter, r *http.Request) {
	text := r.URL.Query().Get("text")
	if text == "" {
		writeErr(w, http.StatusBadRequest, "缺少 text")
		return
	}
	if len(text) > 1024 {
		writeErr(w, http.StatusBadRequest, "text 过长")
		return
	}
	size := 320
	if v := r.URL.Query().Get("size"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &size); err != nil {
			size = 320
		}
	}
	if size < 128 {
		size = 128
	}
	if size > 1024 {
		size = 1024
	}
	png, err := qrcode.Encode(text, qrcode.Medium, size)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(png)
}

// POST /api/system/open {target:"mobilehotspot"} —— 在服务端所在电脑打开系统设置页
func (s *Server) handleOpenSettings(w http.ResponseWriter, r *http.Request) {
	if !canControlSystem(r) {
		writeErr(w, http.StatusForbidden, "仅管理员可打开系统设置")
		return
	}
	var req struct {
		Target string `json:"target"`
	}
	_ = readJSON(r, &req)
	uri := "ms-settings:network-mobilehotspot"
	switch strings.ToLower(strings.TrimSpace(req.Target)) {
	case "", "mobilehotspot", "hotspot":
		uri = "ms-settings:network-mobilehotspot"
	case "wifi":
		uri = "ms-settings:network-wifi"
	case "network":
		uri = "ms-settings:network"
	default:
		writeErr(w, http.StatusBadRequest, "不支持的设置页")
		return
	}
	cmd := exec.Command("cmd", "/c", "start", "", uri)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if err := cmd.Start(); err != nil {
		writeErr(w, http.StatusInternalServerError, "无法打开系统设置:"+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "opened": uri})
}
