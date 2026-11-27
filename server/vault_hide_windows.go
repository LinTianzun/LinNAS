//go:build windows

package main

import "syscall"

// Windows 文件属性位(winnt.h)。
const (
	fileAttributeHidden = 0x2
	fileAttributeSystem = 0x4
)

// hideVaultPath 给保险箱目录打上「隐藏 + 系统」属性,资源管理器默认不显示。
// 只改属性、不动内容;失败时由调用方忽略(非致命)。
func hideVaultPath(abs string) error {
	p, err := syscall.UTF16PtrFromString(abs)
	if err != nil {
		return err
	}
	attrs, err := syscall.GetFileAttributes(p)
	if err != nil {
		return err
	}
	return syscall.SetFileAttributes(p, attrs|fileAttributeHidden|fileAttributeSystem)
}
