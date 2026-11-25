package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/webdav"
)

func (s *Server) withWebDAVAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Token == "" && !s.auth.MultiUser() {
			next.ServeHTTP(w, r.WithContext(withUser(r.Context(), &Session{Role: "admin"})))
			return
		}

		var sess *Session
		token := r.Header.Get("X-Token")
		if token == "" {
			token = r.URL.Query().Get("token")
		}
		if s.cfg.Token != "" && token == s.cfg.Token {
			sess = &Session{Username: "admin", Role: "admin", Token: token}
		} else if token != "" {
			sess = s.auth.Get(token)
		}

		if sess == nil {
			username, password, ok := r.BasicAuth()
			if ok {
				if username == "token" && s.cfg.Token != "" && password == s.cfg.Token {
					sess = &Session{Username: "admin", Role: "admin", Token: password}
				} else if authenticated, valid := s.auth.Authenticate(username, password); valid {
					sess = authenticated
				}
			}
		}
		if sess == nil {
			w.Header().Set("WWW-Authenticate", `Basic realm="linNas WebDAV", charset="UTF-8"`)
			writeErr(w, http.StatusUnauthorized, "需要 WebDAV 账号密码或访问口令")
			return
		}
		next.ServeHTTP(w, r.WithContext(withUser(r.Context(), sess)))
	})
}

func (s *Server) handleWebDAV(w http.ResponseWriter, r *http.Request) {
	const root = "/dav/"
	remainder := strings.TrimPrefix(r.URL.Path, root)
	if remainder == r.URL.Path {
		http.NotFound(w, r)
		return
	}
	libID := strings.SplitN(remainder, "/", 2)[0]
	lib := s.cfg.LibraryByID(libID)
	if lib == nil || !lib.Enabled {
		http.NotFound(w, r)
		return
	}
	sess := userFrom(r)
	role := "admin"
	if sess != nil && sess.Role != "" {
		role = sess.Role
	}
	if (role == "viewer" || lib.ReadOnly) && !webDAVReadMethod(r.Method) {
		writeErr(w, http.StatusForbidden, "只读用户或存储库不能执行 WebDAV 写操作")
		return
	}
	handler := &webdav.Handler{
		Prefix:     "/dav/" + libID,
		FileSystem: &davFileSystem{server: s, libID: libID, role: role},
		LockSystem: s.davLocks,
	}
	if r.Method == http.MethodPut {
		state := &davBodyState{}
		r = r.WithContext(context.WithValue(r.Context(), davBodyStateKey{}, state))
		r.Body = &davTrackingBody{ReadCloser: r.Body, state: state}
	}
	handler.ServeHTTP(w, r)
}

func webDAVReadMethod(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodOptions, "PROPFIND":
		return true
	default:
		return false
	}
}

type davBodyStateKey struct{}

type davBodyState struct {
	mu  sync.Mutex
	err error
}

func (s *davBodyState) setError(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err == nil {
		s.err = err
	}
}

func (s *davBodyState) error() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.err
}

type davTrackingBody struct {
	io.ReadCloser
	state *davBodyState
}

func (b *davTrackingBody) Read(p []byte) (int, error) {
	n, err := b.ReadCloser.Read(p)
	if err != nil && !errors.Is(err, io.EOF) {
		b.state.setError(err)
	}
	return n, err
}

type davFileSystem struct {
	server *Server
	libID  string
	role   string
}

type davPath struct {
	lib  *Library
	abs  string
	root string
}

func (s *Server) auditWebDAV(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodPut, http.MethodDelete, "MKCOL", "MOVE", "COPY", "PROPPATCH":
		default:
			next.ServeHTTP(w, r)
			return
		}
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.audit.Add(AuditEntry{
			Time: time.Now().Unix(), User: currentUserName(r), IP: clientIP(r),
			Action: "WebDAV " + r.Method, Method: r.Method, Path: r.URL.Path, Status: rec.status,
		})
	})
}

