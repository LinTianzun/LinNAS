package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func seedIndexFile(t *testing.T, ix *Index, libID, vpath, name string) {
	t.Helper()
	_, err := ix.db.Exec(`INSERT INTO files
		(lib_id,path,parent,name,is_dir,size,mod_time,kind) VALUES (?,?,?,?,?,?,?,?)`,
		libID, vpath, "/"+libID, name, 0, 1, 1, "other")
	if err != nil {
		t.Fatal(err)
	}
}

func TestScanLibFailurePreservesPreviousRows(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/old.txt", "old.txt")

	cfg := &Config{}
	lib := Library{ID: "lib1", Name: "missing", Path: filepath.Join(t.TempDir(), "missing"), Enabled: true}
	if err := ix.scanLib(cfg, lib, ""); err == nil {
		t.Fatal("scanLib succeeded for a missing library")
	}
	hits, err := ix.Search("old.txt", "lib1", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 || hits[0].Name != "old.txt" {
		t.Fatalf("previous index rows were not preserved: %#v", hits)
	}
}

func TestScanLibReplacesRowsAfterSuccessfulScan(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/old.txt", "old.txt")

	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "new.txt"), []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{}
	lib := Library{ID: "lib1", Name: "library", Path: root, Enabled: true}
	if err := ix.scanLib(cfg, lib, ""); err != nil {
		t.Fatal(err)
	}

	oldHits, err := ix.Search("old.txt", "lib1", 10)
	if err != nil {
		t.Fatal(err)
	}
	newHits, err := ix.Search("new.txt", "lib1", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(oldHits) != 0 || len(newHits) != 1 {
		t.Fatalf("unexpected index after replacement: old=%#v new=%#v", oldHits, newHits)
	}
}

func TestScanLibSkipsHiddenAndExcludedPaths(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	root := t.TempDir()
	vaultDir := filepath.Join(root, ".linNasVault")
	trashDir := filepath.Join(root, ".linNasTrash")
	excludedDir := filepath.Join(root, "excluded")
	for _, dir := range []string{vaultDir, trashDir, excludedDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "hidden.txt"), []byte("hidden"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"visible.txt", "unfinished.part"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("file"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	cfg := &Config{}
	lib := Library{
		ID: "lib1", Name: "library", Path: root, Enabled: true, Exclude: []string{"excluded"},
	}
	if err := ix.scanLib(cfg, lib, vaultDir); err != nil {
		t.Fatal(err)
	}

	for _, hidden := range []string{"hidden.txt", "unfinished.part"} {
		hits, err := ix.Search(hidden, "lib1", 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(hits) != 0 {
			t.Errorf("index unexpectedly contains %q: %#v", hidden, hits)
		}
	}
	hits, err := ix.Search("visible.txt", "lib1", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 {
		t.Fatalf("visible file missing from index: %#v", hits)
	}
}

func TestScanAllUnavailableLibraryKeepsIndexAndScanTime(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/old.txt", "old.txt")
	lastScan := time.Now().Add(-time.Hour)
	ix.mu.Lock()
	ix.lastScan = lastScan
	ix.mu.Unlock()

	cfg := &Config{Libraries: []Library{{
		ID: "lib1", Name: "offline", Path: filepath.Join(t.TempDir(), "offline"), Enabled: true,
	}}}
	ix.ScanAll(cfg)

	deadline := time.Now().Add(5 * time.Second)
	for {
		status := ix.Status()
		if !status["scanning"].(bool) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("index scan did not finish")
		}
		time.Sleep(10 * time.Millisecond)
	}

	status := ix.Status()
	if status["lastScan"].(int64) != lastScan.Unix() {
		t.Fatalf("lastScan advanced after partial scan: got %v, want %d", status["lastScan"], lastScan.Unix())
	}
	if note := status["note"].(string); !strings.HasPrefix(note, "部分失败") {
		t.Fatalf("scan status does not report partial failure: %q", note)
	}
	hits, err := ix.Search("old.txt", "lib1", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 {
		t.Fatalf("offline library index was lost: %#v", hits)
	}
}
