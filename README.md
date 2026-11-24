# LinNAS

局域网内的「本地云盘 / NAS」方案：一台 Windows PC 挂载外置硬盘跑服务端，手机在家连同一 WiFi 即可像云盘一样上传、浏览、下载，并在线播放视频。全程局域网直连，不依赖公网与第三方云服务。

## 组成

| 模块 | 技术栈 | 说明 |
|---|---|---|
| PC 服务端 | Go（标准库 `net/http`，单文件 exe，Web UI 嵌入） | 文件 API、Range 流式播放、分块上传、SQLite 媒体索引、ffmpeg 按需转码、mDNS 发现、保险箱、回收站等 |
| Android 客户端 | Kotlin 原生 + Media3/ExoPlayer + OkHttp | 局域网自动发现、浏览/上传/下载、内置播放器、相册自动备份 |
| Web 管理端 | Vue 3 + Vite（构建产物嵌入 exe） | 库管理、文件管理、上传、设置 |

## 目录结构

```
android/    Android 客户端（Kotlin，Gradle 工程）
server/     Go 服务端与 Web UI
docs/       截图与文档
shots/      各版本界面预览截图
release/    各版本构建的 APK（不纳入源码仓库）
PLAN.md     方案与里程碑
```

## 核心特性

- **Range 流式播放**：`206 Partial Content`，拖动进度条、按需转码（AVI/RMVB/FLV 等老格式走 ffmpeg 边转边发）
- **分块上传 + 断点续传**：5MB 分块，失败只重传该块，SHA256 校验
- **媒体库**：SQLite 索引、全局搜索、缩略图（ffmpeg 抽帧）
- **局域网发现**：mDNS 广播 + 配对二维码，无需手输 IP
- **隐私保险箱 / 回收站 / 存储库排除目录 / 设备管理**
- **相册自动备份**（Android，WorkManager 增量备份）

## 快速开始

**服务端（Windows）**

```bat
cd server
go build -o linNas-server.exe .
.\linNas-server.exe
```

也可直接用 `server/启动linNas.bat` 一键启动并自动打开浏览器（默认端口 52700）。详见 `server/README.md`。

**Android**

用 Android Studio / Gradle 打开 `android/` 构建 `app`，或直接安装 `release/` 下的 APK。详见 `android/README.md`。

## 里程碑

- M1 ✅ Go 服务端：文件 API + Range 播放 + Web UI
- M2 ✅ Android：连接 / 浏览 / 上传 / 观看
- M3 ✅ 播放器、缩略图、ffmpeg 转码、媒体库索引
- M4 🚧 相册自动备份 ✅；WebDAV 挂载、Tailscale 远程访问待做

详细方案见 [PLAN.md](PLAN.md)。
