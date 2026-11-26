package main

import (
	"database/sql"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// Index is the SQLite-backed media/file index (speeds up search & media views
// for huge libraries; directory listings still read the disk for correctness).
type Index struct {
	db *sql.DB

	mu          sync.Mutex
	scanning    bool
	reconciling bool
	lastScan    time.Time
	scanNote    string
	fileCount   int

	// afterIndexMu / afterIndex 是「扫描或对账完成后」的后台回调(用于触发内容索引增量入队)。
	afterIndexMu sync.Mutex
	afterIndex   func()
}

// SetAfterIndex 注册扫描/对账完成后调用的回调(在后台 goroutine 中执行,不阻塞索引)。
func (ix *Index) SetAfterIndex(fn func()) {
	ix.afterIndexMu.Lock()
	ix.afterIndex = fn
	ix.afterIndexMu.Unlock()
}

// fireAfterIndex 异步触发已注册的完成回调(若存在)。
func (ix *Index) fireAfterIndex() {
	ix.afterIndexMu.Lock()
	fn := ix.afterIndex
	ix.afterIndexMu.Unlock()
	if fn != nil {
		go fn()
	}
}

// IndexHit is one search/media result row.
type IndexHit struct {
	LibID   string `json:"libId"`
	Path    string `json:"path"` // virtual path, e.g. /lib1/movies/a.mp4
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
	Kind    string `json:"kind"` // video|image|audio|other
}

// OpenIndex opens/creates index.db in dataDir.
func OpenIndex(dataDir string) (*Index, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	dsn := "file:" + filepath.ToSlash(filepath.Join(dataDir, "index.db")) +
		"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS files (
		lib_id   TEXT NOT NULL,
		path     TEXT NOT NULL,
		parent   TEXT NOT NULL,
		name     TEXT NOT NULL,
		is_dir   INTEGER NOT NULL,
		size     INTEGER NOT NULL,
		mod_time INTEGER NOT NULL,
		kind     TEXT NOT NULL,
		PRIMARY KEY (lib_id, path)
	)`); err != nil {
		return nil, err
	}
	for _, stmt := range []string{
		`CREATE INDEX IF NOT EXISTS idx_files_parent ON files(lib_id, parent)`,
		`CREATE INDEX IF NOT EXISTS idx_files_kind ON files(lib_id, kind, name)`,
		`CREATE INDEX IF NOT EXISTS idx_files_name ON files(name)`,
		`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS storage_snapshots (
			lib_id TEXT NOT NULL,
			ts     INTEGER NOT NULL,
			bytes  INTEGER NOT NULL,
			files  INTEGER NOT NULL,
			PRIMARY KEY (lib_id, ts)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_snap_ts ON storage_snapshots(ts)`,
		`CREATE TABLE IF NOT EXISTS file_sig (
			lib_id   TEXT NOT NULL,
			path     TEXT NOT NULL,
			size     INTEGER NOT NULL,
			mod_time INTEGER NOT NULL,
			sig      TEXT NOT NULL,
			sig_at   INTEGER NOT NULL,
			PRIMARY KEY (lib_id, path)
		)`,
		`CREATE TABLE IF NOT EXISTS content (
			lib_id     TEXT NOT NULL,
			path       TEXT NOT NULL,
			mtime      INTEGER NOT NULL,
			size       INTEGER NOT NULL,
			text       TEXT NOT NULL,
			indexed_at INTEGER NOT NULL,
			PRIMARY KEY (lib_id, path)
		)`,
		`CREATE TABLE IF NOT EXISTS content_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS idx_content_lib ON content(lib_id)`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			return nil, err
		}
	}
	ix := &Index{db: db}
	if err := ix.refreshCount(); err != nil {
		_ = db.Close()
		return nil, err
	}
	// 恢复上次扫描时间(用于判断启动时是否需要重扫)
	var last string
	if err := db.QueryRow(`SELECT v FROM meta WHERE k = 'last_scan'`).Scan(&last); err == nil {
		if sec, perr := strconv.ParseInt(last, 10, 64); perr == nil && sec > 0 {
			ix.lastScan = time.Unix(sec, 0)
		}
	}
	return ix, nil
}

// NeedsScan reports whether the index is older than maxAge (or never built).
func (ix *Index) NeedsScan(maxAge time.Duration) bool {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	return ix.lastScan.IsZero() || time.Since(ix.lastScan) > maxAge
}

// lastScanText formats the last scan time for logs.
func (ix *Index) lastScanText() string {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	if ix.lastScan.IsZero() {
		return "从未"
	}
	return time.Since(ix.lastScan).Round(time.Minute).String() + "前"
}

func (ix *Index) Close() error { return ix.db.Close() }

// refreshCount recomputes non-dir file count.
func (ix *Index) refreshCount() error {
	var n int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM files WHERE is_dir = 0`).Scan(&n); err != nil {
		return err
	}
	ix.mu.Lock()
	ix.fileCount = n
	ix.mu.Unlock()
	return nil
}

