package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// ============================================================
//  保险箱磁盘布局辅助
//
//  加密容器:保险箱里的每个文件落盘为「确定性 token.lvault」,
//  显示文件名加密后存在容器 header 里(v2),资源管理器看不到真实文件名;
//  文件夹保持明文名字(目录名加密留作后续),对外接口一律用显示路径。
//
//  兼容:
//   - v2 容器:磁盘名 = HMAC(VMK, 目录 + 显示名) 的前 128 位,header.Name 解出显示名;
//   - v1 容器(M2 格式):磁盘名 = <显示名>.lvault,header 无 name 字段;
//   - 旧明文文件:磁盘名即显示名。
//
//  迁移接口会把 v1 容器与旧明文文件重写成 v2(见 vault_migrate.go)。
// ============================================================

// vaultExt 是加密容器的磁盘后缀。
const vaultExt = ".lvault"

// vaultDisplayName 把磁盘名换成界面显示名(去掉容器后缀)。
func vaultDisplayName(name string) string {
	if strings.HasSuffix(strings.ToLower(name), vaultExt) && len(name) > len(vaultExt) {
		return name[:len(name)-len(vaultExt)]
	}
	return name
}

// isEncryptedContainer 只读文件头判断是否为 .lvault 容器(不解密、不需要密钥)。
func isEncryptedContainer(abs string) bool {
	f, err := os.Open(abs)
	if err != nil {
		return false
	}
	defer func() { _ = f.Close() }()
	magic := make([]byte, len(lvaultMagic))
	if _, err := io.ReadFull(f, magic); err != nil {
		return false
	}
	return string(magic) == lvaultMagic
}

// vaultContainerHeader 读取容器头(不解包 CEK,不需要密钥)。
func vaultContainerHeader(abs string) (lvaultHeader, error) {
	f, err := os.Open(abs)
	if err != nil {
		return lvaultHeader{}, err
	}
	defer func() { _ = f.Close() }()
	h, _, err := readLvaultHeader(f)
	return h, err
}

// vaultContainerSize 读容器头里的明文长度(不读取密文)。
func vaultContainerSize(abs string) (int64, error) {
	h, err := vaultContainerHeader(abs)
	if err != nil {
		return 0, err
	}
	return h.PlainSize, nil
}

// vaultContainerDisplay 解出容器的显示名:
// v2 读 header.Name;旧容器/无名字段时退回磁盘文件名去掉 .lvault。
func vaultContainerDisplay(mk []byte, abs string) (string, lvaultHeader, error) {
	h, err := vaultContainerHeader(abs)
	if err != nil {
		return "", h, err
	}
	if h.Name != "" && len(mk) == 32 {
		if disp, derr := openVaultName(mk, h.Name, h.NameNonce); derr == nil && disp != "" {
			return disp, h, nil
		}
	}
	return vaultDisplayName(filepath.Base(abs)), h, nil
}

// vaultDirRel 返回 dirAbs 相对保险箱根的路径(正斜杠,根为 "")。
func (s *Server) vaultDirRel(dirAbs string) string {
	root := filepath.Clean(s.vaultDir())
	rel, err := filepath.Rel(root, filepath.Clean(dirAbs))
	if err != nil || rel == "." || strings.HasPrefix(rel, "..") {
		return ""
	}
	return filepath.ToSlash(rel)
}

// vaultFileToken 由 (VMK, 目录, 显示名) 算出确定性文件名 token。
// 用目录参与计算,避免不同目录里的同名文件在磁盘上同名;
// 大小写归一化,贴合 Windows 文件系统不区分大小写的行为。
func (s *Server) vaultFileToken(mk []byte, dirAbs, display string) string {
	mac := hmac.New(sha256.New, vaultSubkey(mk, vaultLabelNameTok))
	mac.Write([]byte(strings.ToLower(s.vaultDirRel(dirAbs))))
	mac.Write([]byte{0})
	mac.Write([]byte(strings.ToLower(display)))
	return hex.EncodeToString(mac.Sum(nil))[:32]
}

// vaultFilePath 返回显示名在目录里的磁盘路径(确定性 token + .lvault)。
func (s *Server) vaultFilePath(mk []byte, dirAbs, display string) string {
	return filepath.Join(dirAbs, s.vaultFileToken(mk, dirAbs, display)+vaultExt)
}

