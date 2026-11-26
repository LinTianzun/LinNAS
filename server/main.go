package main

import (
	"embed"
	"fmt"
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
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed ui
var uiEmbedded embed.FS

// uiFS is the web UI root: the embedded copy by default, or an on-disk
// directory when started in development mode (-ui-dir / LINNAS_UI_DIR).
var uiFS fs.FS

// embeddedUIFS returns the UI embedded in the binary, rooted at server/ui/dist.
func embeddedUIFS() (fs.FS, error) {
	return fs.Sub(uiEmbedded, "ui/dist")
}

// resolveUIFS picks the web UI filesystem. If dir is non-empty and contains an
// index.html file, the UI is served straight from that on-disk directory and the
// source is "disk"; otherwise it falls back to the UI embedded in the binary and
// the source is "embedded". When a disk directory was requested but could not be
// used, an error explains why while the returned fs.FS is still the usable
// embedded UI, so callers never crash on a bad directory.
func resolveUIFS(dir string) (fs.FS, string, error) {
	if dir != "" {
		if info, statErr := os.Stat(filepath.Join(dir, "index.html")); statErr == nil && !info.IsDir() {
			return os.DirFS(dir), "disk", nil
		}
		emb, embErr := embeddedUIFS()
		if embErr != nil {
			return nil, "embedded", fmt.Errorf("目录 %q 不可用,且内嵌 UI 加载失败: %w", dir, embErr)
		}
		return emb, "embedded", fmt.Errorf("目录 %q 下未找到 index.html,已回退内嵌 UI", dir)
	}
	emb, err := embeddedUIFS()
	if err != nil {
		return nil, "embedded", err
	}
	return emb, "embedded", nil
}

func init() {
	var err error
	uiFS, err = embeddedUIFS()
	if err != nil {
		panic(err)
	}
}

func main() {
	// -no-browser disables auto-opening the admin page on start.
	// -tray runs the server with a system tray icon (no console window).
	openBrowser := true
	trayMode := false
	// -ui-dir <dir> serves the web UI straight from an on-disk directory
	// (development mode: edit CSS/JS, no rebuild). LINNAS_UI_DIR is the
	// environment equivalent; the command-line flag wins when both are set.
	uiDir := os.Getenv("LINNAS_UI_DIR")
	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch arg := args[i]; arg {
		case "-no-browser":
			openBrowser = false
		case "-tray":
			trayMode = true
			openBrowser = false
		case "-ui-dir":
			if i+1 >= len(args) {
				log.Printf("参数 -ui-dir 缺少目录值,忽略")
				continue
			}
			i++
			uiDir = args[i]
		default:
			if v, ok := strings.CutPrefix(arg, "-ui-dir="); ok {
				uiDir = v
			}
		}
	}

	resolvedUIFS, uiSource, uiErr := resolveUIFS(uiDir)
	if uiErr != nil {
		log.Printf("Web UI 回退: %v", uiErr)
	}
	uiFS = resolvedUIFS
	if uiSource == "disk" {
		abs, absErr := filepath.Abs(uiDir)
		if absErr != nil {
			abs = uiDir
		}
		log.Printf("Web UI 来源: 磁盘目录 %s", abs)
	} else {
		log.Printf("Web UI 来源: 内嵌")
	}
	if trayMode && !traySupported() {
		log.Printf("当前系统不支持托盘,忽略 -tray")
		trayMode = false
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
		// 扫描/对账完成后异步触发内容索引增量入队(不阻塞索引与 HTTP)。
		ix.SetAfterIndex(func() { srv.enqueueContentIndex() })
		go func() {
			time.Sleep(1500 * time.Millisecond)
			if ix.NeedsScan(6 * time.Hour) {
				ix.ScanAll(cfg) // 后台建索引,不阻塞启动
			} else {
				log.Printf("索引较新(%s),跳过启动扫描", ix.lastScanText())
				// 索引较新时也补一次内容索引(可能上次未完成)。
				srv.enqueueContentIndex()
			}
		}()
		// 周期性增量对账:发现资源管理器等外部改动(移动/删除/重命名)。
		// 首次延迟,避免与启动全量扫描抢资源(扫描中 Reconcile 会自然跳过)。
		go func() {
			time.Sleep(30 * time.Second)
			for {
				secs := cfg.IndexRefreshSeconds // 运行期可变化,每次重新读取
				if secs <= 0 {
					time.Sleep(30 * time.Second)
					continue
				}
				time.Sleep(time.Duration(secs) * time.Second)
				if cfg.IndexRefreshSeconds <= 0 {
					continue
				}
				ix.ReconcileAll(cfg)
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
	// 后台维护:回收站自动清理 + 转码缓存淘汰 + 过期保险箱会话清理
	srv.startHousekeeping()

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
	if trayMode {
		hideConsoleWindow()
	}

	go func() {
		if err := httpSrv.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP 服务异常: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	if trayMode {
		// 托盘模式:主线程跑 systray 的消息循环(Win32 要求),HTTP 服务跑在后台
		// goroutine 里;托盘菜单点「退出」或收到系统信号都通过 shutdown 通道收尾。
		shutdown := make(chan struct{})
		var once sync.Once
		finish := func() { once.Do(func() { close(shutdown) }) }
		go func() {
			<-stop
			finish()
		}()
		runTray(trayOptions{
			URL:      "http://127.0.0.1:" + strconv.Itoa(cfg.Port) + "/ui/",
			DataDir:  dataDir,
			Version:  Version,
			Port:     cfg.Port,
			Shutdown: finish,
		})
		<-shutdown
		log.Printf("正在关闭...")
		_ = httpSrv.Close()
		return
	}

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
