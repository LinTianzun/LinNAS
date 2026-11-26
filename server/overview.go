package main

import (
	"log"
	"net/http"
	"strings"
	"time"
)

// ============================================================
//  总览仪表盘:GET /api/overview
//  汇总各存储库容量、媒体与内容统计、系统状态、近期活动与告警。
//  设计要点:
//   - 任何单个子查询失败都只让对应项降级(计数归零 / 列表为空),
//     接口本身始终返回 200,前端可以照常渲染其余模块。
//   - 所有登录角色都能看;非管理员只能看到自己的审计记录。
// ============================================================

// 总览相关的固定参数。
const (
	// overviewIndexStaleMinutes:索引未更新的最小分钟数(与对账间隔×3 取大者)。
	overviewIndexStaleMinutes = 10
	// overviewMaxAlerts:告警条数上限。
	overviewMaxAlerts = 5
	// overviewRecentLimit:近期活动返回条数。
	overviewRecentLimit = 12
)

// overviewLib 是总览里的一个存储库:容量来自磁盘,文件数与字节数来自索引。
type overviewLib struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Path    string `json:"path"`
	FreeMB  int64  `json:"freeMB"`
	TotalMB int64  `json:"totalMB"`
	Files   int64  `json:"files"`
	Bytes   int64  `json:"bytes"`
}

// overviewTotals 是各存储库的合计。
type overviewTotals struct {
	FreeMB  int64 `json:"freeMB"`
	TotalMB int64 `json:"totalMB"`
	Files   int64 `json:"files"`
	Bytes   int64 `json:"bytes"`
	UsedPct int   `json:"usedPct"` // 已用百分比(整数 0-100)
}

// overviewKinds 是按类型统计的条目数(缺失补 0)。
type overviewKinds struct {
	Video int `json:"video"`
	Image int `json:"image"`
	Audio int `json:"audio"`
	Other int `json:"other"`
	Dir   int `json:"dir"`
}

// overviewIndexState 是索引状态(索引未启用时整个字段为 null)。
type overviewIndexState struct {
	FileCount      int   `json:"fileCount"`
	LastScan       int64 `json:"lastScan"` // Unix 秒,0 = 从未扫描
	Scanning       bool  `json:"scanning"`
	RefreshSeconds int   `json:"refreshSeconds"`
}

// overviewTrash 是回收站统计(字节数只算文件,目录大小不可靠)。
type overviewTrash struct {
	Count int   `json:"count"`
	Bytes int64 `json:"bytes"`
}

// overviewCount 是只有个数的统计项(分享链接、活跃设备)。
type overviewCount struct {
	Count int `json:"count"`
}

// overviewRecent 是近期活动的一条。
type overviewRecent struct {
	Time   int64  `json:"time"`
	User   string `json:"user"`
	Action string `json:"action"`
	Target string `json:"target"`
	Status int    `json:"status"`
}

// overviewAlert 是一条告警(level: warn / info)。
// ID 用于告警记录去重(space:<libID>:crit / :warn、index:*、disk:<drive>);
// acked 来自告警 store 的已读状态。
type overviewAlert struct {
	ID    string `json:"id,omitempty"`
	Level string `json:"level"`
	Text  string `json:"text"`
	LibID string `json:"libId,omitempty"`
	Acked bool   `json:"acked,omitempty"`
}

// overviewResp 是 GET /api/overview 的完整响应(字段名固定)。
type overviewResp struct {
	Version string              `json:"version"`
	Libs    []overviewLib       `json:"libs"`
	Totals  overviewTotals      `json:"totals"`
	Kinds   overviewKinds       `json:"kinds"`
	Index   *overviewIndexState `json:"index"`
	Trash   overviewTrash       `json:"trash"`
	Shares  overviewCount       `json:"shares"`
	Devices overviewCount       `json:"devices"`
	Recent  []overviewRecent    `json:"recent"`
	Alerts  []overviewAlert     `json:"alerts"`
	// UnreadAlerts 是告警 store 里仍生效且未标记已读的条数。
	UnreadAlerts int `json:"unreadAlerts"`
}

