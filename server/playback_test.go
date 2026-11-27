package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPlaybackStoreIsolationAndPersistence(t *testing.T) {
	dir := t.TempDir()
	st := newPlaybackStore(dir)
	st.set("id:u1", "/lib1/a.mp4", 42.5, 600)
	st.set("id:u2", "/lib1/a.mp4", 10, 600)

	if e, ok := st.get("id:u1", "/lib1/a.mp4"); !ok || e.Position != 42.5 {
		t.Fatalf("u1 progress = %+v, ok=%v", e, ok)
	}
	if e, ok := st.get("id:u2", "/lib1/a.mp4"); !ok || e.Position != 10 {
		t.Fatalf("u2 progress = %+v, ok=%v", e, ok)
	}
	if _, ok := st.get("guest", "/lib1/a.mp4"); ok {
		t.Fatal("guest must not see other users' progress")
	}

	// 重新打开：应从 playback.json 恢复。
	st2 := newPlaybackStore(dir)
	if _, ok := st2.get("id:u1", "/lib1/a.mp4"); !ok {
		t.Fatal("progress was not persisted")
	}
}

func TestPlaybackStoreDropsUnneededProgress(t *testing.T) {
	st := newPlaybackStore(t.TempDir())
	st.set("guest", "/lib1/x", 1.0, 100)
	if _, ok := st.get("guest", "/lib1/x"); ok {
		t.Fatal("progress below minimum should be removed")
	}
	st.set("guest", "/lib1/x", 99, 100)
	if _, ok := st.get("guest", "/lib1/x"); ok {
		t.Fatal("progress near the end should be removed")
	}
	st.set("guest", "/lib1/x", 30, 100)
	if e, ok := st.get("guest", "/lib1/x"); !ok || e.Position != 30 {
		t.Fatalf("progress should be kept, got %+v ok=%v", e, ok)
	}
}

func TestValidPlaybackPath(t *testing.T) {
	for _, p := range []string{"/lib1/a.mp4", "/lib2/dir/b.mkv"} {
		if !validPlaybackPath(p) {
			t.Fatalf("%q should be valid", p)
		}
	}
	if validPlaybackPath("") {
		t.Fatal("empty path should be invalid")
	}
	if validPlaybackPath("lib1/a.mp4") {
		t.Fatal("relative path should be invalid")
	}
	if validPlaybackPath("/lib1/../etc/passwd") {
		t.Fatal("traversal path should be invalid")
	}
	if validPlaybackPath("/" + strings.Repeat("a", playbackMaxVPath)) {
		t.Fatal("overlong path should be invalid")
	}
}

func TestPlaybackHandlers(t *testing.T) {
	s := &Server{playback: newPlaybackStore(t.TempDir())}
	sess := &Session{UserID: "u1", Username: "alice"}

	body := strings.NewReader(`{"vpath":"/lib1/a.mp4","position":42,"duration":600}`)
	req := httptest.NewRequest(http.MethodPost, "/api/playback", body)
	req = req.WithContext(withUser(req.Context(), sess))
	rec := httptest.NewRecorder()
	s.handlePlaybackSet(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("set status=%d body=%s", rec.Code, rec.Body.String())
	}

	getReq := httptest.NewRequest(http.MethodGet, "/api/playback?vpath=/lib1/a.mp4", nil)
	getReq = getReq.WithContext(withUser(getReq.Context(), sess))
	getRec := httptest.NewRecorder()
	s.handlePlaybackGet(getRec, getReq)
	if getRec.Code != http.StatusOK || !strings.Contains(getRec.Body.String(), `"found":true`) {
		t.Fatalf("get status=%d body=%s", getRec.Code, getRec.Body.String())
	}

	// 另一个账号看不到 u1 的进度。
	other := &Session{UserID: "u2", Username: "bob"}
	otherReq := httptest.NewRequest(http.MethodGet, "/api/playback?vpath=/lib1/a.mp4", nil)
	otherReq = otherReq.WithContext(withUser(otherReq.Context(), other))
	otherRec := httptest.NewRecorder()
	s.handlePlaybackGet(otherRec, otherReq)
	if !strings.Contains(otherRec.Body.String(), `"found":false`) {
		t.Fatalf("other user should not see progress: %s", otherRec.Body.String())
	}
}
