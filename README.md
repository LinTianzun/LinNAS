# LinNAS

局域网内的「本地云盘 / NAS」方案：一台 Windows PC 挂载外置硬盘跑服务端，手机在家连同一 WiFi 即可像云盘一样上传、浏览、下载，并在线播放视频。全程局域网直连；可选通过 Tailscale 远程访问，不需要公网 IP 或端口映射。

> 当前组件版本：Windows 服务端 **1.7.0**；Android 客户端 **1.8.3**（`versionCode` 12）。组件独立演进，版本号不要求一致。发布说明见 [RELEASING.md](RELEASING.md)。

## 组成

| 模块 | 技术栈 | 说明 |
|---|---|---|
| PC 服务端 | Go（标准库 `net/http`，单文件 exe，Web UI 嵌入） | 文件 API、WebDAV、Range 流式播放、分块上传、SQLite 媒体索引（增量更新 + 定时自动对账）、ffmpeg 按需转码、存储空间分析（大文件 Top N / 重复文件候选）、媒体库排除、多用户与登录防爆破、活跃会话管理、分享、在线音乐播放器、文件夹 Zip 打包下载、网络唤醒（WoL）、Tailscale 等 |
| Android 客户端 | Kotlin 原生 + Media3/ExoPlayer + OkHttp | 局域网自动发现、浏览/上传/下载、内置播放器、离线缓存、统一传输中心（暂停/续传）、相册自动备份、多用户、生物识别、版本检查 |
| Web 管理端 | 原生 JavaScript + CSS（无框架，构建产物嵌入 exe） | 库管理、文件管理、上传、设置 |

## 目录结构

```
android/    Android 客户端（Kotlin，Gradle 工程）
server/     Go 服务端与 Web UI
docs/       截图与文档
shots/      各版本界面预览截图
release/    各版本 APK 与版本化服务端构建产物
PLAN.md     方案与里程碑
CHANGELOG.md 版本变更日志
AGENTS.md   AI / 协作开发约定（构建、测试、规范）
docs/ARCHITECTURE.md 架构说明
docs/FEATURE_ROADMAP.md 后续功能方向与对话空间构想
RELEASING.md Windows 构建、校验与发布流程
DATA_BACKUP.md 应用数据备份与恢复
ANDROID_TESTING.md Android 测试与真机验收
```

## 核心特性

- **Range 流式播放**：`206 Partial Content`，拖动进度条、按需转码（AVI/RMVB/FLV 等老格式走 ffmpeg 边转边发）
- **分块上传 + 断点续传**：5MB 分块，失败只重传该块，SHA256 校验
- **媒体库**：SQLite 索引（文件增删改后**增量更新**，并按可配置间隔**自动对账**发现资源管理器等外部改动）、全局搜索、缩略图（ffmpeg 抽帧）
- **存储空间分析**：库占用概览、大文件 Top N、重复文件候选（按大小分组 + 抽样哈希判定，**人工确认后**才移入回收站）
- **媒体库排除**：存储库内的文件/文件夹可标记「不在媒体库展示」，不影响文件浏览、搜索与访问
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

- **Windows 服务端 1.7.0**：文件管理、媒体索引（增量更新 + 定时自动对账外部改动）、播放/转码（含转码缓存上限）、多用户与登录防爆破、活跃会话管理、分享、Tailscale 远程访问、WebDAV、在线音乐播放器、文件夹 Zip 打包下载、网络唤醒（WoL）、回收站按天自动清理与列表/网格切换、存储空间分析（大文件 Top N / 重复文件候选）、媒体库排除（不在媒体库展示）。
- **Android 1.8.3**：文件/媒体/传输/设置、扫码配对、离线缓存、相册备份、保险箱、回收站、分享与多用户登录、操作日志；统一传输中心（下载/ZIP 暂停续传·重试·取消）、文件多选批量操作、生物识别（指纹解锁/登录）、版本检查、收藏/最近/搜索历史与筛选、B 站式播放器（选集抽屉/手势/倍速/字幕/睡眠定时）、媒体库按文件夹分组；6 套主题（3 深 + 3 浅）；1.8.1 真机反馈修复（媒体双模式 / 横屏播放 / 主题色块 / 面包屑 / 底部菜单 / 缓存下载 / 备份去重）；1.8.2 传输中心提速、播放器更多菜单与横屏右侧选集、二级页视觉重做；1.8.3 浅色「月白」主题、播放页 B 站式重做（横屏右下倍速/字幕/选集、半透明可关闭选集面板、三段式三点菜单、竖屏简介可收起）、二级弹窗统一重做、传输页删除已下载/缓存、传输效率优化。
- **仍未实现**：无。

详细历史与路线图见 [PLAN.md](PLAN.md)；变更日志见 [CHANGELOG.md](CHANGELOG.md)；架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；开发约定见 [AGENTS.md](AGENTS.md)；构建发布见 [RELEASING.md](RELEASING.md)；应用数据备份与恢复见 [DATA_BACKUP.md](DATA_BACKUP.md)；Android 自动化测试与真机验收见 [ANDROID_TESTING.md](ANDROID_TESTING.md)。
后续功能候选与“对话空间”构想见 [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md)。
