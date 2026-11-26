package main

import (
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ============================================================
//  存储空间趋势:读取 storage_snapshots 里的按小时采样。
//  快照由 Index.Snapshot() 在扫描/对账成功后写入(见 index.go)。
// ============================================================

// trendPoint 是趋势上的一个采样点(整点)。
type trendPoint struct {
	T     int64 `json:"t"` // unix 秒（整点）
	Bytes int64 `json:"bytes"`
	Files int   `json:"files"`
	Delta int64 `json:"delta"` // 与上一个采样点的字节差；首个点为 0
}

// trendLib 是一个存储库的趋势序列。
type trendLib struct {
	ID     string       `json:"id"`
	Name   string       `json:"name"`
	Points []trendPoint `json:"points"`
}

// trendResp 是 GET /api/storage/trend 的响应。
type trendResp struct {
	Days  int        `json:"days"`
	Libs  []trendLib `json:"libs"`
	Total struct {
		Points         []trendPoint `json:"points"`
		WeekDeltaBytes int64        `json:"weekDeltaBytes"`
		WeekDeltaPct   float64      `json:"weekDeltaPct"`
	} `json:"total"`
}

// handleStorageTrend GET /api/storage/trend?days=7|30|90&lib=<可选>
// 没有快照时返回空数组(不是 null),索引不可用时同样返回空数据而不报错。
func (s *Server) handleStorageTrend(w http.ResponseWriter, r *http.Request) {
	// days 缺省 30,显式给出时 clamp 1..400。
	days := 30
	if v := strings.TrimSpace(r.URL.Query().Get("days")); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			days = parsed
		}
	}
	if days < 1 {
		days = 1
	}
	if days > 400 {
		days = 400
	}
	libID := r.URL.Query().Get("lib")

	resp := trendResp{Days: days, Libs: []trendLib{}}
	resp.Total.Points = []trendPoint{}
	if s.index == nil {
		writeJSON(w, 200, resp)
		return
	}
	tr, err := s.index.Trend(days, libID)
	if err != nil {
		writeErr(w, 500, "读取存储空间趋势失败: "+err.Error())
		return
	}
	tr.Days = days
	if tr.Libs == nil {
		tr.Libs = []trendLib{}
	}
	if tr.Total.Points == nil {
		tr.Total.Points = []trendPoint{}
	}
	// 库名按配置补全,并按配置顺序排列(配置里没有的库排在最后)。
	if s.cfg != nil {
		names := map[string]string{}
		order := map[string]int{}
		for i, lib := range s.cfg.Libraries {
			names[lib.ID] = lib.Name
			if _, ok := order[lib.ID]; !ok {
				order[lib.ID] = i
			}
		}
		for i := range tr.Libs {
			if n := names[tr.Libs[i].ID]; n != "" {
				tr.Libs[i].Name = n
			} else if tr.Libs[i].Name == "" {
				tr.Libs[i].Name = tr.Libs[i].ID
			}
		}
		sort.SliceStable(tr.Libs, func(i, j int) bool {
			oi, iok := order[tr.Libs[i].ID]
			oj, jok := order[tr.Libs[j].ID]
			if iok != jok {
				return iok
			}
			if iok {
				return oi < oj
			}
			return tr.Libs[i].ID < tr.Libs[j].ID
		})
	}
	writeJSON(w, 200, tr)
}

// Trend 读取最近 days 天的快照,按库聚合为趋势序列(纯读,可在测试中直接调用)。
// libID 为空表示全部库;没有数据时返回空数组(不是 null)。
func (ix *Index) Trend(days int, libID string) (trendResp, error) {
	if days <= 0 {
		days = 30
	}
	if days > 400 {
		days = 400
	}
	resp := trendResp{Days: days, Libs: []trendLib{}}
	resp.Total.Points = []trendPoint{}

	cutoff := time.Now().Add(-time.Duration(days) * 24 * time.Hour).Unix()
	where := "ts >= ?"
	args := []any{cutoff}
	if libID != "" {
		where += " AND lib_id = ?"
		args = append(args, libID)
	}
	rows, err := ix.db.Query(
		`SELECT lib_id, ts, bytes, files FROM storage_snapshots WHERE `+where+
			` ORDER BY ts ASC`, args...)
	if err != nil {
		return resp, err
	}
	defer rows.Close()

	// 1) 按库收集采样点(查询已按 ts 升序,序列天然有序)。
	seqs := map[string][]trendPoint{}
	var libOrder []string
	// 2) 同一 ts 的各库字节/文件数求和,得到总序列。
	totals := map[int64]*trendPoint{}
	var tsOrder []int64
	for rows.Next() {
		var id string
		var ts, bytes int64
		var files int
		if err := rows.Scan(&id, &ts, &bytes, &files); err != nil {
			return resp, err
		}
		if _, ok := seqs[id]; !ok {
			libOrder = append(libOrder, id)
		}
		seqs[id] = append(seqs[id], trendPoint{T: ts, Bytes: bytes, Files: files})
		if p, ok := totals[ts]; ok {
			p.Bytes += bytes
			p.Files += files
		} else {
			totals[ts] = &trendPoint{T: ts, Bytes: bytes, Files: files}
			tsOrder = append(tsOrder, ts)
		}
	}
	if err := rows.Err(); err != nil {
		return resp, err
	}

	// 3) 每个库内计算 Delta(当前点 - 前一点,首点为 0)。
	for _, id := range libOrder {
		points := seqs[id]
		applyTrendDeltas(points)
		resp.Libs = append(resp.Libs, trendLib{ID: id, Points: points})
	}
	// 库顺序固定为 ID 升序,handler 再按配置顺序重排。
	sort.SliceStable(resp.Libs, func(i, j int) bool { return resp.Libs[i].ID < resp.Libs[j].ID })

	// 4) 总序列按 ts 升序并计算 Delta。
	sort.Slice(tsOrder, func(i, j int) bool { return tsOrder[i] < tsOrder[j] })
	totalPoints := make([]trendPoint, 0, len(tsOrder))
	for _, ts := range tsOrder {
		totalPoints = append(totalPoints, *totals[ts])
	}
	applyTrendDeltas(totalPoints)
	resp.Total.Points = totalPoints

	// 5) 本周变化:最后一点与 7 天前最近一点对比;不足 7 天则与最早一点比。
	if n := len(totalPoints); n > 0 {
		last := totalPoints[n-1]
		base := totalPoints[0]
		if last.T-base.T >= 7*86400 {
			target := last.T - 7*86400
			best := int64(math.MaxInt64)
			for _, p := range totalPoints {
				diff := p.T - target
				if diff < 0 {
					diff = -diff
				}
				if diff < best {
					best = diff
					base = p
				}
			}
		}
		delta := last.Bytes - base.Bytes
		resp.Total.WeekDeltaBytes = delta
		if base.Bytes > 0 {
			// 保留 1 位小数。
			resp.Total.WeekDeltaPct = math.Round(float64(delta)/float64(base.Bytes)*1000) / 10
		}
	}
	return resp, nil
}

// applyTrendDeltas 在原序列上计算相邻点的字节差(首点为 0)。
func applyTrendDeltas(points []trendPoint) {
	var prev int64
	for i := range points {
		if i == 0 {
			points[i].Delta = 0
		} else {
			points[i].Delta = points[i].Bytes - prev
		}
		prev = points[i].Bytes
	}
}