// handleOverview GET /api/overview — 总览仪表盘数据(所有登录角色可访问)。
func (s *Server) handleOverview(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.buildOverview(r))
}

// buildOverview 汇总总览数据;任一子查询失败都只降级对应项,不会让接口 500。
func (s *Server) buildOverview(r *http.Request) overviewResp {
	resp := overviewResp{
		Version: Version,
		Libs:    []overviewLib{},
		Kinds:   overviewKinds{},
		Trash:   overviewTrash{},
		Shares:  overviewCount{},
		Devices: overviewCount{},
		Recent:  []overviewRecent{},
		Alerts:  []overviewAlert{},
	}

	// 1) 索引:按库聚合文件数与字节(与存储空间分析同口径,只算 is_dir=0)。
	byLib := s.overviewLibCounts()

	// 2) 各库容量 + 索引计数(磁盘不可用的库跳过,不拖垮整个列表)。
	for _, lib := range s.cfg.Libraries {
		free, total, err := diskUsage(lib.Path)
		if err != nil {
			log.Printf("总览:读取库 %s 容量失败: %v", lib.Name, err)
			continue
		}
		c := byLib[lib.ID]
		resp.Libs = append(resp.Libs, overviewLib{
			ID:      lib.ID,
			Name:    lib.Name,
			Path:    lib.Path,
			FreeMB:  int64(free / 1e6),
			TotalMB: int64(total / 1e6),
			Files:   c.files,
			Bytes:   c.bytes,
		})
	}

	// 3) 合计:容量与索引计数各自求和,已用百分比取整数。
	for _, l := range resp.Libs {
		resp.Totals.FreeMB += l.FreeMB
		resp.Totals.TotalMB += l.TotalMB
		resp.Totals.Files += l.Files
		resp.Totals.Bytes += l.Bytes
	}
	if resp.Totals.TotalMB > 0 {
		used := resp.Totals.TotalMB - resp.Totals.FreeMB
		if used < 0 {
			used = 0
		}
		resp.Totals.UsedPct = int(used * 100 / resp.Totals.TotalMB)
	}

	// 4) 媒体与内容统计(索引不可用时全为 0)。
	resp.Kinds = s.overviewKindCounts()

	// 5) 索引状态(索引未启用时为 null)。
	if s.index != nil {
		st := s.index.Status()
		idx := &overviewIndexState{RefreshSeconds: s.cfg.IndexRefreshSeconds}
		if v, ok := st["fileCount"].(int); ok {
			idx.FileCount = v
		}
		if v, ok := st["lastScan"].(int64); ok {
			idx.LastScan = v
		}
		if v, ok := st["scanning"].(bool); ok {
			idx.Scanning = v
		}
		resp.Index = idx
	}

	// 6) 回收站:条目数 + 非目录条目的字节数。
	for _, it := range s.listTrash("") {
		resp.Trash.Count++
		if !it.IsDir {
			resp.Trash.Bytes += it.Size
		}
	}

	// 7) 分享链接与活跃设备数量。
	if s.shares != nil {
		resp.Shares.Count = len(s.shares.List())
	}
	if s.devices != nil {
		resp.Devices.Count = len(s.devices.list())
	}

	// 8) 近期活动:管理员看全部,非管理员只看自己的记录。
	if s.audit != nil {
		u := userFrom(r)
		isAdmin := u != nil && u.Role == "admin"
		resp.Recent = filterRecentForUser(s.audit.Recent(overviewRecentLimit), currentUserName(r), isAdmin)
	}

	// 9) 告警(最多 5 条,没有则为空数组)。
	// 先按配置阈值与索引状态生成当前告警,再并入磁盘健康告警(只读缓存,
	// 不在总览请求里触发 PowerShell),交给告警 store 合并已读状态。
	now := time.Now()
	alerts := buildOverviewAlerts(resp.Libs, resp.Index, now, s.cfg.IndexRefreshSeconds,
		s.cfg.SpaceWarnPercent, s.cfg.SpaceCriticalPercent)
	alerts = append(alerts, s.diskHealthAlerts()...)
	if len(alerts) > overviewMaxAlerts {
		alerts = alerts[:overviewMaxAlerts]
	}
	current := make([]spaceAlert, 0, len(alerts))
	for _, al := range alerts {
		current = append(current, spaceAlert{
			ID: al.ID, Level: al.Level, LibID: al.LibID, Text: al.Text,
		})
	}
	if s.alerts != nil {
		s.alerts.Sync(current, now)
		resp.Alerts = overviewAlertsFromStore(s.alerts.Active(overviewMaxAlerts))
		resp.UnreadAlerts = s.alerts.Unread()
	} else {
		// 告警 store 不可用(如测试里手工构造 Server):直接返回本轮结果。
		resp.Alerts = alerts
	}
	return resp
}

