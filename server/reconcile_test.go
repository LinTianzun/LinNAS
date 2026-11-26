package main

import (
	"path/filepath"
	"testing"
)

func TestReconcileLibAddsUpdatesRemoves(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "keep.txt"))       // 1 字节
	writeTestFile(t, filepath.Join(root, "sub", "new.txt")) // 新增

	// 预置错误状态:一条盘上已不存在的残留行 + 一条 size 错误的行。
	if _, err := ix.db.Exec(`INSERT INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`,
		"lib1", "/lib1/gone.txt", "/lib1", "gone.txt", 0, 1, 1, "other"); err != nil {
		t.Fatal(err)
	}
	if _, err := ix.db.Exec(`INSERT INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`,
		"lib1", "/lib1/keep.txt", "/lib1", "keep.txt", 0, 999, 1, "other"); err != nil {
		t.Fatal(err)
	}

	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "lib", Path: root, Enabled: true}}}
	if err := ix.ReconcileLib(cfg, cfg.Libraries[0]); err != nil {
		t.Fatal(err)
	}

	if indexPathExists(t, ix, "/lib1/gone.txt") {
		t.Error("盘上不存在的残留行未被移除")
	}
	if !indexPathExists(t, ix, "/lib1/sub/new.txt") {
		t.Error("新增文件未被索引")
	}
	if !indexPathExists(t, ix, "/lib1/sub") {
		t.Error("新增目录行未被索引")
	}
	var size int64
	if err := ix.db.QueryRow(`SELECT size FROM files WHERE path = ?`, "/lib1/keep.txt").Scan(&size); err != nil {
		t.Fatal(err)
	}
	if size != 1 {
		t.Errorf("keep.txt 大小未更新,得到 %d, want 1", size)
	}
}

func TestReconcileLibMissingLibraryKeepsRows(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/old.txt", "old.txt")

	lib := Library{ID: "lib1", Name: "missing", Path: filepath.Join(t.TempDir(), "nope"), Enabled: true}
	if err := ix.ReconcileLib(&Config{}, lib); err != nil {
		t.Fatalf("库不可用时应返回 nil,得到 %v", err)
	}
	if !indexPathExists(t, ix, "/lib1/old.txt") {
		t.Error("库不可用时不应改动旧索引")
	}
}

func TestReconcileAllSkipsWhileScanning(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "a.txt"))
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "lib", Path: root, Enabled: true}}}

	ix.mu.Lock()
	ix.scanning = true
	ix.mu.Unlock()
	ix.ReconcileAll(cfg)
	ix.mu.Lock()
	ix.scanning = false
	ix.mu.Unlock()
	if indexPathExists(t, ix, "/lib1/a.txt") {
		t.Error("全量扫描进行中,ReconcileAll 应跳过而不写入")
	}

	// 关闭扫描后再跑一次,应正常建立索引。
	ix.ReconcileAll(cfg)
	if !indexPathExists(t, ix, "/lib1/a.txt") {
		t.Error("扫描结束后 ReconcileAll 未建立索引")
	}
}

func TestReconcileAllSkipsDisabledLib(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "a.txt"))
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "lib", Path: root, Enabled: false}}}

	ix.ReconcileAll(cfg)
	if indexPathExists(t, ix, "/lib1/a.txt") {
		t.Error("禁用库不应被对账索引")
	}
}
