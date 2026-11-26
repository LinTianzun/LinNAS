package main

import (
	"net/http"
	"os"
	"strconv"
	"strings"
)

// indexUpdate 挂接一次增量索引更新(后台执行,索引不可用时为 no-op)。
// 文件被移动/改名/删除/上传/恢复/保险箱导入导出/WebDAV 写操作后调用。
func (s *Server) indexUpdate(changes ...IndexChange) {
	if s.index == nil || len(changes) == 0 {
		return
	}
	s.index.UpdatePaths(s.cfg, changes...)
}

// filterHits 只保留当前确实存在于存储库所选路径内、且不属于保险箱的条目,
// 防止索引里残留的旧路径(例如库路径改小前扫到的整盘文件)混进结果。
func (s *Server) filterHits(hits []IndexHit) []IndexHit {
	out := make([]IndexHit, 0, len(hits))
	for _, h := range hits {
		_, abs, err := s.cfg.Resolve(h.Path)
		if err != nil {
			continue
		}
		if s.inVault(abs) {
			continue
		}
		if st, serr := os.Stat(abs); serr != nil || st.IsDir() {
			continue
		}
		out = append(out, h)
	}
	return out
}

// handleSearch 按文件名搜索(全库或指定库)。
// GET /api/search?q=xxx&lib=lib1&limit=100
func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	q := r.URL.Query().Get("q")
	lib := r.URL.Query().Get("lib")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	hits, err := s.index.Search(q, lib, limit)
	if err != nil {
		writeErr(w, 500, "搜索失败: "+err.Error())
		return
	}
	writeJSON(w, 200, s.filterHits(hits))
}

// handleMedia 媒体库列表(视频/图片/音频)。
// GET /api/media?type=video&lib=&sort=name|mtime|size&offset=0&limit=100
func (s *Server) handleMedia(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	kind := r.URL.Query().Get("type")
	if kind == "" {
		kind = "video"
	}
	lib := r.URL.Query().Get("lib")
	sortBy := r.URL.Query().Get("sort")
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	hidden := mediaHiddenPrefixes(s.cfg)
	hits, err := s.index.Media(kind, lib, sortBy, offset, limit, hidden)
	if err != nil {
		writeErr(w, 500, "查询失败: "+err.Error())
		return
	}
	writeJSON(w, 200, s.filterHits(hits))
}

// handleReindex 触发后台重建索引。
func (s *Server) handleReindex(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	s.index.ScanAll(s.cfg)
	writeJSON(w, 200, map[string]bool{"started": true})
}

// handleIndexStatus 返回扫描状态与各类型计数。
func (s *Server) handleIndexStatus(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	st := s.index.Status()
	st["stats"] = s.index.Stats()
	writeJSON(w, 200, st)
}

// handleIndexRefresh POST /api/index/refresh {lib} — 立即触发一次对账(可指定单库)。
func (s *Server) handleIndexRefresh(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	var req struct {
		Lib string `json:"lib"`
	}
	_ = readJSON(r, &req) // body 可空
	if libID := strings.TrimSpace(req.Lib); libID != "" {
		s.index.RescanLib(s.cfg, libID)
	} else {
		s.index.ReconcileAll(s.cfg)
	}
	writeJSON(w, 200, map[string]bool{"started": true})
}
