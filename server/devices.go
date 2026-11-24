package main

import (
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// ============================================================
//  设备管理:记录访问过的客户端(IP + User-Agent + 活跃时间)
// ============================================================

type deviceInfo struct {
	IP    string
	UA    string
	First int64
	Last  int64
	Count int64
}

type deviceRegistry struct {
	mu sync.Mutex
	m  map[string]*deviceInfo
}

func newDeviceRegistry() *deviceRegistry {
	return &deviceRegistry{m: map[string]*deviceInfo{}}
}

func (d *deviceRegistry) record(ip, ua string) {
	if ip == "" {
		return
	}
	ua = strings.TrimSpace(ua)
	if len(ua) > 400 {
		ua = ua[:400]
	}
	key := ip + "|" + ua
	now := time.Now().Unix()
	d.mu.Lock()
	defer d.mu.Unlock()
	di, ok := d.m[key]
	if !ok {
		di = &deviceInfo{IP: ip, UA: ua, First: now}
		d.m[key] = di
	}
	di.Last = now
	di.Count++
}

func (d *deviceRegistry) list() []map[string]any {
	d.mu.Lock()
	items := make([]*deviceInfo, 0, len(d.m))
	for _, v := range d.m {
		c := *v
		items = append(items, &c)
	}
	d.mu.Unlock()
	sort.Slice(items, func(i, j int) bool { return items[i].Last > items[j].Last })
	out := make([]map[string]any, 0, len(items))
	for _, it := range items {
		out = append(out, map[string]any{
			"ip":    it.IP,
			"ua":    it.UA,
			"first": it.First,
			"last":  it.Last,
			"count": it.Count,
		})
	}
	return out
}

func (d *deviceRegistry) clear() {
	d.mu.Lock()
	d.m = map[string]*deviceInfo{}
	d.mu.Unlock()
}

// recordDevice middleware records every client touch (best effort).
func (s *Server) recordDevice(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/health" {
			s.devices.record(clientIP(r), r.UserAgent())
		}
		next.ServeHTTP(w, r)
	})
}

// handleDevices GET /api/devices — 已连接/访问过的设备
func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.devices.list())
}

// handleDevicesClear POST /api/devices/clear — 清空设备记录
func (s *Server) handleDevicesClear(w http.ResponseWriter, r *http.Request) {
	s.devices.clear()
	writeJSON(w, 200, map[string]bool{"ok": true})
}
