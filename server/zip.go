package main

import (
	"archive/zip"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// ============================================================
//  文件夹打包下载:把选中的多个文件/文件夹在服务端边打包边流式输出。
//  压缩策略:图片/音视频/压缩包等已压缩格式用 Store(不二次压缩),
//  其余用 Deflate。保险箱、回收站、排除目录、未完成上传(.part)一律跳过。
// ============================================================

// zipStoreExt 判断该扩展名的文件是否已压缩、再次 Deflate 无意义。
func zipStoreExt(ext string) bool {
	switch ext {
	case ".mp4", ".m4v", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".ts", ".rmvb",
		".mp3", ".flac", ".aac", ".m4a", ".ogg", ".opus", ".wav", ".wma",
		".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".avif",
		".zip", ".7z", ".rar", ".gz", ".bz2", ".xz", ".zst", ".iso":
		return true
	}
	return false
}

// zipSlash 把本地路径分隔符统一成 zip 要求的 "/"。
func zipSlash(p string) string { return strings.ReplaceAll(p, "\\", "/") }

// handleZip GET /api/zip?vpaths=/lib1/dir,/lib1/file.txt&name=xxx.zip
// 边打包边流式下载,不落临时文件。
func (s *Server) handleZip(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var vpaths []string
	for _, raw := range q["vpaths"] {
		for _, v := range strings.Split(raw, ",") {
			if v = strings.TrimSpace(v); v != "" {
				vpaths = append(vpaths, v)
			}
		}
	}
	if len(vpaths) == 0 {
		writeErr(w, 400, "未选择要下载的条目")
		return
	}
	name := strings.TrimSpace(q.Get("name"))
	if name == "" {
		name = "下载"
	}
	if !strings.HasSuffix(strings.ToLower(name), ".zip") {
		name += ".zip"
	}

	type zipItem struct {
		lib  *Library
		abs  string
		base string
	}
	var items []zipItem
	used := map[string]bool{}
	for _, vp := range vpaths {
		lib, abs, err := s.cfg.Resolve(vp)
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		if s.inVault(abs) {
			writeErr(w, 403, "保险箱内容请通过「隐私保险箱」访问")
			return
		}
		if s.excluded(lib, abs) {
			writeErr(w, 403, "该路径已被排除")
			return
		}
		if s.inTrash(abs) {
			writeErr(w, 403, "回收站内容无法打包下载")
			return
		}
		if _, err := os.Stat(abs); err != nil {
			writeErr(w, 404, "条目不存在: "+BaseVirtual(vp))
			return
		}
		base := BaseVirtual(vp)
		if base == "" || strings.EqualFold(base, lib.ID) {
			base = lib.Name
		}
		// 同名去重
		uniq := base
		for i := 1; used[uniq]; i++ {
			ext := filepath.Ext(base)
			stem := strings.TrimSuffix(base, ext)
			uniq = stem + " (" + itoa(i) + ")" + ext
		}
		used[uniq] = true
		items = append(items, zipItem{lib: lib, abs: abs, base: uniq})
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlPathEscape(name))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)

	zw := zip.NewWriter(w)
	vaultDir := s.vaultDir()
	for _, it := range items {
		info, err := os.Stat(it.abs)
		if err != nil {
			continue
		}
		if info.IsDir() {
			if err := s.zipDir(zw, it.lib, vaultDir, it.abs, it.base); err != nil {
				log.Printf("打包目录 %s 出错: %v", it.abs, err)
			}
			continue
		}
		if err := addZipFile(zw, it.abs, it.base, info); err != nil {
			log.Printf("打包文件 %s 出错: %v", it.abs, err)
		}
	}
	if err := zw.Close(); err != nil {
		log.Printf("关闭 zip 流出错: %v", err)
	}
}

// zipDir 递归把一个目录下的内容加入 zip,目录本身作为 zipName 前缀。
func (s *Server) zipDir(zw *zip.Writer, lib *Library, vaultDir, abs, zipName string) error {
	root := abs
	return filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // 跳过不可读项
		}
		if p == root {
			return nil
		}
		dec := indexWalkDecision(s.cfg, lib, vaultDir, p, d)
		if dec.skipSubtree {
			return fs.SkipDir
		}
		if dec.skip {
			return nil
		}
		rel, relErr := filepath.Rel(root, p)
		if relErr != nil {
			return nil
		}
		name := path.Join(zipName, zipSlash(rel))
		info, ierr := d.Info()
		if ierr != nil {
			return nil
		}
		if d.IsDir() {
			// 写入目录项(保留空目录结构)
			hdr, herr := zip.FileInfoHeader(info)
			if herr != nil {
				return nil
			}
			hdr.Name = name + "/"
			hdr.Method = zip.Store
			_, _ = zw.CreateHeader(hdr)
			return nil
		}
		if err := addZipFile(zw, p, name, info); err != nil {
			log.Printf("打包文件 %s 出错: %v", p, err)
		}
		return nil
	})
}

// addZipFile 把单个文件写入 zip。已压缩格式用 Store,其余 Deflate。
func addZipFile(zw *zip.Writer, abs, zipName string, info os.FileInfo) error {
	f, err := os.Open(abs)
	if err != nil {
		return err
	}
	defer f.Close()
	hdr, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	hdr.Name = zipName
	if zipStoreExt(strings.ToLower(filepath.Ext(abs))) {
		hdr.Method = zip.Store
	} else {
		hdr.Method = zip.Deflate
	}
	dst, err := zw.CreateHeader(hdr)
	if err != nil {
		return err
	}
	_, err = io.Copy(dst, f)
	return err
}
