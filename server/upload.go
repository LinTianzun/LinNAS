package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// chunkSize is the fixed upload chunk size (5 MiB).
const chunkSize = 5 << 20

// uploadSession tracks one in-flight resumable upload.
type uploadSession struct {
	mu        sync.Mutex
	ID        string
	LibID     string
	DestAbs   string
	Size      int64
	Received  int64
	StartedAt int64
}

type uploadSessionStore struct {
	mu       sync.RWMutex
	sessions map[string]*uploadSession
}

func newUploadSessionStore() *uploadSessionStore {
	return &uploadSessionStore{sessions: make(map[string]*uploadSession)}
}

func (us *uploadSessionStore) reserve(libID, dir, name string, size int64) (*uploadSession, error) {
	us.mu.Lock()
	defer us.mu.Unlock()

	for suffix := 0; ; suffix++ {
		candidateName := name
		if suffix > 0 {
			ext := filepath.Ext(name)
			base := strings.TrimSuffix(name, ext)
			candidateName = fmt.Sprintf("%s (%d)%s", base, suffix, ext)
		}
		dest := filepath.Join(dir, candidateName)

		if _, err := os.Stat(dest); err == nil {
			continue
		} else if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}

		inUse := false
		for _, active := range us.sessions {
			if strings.EqualFold(filepath.Clean(active.DestAbs), filepath.Clean(dest)) {
				inUse = true
				break
			}
		}
		if inUse {
			continue
		}

		received := int64(0)
		if st, err := os.Stat(dest + ".part"); err == nil {
			received = st.Size()
			if size > 0 && received > size {
				return nil, fmt.Errorf("已有临时文件大小超过本次上传声明")
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}

		sess := &uploadSession{
			ID:        newUploadID(),
			LibID:     libID,
			DestAbs:   dest,
			Size:      size,
			Received:  received,
			StartedAt: time.Now().Unix(),
		}
		us.sessions[sess.ID] = sess
		return sess, nil
	}
}

func (us *uploadSessionStore) get(id string) (*uploadSession, bool) {
	us.mu.RLock()
	defer us.mu.RUnlock()
	sess, ok := us.sessions[id]
	return sess, ok
}

func (us *uploadSessionStore) isCurrent(sess *uploadSession) bool {
	us.mu.RLock()
	defer us.mu.RUnlock()
	return us.sessions[sess.ID] == sess
}

func (us *uploadSessionStore) remove(sess *uploadSession) {
	us.mu.Lock()
	defer us.mu.Unlock()
	if us.sessions[sess.ID] == sess {
		delete(us.sessions, sess.ID)
	}
}

// ---- handlers ----