func (fsys *davFileSystem) resolve(name string, write bool) (davPath, error) {
	if strings.ContainsRune(name, '\x00') || strings.Contains(name, `\`) {
		return davPath{}, os.ErrNotExist
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return davPath{}, os.ErrNotExist
		}
	}
	clean := path.Clean("/" + name)
	relative := strings.TrimPrefix(clean, "/")
	lib := fsys.server.cfg.LibraryByID(fsys.libID)
	if lib == nil || !lib.Enabled {
		return davPath{}, os.ErrNotExist
	}
	vpath := "/" + fsys.libID
	if relative != "" && relative != "." {
		vpath += "/" + relative
	}
	resolvedLib, abs, err := fsys.server.cfg.Resolve(vpath)
	if err != nil || resolvedLib.ID != lib.ID {
		return davPath{}, os.ErrNotExist
	}
	rootReal, err := filepath.EvalSymlinks(lib.Path)
	if err != nil {
		return davPath{}, err
	}
	relative, err = filepath.Rel(filepath.Clean(lib.Path), abs)
	if err != nil || strings.HasPrefix(relative, "..") {
		return davPath{}, os.ErrPermission
	}
	realPath, err := resolveRealPath(rootReal, filepath.Join(rootReal, relative))
	if err != nil {
		return davPath{}, err
	}
	if !withinRoot(rootReal, realPath) {
		return davPath{}, os.ErrPermission
	}
	if fsys.hiddenPath(lib, abs, realPath, rootReal) {
		return davPath{}, os.ErrNotExist
	}
	if write {
		if fsys.role == "viewer" || lib.ReadOnly {
			return davPath{}, os.ErrPermission
		}
		if realPath != rootReal && !SafeName(filepath.Base(realPath)) {
			return davPath{}, os.ErrInvalid
		}
	}
	return davPath{lib: lib, abs: realPath, root: rootReal}, nil
}

func (fsys *davFileSystem) hiddenPath(lib *Library, abs, realPath, rootReal string) bool {
	if fsys.server.cfg.ExcludedAbs(lib, abs) ||
		strings.HasSuffix(strings.ToLower(filepath.Base(abs)), ".part") ||
		strings.HasSuffix(strings.ToLower(filepath.Base(realPath)), ".part") {
		return true
	}
	for _, exclude := range lib.Exclude {
		exclude = strings.Trim(strings.ReplaceAll(exclude, `\`, "/"), "/")
		if exclude != "" && withinRoot(filepath.Join(rootReal, filepath.FromSlash(exclude)), realPath) {
			return true
		}
	}
	if fsys.server.inVault(abs) || fsys.server.inTrash(abs) {
		return true
	}
	if vaultRoot, err := filepath.EvalSymlinks(fsys.server.vaultDir()); err == nil &&
		withinRoot(vaultRoot, realPath) {
		return true
	}
	for _, other := range fsys.server.cfg.Libraries {
		trashRoot := trashDirForLib(other)
		if realTrash, err := filepath.EvalSymlinks(trashRoot); err == nil &&
			withinRoot(realTrash, realPath) {
			return true
		}
	}
	return false
}

func resolveRealPath(root, abs string) (string, error) {
	abs = filepath.Clean(abs)
	current := abs
	var missing []string
	for {
		real, err := filepath.EvalSymlinks(current)
		if err == nil {
			for i := len(missing) - 1; i >= 0; i-- {
				real = filepath.Join(real, missing[i])
			}
			return filepath.Clean(real), nil
		}
		if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
		if _, lstatErr := os.Lstat(current); lstatErr == nil {
			return "", os.ErrPermission
		} else if !errors.Is(lstatErr, os.ErrNotExist) {
			return "", lstatErr
		}
		parent := filepath.Dir(current)
		if parent == current || !withinRoot(root, parent) {
			return "", os.ErrPermission
		}
		missing = append(missing, filepath.Base(current))
		current = parent
	}
}

func davWriteFlags(flag int) bool {
	return flag&(os.O_WRONLY|os.O_RDWR|os.O_CREATE|os.O_TRUNC|os.O_APPEND) != 0
}

func (fsys *davFileSystem) Mkdir(_ context.Context, name string, perm os.FileMode) error {
	target, err := fsys.resolve(name, true)
	if err != nil {
		return err
	}
	if target.abs == target.root {
		return os.ErrInvalid
	}
	return os.Mkdir(target.abs, perm)
}

func (fsys *davFileSystem) OpenFile(ctx context.Context, name string, flag int, perm os.FileMode) (webdav.File, error) {
	write := davWriteFlags(flag)
	target, err := fsys.resolve(name, write)
	if err != nil {
		return nil, err
	}
	stageWrite := flag&(os.O_CREATE|os.O_TRUNC|os.O_APPEND|os.O_WRONLY) != 0
	if write && stageWrite {
		if target.abs == target.root {
			return nil, os.ErrInvalid
		}
		existing, statErr := os.Lstat(target.abs)
		if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
			return nil, statErr
		}
		if statErr == nil && flag&os.O_EXCL != 0 {
			return nil, os.ErrExist
		}
		if statErr != nil && flag&os.O_CREATE == 0 {
			return nil, os.ErrNotExist
		}
		if statErr == nil && existing.IsDir() {
			return nil, os.ErrInvalid
		}
		stage, err := os.CreateTemp(filepath.Dir(target.abs), "."+filepath.Base(target.abs)+"-*.part")
		if err != nil {
			return nil, err
		}
		cleanup := func() {
			_ = stage.Close()
			_ = os.Remove(stage.Name())
		}
		if err := stage.Chmod(perm); err != nil {
			cleanup()
			return nil, err
		}
		if statErr == nil && flag&os.O_TRUNC == 0 {
			source, err := os.Open(target.abs)
			if err != nil {
				cleanup()
				return nil, err
			}
			_, copyErr := io.Copy(stage, source)
			closeErr := source.Close()
			if copyErr != nil {
				cleanup()
				return nil, copyErr
			}
			if closeErr != nil {
				cleanup()
				return nil, closeErr
			}
		}
		if flag&os.O_APPEND == 0 {
			if _, err := stage.Seek(0, io.SeekStart); err != nil {
				cleanup()
				return nil, err
			}
		}
		return &davFile{
			File: stage, fs: fsys, name: name,
			stagingPath: stage.Name(), target: target,
			bodyState: bodyStateFromContext(ctx),
		}, nil
	}
	file, err := os.OpenFile(target.abs, flag, perm)
	if err != nil {
		return nil, err
	}
	return &davFile{File: file, fs: fsys, name: name}, nil
}

func bodyStateFromContext(ctx context.Context) *davBodyState {
	state, _ := ctx.Value(davBodyStateKey{}).(*davBodyState)
	return state
}

func (fsys *davFileSystem) RemoveAll(_ context.Context, name string) error {
	target, err := fsys.resolve(name, true)
	if err != nil {
		return err
	}
	if target.abs == target.root {
		return os.ErrInvalid
	}
	_, err = fsys.server.moveToTrash(target.lib, target.abs)
	return err
}

func (fsys *davFileSystem) Rename(_ context.Context, oldName, newName string) error {
	source, err := fsys.resolve(oldName, true)
	if err != nil {
		return err
	}
	target, err := fsys.resolve(newName, true)
	if err != nil {
		return err
	}
	if source.lib.ID != target.lib.ID || source.abs == source.root || target.abs == target.root {
		return os.ErrInvalid
	}
	if source.abs == target.abs {
		return nil
	}
	return os.Rename(source.abs, target.abs)
}

func (fsys *davFileSystem) Stat(_ context.Context, name string) (os.FileInfo, error) {
	target, err := fsys.resolve(name, false)
	if err != nil {
		return nil, err
	}
	return os.Stat(target.abs)
}

type davFile struct {
	*os.File
	fs          *davFileSystem
	name        string
	stagingPath string
	target      davPath
	bodyState   *davBodyState
	mu          sync.Mutex
	list        []os.FileInfo
	pos         int
	read        bool
	closed      bool
	closeErr    error
}

func (f *davFile) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return f.closeErr
	}
	f.closed = true
	if f.stagingPath != "" {
		defer func() { _ = os.Remove(f.stagingPath) }()
	}
	if err := f.File.Close(); err != nil {
		f.closeErr = err
	}
	if f.stagingPath == "" || f.closeErr != nil {
		return f.closeErr
	}
	if f.bodyState != nil {
		if err := f.bodyState.error(); err != nil {
			f.closeErr = err
			return f.closeErr
		}
	}
	if _, err := os.Lstat(f.target.abs); err == nil {
		if _, err := f.fs.server.moveToTrash(f.target.lib, f.target.abs); err != nil {
			f.closeErr = err
			return f.closeErr
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		f.closeErr = err
		return f.closeErr
	}
	f.closeErr = os.Rename(f.stagingPath, f.target.abs)
	return f.closeErr
}

func (f *davFile) Readdir(count int) ([]os.FileInfo, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.read {
		entries, err := f.File.Readdir(0)
		if err != nil {
			return nil, err
		}
		for _, entry := range entries {
			child := path.Join(f.name, entry.Name())
			if _, err := f.fs.resolve(child, false); err == nil {
				f.list = append(f.list, entry)
			}
		}
		f.read = true
	}
	if f.pos >= len(f.list) {
		if count > 0 {
			return nil, io.EOF
		}
		return nil, nil
	}
	start := f.pos
	if count <= 0 {
		f.pos = len(f.list)
	} else {
		f.pos += count
		if f.pos > len(f.list) {
			f.pos = len(f.list)
		}
	}
	return f.list[start:f.pos], nil
}

var _ webdav.FileSystem = (*davFileSystem)(nil)
var _ webdav.File = (*davFile)(nil)
