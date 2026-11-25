# LinNAS

局域网内的「本地云盘 / NAS」方案：一台 Windows PC 挂载外置硬盘跑服务端，手机在家连同一 WiFi 即可像云盘一样上传、浏览、下载，并在线播放视频。全程局域网直连；可选通过 Tailscale 远程访问，不需要公网 IP 或端口映射。

> 当前组件版本：Windows 服务端 **1.7.0**；Android 客户端 **1.5.0**（`versionCode` 7）。组件独立演进，版本号不要求一致。发布说明见 [RELEASING.md](RELEASING.md)。

## 组成

| 模块 | 技术栈 | 说明 |
|---|---|---|
| PC 服务端 | Go（标准库 `net/http`，单文件 exe，Web UI 嵌入） | 文件 API、WebDAV、Range 流式播放、分块上传、SQLite 媒体索引（增量更新）、ffmpeg 按需转码、多用户与登录防爆破、活跃会话管理、分享、在线音乐播放器、文件夹 Zip 打包下载、网络唤醒（WoL）、Tailscale 等 |
| Android 客户端 | Kotlin 原生 + Media3/ExoPlayer + OkHttp | 局域网自动发现、浏览/上传/下载、内置播放器、离线缓存、相册自动备份、多用户 |
| Web 管理端 | 原生 JavaScript + CSS（无框架，构建产物嵌入 exe） | 库管理、文件管理、上传、设置 |

## 目录结构

```
android/    Android 客户端（Kotlin，Gradle 工程）
server/     Go 服务端与 Web UI
docs/       截图与文档
shots/      各版本界面预览截图
release/    各版本 APK 与版本化服务端构建产物
PLAN.md     方案与里程碑
docs/FEATURE_ROADMAP.md 后续功能方向与对话空间构想
RELEASING.md Windows 构建、校验与发布流程
DATA_BACKUP.md 应用数据备份与恢复
ANDROID_TESTING.md Android 测试与真机验收
```

## 核心特性

- **Range 流式播放**：`206 Partial Content`，拖动进度条、按需转码（AVI/RMVB/FLV 等老格式走 ffmpeg 边转边发）
- **分块上传 + 断点续传**：5MB 分块，失败只重传该块，SHA256 校验
- **媒体库**：SQLite 索引（文件增删改后**增量更新**，不再全库重扫）、全局搜索、缩略图（ffmpeg 抽帧）
- **局域网发现**：mDNS 广播 + 配对二维码，无需手输 IP
- **多用户与角色权限 / 登录防爆破（连续失败锁定）/ 活跃会话查看与吊销 / 操作日志 / 可设密码和有效期的分享链接**
- **隐私保险箱 / 回收站（可设保留天数自动清理）/ 存储库排除目录 / 设备管理**
- **在线音乐播放器**：渐变唱片界面、播放列表、进度拖动、自动连播
- **文件夹 Zip 打包下载**：多选或整个文件夹服务端流式打包
- **网络唤醒（Wake-on-LAN）**：记录主机 MAC，一键发送唤醒包
- **相册自动备份**（Android，WorkManager 增量备份）
- **可选远程访问**：通过 Tailscale，不开放公网端口
- **WebDAV**：按存储库挂载，可供兼容客户端访问；权限继承现有账号与存储库设置

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

## 当前状态

- **Windows 服务端 1.7.0**：文件管理、媒体索引（增量更新）、播放/转码（含转码缓存上限）、多用户与登录防爆破、活跃会话管理、分享、Tailscale 远程访问、WebDAV、在线音乐播放器、文件夹 Zip 打包下载、网络唤醒（WoL）、回收站按天自动清理。
- **Android 1.5.0**：文件/媒体/传输/设置、扫码配对、离线缓存、相册备份、保险箱、回收站、分享与多用户登录、操作日志、播放列表连播、画中画；6 套主题（4 深 + 2 浅）、重做的播放器控制层与设置页、备份/下载位置可选。
- **仍未实现**：无。

详细历史与路线图见 [PLAN.md](PLAN.md)；构建发布见 [RELEASING.md](RELEASING.md)；应用数据备份与恢复见 [DATA_BACKUP.md](DATA_BACKUP.md)；Android 自动化测试与真机验收见 [ANDROID_TESTING.md](ANDROID_TESTING.md)。
后续功能候选与“对话空间”构想见 [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md)。
