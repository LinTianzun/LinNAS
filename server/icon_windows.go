//go:build windows

package main

import _ "embed"

// trayIconICO 是托盘图标(多尺寸 .ico,由 docs 脚本生成)。
//
//go:embed assets/linnas.ico
var trayIconICO []byte
