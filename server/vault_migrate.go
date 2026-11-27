package main

import (
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// ============================================================
//  M3:保险箱一次性迁移
//
//  把两类旧文件重写成 v2 容器(token 文件名 + 加密显示名):
//   1. 旧明文文件(M2 之前直接放在保险箱里的文件);
//   2. v1 容器(M2 格式:磁盘名 = 显示名.lvault、header 无 name 字段)。
//  迁移是破坏性操作(会删除旧文件),接口分两步:confirm=false 只统计,
//  confirm=true 才真正写新容器并删源,由 UI 弹出确认。
// ============================================================

type vaultMigrationFile struct {
	abs       string
	display   string
	plainSize int64
	legacy    bool // true = v1 容器,需要解密后重写
}

type vaultMigration struct {
	files  []vaultMigrationFile
	Bytes  int64
	Plain  int
	Legacy int
}

// scanVaultMigration 遍历保险箱,找出需要迁移的文件。
func (s *Server) scanVaultMigration(mk []byte) (vaultMigration, error) {
	var m vaultMigration
	root := s.vaultDir()
	if _, err := os.Stat(root); err != nil {
		if os.IsNotExist(err) {
			return m, nil
		}
		return m, err
	}
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return werr
		}
		if d.IsDir() {
			return nil
		}
		name := d.Name()
		// 跳过上次中断留下的半成品,避免把密文碎片当明文加密。
		if strings.HasSuffix(strings.ToLower(name), ".part") || strings.HasSuffix(strings.ToLower(name), ".tmp") {
			return nil
		}
		info, ierr := d.Info()
		if ierr != nil {
			return nil
		}
		if isEncryptedContainer(p) {
			h, herr := vaultContainerHeader(p)
			if herr != nil {
				return nil
			}
			if h.Name != "" && len(mk) == 32 {
				if _, derr := openVaultName(mk, h.Name, h.NameNonce); derr == nil {
					return nil // 已是 v2,无需迁移
				}
			}
			m.files = append(m.files, vaultMigrationFile{
				abs: p, display: vaultDisplayName(name), plainSize: h.PlainSize, legacy: true,
			})
			m.Legacy++
			m.Bytes += h.PlainSize
			return nil
		}
		m.files = append(m.files, vaultMigrationFile{abs: p, display: name, plainSize: info.Size()})
		m.Plain++
		m.Bytes += info.Size()
		return nil
	})
	return m, err
}

// migrateVaultFile 把一个旧文件重写成 v2 容器(先写 .part 再 rename,最后删源)。
func (s *Server) migrateVaultFile(mk []byte, f vaultMigrationFile) error {
	dir := filepath.Dir(f.abs)
	target := s.vaultFilePath(mk, dir, f.display)
	samePath := filepath.Clean(target) == filepath.Clean(f.abs)
	if !samePath {
		if _, err := os.Stat(target); err == nil {
			return fmt.Errorf("目标已存在同名加密文件,请先手动处理")
		}
	}
	var src io.Reader
	closeSrc := func() error { return nil }
	if f.legacy {
		ef, err := openEncryptedFile(f.abs, mk)
		if err != nil {
			return fmt.Errorf("打开旧容器失败: %w", err)
		}
		src = io.NewSectionReader(ef, 0, ef.PlainSize())
		closeSrc = ef.Close
	} else {
		ff, err := os.Open(f.abs)
		if err != nil {
			return err
		}
		src = ff
		closeSrc = ff.Close
	}
	tmp := target + ".part"
	_, werr := writeEncryptedStreamNamed(tmp, src, mk, f.display)
	cerr := closeSrc()
	if werr != nil {
		_ = os.Remove(tmp)
		return werr
	}
	if cerr != nil {
		_ = os.Remove(tmp)
		return cerr
	}
	if err := os.Rename(tmp, target); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if samePath {
		return nil
	}
	if err := os.Remove(f.abs); err != nil {
		return fmt.Errorf("新容器已写入,但删除旧文件失败: %w", err)
	}
	return nil
}

// handleVaultMigrate POST /api/vault/migrate {confirm}
// confirm=false(默认)只统计,confirm=true 才真正迁移。
func (s *Server) handleVaultMigrate(w http.ResponseWriter, r *http.Request) {
	mk, ok := s.vaultRequestKey(w, r)
	if !ok {
		return
	}
	defer zeroKeyBytes(mk)
	var req struct {
		Confirm bool `json:"confirm"`
	}
	_ = readJSON(r, &req) // 允许空 body,视为 dry-run
	m, err := s.scanVaultMigration(mk)
	if err != nil {
		writeErr(w, 500, "扫描保险箱失败: "+err.Error())
		return
	}
	if !req.Confirm {
		writeJSON(w, 200, map[string]any{
			"confirm":          false,
			"files":            len(m.files),
			"bytes":            m.Bytes,
			"plaintext":        m.Plain,
			"legacyContainers": m.Legacy,
		})
		return
	}
	migrated := 0
	errs := []string{}
	for _, f := range m.files {
		if err := s.migrateVaultFile(mk, f); err != nil {
			errs = append(errs, f.display+": "+err.Error())
			continue
		}
		migrated++
	}
	s.hideVaultTree(s.vaultDir())
	writeJSON(w, 200, map[string]any{
		"confirm":  true,
		"ok":       len(errs) == 0,
		"migrated": migrated,
		"failed":   len(errs),
		"errors":   errs,
	})
}
