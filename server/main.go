package main

import (
	"embed"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"syscall"
	"time"
)

//go:embed ui
var uiEmbedded embed.FS

// uiFS is the embedded web UI, rooted at server/ui/dist.
var uiFS fs.FS

func init() {
	var err error
	uiFS, err = fs.Sub(uiEmbedded, "ui/dist")
	if err != nil {
		panic(err)
	}
}

func main() {
	// -no-browser disables auto-opening the admin page on start.
	openBrowser := true
	for _, arg := range os.Args[1:] {
		if arg == "-no-browser" {
			openBrowser = false
		}
	}

	// Defaults: config lives next to the exe in a "linnas-data" folder,
	// or LINNAS_DATA env override.
	dataDir := os.Getenv("LINNAS_DATA")
	if dataDir == "" {
		exe, err := os.Executable()
		if err == nil {
			dataDir = filepath.Join(filepath.Dir(exe), "linnas-data")
		} else {
			dataDir = "linnas-data"
		}
	}
	cfg, err := LoadConfig(dataDir)
	if err != nil {
		log.Fatalf("读取配置失败: %v", err)
	}

	// 首次运行:自动注册存在的 H:\、I:\ 外置盘,省得手动添加
	if len(cfg.Libraries) == 0 {
		candidates := []struct{ name, path string }{
			{"H盘", `H:\`},
			{"I盘", `I:\`},
		}
		added := false
		for _, c := range candidates {
			if info, statErr := os.Stat(c.path); statErr == nil && info.IsDir() {
				cfg.Libraries = append(cfg.Libraries, Library{
					ID: nextLibID(cfg), Name: c.name, Path: c.path, Enabled: true,
				})
				added = true
				log.Printf("已自动注册存储库: %s (%s)", c.name, c.path)
			}
		}
		if added {
			if saveErr := cfg.SaveConfig(); saveErr != nil {
				log.Printf("保存配置失败: %v", saveErr)
			}
		}
	}

	srv := NewServer(cfg)

	// 媒体/文件索引(SQLite,纯 Go 驱动)。失败不影响主功能。
	if ix, ixErr := OpenIndex(dataDir); ixErr != nil {
		log.Printf("打开索引失败(搜索/媒体库降级): %v", ixErr)
	} else {
		srv.index = ix
		defer func() { _ = ix.Close() }()
		go func() {
			time.Sleep(1500 * time.Millisecond)
			if ix.NeedsScan(6 * time.Hour) {
				ix.ScanAll(cfg) // 后台建索引,不阻塞启动
			} else {
				log.Printf("索引较新(%s),跳过启动扫描", ix.lastScanText())
			}
		}()
	}
	// mDNS 广播:手机端可直接发现本机,无需手输 IP。
	if adv := startMDNS(cfg.Port, "linNas"); adv != nil {
		defer adv.Close()
		log.Printf("mDNS 广播已启动: %s", mdnsServiceType)
	}
	// UDP 局域网发现(应答 + 周期广播),作为 mDNS 的兜底:部分 Windows 环境
	// 无法绑定 5353,广播能在同网段可靠送达。
	srv.startDiscovery()

	addr := srv.ListenAddr()
	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("监听 %s 失败: %v", addr, err)
	}

	// Print local IPs so the phone can be pointed at one.
	log.Printf("%s 启动", cfg)
	log.Printf("配置目录: %s", dataDir)
	for _, ip := range localIPs() {
		log.Printf("  手机浏览器访问: http://%s:%d/", ip, cfg.Port)
	}
	log.Printf("  手机配对(二维码页): http://%s:%d/pair", primaryIP(), cfg.Port)
	if len(cfg.Libraries) == 0 {
		log.Printf("尚未注册存储库,请打开管理界面添加 H:\\ I:\\ 等目录")
	}
	if openBrowser {
		url := "http://127.0.0.1:" + strconv.Itoa(cfg.Port) + "/ui/"
		log.Printf("正在打开管理界面: %s", url)
		go openInBrowser(url)
	}

	go func() {
		if err := httpSrv.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP 服务异常: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	log.Printf("正在关闭...")
	_ = httpSrv.Close()
}

// localIPs lists non-loopback IPv4 addresses of this machine.
func localIPs() []string {
	var out []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return out
	}
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if v4 := ipnet.IP.To4(); v4 != nil {
				out = append(out, v4.String())
			}
		}
	}
	return out
}

// openInBrowser opens url in the default browser (best effort, never fatal).
func openInBrowser(url string) {
	// Give the HTTP server a moment to accept connections.
	time.Sleep(300 * time.Millisecond)
	var err error
	switch runtime.GOOS {
	case "windows":
		err = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		err = exec.Command("open", url).Start()
	default:
		err = exec.Command("xdg-open", url).Start()
	}
	if err != nil {
		log.Printf("自动打开浏览器失败(%v),请手动访问 %s", err, url)
	}
}
