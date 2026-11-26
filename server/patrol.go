package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
//  批次 2:校验巡检(quick / deep)
//  - file_sig 表记录每个文件的签名基线;巡检遍历索引中的文件计算当前签名,
//    与基线比较后只「报告」变化/缺失/读取失败,绝不修改任何文件或索引。
//  - 全局单飞:同一时刻只允许一个巡检任务;支持取消。
//  - 历史落 DataDir/patrol.json,仅保留最近 20 条。
// ============================================================

const (
	// patrolHistoryMax 是 patrol.json 保留的历史条数上限。
	patrolHistoryMax = 20
	// patrolEdgeChunk 是 quick 签名读取的首/尾窗口大小(64KB)。
	patrolEdgeChunk = 64 * 1024
	// patrolDeepRate 是 deep 全文件哈希的限速(约 20MB/s)。
	patrolDeepRate = 20 * 1024 * 1024
	// patrolDeepChunk 是 deep 每次读取的块大小(1MB)。
	patrolDeepChunk = 1 << 20
)

// errPatrolCancelled 表示巡检被用户取消(用于从深函数向上传递取消信号)。
var errPatrolCancelled = errors.New("patrol cancelled")

// patrolItem 是一条巡检结果(内容已变化 / 文件缺失 / 读取失败)。
// 路径为虚拟路径(如 /lib1/dir/a.mp4),name 为文件名。
type patrolItem struct {
	LibID   string `json:"libId"`
	Path    string `json:"path"`
	Name    string `json:"name"`
	Reason  string `json:"reason"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
}

// patrolState 是单飞巡检的当前状态(受 patrolManager.mu 保护)。
// Cancel 只在内部用于取消判断,不对外暴露。
type patrolState struct {
	Running    bool         `json:"running"`
	Mode       string       `json:"mode"`
	LibID      string       `json:"libId"`
	StartedAt  int64        `json:"startedAt"`
	FinishedAt int64        `json:"finishedAt"`
	Done       int          `json:"done"`
	Total      int          `json:"total"`
	Current    string       `json:"current"`
	Changed    []patrolItem `json:"changed"`
	Missing    []patrolItem `json:"missing"`
	Failed     []patrolItem `json:"failed"`
	Cancel     bool         `json:"-"`
	LastError  string       `json:"lastError"`
}

// patrolManager 持有单飞状态、取消通道与历史文件路径。
type patrolManager struct {
	mu       sync.Mutex
	st       patrolState
	cancelCh chan struct{}
	path     string
}

// newPatrolManager 创建巡检管理器,历史落 dataDir/patrol.json。
func newPatrolManager(dataDir string) *patrolManager {
	return &patrolManager{
		path: filepath.Join(dataDir, "patrol.json"),
		st:   emptyPatrolState(),
	}
}

// emptyPatrolState 返回各列表为空的初始状态(保证 JSON 序列化为 [] 而不是 null)。
func emptyPatrolState() patrolState {
	return patrolState{
		Changed: []patrolItem{},
		Missing: []patrolItem{},
		Failed:  []patrolItem{},
	}
}

// patrolHistoryItem 是 patrol.json 里的一条历史记录。
type patrolHistoryItem struct {
	StartedAt   int64  `json:"startedAt"`
	FinishedAt  int64  `json:"finishedAt"`
	Mode        string `json:"mode"`
	LibID       string `json:"libId"`
	Total       int    `json:"total"`
	Changed     int    `json:"changed"`
	Missing     int    `json:"missing"`
	Failed      int    `json:"failed"`
	DurationSec int64  `json:"durationSec"`
	Cancelled   bool   `json:"cancelled"`
}

// patrolHistoryDoc 是 patrol.json 的文档结构。
type patrolHistoryDoc struct {
	Items []patrolHistoryItem `json:"items"`
}

// loadHistory 读取历史文件;不存在或损坏时返回空列表。
func (m *patrolManager) loadHistory() patrolHistoryDoc {
	doc := patrolHistoryDoc{Items: []patrolHistoryItem{}}
	raw, err := os.ReadFile(m.path)
	if err != nil {
		return doc
	}
	var loaded patrolHistoryDoc
	if json.Unmarshal(raw, &loaded) == nil && loaded.Items != nil {
		doc.Items = loaded.Items
	}
	return doc
}

// appendHistory 追加一条历史并落盘,只保留最近 patrolHistoryMax 条。
func (m *patrolManager) appendHistory(item patrolHistoryItem) {
	doc := m.loadHistory()
	doc.Items = append(doc.Items, item)
	if len(doc.Items) > patrolHistoryMax {
		doc.Items = doc.Items[len(doc.Items)-patrolHistoryMax:]
	}
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(m.path, raw, 0o644)
}

// readHistory 返回最近 limit 条历史(最新在前)。
func (m *patrolManager) readHistory(limit int) []patrolHistoryItem {
	doc := m.loadHistory()
	n := len(doc.Items)
	if limit <= 0 || limit > n {
		limit = n
	}
	out := make([]patrolHistoryItem, 0, limit)
	for i := n - 1; i >= n-limit && i >= 0; i-- {
		out = append(out, doc.Items[i])
	}
	return out
}

// quickSig 计算快速签名:sha256(size 十进制 + 前 64KB + 后 64KB)。
// 小文件(≤ 128KB)首尾窗口重叠,只读取一次,避免重复读盘。
func quickSig(p string, size int64) (string, error) {
	h := sha256.New()
	_, _ = fmt.Fprintf(h, "%d", size)
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()

	if size <= 2*patrolEdgeChunk {
		// 小文件:一次读入(避免重复读盘),再按首/尾窗口写入哈希。
		// 文件不超过一个窗口时首=尾=整个文件,只写一次(去重)。
		buf := make([]byte, size)
		if size > 0 {
			n, rerr := io.ReadFull(f, buf)
			if rerr != nil && rerr != io.ErrUnexpectedEOF && rerr != io.EOF {
				return "", rerr
			}
			buf = buf[:n]
		}
		head := buf
		if int64(len(head)) > patrolEdgeChunk {
			head = head[:patrolEdgeChunk]
		}
		_, _ = h.Write(head)
		if int64(len(buf)) > patrolEdgeChunk {
			_, _ = h.Write(buf[int64(len(buf))-patrolEdgeChunk:])
		}
	} else {
		head := make([]byte, patrolEdgeChunk)
		n, rerr := io.ReadFull(f, head)
		if rerr != nil && rerr != io.ErrUnexpectedEOF && rerr != io.EOF {
			return "", rerr
		}
		_, _ = h.Write(head[:n])
		if _, serr := f.Seek(-patrolEdgeChunk, io.SeekEnd); serr != nil {
			return "", serr
		}
		tail := make([]byte, patrolEdgeChunk)
		n, rerr = io.ReadFull(f, tail)
		if rerr != nil && rerr != io.ErrUnexpectedEOF && rerr != io.EOF {
			return "", rerr
		}
		_, _ = h.Write(tail[:n])
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// deepSig 计算整文件 SHA-256,读取过程约 20MB/s 限速,并响应取消通道。
func deepSig(p string, cancel <-chan struct{}) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	buf := make([]byte, patrolDeepChunk)
	start := time.Now()
	var read int64
	for {
		select {
		case <-cancel:
			return "", errPatrolCancelled
		default:
		}
		n, rerr := f.Read(buf)
		if n > 0 {
			_, _ = h.Write(buf[:n])
			read += int64(n)
			// 限速:已读字节数 / 目标速率 应经过的时间;不足则等待。
			expected := time.Duration(float64(read) / float64(patrolDeepRate) * float64(time.Second))
			if elapsed := time.Since(start); elapsed < expected {
				timer := time.NewTimer(expected - elapsed)
				select {
				case <-cancel:
					timer.Stop()
					return "", errPatrolCancelled
				case <-timer.C:
				}
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return "", rerr
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// patrolCandidate 是索引里待巡检的一个文件。
type patrolCandidate struct {
	libID   string
	vpath   string
	name    string
	size    int64
	modTime int64
}

// patrolResult 是一次巡检的纯结果(不涉及全局状态)。
type patrolResult struct {
	Total     int
	Changed   []patrolItem
	Missing   []patrolItem
	Failed    []patrolItem
	Cancelled bool
	LastError string
}

// patrolOnce 执行一次巡检扫描并把结果返回(不修改全局状态,便于单测直接调用)。
// onProgress 可选,用于回写进度(done/total/current)。
func (s *Server) patrolOnce(mode, libID string, cancel <-chan struct{}, onProgress func(done, total int, current string)) patrolResult {
	res := patrolResult{Changed: []patrolItem{}, Missing: []patrolItem{}, Failed: []patrolItem{}}
	if s.index == nil {
		res.LastError = "索引未启用"
		return res
	}
	if s.cfg == nil {
		res.LastError = "配置不可用"
		return res
	}

	// 1) 枚举索引中该库(或全部)的文件(is_dir=0)。
	q := `SELECT lib_id,path,name,size,mod_time FROM files WHERE is_dir = 0`
	var args []any
	if libID != "" {
		q += ` AND lib_id = ?`
		args = append(args, libID)
	}
	q += ` ORDER BY lib_id, path`
	rows, err := s.index.db.Query(q, args...)
	if err != nil {
		res.LastError = "读取索引失败: " + err.Error()
		return res
	}
	var cands []patrolCandidate
	for rows.Next() {
		var c patrolCandidate
		if err := rows.Scan(&c.libID, &c.vpath, &c.name, &c.size, &c.modTime); err != nil {
			_ = rows.Close()
			res.LastError = "读取索引失败: " + err.Error()
			return res
		}
		cands = append(cands, c)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		res.LastError = "读取索引失败: " + err.Error()
		return res
	}
	_ = rows.Close()
	res.Total = len(cands)
	if onProgress != nil {
		onProgress(0, res.Total, "")
	}

	// 2) 载入已有签名基线。
	sigMap, err := s.loadFileSigs(libID)
	if err != nil {
		res.LastError = "读取签名基线失败: " + err.Error()
		return res
	}

	now := time.Now().Unix()
	// 3) 逐个文件计算当前签名并与基线比较。
	for i := range cands {
		c := cands[i]
		select {
		case <-cancel:
			res.Cancelled = true
			return res
		default:
		}
		item := patrolItem{LibID: c.libID, Path: c.vpath, Name: c.name, Size: c.size, ModTime: c.modTime}
		_, abs, rerr := s.cfg.Resolve(c.vpath)
		if rerr != nil {
			item.Reason = "路径无效: " + rerr.Error()
			res.Failed = append(res.Failed, item)
			if onProgress != nil {
				onProgress(i+1, res.Total, c.name)
			}
			continue
		}
		info, serr := os.Stat(abs)
		if serr != nil {
			if os.IsNotExist(serr) {
				item.Reason = "文件缺失"
				res.Missing = append(res.Missing, item)
			} else {
				item.Reason = "读取失败: " + serr.Error()
				res.Failed = append(res.Failed, item)
			}
			if onProgress != nil {
				onProgress(i+1, res.Total, c.name)
			}
			continue
		}
		if info.IsDir() {
			// 索引标记为文件但磁盘上是目录:视为结构变化。
			item.Size = 0
			item.ModTime = info.ModTime().Unix()
			item.Reason = "内容已变化"
			res.Changed = append(res.Changed, item)
			if onProgress != nil {
				onProgress(i+1, res.Total, c.name)
			}
			continue
		}

		actualSize := info.Size()
		actualMod := info.ModTime().Unix()
		var sig string
		if mode == "deep" {
			sig, err = deepSig(abs, cancel)
		} else {
			sig, err = quickSig(abs, actualSize)
		}
		if err != nil {
			if errors.Is(err, errPatrolCancelled) {
				res.Cancelled = true
				return res
			}
			item.Reason = "读取失败: " + err.Error()
			res.Failed = append(res.Failed, item)
			if onProgress != nil {
				onProgress(i+1, res.Total, c.name)
			}
			continue
		}

		item.Size = actualSize
		item.ModTime = actualMod
		// 签名带算法前缀,避免 quick / deep 交替巡检时被互相当成「内容已变化」。
		tagged := mode + ":" + sig
		key := c.libID + "\x00" + c.vpath
		old, ok := sigMap[key]
		switch {
		case !ok || !strings.HasPrefix(old, mode+":"):
			// 首轮基线(或该文件首次以当前模式巡检):只入库签名,不计入 Changed。
			if werr := s.upsertFileSig(c.libID, c.vpath, actualSize, actualMod, tagged, now); werr != nil {
				item.Reason = "写入签名失败: " + werr.Error()
				res.Failed = append(res.Failed, item)
			}
		case old != tagged:
			item.Reason = "内容已变化"
			res.Changed = append(res.Changed, item)
			// 更新基线,避免下次重复报告同一变化。
			_ = s.upsertFileSig(c.libID, c.vpath, actualSize, actualMod, tagged, now)
		default:
			// 一致:仅更新 sig_at。
			_, _ = s.index.db.Exec(`UPDATE file_sig SET sig_at = ? WHERE lib_id = ? AND path = ?`,
				now, c.libID, c.vpath)
		}
		if onProgress != nil {
			onProgress(i+1, res.Total, c.name)
		}
	}
	return res
}

// loadFileSigs 载入签名基线(键为 libID+"\x00"+virtualPath)。
func (s *Server) loadFileSigs(libID string) (map[string]string, error) {
	out := map[string]string{}
	q := `SELECT lib_id, path, sig FROM file_sig`
	var args []any
	if libID != "" {
		q += ` WHERE lib_id = ?`
		args = append(args, libID)
	}
	rows, err := s.index.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var lib, p, sig string
		if err := rows.Scan(&lib, &p, &sig); err != nil {
			return nil, err
		}
		out[lib+"\x00"+p] = sig
	}
	return out, rows.Err()
}

// upsertFileSig 写入/更新一个文件的签名基线。
func (s *Server) upsertFileSig(libID, vpath string, size, modTime int64, sig string, sigAt int64) error {
	_, err := s.index.db.Exec(`INSERT OR REPLACE INTO file_sig
		(lib_id,path,size,mod_time,sig,sig_at) VALUES (?,?,?,?,?,?)`,
		libID, vpath, size, modTime, sig, sigAt)
	return err
}

// runPatrol 在后台执行一次巡检:先扫描,再把结果写回全局状态并落历史。
func (s *Server) runPatrol(mode, libID string, cancel <-chan struct{}, started time.Time) {
	res := s.patrolOnce(mode, libID, cancel, func(done, total int, current string) {
		m := s.patrol
		m.mu.Lock()
		m.st.Done = done
		m.st.Total = total
		m.st.Current = current
		m.mu.Unlock()
	})

	finished := time.Now()
	m := s.patrol
	m.mu.Lock()
	m.st.Running = false
	m.st.FinishedAt = finished.Unix()
	m.st.Done = res.Total
	m.st.Total = res.Total
	m.st.Current = ""
	m.st.Changed = res.Changed
	m.st.Missing = res.Missing
	m.st.Failed = res.Failed
	m.st.Cancel = res.Cancelled
	if res.LastError != "" {
		m.st.LastError = res.LastError
	}
	histMode := m.st.Mode
	histLib := m.st.LibID
	m.cancelCh = nil
	m.mu.Unlock()

	m.appendHistory(patrolHistoryItem{
		StartedAt:   started.Unix(),
		FinishedAt:  finished.Unix(),
		Mode:        histMode,
		LibID:       histLib,
		Total:       res.Total,
		Changed:     len(res.Changed),
		Missing:     len(res.Missing),
		Failed:      len(res.Failed),
		DurationSec: int64(finished.Sub(started).Seconds()),
		Cancelled:   res.Cancelled,
	})
}

// handlePatrolStart POST /api/storage/patrol/start?mode=quick|deep&lib= — 启动单飞巡检。
func (s *Server) handlePatrolStart(w http.ResponseWriter, r *http.Request) {
	if s.index == nil || s.patrol == nil {
		writeErr(w, 503, "巡检未启用")
		return
	}
	mode := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("mode")))
	if mode != "quick" && mode != "deep" {
		writeErr(w, 400, "mode 必须为 quick 或 deep")
		return
	}
	libID := strings.TrimSpace(r.URL.Query().Get("lib"))
	if strings.EqualFold(libID, "all") {
		libID = ""
	}

	m := s.patrol
	m.mu.Lock()
	if m.st.Running {
		m.mu.Unlock()
		writeErr(w, 409, "patrol already running")
		return
	}
	started := time.Now()
	m.st = patrolState{
		Running:   true,
		Mode:      mode,
		LibID:     libID,
		StartedAt: started.Unix(),
		Changed:   []patrolItem{},
		Missing:   []patrolItem{},
		Failed:    []patrolItem{},
	}
	cancelCh := make(chan struct{})
	m.cancelCh = cancelCh
	m.mu.Unlock()

	go s.runPatrol(mode, libID, cancelCh, started)
	writeJSON(w, 200, map[string]any{"ok": true, "mode": mode, "lib": libID})
}

// handlePatrolStatus GET /api/storage/patrol/status — 当前巡检状态与结果。
func (s *Server) handlePatrolStatus(w http.ResponseWriter, r *http.Request) {
	if s.patrol == nil {
		writeJSON(w, 200, emptyPatrolState())
		return
	}
	m := s.patrol
	m.mu.Lock()
	st := m.st
	// 复制切片,避免与后台写入竞争。
	st.Changed = append([]patrolItem{}, m.st.Changed...)
	st.Missing = append([]patrolItem{}, m.st.Missing...)
	st.Failed = append([]patrolItem{}, m.st.Failed...)
	m.mu.Unlock()
	if st.Changed == nil {
		st.Changed = []patrolItem{}
	}
	if st.Missing == nil {
		st.Missing = []patrolItem{}
	}
	if st.Failed == nil {
		st.Failed = []patrolItem{}
	}
	writeJSON(w, 200, st)
}

// handlePatrolCancel POST /api/storage/patrol/cancel — 请求取消当前巡检(无任务时也返回 ok)。
func (s *Server) handlePatrolCancel(w http.ResponseWriter, r *http.Request) {
	if s.patrol != nil {
		m := s.patrol
		m.mu.Lock()
		if m.st.Running {
			m.st.Cancel = true
			if m.cancelCh != nil {
				close(m.cancelCh)
				m.cancelCh = nil
			}
		}
		m.mu.Unlock()
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// handlePatrolLast GET /api/storage/patrol/last?limit=20 — 最近巡检历史(最新在前)。
func (s *Server) handlePatrolLast(w http.ResponseWriter, r *http.Request) {
	limit := patrolHistoryMax
	if v := strings.TrimSpace(r.URL.Query().Get("limit")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	if s.patrol == nil {
		writeJSON(w, 200, map[string]any{"items": []patrolHistoryItem{}})
		return
	}
	writeJSON(w, 200, map[string]any{"items": s.patrol.readHistory(limit)})
}
