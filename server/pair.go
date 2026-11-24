package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image/png"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// discoveryPort is the UDP port used for lan discovery (probe + beacon).
// It sits next to the HTTP port (52700) so it is easy to remember/firewall.
const discoveryPort = 52701

// discoveryMagic is the probe payload an app/client sends to find the server.
const discoveryMagic = "LINNAS_DISCOVER"

// PairInfo is the payload handed to the phone (and encoded into the QR code).
type PairInfo struct {
	Name    string   `json:"name"`
	Version string   `json:"version"`
	Port    int      `json:"port"`
	IPs     []string `json:"ips"`
	URLs    []string `json:"urls"`
	Token   string   `json:"token,omitempty"`
	URI     string   `json:"uri"`
}

// primaryIP prefers a private LAN IPv4 (192.168./10./172.16-31) over others.
// Order: 192.168.* > 10.* > 172.16-31.* > anything else reachable.
// Link-local 169.254.* and CGNAT/VPN ranges are only used as a last resort.
func primaryIP() string {
	best := ""
	rank := func(ip string) int {
		switch {
		case strings.HasPrefix(ip, "192.168."):
			return 1
		case strings.HasPrefix(ip, "10."):
			return 2
		case strings.HasPrefix(ip, "172."):
			var b, c int
			fmt.Sscanf(ip, "172.%d.%d", &b, &c)
			if b >= 16 && b <= 31 {
				return 3
			}
			return 8
		case strings.HasPrefix(ip, "169.254."):
			return 9
		default:
			return 5
		}
	}
	bestRank := 99
	for _, ip := range localIPv4s() {
		if r := rank(ip); r < bestRank {
			bestRank = r
			best = ip
		}
	}
	if best != "" {
		return best
	}
	return "127.0.0.1"
}

// localIPv4s returns all non-loopback IPv4 addresses (dotted quad), ordered.
func localIPv4s() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := ipnet.IP.To4()
			if v4 == nil {
				continue
			}
			out = append(out, v4.String())
		}
	}
	return out
}

// PairInfoFor builds the pairing payload for a given advertised IP.
func (s *Server) PairInfoFor(ip string) PairInfo {
	if ip == "" {
		ip = primaryIP()
	}
	ips := localIPv4s()
	// 让主地址排在最前,便于 UI/日志一致。
	ordered := make([]string, 0, len(ips))
	for _, v := range ips {
		if v == ip {
			ordered = append(ordered, v)
		}
	}
	for _, v := range ips {
		if v != ip {
			ordered = append(ordered, v)
		}
	}
	ips = ordered
	urls := make([]string, 0, len(ips))
	for _, v := range ips {
		urls = append(urls, fmt.Sprintf("http://%s:%d", v, s.cfg.Port))
	}
	uri := fmt.Sprintf("linnas://pair?ip=%s&port=%d&name=%s",
		url.QueryEscape(ip), s.cfg.Port, url.QueryEscape("linNas"))
	if s.cfg.Token != "" {
		uri += "&token=" + url.QueryEscape(s.cfg.Token)
	}
	return PairInfo{
		Name:    "linNas",
		Version: Version,
		Port:    s.cfg.Port,
		IPs:     ips,
		URLs:    urls,
		Token:   s.cfg.Token,
		URI:     uri,
	}
}

// handlePairInfo returns the JSON pairing descriptor.
// It is intentionally *not* token protected so a fresh phone can read it.
func (s *Server) handlePairInfo(w http.ResponseWriter, r *http.Request) {
	info := s.PairInfoFor(r.URL.Query().Get("ip"))
	writeJSON(w, http.StatusOK, info)
}

// handlePairQR renders a PNG QR code of the pairing URI.
// Query: ip (optional), size (optional, default 320, max 1024).
func (s *Server) handlePairQR(w http.ResponseWriter, r *http.Request) {
	size := 320
	if v := r.URL.Query().Get("size"); v != "" {
		fmt.Sscanf(v, "%d", &size)
	}
	if size < 128 {
		size = 128
	}
	if size > 1024 {
		size = 1024
	}
	info := s.PairInfoFor(r.URL.Query().Get("ip"))
	png, err := qrcode.Encode(info.URI, qrcode.Medium, size)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(png)
}

