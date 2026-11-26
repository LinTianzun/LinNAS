//go:build windows

package main

import (
	"log"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"

	"github.com/getlantern/systray"
	"golang.org/x/sys/windows"
)

// hiddenProcAttr 让子进程(ffmpeg / tailscale 等)不弹出控制台窗口。
func hiddenProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{HideWindow: true}
}

// traySupported 报告当前平台是否支持托盘。
func traySupported() bool { return true }

var (
	user32               = windows.NewLazySystemDLL("user32.dll")
	procShowWindow       = user32.NewProc("ShowWindow")
	procMessageBoxW      = user32.NewProc("MessageBoxW")
	kernel32             = windows.NewLazySystemDLL("kernel32.dll")
	procGetConsoleWindow = kernel32.NewProc("GetConsoleWindow")
	procGetConsoleProcs  = kernel32.NewProc("GetConsoleProcessList")
)

// launchedFromTerminal 报告本进程是否挂在「已存在的终端窗口」上(例如用户先开了
// cmd/PowerShell 再运行 exe)。此时不应隐藏窗口,否则会把用户的终端一起藏掉。
func launchedFromTerminal() bool {
	var pids [8]uint32
	n, _, _ := procGetConsoleProcs.Call(uintptr(unsafe.Pointer(&pids[0])), uintptr(len(pids)))
	return n > 1
}

// hideConsoleWindow 把自己的控制台窗口藏起来(托盘模式下不该留一个黑框)。
func hideConsoleWindow() {
	h, _, _ := procGetConsoleWindow.Call()
	if h != 0 {
		const swHide = 0
		procShowWindow.Call(h, swHide)
	}
}

// showErrorBox 在图形界面里报错(托盘模式没有控制台可看)。
func showErrorBox(title, msg string) {
	t, _ := windows.UTF16PtrFromString(title)
	m, _ := windows.UTF16PtrFromString(msg)
	const mbIconError = 0x00000010
	procMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), mbIconError)
}

// copyToClipboard 把文本放进剪贴板(用系统自带的 clip.exe)。
func copyToClipboard(text string) error {
	cmd := exec.Command("cmd", "/c", "clip")
	cmd.Stdin = strings.NewReader(text)
	cmd.SysProcAttr = hiddenProcAttr()
	return cmd.Run()
}

// runTray 在系统托盘里放一个图标,并阻塞直到用户选择「退出」。
// 必须从主 goroutine 调用:systray 的消息循环需要主线程。
func runTray(opt trayOptions) {
	trayActive.Store(true)

	systray.Run(func() {
		systray.SetIcon(trayIconICO)
		systray.SetTitle("linNas")
		systray.SetTooltip("linNas " + opt.Version + "\n" + opt.URL)

		open := systray.AddMenuItem("打开管理界面", opt.URL)
		copyLAN := systray.AddMenuItem("复制局域网地址", "复制本机局域网访问地址")
		remote := systray.AddMenuItem("远程访问(Tailscale)…", "查看/设置外网访问")
		systray.AddSeparator()
		autoItem := systray.AddMenuItemCheckbox("开机自启", "登录 Windows 时自动启动 linNas(托盘)",
			autostartEnabled())
		dataDir := systray.AddMenuItem("打开数据目录", opt.DataDir)
		systray.AddSeparator()
		verItem := systray.AddMenuItem("linNas "+opt.Version, "当前版本")
		verItem.Disable()
		quitItem := systray.AddMenuItem("退出 linNas", "停止服务并退出")

		go func() {
			for {
				select {
				case <-open.ClickedCh:
					openInBrowser(opt.URL)
				case <-copyLAN.ClickedCh:
					u := "http://" + primaryIP() + ":" + itoa(opt.Port) + "/"
					if err := copyToClipboard(u); err != nil {
						log.Printf("复制到剪贴板失败: %v", err)
					}
				case <-remote.ClickedCh:
					openInBrowser(opt.URL + "#remote")
				case <-autoItem.ClickedCh:
					if autoItem.Checked() {
						if err := autostartSet(false); err != nil {
							log.Printf("关闭开机自启失败: %v", err)
						} else {
							autoItem.Uncheck()
						}
					} else {
						if err := autostartSet(true); err != nil {
							log.Printf("开启开机自启失败: %v", err)
						} else {
							autoItem.Check()
						}
					}
				case <-dataDir.ClickedCh:
					_ = exec.Command("explorer", opt.DataDir).Start()
				case <-quitItem.ClickedCh:
					systray.Quit()
					return
				}
			}
		}()
	}, func() {
		trayActive.Store(false)
	})

	// systray.Run 返回意味着用户点了「退出」,把服务收尾。
	if opt.Shutdown != nil {
		opt.Shutdown()
	}
}
