package main

import (
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
)

var (
	errBadPath   = pathError{"非法路径"}
	errNoLibrary = pathError{"存储库不存在或已禁用"}
	errReadOnly  = pathError{"存储库为只读"}
)

// pathError is a user-facing error message.
type pathError struct{ msg string }

func (e pathError) Error() string { return e.msg }

// Resolve converts a virtual path like /lib1/movies/foo.mp4 into the library
// it belongs to and the absolute filesystem path. It guards against any
// path traversal escaping the library root.
func (c *Config) Resolve(vpath string) (*Library, string, error) {
	if vpath == "" {
		vpath = "/"
	}
	vpath = strings.ReplaceAll(vpath, "\\", "/")
	vpath = path.Clean("/" + strings.TrimPrefix(vpath, "/"))

	parts := strings.Split(strings.TrimPrefix(vpath, "/"), "/")
	libID := parts[0]
	if libID == "" {
		return nil, "", errNoLibrary
	}
	lib := c.LibraryByID(libID)
	if lib == nil || !lib.Enabled {
		return nil, "", errNoLibrary
	}
	rel := ""
	if len(parts) > 1 {
		rel = strings.Join(parts[1:], "/")
	}
	abs := filepath.Clean(lib.Path)
	if rel != "" {
		abs = filepath.Join(lib.Path, filepath.FromSlash(rel))
	}
	if !withinRoot(lib.Path, abs) {
		return nil, "", errBadPath
	}
	return lib, abs, nil
}

// VirtualPath builds the virtual path for an absolute path inside lib.
func VirtualPath(lib *Library, abs string) string {
	rel, err := filepath.Rel(filepath.Clean(lib.Path), filepath.Clean(abs))
	if err != nil || strings.HasPrefix(rel, "..") {
		return "/" + lib.ID
	}
	rel = filepath.ToSlash(rel)
	if rel == "." || rel == "" {
		return "/" + lib.ID
	}
	return "/" + lib.ID + "/" + rel
}

// withinRoot reports whether p is root itself or inside root (case-insensitive
// on Windows).
func withinRoot(root, p string) bool {
	root = strings.ToLower(filepath.Clean(root))
	p = strings.ToLower(filepath.Clean(p))
	if p == root {
		return true
	}
	if !strings.HasSuffix(root, string(filepath.Separator)) {
		root += string(filepath.Separator)
	}
	return strings.HasPrefix(p, root)
}

// SafeName reports whether a bare file/dir name is acceptable.
func SafeName(name string) bool {
	if name == "" || name == "." || name == ".." {
		return false
	}
	if strings.ContainsAny(name, `<>:"/\|?*`) {
		return false
	}
	if strings.EqualFold(name, "con") || strings.EqualFold(name, "nul") ||
		strings.EqualFold(name, "aux") {
		return false
	}
	return len(name) <= 255
}

// ExcludedAbs reports whether abs lies inside one of the library's excluded
// sub-paths (relative to the library root).
func (c *Config) ExcludedAbs(lib *Library, abs string) bool {
	if lib == nil || len(lib.Exclude) == 0 {
		return false
	}
	root := filepath.Clean(lib.Path)
	for _, ex := range lib.Exclude {
		ex = strings.Trim(strings.ReplaceAll(ex, "\\", "/"), "/")
		if ex == "" {
			continue
		}
		target := filepath.Join(root, filepath.FromSlash(ex))
		if withinRoot(target, abs) {
			return true
		}
	}
	return false
}

// cleanExcludes normalises a user-supplied list of excluded sub-paths:
// trims whitespace, converts backslashes to slashes, strips leading/trailing
// slashes and drops empty entries.
func cleanExcludes(in []string) []string {
	if len(in) == 0 {
		return nil
	}
	out := make([]string, 0, len(in))
	seen := make(map[string]bool, len(in))
	for _, ex := range in {
		ex = strings.TrimSpace(strings.ReplaceAll(ex, "\\", "/"))
		ex = strings.Trim(ex, "/")
		if ex == "" || seen[ex] {
			continue
		}
		seen[ex] = true
		out = append(out, ex)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// JoinVirtual joins a virtual dir path with a bare name.
func JoinVirtual(dir, name string) string {
	dir = strings.TrimSuffix(dir, "/")
	if dir == "" {
		return "/" + name
	}
	return dir + "/" + name
}

// DirVirtual returns the parent virtual directory of a virtual path.
func DirVirtual(vp string) string {
	vp = strings.TrimSuffix(vp, "/")
	i := strings.LastIndex(vp, "/")
	if i <= 0 {
		return "/"
	}
	return vp[:i]
}

// BaseVirtual returns the last element of a virtual path.
func BaseVirtual(vp string) string {
	vp = strings.TrimSuffix(vp, "/")
	i := strings.LastIndex(vp, "/")
	return vp[i+1:]
}

// ListEntries describes one directory entry for the API.
type ListEntries struct {
	Name    string `json:"name"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"` // unix seconds
}

// dirEntries lists abs directory, sorted dirs-first then case-insensitive name.
func dirEntries(abs string) ([]ListEntries, error) {
	ents, err := os.ReadDir(abs)
	if err != nil {
		return nil, err
	}
	out := make([]ListEntries, 0, len(ents))
	for _, e := range ents {
		info, err := e.Info()
		if err != nil {
			continue // raced delete etc.
		}
		size := info.Size()
		if e.IsDir() {
			size = 0
		}
		out = append(out, ListEntries{
			Name:    e.Name(),
			IsDir:   e.IsDir(),
			Size:    size,
			ModTime: info.ModTime().Unix(),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return out, nil
}

// dirEntriesFiltered is dirEntries plus the library's excluded sub-paths and
// the hidden vault/trash directories.
func (s *Server) dirEntriesFiltered(lib *Library, abs string) ([]ListEntries, error) {
	ents, err := dirEntries(abs)
	if err != nil {
		return nil, err
	}
	vaultDir := filepath.Clean(s.vaultDir())
	trashDir := ""
	if lib != nil {
		trashDir = filepath.Clean(trashDirFor(lib))
	}
	out := make([]ListEntries, 0, len(ents))
	for _, e := range ents {
		child := filepath.Join(abs, e.Name)
		clean := filepath.Clean(child)
		if clean == vaultDir || (trashDir != "" && clean == trashDir) {
			continue
		}
		if e.IsDir && s.cfg.ExcludedAbs(lib, child) {
			continue
		}
		// 未完成的上传(.part)不显示,避免污染目录
		if !e.IsDir && strings.HasSuffix(strings.ToLower(e.Name), ".part") {
			continue
		}
		out = append(out, e)
	}
	return out, nil
}
