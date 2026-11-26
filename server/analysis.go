package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
//  存储空间分析:汇总 / 大文件 Top N / 重复文件候选
//  重复文件扫描为后台异步,一次只允许一个;结果缓存供轮询。
//  删除复用现有 POST /api/delete(移入回收站),必须人工确认。
// ============================================================

// dupFile 是重复文件组里的一个文件(路径为虚拟路径)。
type dupFile struct {
	LibID   string `json:"libId"`
	Path    string `json:"path"`
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
	Kind    string `json:"kind"`
}

// dupGroup 是一组「同大小且抽样哈希相同」的重复文件。
type dupGroup struct {
	Size  int64     `json:"size"`
	Hash  string    `json:"hash"`
	Files []dupFile `json:"files"`
}

// dupCandidate 是扫描阶段的内部结构:虚拟路径 + 绝对路径(用于抽样哈希)。
type dupCandidate struct {
	LibID   string
	VPath   string
	Name    string
	Size    int64
	ModTime int64
	Kind    string
	Abs     string
}

// dupScanState 保存重复扫描的进度与结果(受 mu 保护,后台写、HTTP 读)。
type dupScanState struct {
	mu              sync.Mutex
	scanning        bool
	done            int
	total           int
	scannedAt       int64
	minSize         int64
	groups          []dupGroup
	candidateGroups int
	candidateFiles  int
	wastedBytes     int64
}

// maxDupCandidateFiles 是参与哈希计算的文件数量上限,避免爆量。
const maxDupCandidateFiles = 20000

// dupSampleChunk 是抽样哈希每段读取的字节数。
const dupSampleChunk = 4096

func newDupScanState() *dupScanState {
	return &dupScanState{groups: []dupGroup{}}
}

// handleStorageSummary GET /api/storage/summary — 每库文件数与占用字节。
func (s *Server) handleStorageSummary(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	type libStat struct {
		ID    string `json:"id"`
		Name  string `json:"name"`
		Path  string `json:"path"`
		Files int64  `json:"files"`
		Bytes int64  `json:"bytes"`
	}

	// 先按库聚合索引行(is_dir=0)。
	type agg struct{ files, bytes int64 }
	byLib := map[string]agg{}
	rows, err := s.index.db.Query(
		`SELECT lib_id, COUNT(*), COALESCE(SUM(size), 0) FROM files WHERE is_dir = 0 GROUP BY lib_id`)
	if err != nil {
		writeErr(w, 500, "统计失败: "+err.Error())
		return
	}
	for rows.Next() {
		var id string
		var a agg
		if err := rows.Scan(&id, &a.files, &a.bytes); err != nil {
			_ = rows.Close()
			writeErr(w, 500, "统计失败: "+err.Error())
			return
		}
		byLib[id] = a
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		writeErr(w, 500, "统计失败: "+err.Error())
		return
	}
	_ = rows.Close()

	libs := make([]libStat, 0, len(s.cfg.Libraries))
	var totalFiles, totalBytes int64
	for _, lib := range s.cfg.Libraries {
		a := byLib[lib.ID]
		libs = append(libs, libStat{ID: lib.ID, Name: lib.Name, Path: lib.Path, Files: a.files, Bytes: a.bytes})
		totalFiles += a.files
		totalBytes += a.bytes
	}
	writeJSON(w, 200, map[string]any{
		"libs":       libs,
		"totalFiles": totalFiles,
		"totalBytes": totalBytes,
	})
}

// handleStorageLarge GET /api/storage/large?lib=&limit=100 — 全库(或指定库)最大文件。
func (s *Server) handleStorageLarge(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	libID := r.URL.Query().Get("lib")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 100
	}
	if limit > 500 {
		limit = 500
	}

	where := "is_dir = 0"
	args := []any{}
	if libID != "" {
		where += " AND lib_id = ?"
		args = append(args, libID)
	}
	args = append(args, limit)
	rows, err := s.index.db.Query(
		`SELECT lib_id,path,name,size,mod_time,kind FROM files WHERE `+where+
			` ORDER BY size DESC LIMIT ?`, args...)
	if err != nil {
		writeErr(w, 500, "查询失败: "+err.Error())
		return
	}
	defer rows.Close()
	items, err := scanHits(rows)
	if err != nil {
		writeErr(w, 500, "查询失败: "+err.Error())
		return
	}
	var totalSize int64
	for _, it := range items {
		totalSize += it.Size
	}
	writeJSON(w, 200, map[string]any{
		"items":     items,
		"count":     len(items),
		"totalSize": totalSize,
	})
}