// diskHealthAlerts 把缓存中健康状态异常的磁盘转成 warn 告警;不会触发采集。
func (s *Server) diskHealthAlerts() []overviewAlert {
	var out []overviewAlert
	for _, d := range s.diskHealth.peek() {
		if strings.EqualFold(d.HealthStatus, "Healthy") {
			continue
		}
		out = append(out, overviewAlert{
			ID:    "disk:" + d.Drive,
			Level: "warn",
			Text:  "磁盘 " + d.Drive + " 健康状态异常: " + d.HealthStatus,
		})
	}
	return out
}

// overviewAlertsFromStore 把告警 store 里仍生效的条目转成响应结构(带 acked)。
func overviewAlertsFromStore(items []spaceAlert) []overviewAlert {
	out := make([]overviewAlert, 0, len(items))
	for _, it := range items {
		out = append(out, overviewAlert{
			ID: it.ID, Level: it.Level, Text: it.Text, LibID: it.LibID, Acked: it.Acked,
		})
	}
	return out
}

// overviewLibAgg 是某个库的索引聚合结果。
type overviewLibAgg struct {
	files int64
	bytes int64
}

// overviewLibCounts 按库聚合索引里的文件数与字节数。
// 索引不可用或查询失败时返回空表(计数归零),不影响其余模块。
func (s *Server) overviewLibCounts() map[string]overviewLibAgg {
	out := map[string]overviewLibAgg{}
	if s.index == nil {
		return out
	}
	rows, err := s.index.db.Query(
		`SELECT lib_id, COUNT(*), COALESCE(SUM(size), 0) FROM files WHERE is_dir = 0 GROUP BY lib_id`)
	if err != nil {
		log.Printf("总览:统计各库文件数失败: %v", err)
		return out
	}
	for rows.Next() {
		var id string
		var a overviewLibAgg
		if err := rows.Scan(&id, &a.files, &a.bytes); err != nil {
			log.Printf("总览:读取各库统计失败: %v", err)
			break
		}
		out[id] = a
	}
	if err := rows.Err(); err != nil {
		log.Printf("总览:读取各库统计失败: %v", err)
	}
	_ = rows.Close()
	return out
}

// overviewKindCounts 统计各类型条目数(video/image/audio/other/dir,缺失补 0)。
func (s *Server) overviewKindCounts() overviewKinds {
	var k overviewKinds
	if s.index == nil {
		return k
	}
	rows, err := s.index.db.Query(`SELECT kind, COUNT(*) FROM files GROUP BY kind`)
	if err != nil {
		log.Printf("总览:统计类型失败: %v", err)
		return k
	}
	defer rows.Close()
	for rows.Next() {
		var kind string
		var n int
		if err := rows.Scan(&kind, &n); err != nil {
			log.Printf("总览:读取类型统计失败: %v", err)
			break
		}
		switch kind {
		case "video":
			k.Video = n
		case "image":
			k.Image = n
		case "audio":
			k.Audio = n
		case "dir":
			k.Dir = n
		default:
			k.Other += n
		}
	}
	return k
}

