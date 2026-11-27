//go:build !windows

package main

// hideVaultPath 在非 Windows 平台是空操作:隐藏属性由文件系统/桌面环境自行管理。
func hideVaultPath(abs string) error { return nil }