// handleStorageDupScan POST /api/storage/duplicates/scan {lib,minSizeMB} — 启动后台扫描。
func (s *Server) handleStorageDupScan(w http.ResponseWriter, r *http.Request) {
	if s.index == nil {
		writeErr(w, 503, "索引未启用")
		return
	}
	var req struct {
		Lib       string `json:"lib"`
		MinSizeMB int    `json:"minSizeMB"`
	}
	// body 可空;解析错误时用默认值。
	_ = readJSON(r, &req)
	if req.MinSizeMB < 0 {
		req.MinSizeMB = 0
	}
	if req.MinSizeMB > 1024*1024 {
		req.MinSizeMB = 1024 * 1024
	}
	if req.MinSizeMB == 0 {
		req.MinSizeMB = 1
	}
	minSize := int64(req.MinSizeMB) * 1024 * 1024

	s.dup.mu.Lock()
	if s.dup.scanning {
		s.dup.mu.Unlock()
		writeJSON(w, 200, map[string]bool{"started": false, "busy": true})
		return
	}
	s.dup.scanning = true
	s.dup.done = 0
	s.dup.total = 0
	s.dup.minSize = minSize
	s.dup.groups = []dupGroup{}
	s.dup.candidateGroups = 0
	s.dup.candidateFiles = 0
	s.dup.wastedBytes = 0
	s.dup.mu.Unlock()

	go s.runDupScan(req.Lib, minSize)
	writeJSON(w, 200, map[string]bool{"started": true})
}

// handleStorageDupStatus GET /api/storage/duplicates — 进度与结果。
func (s *Server) handleStorageDupStatus(w http.ResponseWriter, r *http.Request) {
	s.dup.mu.Lock()
	groups := s.dup.groups
	if groups == nil {
		groups = []dupGroup{}
	}
	resp := map[string]any{
		"scanning":        s.dup.scanning,
		"progress":        map[string]int{"done": s.dup.done, "total": s.dup.total},
		"scannedAt":       s.dup.scannedAt,
		"minSize":         s.dup.minSize,
		"groups":          groups,
		"candidateGroups": s.dup.candidateGroups,
		"candidateFiles":  s.dup.candidateFiles,
		"wastedBytes":     s.dup.wastedBytes,
	}
	s.dup.mu.Unlock()
	writeJSON(w, 200, resp)
}

// runDupScan 执行一次后台重复扫描:按大小分组 → 抽样哈希 → 合并最终组。
func (s *Server) runDupScan(libID string, minSize int64) {
	defer func() {
		s.dup.mu.Lock()
		s.dup.scanning = false
		s.dup.scannedAt = time.Now().Unix()
		s.dup.mu.Unlock()
	}()

	// 1) 只取「同大小至少 2 个」的候选(用子查询在 SQLite 里先筛)。
	base := "is_dir = 0 AND size >= ?"
	args := []any{minSize}
	inner := "SELECT size FROM files WHERE is_dir = 0 AND size >= ?"
	iargs := []any{minSize}
	if libID != "" {
		base += " AND lib_id = ?"
		args = append(args, libID)
		inner += " AND lib_id = ?"
		iargs = append(iargs, libID)
	}
	query := `SELECT lib_id,path,name,size,mod_time,kind FROM files WHERE ` + base +
		` AND size IN (` + inner + ` GROUP BY size HAVING COUNT(*) >= 2) ORDER BY size DESC`
	args = append(args, iargs...)

	rows, err := s.index.db.Query(query, args...)
	if err != nil {
		log.Printf("重复文件扫描查询失败: %v", err)
		return
	}
	var candidates []dupCandidate
	for rows.Next() {
		var f dupCandidate
		if err := rows.Scan(&f.LibID, &f.VPath, &f.Name, &f.Size, &f.ModTime, &f.Kind); err != nil {
			continue
		}
		if len(candidates) >= maxDupCandidateFiles {
			continue // 超出上限:仍继续读行但不再收集(保持结果稳定)
		}
		_, abs, rerr := s.cfg.Resolve(f.VPath)
		if rerr != nil {
			continue
		}
		f.Abs = abs
		candidates = append(candidates, f)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		log.Printf("重复文件扫描读取失败: %v", err)
		return
	}
	_ = rows.Close()

	// 2)+3) 对候选文件抽样哈希并合并最终组,过程中更新进度。
	groups := groupDuplicates(candidates, func(done, total int) {
		s.dup.mu.Lock()
		s.dup.done = done
		s.dup.total = total
		s.dup.mu.Unlock()
	})

	// 4) 汇总并写回结果。
	var candidateFiles int
	var wastedBytes int64
	for _, g := range groups {
		candidateFiles += len(g.Files)
		wastedBytes += g.Size * int64(len(g.Files)-1)
	}
	s.dup.mu.Lock()
	s.dup.groups = groups
	s.dup.candidateGroups = len(groups)
	s.dup.candidateFiles = candidateFiles
	s.dup.wastedBytes = wastedBytes
	s.dup.mu.Unlock()
	log.Printf("重复文件扫描完成: %d 组 / %d 个文件 / 可节省 %d 字节", len(groups), candidateFiles, wastedBytes)
}

