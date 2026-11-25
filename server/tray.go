package main

import "sync/atomic"

// 托盘常驻相关的跨平台部分。
// Windows 实现在 tray_windows.go,其它平台是空实现(tray_other.go)。

var trayActive atomic.Bool

// trayRunning 报告当前进程是否以托盘模式运行。
func trayRunning() bool { return trayActive.Load() }

// trayOptions 是托盘需要的运行时信息。
type trayOptions struct {
	URL      string // 打开管理界面用的地址
	DataDir  string // 「打开数据目录」用
	Version  string
	Port     int
	Shutdown func() // 托盘选择「退出」后的清理逻辑
}
