# LinNAS 架构说明

> 更新：2026-11-26。当前版本：Windows 服务端 1.7.0；Android 1.8.2（versionCode 11）。
> 本文描述**现状**；里程碑与版本演进见 [PLAN.md](../PLAN.md)，后续候选见 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md)。

## 1. 目标与约束

- 一台 Windows PC 挂载外置机械硬盘（如 `H:\`、`I:\`）运行服务端；Android 手机在同一 WiFi 下像本地云盘一样上传 / 浏览 / 下载、在线播放视频；浏览器可做管理端。
- 全程局域网直连，不依赖公网 / 云；数据保留在本地磁盘，盘上文件不被改名或打包。
- 可选通过 Tailscale 远程访问（不开放公网端口）。
- 服务端编译为**单个 exe**、无运行时依赖（含内嵌 UI 与纯 Go SQLite 驱动），可双击运行。

## 2. 总体架构

```
[Android App]         [任意浏览器 PC / 手机]
      \   HTTP REST / WebDAV         /
       \                            /
        [Windows 服务端 linNas-server(.exe)]
         ├─ 内嵌 Web UI（//go:embed ui）
         ├─ REST API（/api/*）+ 分享页（/s/{id}）+ 配对页（/pair）+ WebDAV（/dav/<lib>/）
         ├─ SQLite 索引（index.db，WAL）
         ├─ 缩略图 / 转码缓存（thumbs/、transcode/）
         └─ mDNS 广播 + UDP 52701 发现
                |
      [H盘] [I盘] ... 存储库（原始文件；保险箱 .linNasVault；回收站 .linNasTrash）
```

- 通信全部走 HTTP：App 与浏览器共用同一套 REST API；少量 UDP / mDNS 用于局域网发现。
- 写操作经中间件链鉴权与审计：`withToken → auditLog → recordDevice → logRequests`。

## 3. Windows 服务端（Go）

- 技术：Go 1.27，`net/http` 标准库；SQLite 使用 `modernc.org/sqlite`（纯 Go，无 CGO）；托盘 `github.com/getlantern/systray`；二维码 `github.com/skip2/go-qrcode`；mDNS `golang.org/x/net`。
- 启动（`server/main.go`）：解析 `-no-browser / -tray / -ui-dir`；数据目录默认 exe 同级 `linnas-data`（`LINNAS_DATA` 覆盖）；首次运行自动注册存在的 `H:\`、`I:\`；后台启动索引扫描、增量对账、mDNS / UDP 发现、housekeeping（回收站 / 转码缓存 / 保险箱会话清理）。
- Web UI 以 `//go:embed ui` 内嵌 `ui/dist`；`-ui-dir` / `LINNAS_UI_DIR` 可切换到磁盘目录（开发免重建）。

### 3.1 主要源文件（`server/`）

| 文件 | 职责 |
|---|---|
| `main.go` | 启动、参数、数据目录、UI embed / 回退、自动注册库、后台任务启动、托盘 |
| `config.go` / `config_store.go` | `Config` / `Library` 定义与持久化；`Version`、默认端口 52700 |
| `store.go` | 虚拟路径 `/libN/...` 解析与**防穿越**、库内路径换算、名称校验、排除 / 隐藏目录过滤 |
| `api.go` | 路由注册、中间件链、`Server` 结构、通用处理器 |
| `security.go` | 用户 / 会话（pbkdf2 120000 轮）、自动锁定、角色 `authorize`、审计日志、分享链接与分享页 |
| `index.go` / `api_index.go` | SQLite 索引（全量扫描、`UpdatePaths` 增量、`ReconcileAll` 对账）、搜索 / 媒体查询 |
| `upload.go` | 分块上传（5 MiB）会话、断点续传、SHA-256、冲突 / 回滚 |
| `media.go` / `subtitle.go` / `transcode.go` / `info.go` | 缩略图抽帧；字幕列表 / 转 WebVTT；ffmpeg 按需转码与缓存；ffprobe 详情 |
| `vault.go` | 隐私保险箱（隐藏目录 + 密码，非加密；导入 / 导出 / 浏览 / 播放） |
| `trash.go` | 回收站（`.linNasTrash` + `.meta`，恢复 / 彻底删除 / 清空 / 按天清理 / 缩略图） |
| `fsops.go` / `size.go` / `disks.go` | 目录选择器 / 盘符枚举、移动 / 复制 / 改名 / 删除、目录递归大小、磁盘剩余 |
| `analysis.go` | 存储空间分析：概览、大文件 Top N、重复文件候选（后台抽样哈希，人工确认后删除） |
| `media_hide.go` | 「不在媒体库展示」路径的增删查（仍索引、仍可访问） |
| `trend.go` | 存储增长趋势：`storage_snapshots` 按小时快照 + `GET /api/storage/trend`（7/30/90 天，SVG 面积图） |
| `disk_health.go` | 磁盘健康：Windows `Get-PhysicalDisk`/`Get-Volume`，5 分钟缓存，`GET /api/disks/health`（非 Windows → supported:false） |
| `alerts.go` | 空间告警存储：`alerts.json` 触发 / 恢复 / 已读，`GET /api/alerts`、`POST /api/alerts/ack` |
| `patrol.go` | 校验巡检（`file_sig` 表）：quick（大小 + 首尾 64KB 抽样 SHA-256）/ deep（全文件 SHA-256，~20MB/s 限速）签名比对，单飞可取消，只报告不修改；`POST /api/storage/patrol/{start,cancel}`、`GET /api/storage/patrol/{status,last}` |
| `overview.go` | 总览仪表盘数据：`GET /api/overview`（库容量、全库与类型统计、索引 / 回收站 / 分享 / 设备、近期活动、空间与索引告警） |
| `content.go` | 正文索引与内容搜索：`content` / `content_meta` 表、后台抽取 worker（并发 2、300ms 软超时、可取消、单文件 ≤2MB、文本截断 1MB、UTF-8 优先 / GB18030 兜底）、`GET /api/search/content`（命中片段高亮）、`GET /api/content/status` |
| `webdav.go` | WebDAV 服务（`/dav/<库ID>/`，继承账号 / 只读，敏感目录不可访问） |
| `zip.go` | 文件夹 / 多选服务端流式 Zip 打包 |
| `wol.go` | 网络唤醒主机管理与 magic packet |
| `mdns.go` / `pair.go` | mDNS 广播 `_linnas._tcp.`、UDP 52701 发现 / 广播、配对 JSON / 二维码 / 配对页 |
| `tailscale.go` / `hotspot.go` | Tailscale 状态与 serve；Windows 移动热点（WinRT）辅助未同网时配对 |
| `autostart.go` / `tray*.go` | 开机自启、系统托盘（Windows / 其它平台构建变体） |
| `housekeeping.go` | 周期维护：回收站按天清理、转码缓存淘汰、过期保险箱会话 |
| `app_update.go` | `GET /api/app/version`、`GET /api/app/apk`（来源 `release/android-version.json` 与 APK，防穿越） |
| `settings.go` / `devices.go` / `util.go` / `icon_windows.go` | 设置读写、设备记录、工具、Windows 图标 |

### 3.2 API 概览（`/api/*`，`?token=` 或 `X-Token` 鉴权）

- 文件：`ls`、`raw`、`stream`、`thumb`、`upload/{init,chunk,complete,abort,status}`、`mkdir`、`rename`、`move`、`copy`、`delete`、`zip`、`size`、`info`
- 媒体 / 索引：`search`、`search/content`、`content/status`、`media`、`index/status`、`index/refresh`、`reindex`、`subtitle`、`subtitle/content`
- 存储库 / 磁盘：`libs`（GET / POST / DELETE / update / media-hide）、`fs/dirs`、`disks`
- 安全 / 账号：`auth/{login,logout,me,status,sessions,revoke}`、`users`（+password / update）、`audit`、`devices`、`shares`、`share/{id}`、`share/{id}/raw`
- 保险箱：`vault/{status,setup,unlock,lock,ls,raw,upload,delete,mkdir,import,export,改密}`
- 回收站：`trash`、`trash/{restore,delete,empty,thumb}`
- 其它：`health`、`settings`、`pair`、`pair/qr`、`qr`、`remote`、`remote/serve`、`hotspot`、`autostart`、`app/version`、`app/apk`、`wol`、`wol/send`、`maintenance/cleanup`、`system/open`、`overview`、`storage/{summary,large,duplicates,duplicates/scan,trend}`、`disks/health`（可 `?lib=` 按存储库过滤）、`alerts`、`alerts/ack`、`storage/patrol/{start,status,cancel,last}`
- 非 `/api`：`/pair`（配对页）、`/s/{id}`（分享页）、`/dav/<lib>/`（WebDAV）、`/ui/`（管理端）

### 3.3 鉴权与权限

- 无用户且无全局口令：局域网开放（原单用户模式）。
- 设了全局访问口令：视为 `admin`。
- 创建用户后进入多用户模式，需登录拿会话 token；会话闲置超 `idleLockMinutes`（默认 30）视为自动锁定。
- 角色：`admin` 全通；`user` 可读写文件但不能改 settings / libs / users / audit 等；`viewer` 只读（保险箱解锁 / 上锁除外）。
- 登录防爆破：按「用户名 + IP」计失败，连续 5 次锁定 30s 起、指数退避封顶 15min，返回 `429` + 剩余秒数并入审计。

## 4. Android 客户端（Kotlin）

- 技术：Kotlin 原生 + Media3/ExoPlayer（播放）+ OkHttp（网络 / 分块上传）；`minSdk 24 / targetSdk 34 / compileSdk 34`；`applicationId com.linnas.app`。
- 界面：底部 4 Tab（文件 / 媒体 / 传输 / 设置）+ 顶部连接状态 / 搜索 / 面包屑；纯 XML + 代码动态视图（无 Compose），设计规范见 [DESIGN.md](../DESIGN.md)。

主要类（`android/app/src/main/java/com/linnas/app/`）：

| 类别 | 文件 |
|---|---|
| 主界面 / 导航 | `MainActivity.kt`、`ScreenScaffold.kt`、`LinNasApp.kt`（全局崩溃捕获 → `crash.txt` + `CrashActivity`） |
| 连接 / 发现 | `Discovery.kt`（UDP 52701 + /24 扫描）、`MdnsDiscovery.kt`（NsdManager）、`QrCode.kt`、`LoginActivity.kt`、`LinNasClient.kt`、`Models.kt` |
| 文件 / 媒体展示 | `FileListAdapter.kt`、`FileGridAdapter.kt`、`MediaGridAdapter.kt`、`FileFilters.kt` |
| 播放 | `PlayerActivity.kt`（ExoPlayer、手势、倍速、字幕、睡眠定时）、`PlaybackStore.kt`、`PlaybackProgressPolicy.kt` |
| 传输 / 离线 | `upload/UploadService.kt`、`transfer/TransferService.kt`、`TransferStore.kt`、`Downloads.kt`、`OfflineDownloader.kt`、`OfflineDownloadProtocol.kt`、`OfflineStore.kt` |
| 保险箱 / 安全 | `VaultActivity.kt`、`BiometricGate.kt`（BiometricPrompt / FingerprintManager + Keystore AES/GCM） |
| 管理页 | `SessionsActivity.kt`、`UserAdminActivity.kt`、`MaintenanceActivity.kt`、`LibraryAdminActivity.kt`、`WolActivity.kt`、`SharesActivity.kt`、`TrashActivity.kt`、`AuditActivity.kt`、`VersionCheckActivity.kt` |
| 偏好 / 主题 | `ThemeStore.kt`、`ThemeColors.kt`、`FavoritesStore.kt` |
| 备份 | `backup/PhotoBackup.kt`（WorkManager 相册增量备份） |
| 单测 | `app/src/test/java/com/linnas/app/`（如 `FileFiltersTest`、`OfflineDownloadProtocol` 等） |

## 5. Web 管理端

- 原生 JavaScript + CSS（无框架），源码在 `server/ui/`，构建产物 `server/ui/dist/`（`index.html`、`app.js`、`style.css`）经 `//go:embed` 内嵌进 exe。
- 布局：全宽主区（`.app` 不再限制 1180px）+ 236px 固定侧栏；侧栏按「总览 / 存储库 / 媒体 / 存储 / 设备与网络 / 分享与管理」分组，低频入口按角色隐藏。
- 视图：总览仪表盘、文件浏览、媒体库、音乐播放器、回收站、保险箱、分享、用户、审计、会话、维护、网络唤醒、存储分析等；主题深 / 浅 / 跟随系统。

## 6. 数据与存储

- **数据目录** `server/linnas-data/`（`LINNAS_DATA` 可覆盖）：
  - `config.json`：端口、库列表、口令、`idleLockMinutes`、`trashRetentionDays`、`transcodeCacheMB`、`indexRefreshSeconds`、`wolHosts`。
  - `users.json`、`shares.json`、`audit.log`（≤5000 条，启动读入）。
  - `index.db`（SQLite，WAL；伴随 `-wal/-shm`）；`thumbs/`、`transcode/`。
  - 备份仅覆盖以上应用数据；媒体文件、保险箱文件（存储库内 `.linNasVault`）需另行备份，见 [DATA_BACKUP.md](../DATA_BACKUP.md)。
- **存储库 `Library`**：`ID`（`lib1`…）、`Name`、`Path`（绝对路径）、`ReadOnly`、`Enabled`、`Exclude`（完全隐藏且不索引）、`MediaHide`（仍索引 / 可访问，仅不在媒体库展示）。
- **索引表** `files(lib_id, path, parent, name, is_dir, size, mod_time, kind)`；全量扫描约 24.5 万文件 ~75s；启动 6 小时内不重扫。
- **辅助表** `storage_snapshots(lib_id, ts, bytes, files)`（按小时容量快照，趋势用，保留 400 天）；`file_sig(lib_id, path, size, mod_time, sig, sig_at)`（巡检签名基线，`sig` 带 `quick:`/`deep:` 前缀）；`content(lib_id, path, mtime, size, text, indexed_at, PRIMARY KEY(lib_id, path))` 与 `content_meta(k, v)`（正文索引与抽取批次统计）。
- **隐藏目录**：保险箱 `.linNasVault`、回收站 `.linNasTrash`（含 `.meta`），不出现在浏览 / 搜索 / 索引。

## 7. 关键设计点

- **Range 206**：视频拖动进度条的核心；`/api/raw`、`/api/stream`、分享 raw、WebDAV 均支持 Range。
- **分块上传**：固定 5 MiB/块，失败只重传该块，完成后 SHA-256 校验；`.part` 不显示不入索引。
- **按需转码**：AVI / RMVB / FLV 等老格式走 ffmpeg 边转边发（frag-mp4），结果按 `sha1(abs|mtime|size|v1)` 缓存复用。
- **三路发现**：mDNS + UDP 52701 广播 + /24 扫描；Windows 上 5353 常被系统占用，故叠加兜底。
- **单 exe 分发**：UI 内嵌 + 纯 Go SQLite，无 CGO / 无外部运行时；改 UI 需重编译。
- **独立版本**：服务端与 Android 版本号互不关联，见 [RELEASING.md](../RELEASING.md)。

## 8. 相关文档

[README.md](../README.md) · [PLAN.md](../PLAN.md) · [RELEASING.md](../RELEASING.md) · [DESIGN.md](../DESIGN.md) · [DATA_BACKUP.md](../DATA_BACKUP.md) · [ANDROID_TESTING.md](../ANDROID_TESTING.md) · [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) · [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) · [TASK_HISTORY.md](TASK_HISTORY.md)
