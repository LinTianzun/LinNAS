package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// ============================================================
//  空间告警记录:跨请求保留 firstAt / acked,落盘 DataDir/alerts.json。
//  告警内容由总览页(buildOverview)在每次请求时重新计算并同步进 store:
//   - 首次出现 → FirstAt = LastAt = now;
//   - 持续存在 → 保留 FirstAt / Acked,内容变化时才更新并刷新 LastAt;
//   - 消失(恢复)→ Active = false,仍保留在列表中供前端显示「已恢复」。
// ============================================================

// spaceAlert 是一条空间/健康告警记录(与总览告警同源)。
type spaceAlert struct {
	ID      string `json:"id"`      // "space:<libID>:crit" / ":warn"
	Level   string `json:"level"`   // warn | info
	LibID   string `json:"libId"`   // 关联的存储库 ID(非库告警为空)
	Text    string `json:"text"`    // 展示文案
	FirstAt int64  `json:"firstAt"` // 首次出现时间(unix 秒)
	LastAt  int64  `json:"lastAt"`  // 最近一次仍在生效的时间(unix 秒)
	Active  bool   `json:"active"`  // 当前是否仍生效
	Acked   bool   `json:"acked"`   // 是否已读
}

// alertStoreMax 是保留的告警上限(按 LastAt 保留最新的)。
const alertStoreMax = 200

// alertsListLimit 是 GET /api/alerts 返回的已恢复(active=false)条目上限。
const alertsListLimit = 30

// alertDoc 是 alerts.json 的落盘结构。
type alertDoc struct {
	Alerts  []spaceAlert `json:"alerts"`
	SavedAt int64        `json:"savedAt"`
}

// alertStore 保存告警记录(受 mu 保护,HTTP 读写、后台同步)。
type alertStore struct {
	mu    sync.Mutex
	items []spaceAlert
	path  string
}

// newAlertStore 打开 DataDir/alerts.json(不存在时从空列表开始)。
func newAlertStore(dataDir string) *alertStore {
	st := &alertStore{path: filepath.Join(dataDir, "alerts.json")}
	if raw, err := os.ReadFile(st.path); err == nil {
		var doc alertDoc
		if json.Unmarshal(raw, &doc) == nil {
			st.items = doc.Alerts
		}
	}
	return st
}

// saveLocked 把当前列表写盘(调用方需持有 mu)。
func (a *alertStore) saveLocked() error {
	raw, err := json.MarshalIndent(alertDoc{Alerts: a.items, SavedAt: time.Now().Unix()}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(a.path, raw, 0o600)
}

// Sync 合并当前告警:保留 firstAt/acked,内容变化时更新并刷新 lastAt,
// 本次未出现的告警标记为 Active=false;只有内容真正变化时才写盘。
func (a *alertStore) Sync(current []spaceAlert, now time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	ts := now.Unix()

	byID := make(map[string]int, len(a.items))
	for i := range a.items {
		byID[a.items[i].ID] = i
	}
	seen := make(map[string]bool, len(current))
	changed := false
	for _, cur := range current {
		if cur.ID == "" {
			continue
		}
		seen[cur.ID] = true
		idx, ok := byID[cur.ID]
		if !ok {
			cur.FirstAt = ts
			cur.LastAt = ts
			cur.Active = true
			cur.Acked = false
			a.items = append(a.items, cur)
			byID[cur.ID] = len(a.items) - 1
			changed = true
			continue
		}
		old := &a.items[idx]
		contentChanged := old.Level != cur.Level || old.LibID != cur.LibID || old.Text != cur.Text
		if contentChanged || !old.Active {
			// 保留 FirstAt / Acked,只更新内容与最近生效时间。
			old.Level = cur.Level
			old.LibID = cur.LibID
			old.Text = cur.Text
			old.Active = true
			old.LastAt = ts
			changed = true
		}
	}
	// 本次不再出现的告警视为已恢复。
	for i := range a.items {
		if a.items[i].Active && !seen[a.items[i].ID] {
			a.items[i].Active = false
			changed = true
		}
	}
	// 控制总量:按最近生效时间保留最新的 alertStoreMax 条。
	if len(a.items) > alertStoreMax {
		sort.SliceStable(a.items, func(i, j int) bool {
			return a.items[i].LastAt > a.items[j].LastAt
		})
		a.items = a.items[:alertStoreMax]
		changed = true
	}
	// 内部统一按 ID 排序,便于落盘内容稳定与比较。
	sort.SliceStable(a.items, func(i, j int) bool { return a.items[i].ID < a.items[j].ID })
	if changed {
		if err := a.saveLocked(); err != nil {
			log.Printf("保存告警记录失败: %v", err)
		}
	}
}

// List 返回告警副本:Active 优先,其次按 LastAt 倒序,同时间按 ID 升序。
func (a *alertStore) List() []spaceAlert {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]spaceAlert, len(a.items))
	copy(out, a.items)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Active != out[j].Active {
			return out[i].Active
		}
		if out[i].LastAt != out[j].LastAt {
			return out[i].LastAt > out[j].LastAt
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// Active 返回当前仍生效的告警(已按 LastAt 倒序);limit<=0 表示不限制。
func (a *alertStore) Active(limit int) []spaceAlert {
	all := a.List()
	out := make([]spaceAlert, 0, len(all))
	for _, it := range all {
		if !it.Active {
			continue
		}
		if limit > 0 && len(out) >= limit {
			break
		}
		out = append(out, it)
	}
	return out
}

// Ack 标记已读:all=true 标记全部,否则按 ids 匹配;返回本次实际标记的条数。
func (a *alertStore) Ack(ids []string, all bool) int {
	a.mu.Lock()
	defer a.mu.Unlock()
	want := make(map[string]bool, len(ids))
	for _, id := range ids {
		want[id] = true
	}
	n := 0
	for i := range a.items {
		if a.items[i].Acked {
			continue
		}
		if !all && !want[a.items[i].ID] {
			continue
		}
		a.items[i].Acked = true
		n++
	}
	if n > 0 {
		if err := a.saveLocked(); err != nil {
			log.Printf("保存告警已读状态失败: %v", err)
		}
	}
	return n
}

// Unread 返回未读告警数(仍生效且未标记已读)。
func (a *alertStore) Unread() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	n := 0
	for i := range a.items {
		if a.items[i].Active && !a.items[i].Acked {
			n++
		}
	}
	return n
}

// handleAlerts GET /api/alerts — 告警列表(含最近 30 条已恢复的)与未读数。
func (s *Server) handleAlerts(w http.ResponseWriter, r *http.Request) {
	items := []spaceAlert{}
	unread := 0
	if s.alerts != nil {
		inactive := 0
		for _, it := range s.alerts.List() {
			if it.Active {
				items = append(items, it)
				continue
			}
			if inactive >= alertsListLimit {
				continue
			}
			inactive++
			items = append(items, it)
		}
		unread = s.alerts.Unread()
	}
	writeJSON(w, 200, map[string]any{"items": items, "unread": unread})
}

// handleAlertsAck POST /api/alerts/ack {ids,all} — 标记已读(任意登录角色可调用)。
func (s *Server) handleAlertsAck(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IDs []string `json:"ids"`
		All bool     `json:"all"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	acked, unread := 0, 0
	if s.alerts != nil {
		acked = s.alerts.Ack(req.IDs, req.All)
		unread = s.alerts.Unread()
	}
	writeJSON(w, 200, map[string]any{"acked": acked, "unread": unread})
}
