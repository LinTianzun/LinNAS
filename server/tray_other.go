//go:build !windows

package main

import (
	"log"
	"os"
	"os/exec"
	"strings"
	"syscall"
)

// 非 Windows 平台上托盘不可用,这里给出空实现保证可编译。

func hiddenProcAttr() *syscall.SysProcAttr { return nil }

func traySupported() bool { return false }

func hideConsoleWindow() {}

// launchedFromTerminal 非 Windows 平台无「双击隐藏控制台」概念。
func launchedFromTerminal() bool { return false }

func showErrorBox(title, msg string) { log.Printf("%s: %s", title, msg) }

func copyToClipboard(text string) error {
	for _, bin := range []string{"xclip", "pbcopy"} {
		if _, err := exec.LookPath(bin); err == nil {
			cmd := exec.Command(bin)
			cmd.Stdin = strings.NewReader(text)
			return cmd.Run()
		}
	}
	return os.ErrNotExist
}

func runTray(opt trayOptions) {
	if opt.Shutdown != nil {
		opt.Shutdown()
	}
}