// sampleHashOfFile 计算文件的抽样哈希:size + 至多 3 段(f头/中间/尾)各 4096 字节。
// 文件小于 3 段时退化为读取全部(此时 size 已足够小,整体读入)。
func sampleHashOfFile(p string, size int64) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	var sz [8]byte
	binary.LittleEndian.PutUint64(sz[:], uint64(size))
	_, _ = h.Write(sz[:])

	const chunk = dupSampleChunk
	if size <= int64(chunk)*3 {
		buf := make([]byte, size)
		if size > 0 {
			n, rerr := io.ReadFull(f, buf)
			if rerr != nil && rerr != io.ErrUnexpectedEOF && rerr != io.EOF {
				return "", rerr
			}
			_, _ = h.Write(buf[:n])
		}
	} else {
		offsets := []int64{0, size/2 - chunk/2, size - chunk}
		buf := make([]byte, chunk)
		for _, off := range offsets {
			n, rerr := f.ReadAt(buf, off)
			if rerr != nil && rerr != io.EOF {
				return "", rerr
			}
			_, _ = h.Write(buf[:n])
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// groupDuplicates 按 size 分组 → 对 size 组内文件抽样哈希 → 合并成最终重复组。
// onProgress(可选)在每个文件哈希后回调 done/total。
func groupDuplicates(files []dupCandidate, onProgress func(done, total int)) []dupGroup {
	bySize := map[int64][]dupCandidate{}
	var sizes []int64
	for _, f := range files {
		if _, ok := bySize[f.Size]; !ok {
			sizes = append(sizes, f.Size)
		}
		bySize[f.Size] = append(bySize[f.Size], f)
	}

	var candidateGroups [][]dupCandidate
	for _, sz := range sizes {
		if g := bySize[sz]; len(g) >= 2 {
			candidateGroups = append(candidateGroups, g)
		}
	}

	total := 0
	for _, g := range candidateGroups {
		total += len(g)
	}
	done := 0
	if onProgress != nil {
		onProgress(0, total)
	}

	groups := []dupGroup{}
	for _, g := range candidateGroups {
		byHash := map[string][]dupFile{}
		var hashes []string
		for _, f := range g {
			h, herr := sampleHashOfFile(f.Abs, f.Size)
			done++
			if onProgress != nil {
				onProgress(done, total)
			}
			if herr != nil {
				continue // 读失败的文件跳过
			}
			if _, ok := byHash[h]; !ok {
				hashes = append(hashes, h)
			}
			byHash[h] = append(byHash[h], dupFile{
				LibID: f.LibID, Path: f.VPath, Name: f.Name,
				Size: f.Size, ModTime: f.ModTime, Kind: f.Kind,
			})
		}
		for _, h := range hashes {
			fg := byHash[h]
			if len(fg) < 2 {
				continue
			}
			sort.Slice(fg, func(i, j int) bool {
				return strings.ToLower(fg[i].Path) < strings.ToLower(fg[j].Path)
			})
			groups = append(groups, dupGroup{Size: fg[0].Size, Hash: h, Files: fg})
		}
	}
	// 组按可节省空间从大到小排序,便于前端展示。
	sort.SliceStable(groups, func(i, j int) bool {
		wi := groups[i].Size * int64(len(groups[i].Files)-1)
		wj := groups[j].Size * int64(len(groups[j].Files)-1)
		if wi != wj {
			return wi > wj
		}
		return groups[i].Size > groups[j].Size
	})
	return groups
}