// buildOverviewAlerts 生成告警列表(纯函数,便于单测)。
//   - 单库可用比例 < critPct → warn「<库名> 可用空间不足 <critPct>%」
//   - 单库可用比例 < warnPct → warn「<库名> 可用空间不足 <warnPct>%」
//     (同一库只取更严重的一条,阈值来自配置而不是写死 10/3)
//   - 索引未启用 → info「媒体索引未启用」
//   - 索引久未更新 → info「索引已 X 分钟未更新」
//
// 每条告警带稳定 ID(space:<libID>:crit / :warn、index:*),供告警 store 去重与
// 记录已读状态。最多返回 overviewMaxAlerts 条,没有告警时返回空数组(不是 null)。
func buildOverviewAlerts(libs []overviewLib, index *overviewIndexState, now time.Time, refreshSeconds int, warnPct, critPct int) []overviewAlert {
	alerts := []overviewAlert{}
	for _, lib := range libs {
		if lib.TotalMB <= 0 {
			continue // 容量未知,不做判断
		}
		freePct := float64(lib.FreeMB) / float64(lib.TotalMB) * 100
		switch {
		case critPct > 0 && freePct < float64(critPct):
			alerts = append(alerts, overviewAlert{
				ID: "space:" + lib.ID + ":crit", LibID: lib.ID, Level: "warn",
				Text: "「" + lib.Name + "」可用空间不足 " + itoa(critPct) + "%",
			})
		case warnPct > 0 && freePct < float64(warnPct):
			alerts = append(alerts, overviewAlert{
				ID: "space:" + lib.ID + ":warn", LibID: lib.ID, Level: "warn",
				Text: "「" + lib.Name + "」可用空间不足 " + itoa(warnPct) + "%",
			})
		}
	}
	if index == nil {
		alerts = append(alerts, overviewAlert{ID: "index:disabled", Level: "info", Text: "媒体索引未启用"})
	} else if index.LastScan == 0 {
		// 索引已启用但从未完成首次扫描。
		alerts = append(alerts, overviewAlert{ID: "index:firstscan", Level: "info", Text: "索引尚未完成首次扫描"})
	} else if refreshSeconds > 0 {
		stale := time.Duration(refreshSeconds*3) * time.Second
		if minStale := overviewIndexStaleMinutes * time.Minute; stale < minStale {
			stale = minStale
		}
		if elapsed := now.Sub(time.Unix(index.LastScan, 0)); elapsed > stale {
			alerts = append(alerts, overviewAlert{
				ID: "index:stale", Level: "info",
				Text: "索引已 " + itoa(int(elapsed.Minutes())) + " 分钟未更新",
			})
		}
	}
	if len(alerts) > overviewMaxAlerts {
		alerts = alerts[:overviewMaxAlerts]
	}
	return alerts
}

// filterRecentForUser 按角色过滤近期活动(纯函数,便于单测)。
// 管理员看全部;其他人只看用户名为自己的记录。
// action 为空时依次用 method、"访问" 兜底;target 为空时用 path。
func filterRecentForUser(entries []AuditEntry, username string, isAdmin bool) []overviewRecent {
	out := []overviewRecent{}
	for _, e := range entries {
		if !isAdmin && !strings.EqualFold(e.User, username) {
			continue
		}
		action := strings.TrimSpace(e.Action)
		if action == "" {
			action = strings.TrimSpace(e.Method)
		}
		if action == "" {
			action = "访问"
		}
		target := e.Target
		if target == "" {
			target = e.Path
		}
		out = append(out, overviewRecent{
			Time:   e.Time,
			User:   e.User,
			Action: action,
			Target: target,
			Status: e.Status,
		})
	}
	return out
}
