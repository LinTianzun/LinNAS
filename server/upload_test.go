package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func newUploadTestServer(t *testing.T) *Server {
	t.Helper()
	root := t.TempDir()
	libDir := filepath.Join(root, "library")
	if err := os.MkdirAll(libDir, 0o755); err != nil {
		t.Fatal(err)
	}
	return &Server{
		cfg: &Config{
			DataDir: root,
			Libraries: []Library{{
				ID:      "lib1",
				Name:    "test",
				Path:    libDir,
				Enabled: true,
			}},
		},
		uploads: newUploadSessionStore(),
	}
}

func startTestUpload(s *Server, name string, size int64) (*uploadSession, int, error) {
	body, err := json.Marshal(map[string]any{
		"vpath": "/lib1",
		"name":  name,
		"size":  size,
	})
	if err != nil {
		return nil, 0, err
	}
	req := httptest.NewRequest(http.MethodPost, "/api/upload/init", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	s.handleUploadInit(rec, req)
	if rec.Code != http.StatusOK {
		return nil, rec.Code, fmt.Errorf("init failed: %s", rec.Body.String())
	}
	var response struct {
		UploadID string `json:"uploadId"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
		return nil, rec.Code, err
	}
	sess, ok := s.uploads.get(response.UploadID)
	if !ok {
		return nil, rec.Code, fmt.Errorf("upload session not found")
	}
	return sess, rec.Code, nil
}

func TestUploadInitReservesDistinctPathsConcurrently(t *testing.T) {
	s := newUploadTestServer(t)
	const count = 24
	results := make(chan *uploadSession, count)
	errs := make(chan error, count)
	var wg sync.WaitGroup

	for i := 0; i < count; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sess, _, err := startTestUpload(s, "same.bin", 1)
			if err != nil {
				errs <- err
				return
			}
			results <- sess
		}()
	}
	wg.Wait()
	close(results)
	close(errs)

	for err := range errs {
		t.Error(err)
	}
	paths := make(map[string]bool, count)
	for sess := range results {
		if paths[filepath.Clean(sess.DestAbs)] {
			t.Errorf("duplicate destination reserved: %q", sess.DestAbs)
		}
		paths[filepath.Clean(sess.DestAbs)] = true
	}
	if len(paths) != count {
		t.Fatalf("got %d distinct destinations, want %d", len(paths), count)
	}
}

func TestConcurrentChunksForSessionSerializeOffsets(t *testing.T) {
	s := newUploadTestServer(t)
	sess, _, err := startTestUpload(s, "movie.bin", 5)
	if err != nil {
		t.Fatal(err)
	}

	start := make(chan struct{})
	statuses := make(chan int, 2)
	bodies := [][]byte{[]byte("first"), []byte("other")}
	var wg sync.WaitGroup
	for _, body := range bodies {
		wg.Add(1)
		go func(body []byte) {
			defer wg.Done()
			<-start
			url := fmt.Sprintf("/api/upload/chunk?id=%s&offset=0", sess.ID)
			req := httptest.NewRequest(http.MethodPost, url, bytes.NewReader(body))
			rec := httptest.NewRecorder()
			s.handleUploadChunk(rec, req)
			statuses <- rec.Code
		}(body)
	}
	close(start)
	wg.Wait()
	close(statuses)

	got := map[int]int{}
	for status := range statuses {
		got[status]++
	}
	if got[http.StatusOK] != 1 || got[http.StatusConflict] != 1 {
		t.Fatalf("chunk responses = %v, want one 200 and one 409", got)
	}

	sess.mu.Lock()
	received := sess.Received
	sess.mu.Unlock()
	if received != 5 {
		t.Fatalf("received = %d, want 5", received)
	}
	data, err := os.ReadFile(sess.DestAbs + ".part")
	if err != nil {
		t.Fatal(err)
	}
	if len(data) != 5 {
		t.Fatalf("partial file length = %d, want 5", len(data))
	}
}

func TestUploadChunkOverLimitRollsBackPartialData(t *testing.T) {
	s := newUploadTestServer(t)
	sess, _, err := startTestUpload(s, "small.bin", 4)
	if err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest(http.MethodPost,
		fmt.Sprintf("/api/upload/chunk?id=%s&offset=0", sess.ID),
		bytes.NewReader([]byte("12345")))
	req.ContentLength = -1
	rec := httptest.NewRecorder()
	s.handleUploadChunk(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want 413; body: %s", rec.Code, rec.Body.String())
	}

	sess.mu.Lock()
	received := sess.Received
	sess.mu.Unlock()
	if received != 0 {
		t.Fatalf("received = %d, want 0", received)
	}
	info, err := os.Stat(sess.DestAbs + ".part")
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() != 0 {
		t.Fatalf("partial file size = %d, want 0", info.Size())
	}
}

func TestUploadResumesPartialFileAndCompletes(t *testing.T) {
	s := newUploadTestServer(t)
	libDir := s.cfg.Libraries[0].Path
	partPath := filepath.Join(libDir, "resume.bin.part")
	if err := os.WriteFile(partPath, []byte("ab"), 0o644); err != nil {
		t.Fatal(err)
	}

	sess, _, err := startTestUpload(s, "resume.bin", 5)
	if err != nil {
		t.Fatal(err)
	}
	if sess.Received != 2 {
		t.Fatalf("initial received = %d, want 2", sess.Received)
	}

	chunkURL := fmt.Sprintf("/api/upload/chunk?id=%s&offset=2", sess.ID)
	chunkReq := httptest.NewRequest(http.MethodPost, chunkURL, bytes.NewReader([]byte("cde")))
	chunkRec := httptest.NewRecorder()
	s.handleUploadChunk(chunkRec, chunkReq)
	if chunkRec.Code != http.StatusOK {
		t.Fatalf("chunk status = %d, want 200; body: %s", chunkRec.Code, chunkRec.Body.String())
	}

	completeBody, _ := json.Marshal(map[string]string{"uploadId": sess.ID})
	completeReq := httptest.NewRequest(http.MethodPost, "/api/upload/complete", bytes.NewReader(completeBody))
	completeRec := httptest.NewRecorder()
	s.handleUploadComplete(completeRec, completeReq)
	if completeRec.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body: %s", completeRec.Code, completeRec.Body.String())
	}
	data, err := os.ReadFile(filepath.Join(libDir, "resume.bin"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "abcde" {
		t.Fatalf("completed data = %q, want %q", data, "abcde")
	}
}

func TestUploadCompleteRequiresDeclaredSize(t *testing.T) {
	s := newUploadTestServer(t)
	sess, _, err := startTestUpload(s, "incomplete.bin", 3)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]string{"uploadId": sess.ID})
	req := httptest.NewRequest(http.MethodPost, "/api/upload/complete", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	s.handleUploadComplete(rec, req)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want 409; body: %s", rec.Code, rec.Body.String())
	}
}
