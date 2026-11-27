package main

import (
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

// ============================================================
//  保险箱导入 / 导出的加密搬运
//
//  导入:存储库明文 → 分块加密写成 token.lvault(显示名加密进 header)→ 删源。
//  导出:读容器头解出显示名 → 解密流写明文到存储库 → 删容器。
//  目录按原结构递归处理(目录名仍明文);旧的 v1 容器 / 明文保险箱文件按兼容路径处理。
//  任何一步失败都不删源文件,半成品会被清掉,不会留下无法解密的残片。
// ============================================================

// vaultImportOne 把库里的明文文件/目录加密搬进保险箱,返回保险箱内的最终路径。
func (s *Server) vaultImportOne(vmk []byte, srcAbs, destDirAbs string) (string, error) {
	info, err := os.Stat(srcAbs)
	if err != nil {
		return "", err
	}
	base := filepath.Base(srcAbs)
	if info.IsDir() {
		dstDir := filepath.Join(destDirAbs, vaultUniqueDirName(destDirAbs, base))
		if err := s.encryptTree(srcAbs, dstDir, vmk); err != nil {
			_ = os.RemoveAll(dstDir)
			return "", err
		}
		if err := os.RemoveAll(srcAbs); err != nil {
			_ = os.RemoveAll(dstDir)
			return "", err
		}
		return dstDir, nil
	}
	display := s.vaultUniqueDisplay(destDirAbs, vmk, base)
	dst := s.vaultFilePath(vmk, destDirAbs, display)
	in, oerr := os.Open(srcAbs)
	if oerr != nil {
		return "", oerr
	}
	_, werr := writeEncryptedStreamNamed(dst, in, vmk, display)
	cerr := in.Close()
	if werr != nil {
		_ = os.Remove(dst)
		return "", werr
	}
	if cerr != nil {
		_ = os.Remove(dst)
		return "", cerr
	}
	if err := os.Remove(srcAbs); err != nil {
		_ = os.Remove(dst)
		return "", err
	}
	return dst, nil
}

// encryptTree 递归把 srcDir 的明文加密到 dstDir(每个文件一个 token.lvault 容器)。
func (s *Server) encryptTree(srcDir, dstDir string, vmk []byte) error {
	if err := os.MkdirAll(dstDir, 0o755); err != nil {
		return err
	}
	return filepath.WalkDir(srcDir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, rerr := filepath.Rel(srcDir, p)
		if rerr != nil {
			return rerr
		}
		if rel == "." {
			return nil
		}
		dst := filepath.Join(dstDir, rel)
		if d.IsDir() {
			return os.MkdirAll(dst, 0o755)
		}
		display := filepath.Base(p)
		target := s.vaultFilePath(vmk, filepath.Dir(dst), display)
		in, oerr := os.Open(p)
		if oerr != nil {
			return oerr
		}
		_, werr := writeEncryptedStreamNamed(target, in, vmk, display)
		cerr := in.Close()
		if werr != nil {
			return werr
		}
		return cerr
	})
}

// vaultExportOne 把保险箱里的文件/目录解密搬进存储库,返回存储库内的最终路径。
func (s *Server) vaultExportOne(vmk []byte, srcAbs, destDirAbs string) (string, error) {
	info, err := os.Stat(srcAbs)
	if err != nil {
		return "", err
	}
	base := filepath.Base(srcAbs)
	if info.IsDir() {
		dstDir := filepath.Join(destDirAbs, base)
		if _, serr := os.Stat(dstDir); serr == nil {
			dstDir = filepath.Join(destDirAbs, uniqueName(destDirAbs, base))
		}
		if err := s.decryptTree(vmk, srcAbs, dstDir); err != nil {
			_ = os.RemoveAll(dstDir)
			return "", err
		}
		if err := os.RemoveAll(srcAbs); err != nil {
			_ = os.RemoveAll(dstDir)
			return "", err
		}
		return dstDir, nil
	}
	display := base
	if isEncryptedContainer(srcAbs) {
		disp, _, derr := vaultContainerDisplay(vmk, srcAbs)
		if derr == nil && disp != "" {
			display = disp
		} else {
			display = vaultDisplayName(base)
		}
	}
	dst := filepath.Join(destDirAbs, display)
	if _, serr := os.Stat(dst); serr == nil {
		dst = filepath.Join(destDirAbs, uniqueName(destDirAbs, display))
	}
	if err := s.exportVaultFile(vmk, srcAbs, dst); err != nil {
		_ = os.Remove(dst)
		return "", err
	}
	if err := os.Remove(srcAbs); err != nil {
		_ = os.Remove(dst)
		return "", err
	}
	return dst, nil
}

// decryptTree 递归把保险箱目录解密到 dstDir(明文)。目录名明文,文件名用 header 里的显示名。
func (s *Server) decryptTree(vmk []byte, srcDir, dstDir string) error {
	if err := os.MkdirAll(dstDir, 0o755); err != nil {
		return err
	}
	return filepath.WalkDir(srcDir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, rerr := filepath.Rel(srcDir, p)
		if rerr != nil {
			return rerr
		}
		if rel == "." {
			return nil
		}
		if d.IsDir() {
			return os.MkdirAll(filepath.Join(dstDir, rel), 0o755)
		}
		dst := filepath.Join(dstDir, rel)
		if isEncryptedContainer(p) {
			display, _, derr := vaultContainerDisplay(vmk, p)
			if derr != nil || display == "" {
				display = vaultDisplayName(filepath.Base(p))
			}
			dst = filepath.Join(dstDir, filepath.Dir(rel), display)
			return decryptFileTo(vmk, p, dst)
		}
		return copyFile(p, dst, 0o644)
	})
}

// exportVaultFile 把一个保险箱文件写成明文:dst 为存储库里的目标明文路径。
func (s *Server) exportVaultFile(vmk []byte, srcAbs, dst string) error {
	if !isEncryptedContainer(srcAbs) {
		// 旧的明文保险箱文件(M3 迁移前):直接复制出来。
		return copyFile(srcAbs, dst, 0o644)
	}
	return decryptFileTo(vmk, srcAbs, dst)
}

// decryptFileTo 把 .lvault 容器解密流写入 dst(明文),不在磁盘留明文临时文件。
func decryptFileTo(vmk []byte, srcAbs, dst string) error {
	ef, err := openEncryptedFile(srcAbs, vmk)
	if err != nil {
		return err
	}
	defer func() { _ = ef.Close() }()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	sr := io.NewSectionReader(ef, 0, ef.PlainSize())
	if _, cerr := io.Copy(out, sr); cerr != nil {
		_ = out.Close()
		return cerr
	}
	return out.Close()
}