// handlePairPage serves a tiny self-contained pairing page (QR + addresses).
// It is registered on the root mux (not under /api so it needs no token).
func (s *Server) handlePairPage(w http.ResponseWriter, r *http.Request) {
	info := s.PairInfoFor(r.URL.Query().Get("ip"))
	var rows strings.Builder
	for i, u := range info.URLs {
		ip := info.IPs[i]
		mark := ""
		if ip == info.IPs[0] {
			mark = " <span class=\"tag\">推荐</span>"
		}
		rows.WriteString(fmt.Sprintf("<li><code>%s</code>%s</li>", u, mark))
	}
	if len(info.URLs) == 0 {
		rows.WriteString("<li><code>未检测到局域网地址</code></li>")
	}
	tok := ""
	if info.Token != "" {
		tok = "<p class=\"tok\">访问令牌:<code>" + info.Token + "</code></p>"
	}
	html := `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>linNas 配对</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#0B0D11;color:#E9EDF5;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,"Noto Sans SC",sans-serif}
.wrap{max-width:560px;margin:0 auto;padding:28px 20px 48px}
h1{font-size:22px;margin:0 0 4px;letter-spacing:.02em}
.sub{color:#8A94A6;font-size:13px;margin:0 0 22px}
.card{background:#14181F;border:1px solid #2A323E;border-radius:18px;padding:20px;text-align:center}
.card img{width:100%;max-width:320px;height:auto;background:#fff;border-radius:12px;padding:10px}
.steps{color:#8A94A6;font-size:13px;line-height:1.8;margin:16px 0 0;text-align:left}
ul{list-style:none;padding:0;margin:18px 0 0}
li{background:#1C222C;border:1px solid #2A323E;border-radius:12px;padding:10px 12px;margin:8px 0;font-size:13px}
code{color:#79E2B6;font-size:13px}
.tag{color:#6EA8FE;font-size:11px;border:1px solid #6EA8FE33;border-radius:999px;padding:1px 7px;margin-left:6px}
.tok{color:#8A94A6;font-size:12px;margin:14px 0 0;word-break:break-all}
</style></head><body><div class="wrap">
<h1>linNas · 配对</h1>
<p class="sub">用手机 App 扫描下方二维码,或从列表里选择地址连接。无需手动输入 IP。</p>
<div class="card">
<img src="/api/pair/qr?size=320&ip=` + url.QueryEscape(info.IPs0()) + `" alt="配对二维码">
<p class="steps">① 手机打开 linNas App<br>② 点「扫码配对」扫这张图<br>③ 或 App 自动发现后点选本机</p>
</div>
<ul>` + rows.String() + `</ul>` + tok + `
</div></body></html>`
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write([]byte(html))
}

// IPs0 returns the first IP or empty.
func (p PairInfo) IPs0() string {
	if len(p.IPs) > 0 {
		return p.IPs[0]
	}
	return ""
}

// startDiscovery runs a UDP discovery responder and periodic beacon.
// Any client on the LAN can send the ASCII probe "LINNAS_DISCOVER" to
// UDP :52701 and gets back a JSON PairInfo unicast to its address; the
// server also broadcasts the same JSON every few seconds so clients that
// only listen can find it.
func (s *Server) startDiscovery() {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: discoveryPort})
	if err != nil {
		log.Printf("局域网发现: 无法监听 UDP %d: %v(将仅发送广播)", discoveryPort, err)
		conn = nil
	}
	if conn != nil {
		go func() {
			buf := make([]byte, 512)
			for {
				n, addr, err := conn.ReadFromUDP(buf)
				if err != nil {
					return
				}
				msg := strings.TrimSpace(string(buf[:n]))
				if msg == discoveryMagic || strings.HasPrefix(msg, "LINNAS_DISCOVER") {
					ip := primaryIP()
					if local := addr.IP.String(); !strings.HasPrefix(local, "127.") {
						// reply with the address the client can actually reach us on:
						// if we share a subnet, use our address on that subnet.
						if sameSubnet(addr.IP) != "" {
							ip = sameSubnet(addr.IP)
						}
					}
					payload, _ := json.Marshal(s.PairInfoFor(ip))
					conn.WriteToUDP(payload, addr)
				}
			}
		}()
		log.Printf("局域网发现: UDP 应答已启动 (端口 %d)", discoveryPort)
	}

	// Beacon every 3s to broadcast addresses.
	go func() {
		send := func() {
			payload, _ := json.Marshal(s.PairInfoFor(primaryIP()))
			targets := [][4]byte{{255, 255, 255, 255}}
			for _, b := range broadcastIPv4() {
				targets = append(targets, [4]byte{b[0], b[1], b[2], b[3]})
			}
			for _, t := range targets {
				addr := &net.UDPAddr{IP: net.IPv4(t[0], t[1], t[2], t[3]), Port: discoveryPort}
				c, err := net.DialUDP("udp4", nil, addr)
				if err != nil {
					continue
				}
				c.Write(payload)
				c.Close()
			}
		}
		send()
		t := time.NewTicker(3 * time.Second)
		defer t.Stop()
		for range t.C {
			send()
		}
	}()
}

// sameSubnet returns our IPv4 address that shares a subnet with remote, or "".
func sameSubnet(remote net.IP) string {
	r4 := remote.To4()
	if r4 == nil {
		return ""
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := ipnet.IP.To4()
			if v4 == nil {
				continue
			}
			if ipnet.Contains(r4) {
				return v4.String()
			}
		}
	}
	return ""
}

// broadcastIPv4 returns subnet-directed broadcast addresses for up interfaces.
func broadcastIPv4() [][]byte {
	var out [][]byte
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := ipnet.IP.To4()
			if v4 == nil || len(ipnet.Mask) != 4 {
				continue
			}
			b := make([]byte, 4)
			for i := 0; i < 4; i++ {
				b[i] = v4[i] | ^ipnet.Mask[i]
			}
			out = append(out, b)
		}
	}
	return out
}

// pairPNG is a helper used by tests/CLI to render a QR to bytes.
func pairPNG(uri string, size int) ([]byte, error) {
	var buf bytes.Buffer
	q, err := qrcode.New(uri, qrcode.Medium)
	if err != nil {
		return nil, err
	}
	if err := png.Encode(&buf, q.Image(size)); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