// vaultNameTaken 报告显示名在目录里是否已被占用(新 token 容器 / 旧容器 / 旧明文都算)。
func (s *Server) vaultNameTaken(dirAbs string, mk []byte, display string) bool {
	if display == "" {
		return true
	}
	if _, err := os.Lstat(s.vaultFilePath(mk, dirAbs, display)); err == nil {
		return true
	}
	if _, err := os.Lstat(filepath.Join(dirAbs, display)); err == nil {
		return true
	}
	if _, err := os.Lstat(filepath.Join(dirAbs, display+vaultExt)); err == nil {
		return true
	}
	return false
}

// vaultUniqueDirName 为新建目录找一个不与文件/容器冲突的名字(目录名仍为明文)。
func vaultUniqueDirName(dirAbs, name string) string {
	taken := func(cand string) bool {
		for _, p := range []string{cand, cand + vaultExt} {
			if _, err := os.Lstat(filepath.Join(dirAbs, p)); err == nil {
				return true
			}
		}
		return false
	}
	if !taken(name) {
		return name
	}
	for i := 1; ; i++ {
		cand := fmt.Sprintf("%s (%d)", name, i)
		if !taken(cand) {
			return cand
		}
	}
}

// vaultUniqueDisplay 在目录里为 display 找一个不冲突的显示名。
func (s *Server) vaultUniqueDisplay(dirAbs string, mk []byte, display string) string {
	if !s.vaultNameTaken(dirAbs, mk, display) {
		return display
	}
	ext := filepath.Ext(display)
	base := strings.TrimSuffix(display, ext)
	for i := 1; ; i++ {
		cand := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if !s.vaultNameTaken(dirAbs, mk, cand) {
			return cand
		}
	}
}

// vaultLookup 把「显示路径」(不含 .lvault 后缀)解析成磁盘真实路径。
// 返回 encrypted 表示命中的文件是加密容器,display 是界面显示名。
func (s *Server) vaultLookup(rel string, mk []byte) (abs string, encrypted bool, display string, err error) {
	abs, err = s.vaultAbs(rel)
	if err != nil {
		return "", false, "", err
	}
	// 1) 磁盘名精确命中:旧明文文件、v1 容器,或调用方直接传了磁盘名。
	if info, serr := os.Lstat(abs); serr == nil {
		if info.IsDir() {
			return abs, false, filepath.Base(abs), nil
		}
		if isEncryptedContainer(abs) {
			disp, _, _ := vaultContainerDisplay(mk, abs)
			return abs, true, disp, nil
		}
		return abs, false, filepath.Base(abs), nil
	}
	dirAbs := filepath.Dir(abs)
	disp := filepath.Base(abs)
	// 2) 新格式:按显示名算 token 文件名。
	if cand := s.vaultFilePath(mk, dirAbs, disp); cand != "" {
		if info, serr := os.Lstat(cand); serr == nil && !info.IsDir() {
			return cand, isEncryptedContainer(cand), disp, nil
		}
	}
	// 3) 旧格式容器:<显示名>.lvault。
	if !strings.HasSuffix(strings.ToLower(abs), vaultExt) {
		if info, serr := os.Lstat(abs + vaultExt); serr == nil && !info.IsDir() {
			return abs + vaultExt, isEncryptedContainer(abs + vaultExt), disp, nil
		}
	}
	return "", false, "", os.ErrNotExist
}

// vaultEntry 描述目录里一个文件/文件夹的显示信息(列表与迁移共用)。
func vaultEntryInfo(mk []byte, abs, diskName string, info os.FileInfo) (display string, size int64, encrypted bool) {
	if info.IsDir() {
		return diskName, 0, false
	}
	if !isEncryptedContainer(abs) {
		return diskName, info.Size(), false
	}
	h, herr := vaultContainerHeader(abs)
	if herr != nil {
		return vaultDisplayName(diskName), info.Size(), true
	}
	display = vaultDisplayName(diskName)
	if h.Name != "" && len(mk) == 32 {
		if disp, derr := openVaultName(mk, h.Name, h.NameNonce); derr == nil && disp != "" {
			display = disp
		}
	}
	return display, h.PlainSize, true
}

// hideVaultTree 尽力给保险箱目录树打 Windows 隐藏 + 系统属性(其他平台 no-op)。
func (s *Server) hideVaultTree(root string) {
	if strings.TrimSpace(root) == "" {
		return
	}
	_ = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		_ = hideVaultPath(p)
		return nil
	})
}
