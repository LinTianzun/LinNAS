package main

import (
	"net/http"
	"path/filepath"
	"strings"
)

// ============================================================
//  「不在媒体库展示」:被标记的路径仍索引、仍可在文件浏览/搜索中看到与
//  访问,只是不出现在媒体库(视频/图片/音乐)列表里。
//  MediaHide 元素为相对库根、正斜杠的路径(文件或目录)。
// ============================================================

// normalizeRel 归一化一个相对库根的路径:去空白、反斜杠转正斜杠、去首尾斜杠。
func normalizeRel(s string) string {
	return strings.Trim(strings.ReplaceAll(strings.TrimSpace(s), "\\", "/"), "/")
}

// isAncestorPath 判断 anc 是否是 child 的祖先目录(两者都已小写、正斜杠)。
func isAncestorPath(anc, child string) bool {
	return child != anc && strings.HasPrefix(child, anc+"/")
}

// setMediaHide 在 lib.MediaHide 上增删一项 rel:
//   - hidden=true: 去重;若已被某个祖先目录覆盖则不再添加;添加时会清掉被它覆盖的后代项。
//   - hidden=false: 移除 rel 本身,并清理依赖它的后代项(冗余)。
func (lib *Library) setMediaHide(rel string, hidden bool) {
	rel = normalizeRel(rel)
	if rel == "" {
		return
	}
	low := strings.ToLower(rel)
	out := make([]string, 0, len(lib.MediaHide)+1)
	covered := false
	for _, e := range lib.MediaHide {
		e = normalizeRel(e)
		if e == "" {
			continue
		}
		el := strings.ToLower(e)
		if el == low {
			// 已存在同一项。
			if hidden {
				covered = true
				out = append(out, e)
			}
			continue
		}
		if hidden {
			if isAncestorPath(el, low) {
				// 已有祖先覆盖,冗余,不再添加。
				covered = true
				out = append(out, e)
				continue
			}
			if isAncestorPath(low, el) {
				// 新的项覆盖了这个后代,丢弃该后代。
				continue
			}
			out = append(out, e)
		} else {
			// 取消隐藏:丢弃 rel 及其后代(它们都依赖 rel)。
			if isAncestorPath(low, el) {
				continue
			}
			out = append(out, e)
		}
	}
	if hidden && !covered {
		out = append(out, rel)
	}
	lib.MediaHide = out
}

// handleMediaHide POST /api/libs/media-hide {vpath,hidden} — 切换「不在媒体库展示」。
func (s *Server) handleMediaHide(w http.ResponseWriter, r *http.Request) {
	var req struct {
		VPath  string `json:"vpath"`
		Hidden bool   `json:"hidden"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	lib, abs, err := s.cfg.Resolve(req.VPath)
	if err != nil {
		if err == errNoLibrary {
			writeErr(w, 404, err.Error())
			return
		}
		writeErr(w, 400, err.Error())
		return
	}
	rel, rerr := filepath.Rel(filepath.Clean(lib.Path), filepath.Clean(abs))
	if rerr != nil || strings.HasPrefix(rel, "..") {
		writeErr(w, 400, "路径不合法")
		return
	}
	rel = normalizeRel(rel)
	if rel == "" {
		writeErr(w, 400, "不能隐藏存储库根目录")
		return
	}
	lib.setMediaHide(rel, req.Hidden)
	if err := s.cfg.SaveConfig(); err != nil {
		writeErr(w, 500, "保存配置失败: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{
		"ok":        true,
		"hidden":    req.Hidden,
		"mediaHide": lib.MediaHide,
	})
}

// mediaHiddenPrefixes 返回所有「完整虚拟前缀」= /libid/rel(正斜杠)。
func mediaHiddenPrefixes(cfg *Config) []string {
	var out []string
	for i := range cfg.Libraries {
		lib := &cfg.Libraries[i]
		for _, rel := range lib.MediaHide {
			rel = normalizeRel(rel)
			if rel == "" {
				continue
			}
			out = append(out, "/"+lib.ID+"/"+rel)
		}
	}
	return out
}

// vpathMediaHidden 判断 vpath 是否等于某前缀或在其子路径下(大小写不敏感)。
func vpathMediaHidden(prefixes []string, vpath string) bool {
	vp := strings.ToLower(strings.TrimSuffix(strings.ReplaceAll(vpath, "\\", "/"), "/"))
	if vp == "" {
		return false
	}
	for _, p := range prefixes {
		pl := strings.ToLower(strings.TrimSuffix(strings.ReplaceAll(p, "\\", "/"), "/"))
		if pl == "" {
			continue
		}
		if vp == pl || strings.HasPrefix(vp, pl+"/") {
			return true
		}
	}
	return false
}
