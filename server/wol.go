package main

import (
	"errors"
	"net"
	"net/http"
	"strings"
	"time"
)

// ============================================================
//  Wake-on-LAN:记住若干主机的 MAC,一键发送 magic packet 唤醒。
//  手机/浏览器出门在外也可先用 Tailscale 连回本机再唤醒家里 PC。
// ============================================================

// WolHost 一条记住的待唤醒主机。
type WolHost struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	MAC  string `json:"mac"`
}

// normalizeMAC 接受 aa:bb:cc:dd:ee:ff / aa-bb-... / aabbccddeeff,
// 统一返回小写冒号分隔形式。
func normalizeMAC(s string) (string, error) {
	clean := strings.NewReplacer(":", "", "-", "", ".", "", " ", "").Replace(strings.TrimSpace(s))
	if len(clean) != 12 {
		return "", errors.New("MAC 地址格式不正确")
	}
	var parts []string
	for i := 0; i < 12; i += 2 {
		b := clean[i : i+2]
		for _, c := range b {
			if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
				return "", errors.New("MAC 地址包含非法字符")
			}
		}
		parts = append(parts, strings.ToLower(b))
	}
	return strings.Join(parts, ":"), nil
}

// magicPacket 构造 6 字节 0xFF + 16 次 MAC 的 102 字节唤醒包。
func magicPacket(mac string) ([]byte, error) {
	norm, err := normalizeMAC(mac)
	if err != nil {
		return nil, err
	}
	hw, err := net.ParseMAC(norm)
	if err != nil {
		return nil, err
	}
	pkt := make([]byte, 0, 102)
	for i := 0; i < 6; i++ {
		pkt = append(pkt, 0xFF)
	}
	for i := 0; i < 16; i++ {
		pkt = append(pkt, hw...)
	}
	return pkt, nil
}

// sendMagicPacket 通过 UDP 广播发送唤醒包。
func sendMagicPacket(mac, addr string) error {
	pkt, err := magicPacket(mac)
	if err != nil {
		return err
	}
	if strings.TrimSpace(addr) == "" {
		addr = "255.255.255.255:9"
	}
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return err
	}
	conn, err := net.DialUDP("udp", nil, udpAddr)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	_, err = conn.Write(pkt)
	return err
}

// handleWolList GET /api/wol — 返回已保存的主机列表
func (s *Server) handleWolList(w http.ResponseWriter, r *http.Request) {
	hosts := s.cfg.WolHosts
	if hosts == nil {
		hosts = []WolHost{}
	}
	writeJSON(w, 200, map[string]any{"hosts": hosts})
}

// handleWolAdd POST /api/wol {name, mac}
func (s *Server) handleWolAdd(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
		MAC  string `json:"mac"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	mac, err := normalizeMAC(req.MAC)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = mac
	}
	host := WolHost{ID: randHex(4), Name: name, MAC: mac}
	s.cfg.WolHosts = append(s.cfg.WolHosts, host)
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "host": host})
}

// handleWolDelete DELETE /api/wol?id=xxx
func (s *Server) handleWolDelete(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	var keep []WolHost
	found := false
	for _, h := range s.cfg.WolHosts {
		if h.ID == id {
			found = true
			continue
		}
		keep = append(keep, h)
	}
	if !found {
		writeErr(w, 404, "主机不存在")
		return
	}
	s.cfg.WolHosts = keep
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handleWolSend POST /api/wol/send {mac, addr?}
func (s *Server) handleWolSend(w http.ResponseWriter, r *http.Request) {
	var req struct {
		MAC  string `json:"mac"`
		Addr string `json:"addr"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if err := sendMagicPacket(req.MAC, req.Addr); err != nil {
		writeErr(w, 400, "发送失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "mac": req.MAC})
}
