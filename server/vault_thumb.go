package main

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

// ============================================================
//  保险箱缩略图
//
//  缩略图必须由解密后的数据生成,但**绝不能落盘**(红线:不落明文临时文件),
//  因此:解密流 → ffmpeg stdin → stdout JPEG → 内存 LRU 缓存 → 响应。
//  缓存按「磁盘路径 + mtime + 宽度」索引,锁定时整体清空。
// ============================================================

const (
	vaultThumbMaxCount = 256
	vaultThumbMaxBytes = 32 << 20 // 32MB
)

type vaultThumbCache struct {
	mu    sync.Mutex
	order []string
	items map[string][]byte
	bytes int
}

var vaultThumbs = &vaultThumbCache{items: map[string][]byte{}}

func (c *vaultThumbCache) get(key string) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	data, ok := c.items[key]
	if !ok {
		return nil
	}
	return data
}

func (c *vaultThumbCache) put(key string, data []byte) {
	if len(data) == 0 || len(data) > vaultThumbMaxBytes {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.items[key]; exists {
		return
	}
	c.items[key] = data
	c.order = append(c.order, key)
	c.bytes += len(data)
	for len(c.order) > vaultThumbMaxCount || c.bytes > vaultThumbMaxBytes {
		old := c.order[0]
		c.order = c.order[1:]
		if b, ok := c.items[old]; ok {
			c.bytes -= len(b)
			delete(c.items, old)
		}
	}
}

func (c *vaultThumbCache) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items = map[string][]byte{}
	c.order = nil
	c.bytes = 0
}

// resetVaultThumbCache 清空内存缩略图(锁定 / 改密码时调用)。
func resetVaultThumbCache() { vaultThumbs.reset() }

// handleVaultThumb GET /api/vault/thumb?path=&vt=&w=
func (s *Server) handleVaultThumb(w http.ResponseWriter, r *http.Request) {
	vmk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(vmk)
	rel := r.URL.Query().Get("path")
	abs, encrypted, disp, err := s.vaultLookup(rel, vmk)
	if err != nil {
		writeErr(w, 404, "文件不存在")
		return
	}
	info, serr := os.Stat(abs)
	if serr != nil || info.IsDir() {
		writeErr(w, 404, "文件不存在")
		return
	}
	ext := fileExt(disp)
	if !isImageExt(ext) && !isVideoExt(ext) {
		writeErr(w, 415, "不支持的类型")
		return
	}
	width := thumbWidth(r)
	key := fmt.Sprintf("%s|%d|%d", abs, info.ModTime().UnixNano(), width)
	if data := vaultThumbs.get(key); data != nil {
		writeVaultThumb(w, data)
		return
	}
	data, rerr := s.renderVaultThumb(abs, encrypted, vmk, width)
	if rerr != nil {
		writeErr(w, 500, "生成缩略图失败: "+rerr.Error())
		return
	}
	vaultThumbs.put(key, data)
	writeVaultThumb(w, data)
}

func writeVaultThumb(w http.ResponseWriter, data []byte) {
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Cache-Control", "private, max-age=300")
	_, _ = w.Write(data)
}

// renderVaultThumb 解密文件流并让 ffmpeg 从 stdin 读取、输出 JPEG 到 stdout。
// 视频只能取容器首帧(管道不可 seek),图片取完整解码。
func (s *Server) renderVaultThumb(abs string, encrypted bool, vmk []byte, width int) ([]byte, error) {
	ff := findFFmpeg()
	if ff == "" {
		return nil, fmt.Errorf("未安装 ffmpeg,无法生成缩略图")
	}
	var src io.Reader
	if encrypted {
		ef, err := openEncryptedFile(abs, vmk)
		if err != nil {
			return nil, err
		}
		defer func() { _ = ef.Close() }()
		src = io.NewSectionReader(ef, 0, ef.PlainSize())
	} else {
		f, err := os.Open(abs)
		if err != nil {
			return nil, err
		}
		defer func() { _ = f.Close() }()
		src = f
	}
	args := []string{
		"-v", "error", "-nostdin",
		"-i", "pipe:0",
		"-frames:v", "1",
		"-vf", fmt.Sprintf("scale=%d:-2", width),
		"-q:v", "4",
		"-f", "image2",
		"pipe:1",
	}
	cmd := exec.Command(ff, args...)
	cmd.Stdin = src
	var out, errBuf bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errBuf
	runErr := cmd.Run()
	if out.Len() == 0 {
		msg := strings.TrimSpace(errBuf.String())
		if msg == "" && runErr != nil {
			msg = runErr.Error()
		}
		if msg == "" {
			msg = "ffmpeg 未输出图像"
		}
		return nil, fmt.Errorf("%s", msg)
	}
	return out.Bytes(), nil
}
