package main

import (
	"database/sql"
	"fmt"
	"io/fs"
	"log"
	"os"
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
	cfg *Config

	mu        sync.Mutex
	scanning  bool
	lastScan  time.Time
	scanNote  string
	fileCount int
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
	} {
		if _, err := db.Exec(stmt); err != nil {
			return nil, err
		}
	}
	ix := &Index{db: db}
	ix.refreshCount()
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
func (ix *Index) refreshCount() {
	var n int
	_ = ix.db.QueryRow(`SELECT COUNT(*) FROM files WHERE is_dir = 0`).Scan(&n)
	ix.mu.Lock()
	ix.fileCount = n
	ix.mu.Unlock()
}

// ScanAll rescans every enabled library in the background (non-blocking).
func (ix *Index) ScanAll(cfg *Config) {
	ix.mu.Lock()
	ix.cfg = cfg
	if ix.scanning {
		ix.mu.Unlock()
		return
	}
	ix.scanning = true
	ix.scanNote = "扫描中…"
	ix.mu.Unlock()

	go func() {
		start := time.Now()
		vaultDir := vaultDirForConfig(cfg)
		for _, lib := range cfg.Libraries {
			if !lib.Enabled {
				continue
			}
			if _, err := os.Stat(lib.Path); err != nil {
				continue // 离线盘跳过
			}
			ix.scanLib(lib, vaultDir)
		}
		ix.refreshCount()
		_, _ = ix.db.Exec(`INSERT OR REPLACE INTO meta(k, v) VALUES ('last_scan', ?)`,
			strconv.FormatInt(time.Now().Unix(), 10))
		ix.mu.Lock()
		ix.scanning = false
		ix.lastScan = time.Now()
		ix.scanNote = fmt.Sprintf("完成,共 %d 个文件,用时 %s", ix.fileCount, time.Since(start).Round(time.Second))
		ix.mu.Unlock()
		log.Printf("索引扫描完成: %d 文件, %s", ix.fileCount, time.Since(start).Round(time.Second))
	}()
}

// scanLib replaces all rows of one library.
func (ix *Index) scanLib(lib Library, vaultDir string) {
	cfg := ix.cfg
	if cfg == nil {
		return
	}
	// 清空旧行
	if _, err := ix.db.Exec(`DELETE FROM files WHERE lib_id = ?`, lib.ID); err != nil {
		log.Printf("清空索引失败(%s): %v", lib.ID, err)
		return
	}
	type row struct {
		path, parent, name, kind string
		isDir                    bool
		size, modTime            int64
	}
	batch := make([]row, 0, 2000)
	flush := func() {
		if len(batch) == 0 {
			return
		}
		tx, err := ix.db.Begin()
		if err != nil {
			log.Printf("索引事务失败: %v", err)
			return
		}
		stmt, err := tx.Prepare(`INSERT OR REPLACE INTO files
			(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`)
		if err != nil {
			_ = tx.Rollback()
			return
		}
		for _, r := range batch {
			id := 0
			if r.isDir {
				id = 1
			}
			_, _ = stmt.Exec(lib.ID, r.path, r.parent, r.name, id, r.size, r.modTime, r.kind)
		}
		_ = stmt.Close()
		_ = tx.Commit()
		batch = batch[:0]
	}

	count := 0
	_ = filepath.WalkDir(lib.Path, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // 跳过无权限目录
		}
		if vaultDir != "" && withinRoot(vaultDir, p) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() && cfg.ExcludedAbs(&lib, p) {
			return filepath.SkipDir // 排除的子目录:不索引
		}
		if p == filepath.Clean(lib.Path) {
			return nil
		}
		vpath := VirtualPath(&lib, p)
		parentV := DirVirtual(vpath)
		info, ierr := d.Info()
		var size, mt int64
		if ierr == nil {
			size = info.Size()
			mt = info.ModTime().Unix()
		}
		kind := "other"
		if d.IsDir() {
			kind = "dir"
		} else {
			kind = kindForExt(fileExt(p))
		}
		batch = append(batch, row{vpath, parentV, d.Name(), kind, d.IsDir(), size, mt})
		count++
		if len(batch) >= 2000 {
			flush()
			ix.mu.Lock()
			ix.scanNote = fmt.Sprintf("扫描 %s: 已处理 %d…", lib.Name, count)
			ix.mu.Unlock()
		}
		return nil
	})
	flush()
	log.Printf("库 %s 索引完成: %d 项", lib.Name, count)
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
func (ix *Index) Media(kind, libID, sortBy string, offset, limit int) ([]IndexHit, error) {
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
