package main

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

type failingWebDAVBody struct {
	sent bool
}

func (b *failingWebDAVBody) Read(p []byte) (int, error) {
	if !b.sent {
		b.sent = true
		return copy(p, []byte("partial")), nil
	}
	return 0, errors.New("injected request body failure")
}

func (b *failingWebDAVBody) Close() error { return nil }

func newWebDAVTestServer(t *testing.T, readOnly bool) (*Server, string) {
	t.Helper()
	root := t.TempDir()
	libPath := filepath.Join(root, "library")
	if err := os.MkdirAll(libPath, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		DataDir: root,
		Libraries: []Library{{
			ID: "lib1", Name: "test", Path: libPath, Enabled: true, ReadOnly: readOnly,
		}},
	}
	return NewServer(cfg), libPath
}

func TestWebDAVPutAndGet(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	put := httptest.NewRequest(http.MethodPut, "/dav/lib1/hello.txt", bytes.NewBufferString("hello"))
	putRec := httptest.NewRecorder()
	s.Handler().ServeHTTP(putRec, put)
	if putRec.Code != http.StatusCreated && putRec.Code != http.StatusNoContent {
		t.Fatalf("PUT status = %d, want 201 or 204: %s", putRec.Code, putRec.Body.String())
	}
	data, err := os.ReadFile(filepath.Join(libPath, "hello.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello" {
		t.Fatalf("uploaded content = %q, want %q", data, "hello")
	}

	get := httptest.NewRequest(http.MethodGet, "/dav/lib1/hello.txt", nil)
	getRec := httptest.NewRecorder()
	s.Handler().ServeHTTP(getRec, get)
	if getRec.Code != http.StatusOK || getRec.Body.String() != "hello" {
		t.Fatalf("GET = %d %q, want 200 %q", getRec.Code, getRec.Body.String(), "hello")
	}
}

func TestWebDAVOverwriteMovesPreviousFileToTrash(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	target := filepath.Join(libPath, "existing.txt")
	if err := os.WriteFile(target, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}

	put := httptest.NewRequest(http.MethodPut, "/dav/lib1/existing.txt", bytes.NewBufferString("new"))
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, put)
	if rec.Code != http.StatusCreated {
		t.Fatalf("PUT status = %d, want 201: %s", rec.Code, rec.Body.String())
	}
	data, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "new" {
		t.Fatalf("overwritten content = %q, want %q", data, "new")
	}

	trashEntries, err := os.ReadDir(filepath.Join(libPath, ".linNasTrash"))
	if err != nil {
		t.Fatal(err)
	}
	var trashedName string
	for _, entry := range trashEntries {
		if !entry.IsDir() {
			trashedName = entry.Name()
		}
	}
	if trashedName == "" {
		t.Fatal("trash does not contain the replaced file")
	}
	trashed, err := os.ReadFile(filepath.Join(libPath, ".linNasTrash", trashedName))
	if err != nil {
		t.Fatal(err)
	}
	if string(trashed) != "old" {
		t.Fatalf("trashed content = %q, want %q", trashed, "old")
	}
}

func TestWebDAVIncompletePutPreservesPreviousFile(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	target := filepath.Join(libPath, "existing.txt")
	if err := os.WriteFile(target, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}

	put := httptest.NewRequest(http.MethodPut, "/dav/lib1/existing.txt", nil)
	put.Body = &failingWebDAVBody{}
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, put)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PUT status = %d, want 405: %s", rec.Code, rec.Body.String())
	}
	data, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "old" {
		t.Fatalf("existing content after incomplete PUT = %q, want %q", data, "old")
	}
	staged, err := filepath.Glob(filepath.Join(libPath, ".existing.txt-*.part"))
	if err != nil {
		t.Fatal(err)
	}
	if len(staged) != 0 {
		t.Fatalf("unexpected staged upload remains: %v", staged)
	}
}

func TestWebDAVAuthenticationAndViewerWriteRestriction(t *testing.T) {
	s, _ := newWebDAVTestServer(t, false)
	if _, err := s.auth.CreateUser("viewer", "test-password", "viewer"); err != nil {
		t.Fatal(err)
	}

	unauthenticated := httptest.NewRequest(http.MethodGet, "/dav/lib1/", nil)
	unauthenticatedRec := httptest.NewRecorder()
	s.Handler().ServeHTTP(unauthenticatedRec, unauthenticated)
	if unauthenticatedRec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d, want 401", unauthenticatedRec.Code)
	}

	put := httptest.NewRequest(http.MethodPut, "/dav/lib1/blocked.txt", bytes.NewBufferString("no"))
	put.SetBasicAuth("viewer", "test-password")
	putRec := httptest.NewRecorder()
	s.Handler().ServeHTTP(putRec, put)
	if putRec.Code != http.StatusForbidden {
		t.Fatalf("viewer PUT status = %d, want 403: %s", putRec.Code, putRec.Body.String())
	}
}

func TestWebDAVHidesVaultTrashExcludedAndPartialFiles(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	excluded := filepath.Join(libPath, "excluded")
	vault := filepath.Join(libPath, ".linNasVault")
	trash := filepath.Join(libPath, ".linNasTrash")
	for _, dir := range []string{excluded, vault, trash} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	s.cfg.Libraries[0].Exclude = []string{"excluded"}
	fsys := &davFileSystem{server: s, libID: "lib1", role: "admin"}
	for _, name := range []string{"/excluded", "/.linNasVault", "/.linNasTrash", "/unfinished.part", "/../outside"} {
		if _, err := fsys.resolve(name, false); err == nil {
			t.Errorf("resolve(%q) unexpectedly succeeded", name)
		}
	}
}

func TestWebDAVHidesVaultReachedThroughSymlink(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	vaultPath := filepath.Join(libPath, ".linNasVault")
	if err := os.MkdirAll(vaultPath, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(vaultPath, "secret.txt"), []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(libPath, "vault-link")
	if err := os.Symlink(vaultPath, alias); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}

	fsys := &davFileSystem{server: s, libID: "lib1", role: "admin"}
	if _, err := fsys.resolve("/vault-link/secret.txt", false); err == nil {
		t.Fatal("resolving a symlink into the vault unexpectedly succeeded")
	}
}

func TestWebDAVRejectsBrokenSymlinkWrites(t *testing.T) {
	s, libPath := newWebDAVTestServer(t, false)
	link := filepath.Join(libPath, "broken-link")
	target := filepath.Join(t.TempDir(), "not-created.txt")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}

	fsys := &davFileSystem{server: s, libID: "lib1", role: "admin"}
	if _, err := fsys.resolve("/broken-link", true); err == nil {
		t.Fatal("resolving a broken symlink for writing unexpectedly succeeded")
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("outside symlink target unexpectedly exists: %v", err)
	}
}

func TestWebDAVReadOnlyLibraryRejectsWrites(t *testing.T) {
	s, _ := newWebDAVTestServer(t, true)
	put := httptest.NewRequest(http.MethodPut, "/dav/lib1/read-only.txt", bytes.NewBufferString("no"))
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, put)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("read-only library PUT status = %d, want 403: %s", rec.Code, rec.Body.String())
	}
}
