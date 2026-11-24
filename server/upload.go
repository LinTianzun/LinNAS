package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// chunkSize is the fixed upload chunk size (5 MiB).
const chunkSize = 5 << 20

// uploadSession tracks one in-flight resumable upload.
type uploadSession struct {
	ID        string
	LibID     string
	DestAbs   string
	Size      int64
	Received  int64
	StartedAt int64
}

// uploadSessions is in-memory state; good enough for M1 (lost on restart,
// client re-inits and the .part file lets it resume by byte offset).
var uploadSessions = map[string]*uploadSession{}

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
	dst := filepath.Join(dirAbs, req.Name)
	if _, err := os.Stat(dst); err == nil {
		dst = uniqueName(dirAbs, req.Name) // 已存在则自动加 (1) 后缀
	}
	// 断点续传:已有 .part 就按它的字节数续传
	received := int64(0)
	if st, err := os.Stat(dst + ".part"); err == nil {
		received = st.Size()
	}
	sess := &uploadSession{
		ID:        newUploadID(),
		LibID:     lib.ID,
		DestAbs:   dst,
		Size:      req.Size,
		Received:  received,
		StartedAt: time.Now().Unix(),
	}
	uploadSessions[sess.ID] = sess
	writeJSON(w, 200, map[string]any{
		"uploadId":  sess.ID,
		"received":  sess.Received,
		"chunkSize": chunkSize,
	})
}

// handleUploadStatus reports progress for resume.
func (s *Server) handleUploadStatus(w http.ResponseWriter, r *http.Request) {
	sess, ok := uploadSessions[r.URL.Query().Get("id")]
	if !ok {
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
	sess, ok := uploadSessions[id]
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	if offset != sess.Received {
		writeErr(w, 409, fmt.Sprintf("offset 不匹配,期望 %d,收到 %d", sess.Received, offset))
		return
	}
	f, err := os.OpenFile(sess.DestAbs+".part", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		writeErr(w, 500, "写文件失败: "+err.Error())
		return
	}
	n, copyErr := io.Copy(f, r.Body)
	closeErr := f.Close()
	if copyErr != nil {
		writeErr(w, 500, "接收数据失败: "+copyErr.Error())
		return
	}
	if closeErr != nil {
		writeErr(w, 500, "关闭文件失败: "+closeErr.Error())
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
	sess, ok := uploadSessions[req.UploadID]
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	if sess.Size > 0 && sess.Received < sess.Size {
		writeErr(w, 409, fmt.Sprintf("还差 %d 字节", sess.Size-sess.Received))
		return
	}
	if err := os.Rename(sess.DestAbs+".part", sess.DestAbs); err != nil {
		writeErr(w, 500, "完成失败: "+err.Error())
		return
	}
	delete(uploadSessions, sess.ID)
	lib := s.cfg.LibraryByID(sess.LibID)
	if lib == nil {
		writeErr(w, 500, "存储库已移除")
		return
	}
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
	sess, ok := uploadSessions[req.UploadID]
	if !ok {
		writeErr(w, 404, "上传会话不存在")
		return
	}
	_ = os.Remove(sess.DestAbs + ".part")
	delete(uploadSessions, sess.ID)
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
