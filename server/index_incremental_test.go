package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// indexPathExists reports whether the index holds a row for vpath.
func indexPathExists(t *testing.T, ix *Index, vpath string) bool {
	t.Helper()
	var n int
	if err := ix.db.QueryRow(`SELECT COUNT(*) FROM files WHERE path = ?`, vpath).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n > 0
}

// waitIndexPath waits (up to 5s) until vpath is present/absent in the index.
func waitIndexPath(t *testing.T, ix *Index, vpath string, want bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if indexPathExists(t, ix, vpath) == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("index path %q presence != %v after timeout", vpath, want)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func writeTestFile(t *testing.T, p string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestUpdateSubtreeIndexesNewFileAndAncestors(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()

	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "sub", "new.txt"))
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "library", Path: root, Enabled: true}}}

	if err := ix.updateSubtree(cfg, "/lib1/sub/new.txt", ""); err != nil {
		t.Fatal(err)
	}
	for _, vp := range []string{"/lib1/sub", "/lib1/sub/new.txt"} {
		if !indexPathExists(t, ix, vp) {
			t.Errorf("index is missing %q", vp)
		}
	}
	// 文件行必须是非目录行,目录行 is_dir=1
	var isDir int
	if err := ix.db.QueryRow(`SELECT is_dir FROM files WHERE path = ?`, "/lib1/sub").Scan(&isDir); err != nil {
		t.Fatal(err)
	}
	if isDir != 1 {
		t.Errorf("ancestor row is_dir = %d, want 1", isDir)
	}
}

func TestUpdateSubtreeClearsRowsWhenPathIsGone(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/gone.txt", "gone.txt")

	root := t.TempDir()
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "library", Path: root, Enabled: true}}}
	if err := ix.updateSubtree(cfg, "/lib1/gone.txt", ""); err != nil {
		t.Fatal(err)
	}
	if indexPathExists(t, ix, "/lib1/gone.txt") {
		t.Error("stale row for a deleted path was kept")
	}
}

func TestRemoveSubtreeRowsDropsWholeDirectory(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	rows := []string{"/lib1/dir", "/lib1/dir/a.txt", "/lib1/dir/b/c.txt", "/lib1/dir2/keep.txt", "/lib1/keep.txt"}
	for _, vp := range rows {
		seedIndexFile(t, ix, "lib1", vp, filepath.Base(vp))
	}

	ix.removeSubtreeRows("lib1", "/lib1/dir")
	for _, vp := range []string{"/lib1/dir", "/lib1/dir/a.txt", "/lib1/dir/b/c.txt"} {
		if indexPathExists(t, ix, vp) {
			t.Errorf("subtree row %q survived removal", vp)
		}
	}
	for _, vp := range []string{"/lib1/dir2/keep.txt", "/lib1/keep.txt"} {
		if !indexPathExists(t, ix, vp) {
			t.Errorf("unrelated row %q was removed", vp)
		}
	}
}

func TestRemoveSubtreeRowsTreatsWildcardsLiterally(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	for _, vp := range []string{"/lib1/a%b/x.txt", "/lib1/abcdef/x.txt"} {
		seedIndexFile(t, ix, "lib1", vp, "x.txt")
	}

	ix.removeSubtreeRows("lib1", "/lib1/a%b")
	if indexPathExists(t, ix, "/lib1/a%b/x.txt") {
		t.Error("row under %-named dir was not removed")
	}
	if !indexPathExists(t, ix, "/lib1/abcdef/x.txt") {
		t.Error("LIKE pattern deleted an unrelated sibling dir")
	}
}

func TestUpdateSubtreeRefusesExcludedDirectory(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/excluded/x.txt", "x.txt")

	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "excluded", "x.txt"))
	cfg := &Config{Libraries: []Library{{
		ID: "lib1", Name: "library", Path: root, Enabled: true, Exclude: []string{"excluded"},
	}}}
	if err := ix.updateSubtree(cfg, "/lib1/excluded", ""); err != nil {
		t.Fatal(err)
	}
	if indexPathExists(t, ix, "/lib1/excluded/x.txt") {
		t.Error("excluded directory was indexed anyway")
	}
}

func TestUpdatePathsAppliesChangesInBackground(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "late.txt"))
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "library", Path: root, Enabled: true}}}

	ix.UpdatePaths(cfg, IndexChange{Add: "/lib1/late.txt"})
	waitIndexPath(t, ix, "/lib1/late.txt", true)

	ix.UpdatePaths(cfg, IndexChange{Remove: "/lib1/late.txt"})
	waitIndexPath(t, ix, "/lib1/late.txt", false)
}

func TestUpdatePathsSkippedWhileFullScanRuns(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "skip.txt"))
	cfg := &Config{Libraries: []Library{{ID: "lib1", Name: "library", Path: root, Enabled: true}}}

	ix.mu.Lock()
	ix.scanning = true
	ix.mu.Unlock()
	ix.UpdatePaths(cfg, IndexChange{Add: "/lib1/skip.txt"})
	time.Sleep(150 * time.Millisecond)
	ix.mu.Lock()
	ix.scanning = false
	ix.mu.Unlock()
	if indexPathExists(t, ix, "/lib1/skip.txt") {
		t.Error("incremental update ran during a full scan")
	}
}

func TestRemoveLibRowsDropsOnlyThatLibrary(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	seedIndexFile(t, ix, "lib1", "/lib1/a.txt", "a.txt")
	seedIndexFile(t, ix, "lib2", "/lib2/b.txt", "b.txt")

	ix.RemoveLibRows("lib1")
	waitIndexPath(t, ix, "/lib1/a.txt", false)
	if !indexPathExists(t, ix, "/lib2/b.txt") {
		t.Error("rows of another library were removed")
	}
}

func TestLikeEscape(t *testing.T) {
	cases := map[string]string{
		"plain":     "plain",
		"a%b":       `a\%b`,
		"a_b":       `a\_b`,
		`a\b`:       `a\\b`,
		"100%_done": `100\%\_done`,
	}
	for in, want := range cases {
		if got := likeEscape(in); got != want {
			t.Errorf("likeEscape(%q) = %q, want %q", in, got, want)
		}
	}
}
