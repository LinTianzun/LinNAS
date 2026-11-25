package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func newMaintTestServer(t *testing.T) *Server {
	t.Helper()
	root := t.TempDir()
	libDir := filepath.Join(root, "library")
	if err := os.MkdirAll(libDir, 0o755); err != nil {
		t.Fatal(err)
	}
	return &Server{cfg: &Config{
		DataDir:            root,
		TrashRetentionDays: 30,
		TranscodeCacheMB:   2,
		Libraries: []Library{{
			ID: "lib1", Name: "test", Path: libDir, Enabled: true,
		}},
	}}
}

// seedTrashItem creates a trashed file plus its metadata with the given age.
func seedTrashItem(t *testing.T, s *Server, lib Library, id, name string, age time.Duration) {
	t.Helper()
	td := trashDirForLib(lib)
	metaDir := filepath.Join(td, ".meta")
	if err := os.MkdirAll(metaDir, 0o755); err != nil {
		t.Fatal(err)
	}
	dataPath := filepath.Join(td, id+"__"+name)
	if err := os.WriteFile(dataPath, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	it := trashItem{
		ID:        id,
		Name:      name,
		OrigPath:  filepath.Join(lib.Path, name),
		LibID:     lib.ID,
		Size:      1,
		TrashedAt: time.Now().Add(-age).Unix(),
	}
	raw, _ := json.Marshal(it)
	if err := os.WriteFile(trashMetaPath(td, id), raw, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestCleanTrashByAgeRemovesOnlyExpired(t *testing.T) {
	s := newMaintTestServer(t)
	lib := s.cfg.Libraries[0]
	seedTrashItem(t, s, lib, "old1", "a.txt", 40*24*time.Hour)
	seedTrashItem(t, s, lib, "new1", "b.txt", 1*time.Hour)

	if n := s.cleanTrashByAge(30); n != 1 {
		t.Fatalf("expected 1 removed, got %d", n)
	}
	td := trashDirForLib(lib)
	if _, err := os.Stat(filepath.Join(td, "old1__a.txt")); !os.IsNotExist(err) {
		t.Errorf("expired trash file should be deleted")
	}
	if _, err := os.Stat(trashMetaPath(td, "old1")); !os.IsNotExist(err) {
		t.Errorf("expired trash meta should be deleted")
	}
	if _, err := os.Stat(filepath.Join(td, "new1__b.txt")); err != nil {
		t.Errorf("fresh trash file should be kept: %v", err)
	}
}

func TestCleanTrashByAgeDisabled(t *testing.T) {
	s := newMaintTestServer(t)
	lib := s.cfg.Libraries[0]
	seedTrashItem(t, s, lib, "old1", "a.txt", 100*24*time.Hour)
	if n := s.cleanTrashByAge(0); n != 0 {
		t.Fatalf("days=0 must disable cleanup, got %d", n)
	}
	if _, err := os.Stat(filepath.Join(trashDirForLib(lib), "old1__a.txt")); err != nil {
		t.Errorf("nothing should be deleted when disabled: %v", err)
	}
}

func TestTrimTranscodeCacheEvictsOldest(t *testing.T) {
	s := newMaintTestServer(t)
	dir := filepath.Join(s.cfg.DataDir, "transcode")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	// 三个 1MB 文件,修改时间从旧到新。
	const oneMB = 1024 * 1024
	names := []string{"a.mp4", "b.mp4", "c.mp4"}
	for i, name := range names {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, make([]byte, oneMB), 0o644); err != nil {
			t.Fatal(err)
		}
		mt := time.Now().Add(-time.Duration(len(names)-i) * time.Hour)
		if err := os.Chtimes(p, mt, mt); err != nil {
			t.Fatal(err)
		}
	}

	removed, freed := s.trimTranscodeCache(2) // 上限 2MB → 需淘汰最旧的 1 个
	if removed != 1 {
		t.Fatalf("expected 1 removed, got %d", removed)
	}
	if freed != oneMB {
		t.Fatalf("expected freed %d, got %d", oneMB, freed)
	}
	if _, err := os.Stat(filepath.Join(dir, "a.mp4")); !os.IsNotExist(err) {
		t.Errorf("oldest file a.mp4 should be evicted")
	}
	if _, err := os.Stat(filepath.Join(dir, "c.mp4")); err != nil {
		t.Errorf("newest file c.mp4 should be kept: %v", err)
	}
}

func TestTrimTranscodeCacheNoLimit(t *testing.T) {
	s := newMaintTestServer(t)
	dir := filepath.Join(s.cfg.DataDir, "transcode")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "a.mp4"), make([]byte, 1024), 0o644); err != nil {
		t.Fatal(err)
	}
	if removed, _ := s.trimTranscodeCache(0); removed != 0 {
		t.Fatalf("maxMB=0 must disable trimming, got %d", removed)
	}
	if _, err := os.Stat(filepath.Join(dir, "a.mp4")); err != nil {
		t.Errorf("file should be kept when unlimited: %v", err)
	}
}
