package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 开机自启脚本的读写与「指向旧 exe」判定。

func TestAutostartScriptRoundTrip(t *testing.T) {
	exe := `C:\Tools\linNas-server.exe`
	script := autostartScript(exe)
	if got := autostartExeFromScript(script); got != exe {
		t.Fatalf("解析出的 exe = %q, want %q", got, exe)
	}
	// 脚本必须是隐藏窗口的托盘启动
	if !strings.Contains(script, "-tray") {
		t.Fatalf("脚本缺少 -tray: %q", script)
	}
	if !strings.Contains(script, `"""`+exe+`""`) {
		t.Fatalf("脚本未按 VBS 规则转义引号: %q", script)
	}
}

func TestAutostartExeFromScriptLegacy(t *testing.T) {
	// 旧版(1.7.0 时代)写下的脚本:启动文件名是 linNas.exe
	legacy := "Set sh = CreateObject(\"WScript.Shell\")\r\n" +
		"sh.Run \"\"\"E:\\linNas\\server\\linNas.exe\"\" -tray\", 0, False\r\n"
	if got, want := autostartExeFromScript(legacy), `E:\linNas\server\linNas.exe`; got != want {
		t.Fatalf("旧脚本解析出的 exe = %q, want %q", got, want)
	}
	if !strings.Contains(legacy, "linNas.exe") {
		t.Fatal("用例数据应包含旧文件名")
	}
}

func TestAutostartExeFromScriptInvalid(t *testing.T) {
	for _, s := range []string{"", "garbage", "sh.Run notepad.exe", `sh.Run """unterminated`} {
		if got := autostartExeFromScript(s); got != "" {
			t.Fatalf("内容 %q 应解析为空,得到 %q", s, got)
		}
	}
}

func TestSamePath(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{`E:\linNas\server\linNas-server.exe`, `e:\LInNAS\server\linNas-server.exe`, true},
		{`E:\linNas\server\linNas-server.exe`, `E:\linNas\server\linNas.exe`, false},
		{`E:\linNas\server\..\server\linNas-server.exe`, `E:\linNas\server\linNas-server.exe`, true},
		{"", "", false},
	}
	for _, c := range cases {
		if got := samePath(c.a, c.b); got != c.want {
			t.Fatalf("samePath(%q, %q) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}

func TestAutostartSetAtAndTarget(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "Startup", autostartFileName)
	exe := filepath.Join(dir, "linNas-server.exe")

	// 开启:写入脚本并可被解析
	if err := autostartSetAt(p, exe, true); err != nil {
		t.Fatalf("开启失败: %v", err)
	}
	if got := autostartTargetAt(p); got != exe {
		t.Fatalf("target = %q, want %q", got, exe)
	}

	// 指向旧 exe 后再次写入 = 自愈
	old := filepath.Join(dir, "linNas.exe")
	if err := autostartSetAt(p, old, true); err != nil {
		t.Fatalf("写入旧路径失败: %v", err)
	}
	if got := autostartTargetAt(p); got != old {
		t.Fatalf("自愈前 target = %q, want %q", got, old)
	}
	if err := autostartSetAt(p, exe, true); err != nil {
		t.Fatalf("重写失败: %v", err)
	}
	if got := autostartTargetAt(p); got != exe {
		t.Fatalf("自愈后 target = %q, want %q", got, exe)
	}

	// 关闭:脚本删除后 target 为空
	if err := autostartSetAt(p, exe, false); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatalf("关闭后脚本仍存在: %v", err)
	}
	if got := autostartTargetAt(p); got != "" {
		t.Fatalf("关闭后 target 应为空,得到 %q", got)
	}
}

func TestAutostartSetAtRejectsEmpty(t *testing.T) {
	if err := autostartSetAt("", "x.exe", true); err == nil {
		t.Fatal("空路径应报错")
	}
	dir := t.TempDir()
	if err := autostartSetAt(filepath.Join(dir, autostartFileName), "", true); err == nil {
		t.Fatal("空 exe 应报错")
	}
}