// handleUploadInit creates an upload session.
// Request: {"vpath":"/lib1/videos","name":"movie.mp4","size":12345}
// Response: {"uploadId":"...","received":0,"chunkSize":5242880}
func (s *Server) handleUploadInit(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath string `json:"vpath"`
		Name  string `json:"name"`
		Size  int64  `json:"size"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if req.Size < 0 {
		writeErr(w, 400, "文件大小不能为负数")
		return
	}
	if !SafeName(req.Name) {
		writeErr(w, 400, "文件名不合法")
		return
	}
	lib, dirAbs, err := s.cfg.Resolve(req.VPath)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if s.inVault(dirAbs) {
		writeErr(w, 403, "请通过「隐私保险箱」上传")
		return
	}
	if lib.ReadOnly {
		writeErr(w, 403, errReadOnly.Error())
		return
	}
	if info, err := os.Stat(dirAbs); err != nil || !info.IsDir() {
		writeErr(w, 404, "目标目录不存在")
		return
	}
	sess, err := s.uploads.reserve(lib.ID, dirAbs, req.Name, req.Size)
	if err != nil {
		writeErr(w, 500, "准备上传失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{
		"uploadId":  sess.ID,
		"received":  sess.Received,
		"chunkSize": chunkSize,
	})
}

// handleUploadStatus reports progress for resume.
func (s *Server) handleUploadStatus(w http.ResponseWriter, r *http.Request) {
	sess, ok := s.uploads.get(r.URL.Query().Get("id"))
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if !s.uploads.isCurrent(sess) {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	writeJSON(w, 200, map[string]any{
		"received":  sess.Received,
		"size":      sess.Size,
		"chunkSize": chunkSize,
		"done":      sess.Size > 0 && sess.Received >= sess.Size,
	})
}

// handleUploadChunk appends one chunk. Query: id, offset. Body: raw bytes.
func (s *Server) handleUploadChunk(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	if err != nil {
		writeErr(w, 400, "offset 无效")
		return
	}
	sess, ok := s.uploads.get(id)
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if !s.uploads.isCurrent(sess) {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	if offset != sess.Received {
		writeErr(w, 409, fmt.Sprintf("offset 不匹配,期望 %d,收到 %d", sess.Received, offset))
		return
	}
	maxChunk := int64(chunkSize)
	if sess.Size > 0 {
		remaining := sess.Size - sess.Received
		if remaining <= 0 {
			writeErr(w, 409, "上传数据已达到声明大小")
			return
		}
		if remaining < maxChunk {
			maxChunk = remaining
		}
	}
	if r.ContentLength > maxChunk {
		writeErr(w, http.StatusRequestEntityTooLarge, "分块超过允许大小")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxChunk)

	f, err := os.OpenFile(sess.DestAbs+".part", os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		writeErr(w, 500, "写文件失败: "+err.Error())
		return
	}
	start := sess.Received
	if err := f.Truncate(start); err != nil {
		if closeErr := f.Close(); closeErr != nil {
			writeErr(w, 500, "准备临时文件失败: "+err.Error()+"; 关闭文件失败: "+closeErr.Error())
			return
		}
		writeErr(w, 500, "准备临时文件失败: "+err.Error())
		return
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		if closeErr := f.Close(); closeErr != nil {
			writeErr(w, 500, "定位临时文件失败: "+err.Error()+"; 关闭文件失败: "+closeErr.Error())
			return
		}
		writeErr(w, 500, "定位临时文件失败: "+err.Error())
		return
	}
	n, copyErr := io.Copy(f, r.Body)
	if copyErr != nil {
		truncateErr := f.Truncate(start)
		closeErr := f.Close()
		if truncateErr != nil {
			writeErr(w, 500, "回滚不完整分块失败: "+truncateErr.Error())
			return
		}
		if closeErr != nil {
			writeErr(w, 500, "关闭临时文件失败: "+closeErr.Error())
			return
		}
		var maxErr *http.MaxBytesError
		if errors.As(copyErr, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, "分块超过允许大小")
			return
		}
		writeErr(w, 500, "接收数据失败: "+copyErr.Error())
		return
	}
	closeErr := f.Close()
	if closeErr != nil {
		if truncateErr := os.Truncate(sess.DestAbs+".part", start); truncateErr != nil {
			writeErr(w, 500, "关闭文件失败: "+closeErr.Error()+"; 回滚临时文件失败: "+truncateErr.Error())
			return
		}
		writeErr(w, 500, "关闭文件失败: "+closeErr.Error())
		return
	}
	if n == 0 {
		writeErr(w, 400, "上传分块不能为空")
		return
	}
	sess.Received += n
	writeJSON(w, 200, map[string]any{"received": sess.Received})
}

// handleUploadComplete renames .part to the final file.
func (s *Server) handleUploadComplete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UploadID string `json:"uploadId"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	sess, ok := s.uploads.get(req.UploadID)
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if !s.uploads.isCurrent(sess) {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	if sess.Size > 0 && sess.Received != sess.Size {
		writeErr(w, 409, fmt.Sprintf("已接收 %d 字节,声明大小为 %d 字节", sess.Received, sess.Size))
		return
	}
	lib := s.cfg.LibraryByID(sess.LibID)
	if lib == nil {
		writeErr(w, 500, "存储库已移除")
		return
	}
	if err := os.Rename(sess.DestAbs+".part", sess.DestAbs); err != nil {
		writeErr(w, 500, "完成失败: "+err.Error())
		return
	}
	s.uploads.remove(sess)
	writeJSON(w, 200, map[string]string{
		"vpath": VirtualPath(lib, sess.DestAbs),
		"name":  filepath.Base(sess.DestAbs),
	})
}

// handleUploadAbort discards a session and its partial file (used when the
// user cancels an upload).
func (s *Server) handleUploadAbort(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UploadID string `json:"uploadId"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	sess, ok := s.uploads.get(req.UploadID)
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if !s.uploads.isCurrent(sess) {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	if err := os.Remove(sess.DestAbs + ".part"); err != nil && !errors.Is(err, os.ErrNotExist) {
		writeErr(w, 500, "清理临时文件失败: "+err.Error())
		return
	}
	s.uploads.remove(sess)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---- helpers ----

func newUploadID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// uniqueName finds "name (1).ext", "name (2).ext" ... inside dir.
func uniqueName(dir, name string) string {
	x := filepath.Ext(name)
	base := strings.TrimSuffix(name, x)
	for i := 1; ; i++ {
		cand := fmt.Sprintf("%s (%d)%s", base, i, x)
		if _, err := os.Stat(filepath.Join(dir, cand)); err != nil {
			return cand
		}
	}
}