// ============================================================
//  存储空间趋势快照
// ============================================================

// Snapshot 记录当前各库的占用快照;同一小时只保留一条;顺便清理 400 天前的数据。
func (ix *Index) Snapshot() error {
	ts := time.Now().Truncate(time.Hour).Unix()
	rows, err := ix.db.Query(
		`SELECT lib_id, COUNT(*), COALESCE(SUM(size), 0) FROM files WHERE is_dir = 0 GROUP BY lib_id`)
	if err != nil {
		return err
	}
	type snap struct {
		libID string
		files int64
		bytes int64
	}
	var snaps []snap
	for rows.Next() {
		var s snap
		if err := rows.Scan(&s.libID, &s.files, &s.bytes); err != nil {
			_ = rows.Close()
			return err
		}
		snaps = append(snaps, s)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	_ = rows.Close()

	tx, err := ix.db.Begin()
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()
	for _, s := range snaps {
		if _, err := tx.Exec(`INSERT OR REPLACE INTO storage_snapshots
			(lib_id, ts, bytes, files) VALUES (?,?,?,?)`, s.libID, ts, s.bytes, s.files); err != nil {
			return err
		}
	}
	// 清理 400 天前的历史,避免快照表无限增长。
	if _, err := tx.Exec(`DELETE FROM storage_snapshots WHERE ts < ?`,
		time.Now().Unix()-400*86400); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	return nil
}

// ScanAll rescans every enabled library in the background (non-blocking).
func (ix *Index) ScanAll(cfg *Config) {
	cfgSnapshot := *cfg
	cfgSnapshot.Libraries = append([]Library(nil), cfg.Libraries...)
	for i := range cfgSnapshot.Libraries {
		cfgSnapshot.Libraries[i].Exclude = append([]string(nil), cfgSnapshot.Libraries[i].Exclude...)
	}

	ix.mu.Lock()
	if ix.scanning {
		ix.mu.Unlock()
		return
	}
	ix.scanning = true
	ix.scanNote = "扫描中…"
	ix.mu.Unlock()

	go func() {
		start := time.Now()
		vaultDir := vaultDirForConfig(&cfgSnapshot)
		var scanErrors []string
		scannedLibs := make(map[string]bool)
		for _, lib := range cfgSnapshot.Libraries {
			if !lib.Enabled {
				continue
			}
			if _, err := os.Stat(lib.Path); err != nil {
				scanErrors = append(scanErrors, fmt.Sprintf("%s: 存储库不可用: %v", lib.Name, err))
				log.Printf("库 %s 不可用,保留旧索引: %v", lib.Name, err)
				continue
			}
			if err := ix.scanLib(&cfgSnapshot, lib, vaultDir); err != nil {
				scanErrors = append(scanErrors, fmt.Sprintf("%s: %v", lib.Name, err))
				log.Printf("库 %s 索引扫描失败,保留旧索引: %v", lib.Name, err)
				continue
			}
			scannedLibs[lib.ID] = true
		}
		if len(scanErrors) == 0 {
			if err := ix.removeUnconfiguredLibraries(scannedLibs); err != nil {
				scanErrors = append(scanErrors, "清理已移除存储库索引失败: "+err.Error())
			}
		}
		if err := ix.refreshCount(); err != nil {
			scanErrors = append(scanErrors, "统计索引失败: "+err.Error())
		}
		scanFinished := time.Now()
		if len(scanErrors) == 0 {
			if _, err := ix.db.Exec(`INSERT OR REPLACE INTO meta(k, v) VALUES ('last_scan', ?)`,
				strconv.FormatInt(scanFinished.Unix(), 10)); err != nil {
				scanErrors = append(scanErrors, "保存扫描时间失败: "+err.Error())
			}
		}
		// 扫描成功后记一条空间快照(失败只记日志,不影响扫描结果)。
		if len(scanErrors) == 0 {
			if err := ix.Snapshot(); err != nil {
				log.Printf("记录存储空间快照失败: %v", err)
			}
		}
		ix.mu.Lock()
		ix.scanning = false
		if len(scanErrors) == 0 {
			ix.lastScan = scanFinished
			ix.scanNote = fmt.Sprintf("完成,共 %d 个文件,用时 %s", ix.fileCount, time.Since(start).Round(time.Second))
		} else {
			ix.scanNote = "部分失败: " + strings.Join(scanErrors, "; ")
		}
		note := ix.scanNote
		ix.mu.Unlock()
		log.Printf("索引扫描结束: %s (用时 %s)", note, time.Since(start).Round(time.Second))
		// 扫描结束后触发内容索引增量入队(异步,不阻塞)。
		ix.fireAfterIndex()
	}()
}

func (ix *Index) removeUnconfiguredLibraries(enabled map[string]bool) error {
	tx, err := ix.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	rows, err := tx.Query(`SELECT DISTINCT lib_id FROM files`)
	if err != nil {
		return err
	}
	var stale []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			_ = rows.Close()
			return err
		}
		if !enabled[id] {
			stale = append(stale, id)
		}
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, id := range stale {
		if _, err := tx.Exec(`DELETE FROM files WHERE lib_id = ?`, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// scanLib replaces one library's rows atomically; failed walks keep the old index.
func (ix *Index) scanLib(cfg *Config, lib Library, vaultDir string) error {
	tx, err := ix.db.Begin()
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()
	if _, err := tx.Exec(`DELETE FROM files WHERE lib_id = ?`, lib.ID); err != nil {
		return err
	}
	stmt, err := tx.Prepare(`INSERT OR REPLACE INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	stmtClosed := false
	defer func() {
		if !stmtClosed {
			_ = stmt.Close()
		}
	}()

	type row struct {
		path, parent, name, kind string
		isDir                    bool
		size, modTime            int64
	}
	batch := make([]row, 0, 2000)
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		for _, r := range batch {
			id := 0
			if r.isDir {
				id = 1
			}
			if _, err := stmt.Exec(lib.ID, r.path, r.parent, r.name, id, r.size, r.modTime, r.kind); err != nil {
				return err
			}
		}
		batch = batch[:0]
		return nil
	}

	count := 0
	walkErr := filepath.WalkDir(lib.Path, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if dec := indexWalkDecision(cfg, &lib, vaultDir, p, d); dec.skip {
			if dec.skipSubtree {
				return filepath.SkipDir
			}
			return nil
		}
		if p == filepath.Clean(lib.Path) {
			return nil
		}
		vpath := VirtualPath(&lib, p)
		parentV := DirVirtual(vpath)
		info, ierr := d.Info()
		if ierr != nil {
			return ierr
		}
		size := info.Size()
		mt := info.ModTime().Unix()
		kind := "other"
		if d.IsDir() {
			kind = "dir"
		} else {
			kind = kindForExt(fileExt(p))
		}
		batch = append(batch, row{vpath, parentV, d.Name(), kind, d.IsDir(), size, mt})
		count++
		if len(batch) >= 2000 {
			if err := flush(); err != nil {
				return err
			}
			ix.mu.Lock()
			ix.scanNote = fmt.Sprintf("扫描 %s: 已处理 %d…", lib.Name, count)
			ix.mu.Unlock()
		}
		return nil
	})
	if walkErr != nil {
		return walkErr
	}
	if err := flush(); err != nil {
		return err
	}
	if err := stmt.Close(); err != nil {
		return err
	}
	stmtClosed = true
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	log.Printf("库 %s 索引完成: %d 项", lib.Name, count)
	return nil
}

// walkDecision says how the indexer treats one walked entry.
type walkDecision struct {
	skip        bool // do not index this entry
	skipSubtree bool // do not descend (the entry is a hidden/excluded dir)
}

// indexWalkDecision mirrors the index filter rules (vault, library trash
// folder, excluded sub-paths, .part temp files) for both full scans and
// incremental updates.
func indexWalkDecision(cfg *Config, lib *Library, vaultDir, p string, d fs.DirEntry) walkDecision {
	if vaultDir != "" && withinRoot(vaultDir, p) {
		return walkDecision{skip: true, skipSubtree: d.IsDir()}
	}
	if withinRoot(trashDirFor(lib), p) {
		return walkDecision{skip: true, skipSubtree: d.IsDir()}
	}
	// 跳过操作系统回收站/卷影信息等无权限且无意义的数据
	if d.IsDir() {
		switch strings.ToLower(d.Name()) {
		case "$recycle.bin", "system volume information", "found.000", "$windows.~bt", "$windows.~ws":
			return walkDecision{skip: true, skipSubtree: true}
		}
	}
	if d.IsDir() && cfg.ExcludedAbs(lib, p) {
		return walkDecision{skip: true, skipSubtree: true}
	}
	if !d.IsDir() && strings.HasSuffix(strings.ToLower(d.Name()), ".part") {
		return walkDecision{skip: true}
	}
	return walkDecision{}
}

// ============================================================
//  增量索引更新
// ============================================================

// IndexChange is one incremental index update: remove an old virtual path (or
// whole subtree) and/or re-index a new one.
type IndexChange struct {
	Remove string
	Add    string
}

// UpdatePaths applies incremental index changes in the background
// (non-blocking). Changes submitted while a full scan is running are skipped —
// the in-flight full scan rebuilds everything anyway.
func (ix *Index) UpdatePaths(cfg *Config, changes ...IndexChange) {
	if len(changes) == 0 {
		return
	}
	ix.mu.Lock()
	busy := ix.scanning
	ix.mu.Unlock()
	if busy {
		return
	}
	cfgSnapshot := *cfg
	cfgSnapshot.Libraries = append([]Library(nil), cfg.Libraries...)
	for i := range cfgSnapshot.Libraries {
		cfgSnapshot.Libraries[i].Exclude = append([]string(nil), cfgSnapshot.Libraries[i].Exclude...)
	}
	go func() {
		vaultDir := vaultDirForConfig(&cfgSnapshot)
		for _, ch := range changes {
			if ch.Remove != "" {
				ix.removeSubtreeRows(libIDOf(ch.Remove), ch.Remove)
			}
			if ch.Add != "" {
				if err := ix.updateSubtree(&cfgSnapshot, ch.Add, vaultDir); err != nil {
					log.Printf("增量索引 %s 失败: %v", ch.Add, err)
				}
			}
		}
		if err := ix.refreshCount(); err != nil {
			log.Printf("更新索引计数失败: %v", err)
		}
	}()
}

// libIDOf returns the library id part of a virtual path ("" when empty).
func libIDOf(vpath string) string {
	vpath = strings.TrimPrefix(vpath, "/")
	if i := strings.Index(vpath, "/"); i >= 0 {
		return vpath[:i]
	}
	return vpath
}

// removeSubtreeRows drops one row (file) or a whole subtree (directory).
func (ix *Index) removeSubtreeRows(libID, vpath string) {
	vpath = path.Clean("/" + strings.TrimPrefix(vpath, "/"))
	pat := likeEscape(vpath) + "/%"
	if _, err := ix.db.Exec(
		`DELETE FROM files WHERE lib_id = ? AND (path = ? COLLATE NOCASE OR path LIKE ? ESCAPE '\')`,
		libID, vpath, pat); err != nil {
		log.Printf("移除索引 %s 失败: %v", vpath, err)
	}
}

// likeEscape escapes SQL LIKE wildcards so a path matches literally.
func likeEscape(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `%`, `\%`)
	return strings.ReplaceAll(s, `_`, `\_`)
}

// libFromVPath finds the enabled library of a virtual path inside the config
// snapshot and returns it with the slash-separated library-relative path.
func libFromVPath(cfg *Config, vpath string) (*Library, string, bool) {
	vpath = path.Clean("/" + strings.TrimPrefix(vpath, "/"))
	parts := strings.Split(strings.TrimPrefix(vpath, "/"), "/")
	if parts[0] == "" {
		return nil, "", false
	}
	for i := range cfg.Libraries {
		lib := &cfg.Libraries[i]
		if lib.ID == parts[0] && lib.Enabled && lib.Path != "" {
			rel := ""
			if len(parts) > 1 {
				rel = strings.Join(parts[1:], "/")
			}
			return lib, rel, true
		}
	}
	return nil, "", false
}

// updateSubtree (re-)indexes one virtual path: a file becomes a single row,
// a directory is walked with the same filters as a full scan. Missing or
// filtered paths just clear their stale rows.
func (ix *Index) updateSubtree(cfg *Config, vpath, vaultDir string) error {
	lib, rel, ok := libFromVPath(cfg, vpath)
	if !ok {
		return nil
	}
	if rel == "" {
		// 整个库路径:退化为单库重扫
		if _, err := os.Stat(lib.Path); err != nil {
			return nil
		}
		return ix.scanLib(cfg, *lib, vaultDir)
	}
	abs := filepath.Join(lib.Path, filepath.FromSlash(rel))
	info, err := os.Stat(abs)
	if err != nil {
		ix.removeSubtreeRows(lib.ID, vpath)
		return nil
	}
	if info.IsDir() {
		if cfg.ExcludedAbs(lib, abs) ||
			(vaultDir != "" && withinRoot(vaultDir, abs)) ||
			withinRoot(trashDirFor(lib), abs) {
			ix.removeSubtreeRows(lib.ID, vpath)
			return nil
		}
	} else if strings.HasSuffix(strings.ToLower(info.Name()), ".part") {
		ix.removeSubtreeRows(lib.ID, vpath)
		return nil
	}
	if err := ix.ensureAncestorRows(lib, abs); err != nil {
		return err
	}
	return ix.insertSubtree(cfg, lib, vaultDir, abs)
}

// ensureAncestorRows makes sure every directory between the library root and
// abs (exclusive) has an index row, in case the dir chain is brand new.
func (ix *Index) ensureAncestorRows(lib *Library, abs string) error {
	root := filepath.Clean(lib.Path)
	dir := filepath.Clean(abs)
	if !withinRoot(root, dir) {
		return nil
	}
	var ancestors []string
	for {
		dir = filepath.Dir(dir)
		if !withinRoot(root, dir) || filepath.Clean(dir) == root {
			break
		}
		ancestors = append(ancestors, dir)
	}
	for i := len(ancestors) - 1; i >= 0; i-- {
		p := ancestors[i]
		info, err := os.Stat(p)
		if err != nil || !info.IsDir() {
			continue
		}
		vp := VirtualPath(lib, p)
		if _, err := ix.db.Exec(`INSERT OR IGNORE INTO files
			(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,1,0,?,?)`,
			lib.ID, vp, DirVirtual(vp), filepath.Base(p), info.ModTime().Unix(), "dir"); err != nil {
			return err
		}
	}
	return nil
}

// insertSubtree walks abs and inserts/replaces index rows for it and its
// descendants (single tx; on error the old rows are kept).
func (ix *Index) insertSubtree(cfg *Config, lib *Library, vaultDir, abs string) error {
	tx, err := ix.db.Begin()
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()
	stmt, err := tx.Prepare(`INSERT OR REPLACE INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	defer func() { _ = stmt.Close() }()

	walkErr := filepath.WalkDir(abs, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if dec := indexWalkDecision(cfg, lib, vaultDir, p, d); dec.skip {
			if dec.skipSubtree {
				return filepath.SkipDir
			}
			return nil
		}
		pi, ierr := d.Info()
		if ierr != nil {
			return ierr
		}
		vpath := VirtualPath(lib, p)
		kind := "other"
		id := 0
		if d.IsDir() {
			kind = "dir"
			id = 1
		} else {
			kind = kindForExt(fileExt(p))
		}
		if _, err := stmt.Exec(lib.ID, vpath, DirVirtual(vpath), d.Name(), id, pi.Size(), pi.ModTime().Unix(), kind); err != nil {
			return err
		}
		return nil
	})
	if walkErr != nil {
		return walkErr
	}
	if err := stmt.Close(); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	return nil
}

// RescanLib rescans one library in the background (used after a library is
// added, renamed, re-pointed or its exclusion rules change). Skipped when a
// scan is already running or the library is disabled/unknown.
func (ix *Index) RescanLib(cfg *Config, libID string) {
	cfgSnapshot := *cfg
	cfgSnapshot.Libraries = append([]Library(nil), cfg.Libraries...)
	for i := range cfgSnapshot.Libraries {
		cfgSnapshot.Libraries[i].Exclude = append([]string(nil), cfgSnapshot.Libraries[i].Exclude...)
	}
	var lib *Library
	for i := range cfgSnapshot.Libraries {
		if cfgSnapshot.Libraries[i].ID == libID {
			lib = &cfgSnapshot.Libraries[i]
			break
		}
	}
	if lib == nil || !lib.Enabled {
		return
	}
	ix.mu.Lock()
	busy := ix.scanning
	ix.mu.Unlock()
	if busy {
		return
	}
	go func() {
		if _, err := os.Stat(lib.Path); err != nil {
			log.Printf("库 %s 不可用,跳过增量扫描: %v", lib.Name, err)
			return
		}
		if err := ix.scanLib(&cfgSnapshot, *lib, vaultDirForConfig(&cfgSnapshot)); err != nil {
			log.Printf("库 %s 增量扫描失败,保留旧索引: %v", lib.Name, err)
			return
		}
		if err := ix.refreshCount(); err != nil {
			log.Printf("更新索引计数失败: %v", err)
		}
	}()
}

// ============================================================
//  周期性增量对账:发现资源管理器等外部改动。
// ============================================================

// reconcileRow 是磁盘上「应有」的一条索引行。
type reconcileRow struct {
	path, parent, name, kind string
	isDir                    bool
	size, modTime            int64
}

// ReconcileLib 用 WalkDir 得到磁盘上应有的行,与该库现有 DB 行做差异比较,
// 仅对差异写入(新增/大小/时间/类型变化 → INSERT OR REPLACE;库有盘无 → 删除),
// 单事务提交。库不可用(os.Stat 失败)保留旧索引返回 nil;全量扫描进行中则跳过。
func (ix *Index) ReconcileLib(cfg *Config, lib Library) error {
	ix.mu.Lock()
	scanning := ix.scanning
	ix.mu.Unlock()
	if scanning {
		return nil
	}
	if _, err := os.Stat(lib.Path); err != nil {
		log.Printf("库 %s 不可用,跳过对账,保留旧索引: %v", lib.Name, err)
		return nil
	}
	vaultDir := vaultDirForConfig(cfg)

	disk := make(map[string]reconcileRow)
	walkErr := filepath.WalkDir(lib.Path, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if dec := indexWalkDecision(cfg, &lib, vaultDir, p, d); dec.skip {
			if dec.skipSubtree {
				return filepath.SkipDir
			}
			return nil
		}
		if p == filepath.Clean(lib.Path) {
			return nil
		}
		info, ierr := d.Info()
		if ierr != nil {
			return ierr
		}
		vpath := VirtualPath(&lib, p)
		kind := "other"
		if d.IsDir() {
			kind = "dir"
		} else {
			kind = kindForExt(fileExt(p))
		}
		disk[vpath] = reconcileRow{
			path: vpath, parent: DirVirtual(vpath), name: d.Name(), kind: kind,
			isDir: d.IsDir(), size: info.Size(), modTime: info.ModTime().Unix(),
		}
		return nil
	})
	if walkErr != nil {
		return walkErr
	}

	// 读取该库现有 DB 行(is_dir → int)。
	type dbRow struct {
		isDir   int
		size    int64
		modTime int64
	}
	dbRows := make(map[string]dbRow)
	rows, err := ix.db.Query(`SELECT path,is_dir,size,mod_time FROM files WHERE lib_id = ?`, lib.ID)
	if err != nil {
		return err
	}
	for rows.Next() {
		var p string
		var r dbRow
		if err := rows.Scan(&p, &r.isDir, &r.size, &r.modTime); err != nil {
			_ = rows.Close()
			return err
		}
		dbRows[p] = r
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	_ = rows.Close()

	tx, err := ix.db.Begin()
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()

	var changed, removed int
	for vpath, r := range disk {
		id := 0
		if r.isDir {
			id = 1
		}
		old, ok := dbRows[vpath]
		if !ok || old.isDir != id || old.size != r.size || old.modTime != r.modTime {
			if _, err := tx.Exec(`INSERT OR REPLACE INTO files
				(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`,
				lib.ID, r.path, r.parent, r.name, id, r.size, r.modTime, r.kind); err != nil {
				return err
			}
			changed++
		}
		delete(dbRows, vpath)
	}
	// 剩余即「库有盘无」的残留行,删除。
	for vpath := range dbRows {
		if _, err := tx.Exec(`DELETE FROM files WHERE lib_id = ? AND path = ?`, lib.ID, vpath); err != nil {
			return err
		}
		removed++
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	if changed > 0 || removed > 0 {
		log.Printf("库 %s 对账: 新增/更新 %d 项,移除 %d 项", lib.Name, changed, removed)
	}
	return nil
}

// ReconcileAll 对每个启用库调用 ReconcileLib,最后刷新计数。
// reconciling 受 ix.mu 保护,防止重入;全量扫描进行中直接跳过。
func (ix *Index) ReconcileAll(cfg *Config) {
	cfgSnapshot := *cfg
	cfgSnapshot.Libraries = append([]Library(nil), cfg.Libraries...)
	for i := range cfgSnapshot.Libraries {
		cfgSnapshot.Libraries[i].Exclude = append([]string(nil), cfgSnapshot.Libraries[i].Exclude...)
		cfgSnapshot.Libraries[i].MediaHide = append([]string(nil), cfgSnapshot.Libraries[i].MediaHide...)
	}

	ix.mu.Lock()
	if ix.scanning || ix.reconciling {
		ix.mu.Unlock()
		return
	}
	ix.reconciling = true
	ix.mu.Unlock()
	defer func() {
		ix.mu.Lock()
		ix.reconciling = false
		ix.mu.Unlock()
	}()

	for _, lib := range cfgSnapshot.Libraries {
		if !lib.Enabled {
			continue
		}
		if err := ix.ReconcileLib(&cfgSnapshot, lib); err != nil {
			log.Printf("库 %s 对账失败,保留旧索引: %v", lib.Name, err)
		}
	}
	if err := ix.refreshCount(); err != nil {
		log.Printf("更新索引计数失败: %v", err)
	}
	// 对账完成后记一条空间快照(失败只记日志,不影响对账结果)。
	if err := ix.Snapshot(); err != nil {
		log.Printf("记录存储空间快照失败: %v", err)
	}
	// 对账结束后触发内容索引增量入队(异步,不阻塞)。
	ix.fireAfterIndex()
}

// RemoveLibRows drops every indexed row of one library (used when a library is
// removed from the config or disabled).
func (ix *Index) RemoveLibRows(libID string) {
	go func() {
		if _, err := ix.db.Exec(`DELETE FROM files WHERE lib_id = ?`, libID); err != nil {
			log.Printf("移除库 %s 索引失败: %v", libID, err)
			return
		}
		if err := ix.refreshCount(); err != nil {
			log.Printf("更新索引计数失败: %v", err)
		}
	}()
}

func kindForExt(ext string) string {
	switch {
	case isVideoExt(ext):
		return "video"
	case isImageExt(ext):
		return "image"
	case ext == "mp3" || ext == "flac" || ext == "wav" || ext == "aac" || ext == "ogg" || ext == "m4a" || ext == "wma":
		return "audio"
	}
	return "other"
}

// Search does a substring match on file names (case-insensitive via LIKE).
func (ix *Index) Search(q string, libID string, limit int) ([]IndexHit, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	q = strings.TrimSpace(q)
	if q == "" {
		return []IndexHit{}, nil
	}
	args := []any{"%" + q + "%"}
	where := "name LIKE ? AND is_dir = 0"
	if libID != "" {
		where += " AND lib_id = ?"
		args = append(args, libID)
	}
	args = append(args, limit)
	rows, err := ix.db.Query(`SELECT lib_id,path,name,size,mod_time,kind FROM files WHERE `+where+
		` ORDER BY CASE kind WHEN 'video' THEN 0 WHEN 'image' THEN 1 WHEN 'audio' THEN 2 ELSE 3 END, name LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanHits(rows)
}

// Media lists files of one kind (video/image/audio) for the media view.
// hidden 为「不在媒体库展示」的完整虚拟前缀(如 /lib1/dir),命中的路径被排除。
func (ix *Index) Media(kind, libID, sortBy string, offset, limit int, hidden []string) ([]IndexHit, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	order := "name"
	if sortBy == "mtime" {
		order = "mod_time DESC"
	} else if sortBy == "size" {
		order = "size DESC"
	} else {
		order = "name"
	}
	args := []any{kind}
	where := "kind = ?"
	if libID != "" {
		where += " AND lib_id = ?"
		args = append(args, libID)
	}
	// 排除「不在媒体库展示」的前缀:等于前缀本身,或位于其子目录下。
	for _, prefix := range hidden {
		prefix = strings.TrimSuffix(strings.ReplaceAll(prefix, "\\", "/"), "/")
		if prefix == "" {
			continue
		}
		where += ` AND path <> ? COLLATE NOCASE AND path NOT LIKE ? ESCAPE '\'`
		args = append(args, prefix, likeEscape(prefix)+"/%")
	}
	args = append(args, limit, offset)
	rows, err := ix.db.Query(`SELECT lib_id,path,name,size,mod_time,kind FROM files WHERE `+where+
		` ORDER BY `+order+` LIMIT ? OFFSET ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanHits(rows)
}

func scanHits(rows *sql.Rows) ([]IndexHit, error) {
	out := []IndexHit{}
	for rows.Next() {
		var h IndexHit
		if err := rows.Scan(&h.LibID, &h.Path, &h.Name, &h.Size, &h.ModTime, &h.Kind); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// Stats returns counts per kind.
func (ix *Index) Stats() map[string]int {
	out := map[string]int{}
	rows, err := ix.db.Query(`SELECT kind, COUNT(*) FROM files GROUP BY kind`)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var k string
		var n int
		if rows.Scan(&k, &n) == nil {
			out[k] = n
		}
	}
	return out
}

// Status returns scan state for the API.
func (ix *Index) Status() map[string]any {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	var last int64
	if !ix.lastScan.IsZero() {
		last = ix.lastScan.Unix()
	}
	return map[string]any{
		"scanning":  ix.scanning,
		"note":      ix.scanNote,
		"fileCount": ix.fileCount,
		"lastScan":  last,
	}
}
