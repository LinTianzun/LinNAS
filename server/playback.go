package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// playbackMaxPerUser 限制每个账号保留的进度条数，避免无限增长。
	playbackMaxPerUser = 1000
	// playbackMinSeconds 小于该位置（或距结尾少于该值）视为“不必续播”，直接删除记录。
	playbackMinSeconds = 3.0
	playbackMaxVPath   = 4096
)

// playbackEntry 是某个账号在某个虚拟路径上的播放进度（秒）。
type playbackEntry struct {
	VPath    string  `json:"vpath"`
	Position float64 `json:"position"`
	Duration float64 `json:"duration"`
	Updated  int64   `json:"updated"`
}

// playbackDoc 是 playback.json 的落盘结构。
type playbackDoc struct {
	Users map[string][]playbackEntry `json:"users"`
}

// playbackStore 按账号保存播放进度，落盘 DataDir/playback.json。
type playbackStore struct {
	mu    sync.Mutex
	users map[string][]playbackEntry
	path  string
}

func newPlaybackStore(dataDir string) *playbackStore {
	st := &playbackStore{
		users: map[string][]playbackEntry{},
		path:  filepath.Join(dataDir, "playback.json"),
	}
	if raw, err := os.ReadFile(st.path); err == nil {
		var doc playbackDoc
		if json.Unmarshal(raw, &doc) == nil && doc.Users != nil {
			st.users = doc.Users
		}
	}
	return st
}

// saveLocked 先写临时文件再原子替换（调用方需持有 mu）。
func (p *playbackStore) saveLocked() error {
	raw, err := json.MarshalIndent(playbackDoc{Users: p.users}, "", "  ")
	if err != nil {
		return err
	}
	tmp := p.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p.path)
}

// playbackUserKey 返回当前账号的隔离键；未登录（局域网开放模式）归入 guest。
func playbackUserKey(r *http.Request) string {
	if u := userFrom(r); u != nil {
		if u.UserID != "" {
			return "id:" + u.UserID
		}
		if u.Username != "" {
			return "name:" + u.Username
		}
	}
	return "guest"
}

func validPlaybackPath(vpath string) bool {
	return vpath != "" && len(vpath) <= playbackMaxVPath &&
		strings.HasPrefix(vpath, "/") && !strings.Contains(vpath, "..")
}

func (p *playbackStore) get(user, vpath string) (playbackEntry, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, e := range p.users[user] {
		if e.VPath == vpath {
			return e, true
		}
	}
	return playbackEntry{}, false
}

// set 更新进度；位置太靠前或太接近结尾时删除记录（表示“不必续播”）。
// 返回记录与是否保留。
func (p *playbackStore) set(user, vpath string, position, duration float64) (playbackEntry, bool) {
	if position < 0 {
		position = 0
	}
	if duration < 0 {
		duration = 0
	}
	remove := position < playbackMinSeconds || (duration > 0 && duration-position < playbackMinSeconds)

	p.mu.Lock()
	defer p.mu.Unlock()
	list := p.users[user]
	idx := -1
	for i := range list {
		if list[i].VPath == vpath {
			idx = i
			break
		}
	}
	if remove {
		if idx >= 0 {
			p.users[user] = append(list[:idx], list[idx+1:]...)
			_ = p.saveLocked()
		}
		return playbackEntry{VPath: vpath}, false
	}
	e := playbackEntry{VPath: vpath, Position: position, Duration: duration, Updated: time.Now().Unix()}
	if idx >= 0 {
		list[idx] = e
	} else {
		list = append(list, e)
	}
	if len(list) > playbackMaxPerUser {
		sort.SliceStable(list, func(i, j int) bool { return list[i].Updated > list[j].Updated })
		list = list[:playbackMaxPerUser]
	}
	p.users[user] = list
	_ = p.saveLocked()
	return e, true
}

func (p *playbackStore) recent(user string, limit int) []playbackEntry {
	p.mu.Lock()
	defer p.mu.Unlock()
	list := append([]playbackEntry(nil), p.users[user]...)
	sort.SliceStable(list, func(i, j int) bool { return list[i].Updated > list[j].Updated })
	if limit > 0 && len(list) > limit {
		list = list[:limit]
	}
	return list
}

// GET /api/playback?vpath=... 返回当前账号在该路径上的进度。
func (s *Server) handlePlaybackGet(w http.ResponseWriter, r *http.Request) {
	vpath := r.URL.Query().Get("vpath")
	if vpath == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "缺少 vpath"})
		return
	}
	if !validPlaybackPath(vpath) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "vpath 不合法"})
		return
	}
	e, ok := s.playback.get(playbackUserKey(r), vpath)
	if !ok {
		writeJSON(w, http.StatusOK, map[string]any{
			"vpath": vpath, "position": 0, "duration": 0, "updated": 0, "found": false,
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"vpath": e.VPath, "position": e.Position, "duration": e.Duration, "updated": e.Updated, "found": true,
	})
}

// POST /api/playback {vpath, position, duration} 保存当前账号的进度。
func (s *Server) handlePlaybackSet(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath    string  `json:"vpath"`
		Position float64 `json:"position"`
		Duration float64 `json:"duration"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求体不是合法 JSON"})
		return
	}
	if !validPlaybackPath(req.VPath) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "vpath 不合法"})
		return
	}
	e, kept := s.playback.set(playbackUserKey(r), req.VPath, req.Position, req.Duration)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "kept": kept, "position": e.Position, "duration": e.Duration, "updated": e.Updated,
	})
}

// GET /api/playback/recent?limit=20 返回当前账号最近播放的进度列表。
func (s *Server) handlePlaybackRecent(w http.ResponseWriter, r *http.Request) {
	limit := 20
	if v, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && v > 0 && v <= 200 {
		limit = v
	}
	items := s.playback.recent(playbackUserKey(r), limit)
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}
