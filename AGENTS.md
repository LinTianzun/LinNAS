# AGENTS.md — 面向 AI / 协作开发者的仓库约定

本文件给在本仓库工作的 AI 编码助手与人类贡献者提供最小必要上下文与硬性约定。开始改动前，先读 [README.md](README.md)、[PLAN.md](PLAN.md) 与 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 项目简介

LinNAS 是局域网内的「本地云盘 / NAS」：一台 Windows PC 挂外置硬盘跑 Go 服务端，手机（Android）与浏览器在同一 WiFi 下像云盘一样上传 / 浏览 / 下载并在线播放视频；可选通过 Tailscale 远程访问。数据保存在自己的硬盘，不依赖第三方云。

## 目录结构

```
server/     Go 服务端 + 内嵌 Web UI（单文件 exe），模块见 docs/ARCHITECTURE.md
android/    Android 客户端（Kotlin + Media3/ExoPlayer，Gradle 工程）
docs/       架构 / 状态 / 历史文档与截图
scripts/    release.ps1、backup-data.ps1、restore-data.ps1 等 PowerShell 脚本
shots/      各版本界面预览截图
release/    构建产物（APK / 服务端 exe，已被 .gitignore 忽略）
```

根目录还有：`README.md`（总览）、`PLAN.md`（里程碑与历史）、`RELEASING.md`（构建发布）、`DESIGN.md`（Android 设计系统）、`DATA_BACKUP.md`、`ANDROID_TESTING.md`、`CHANGELOG.md`；`docs/FEATURE_ROADMAP.md`（后续候选）。

## 组件与版本（各自独立演进）

| 组件 | 当前版本 | 版本号位置 |
|---|---|---|
| Windows 服务端 | 1.8.5 | `server/config.go` 的 `const Version` |
| Android 客户端 | 1.8.13（versionCode 22） | `android/app/build.gradle.kts` |

改版本号时同时更新对应 README、`PLAN.md` 与 `RELEASING.md`；两个组件版本号互不推算。

## 构建与测试

服务端（在 `server/` 下）：

```powershell
cd server
go test ./...
go vet ./...
go build -trimpath -ldflags "-s -w" -o linNas-server.exe .
```

- Go 1.27；SQLite 用 `modernc.org/sqlite`（纯 Go，无 CGO，保证单 exe）。
- Web UI 用 `//go:embed ui` 内嵌；改 `server/ui/` 后**必须重新 `go build`** 才生效。开发时可用 `-ui-dir <目录>` 或 `LINNAS_UI_DIR` 直接读磁盘目录、免重建。

Android（在 `android/` 下）：

```powershell
cd android
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest   # 需设备 / 模拟器
```

- `minSdk 24 / targetSdk 34 / compileSdk 34`，JDK 17+，Gradle Wrapper 8.7。

发布与数据：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\release.ps1     # 构建带版本号产物 + SHA256
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-data.ps1 -Destination <目录>
```

## 硬性约定 / 注意

- **运行期数据目录** `server/linnas-data/`（可用 `LINNAS_DATA` 覆盖）已被忽略，勿提交：含 `config.json`、`users.json`、`shares.json`、`audit.log`、`index.db`（WAL）、`thumbs/`、`transcode/`。
- **不要提交构建产物与本地文件**：`release/`、`server/*.exe`、`*.apk`、`linnas-data/`、`ui-preview/`、`*.bak`、`*.part` 等，见 `.gitignore`。
- **安全**：日志 / 文档 / 提交里不要出现密码、token、会话串等敏感值；`.md` 文件保持 UTF-8。
- **测试随改动提供**：Go 单测与实现同目录（`*_test.go`）；Android 单测在 `android/app/src/test/`。动到下载协议、索引、鉴权、回收站、Zip、WoL 等请在对应 `*_test.go` 增补断言。
- **虚拟路径与防穿越**：所有文件访问走 `/libN/...` 虚拟路径，经 `Config.Resolve` 校验；不要绕过。
- **权限模型**：角色 `admin / user / viewer`；新增写接口要在 `security.go` 的 `authorize` 中正确守卫。
- **媒体库 / 排除语义**：`Exclude` 完全隐藏并跳过索引；`MediaHide` 仍索引、可在文件浏览 / 搜索访问，只是不出现在媒体库。
- **破坏性操作需人工确认**：重复文件 / 大文件清理等必须先由用户确认，再走 `POST /api/delete`（进回收站）。

## 变更记录习惯

- 面向用户的改动写进 [CHANGELOG.md](CHANGELOG.md) 与 [PLAN.md](PLAN.md)。
- 当前进度见 [docs/DEVELOPMENT_STATUS.md](docs/DEVELOPMENT_STATUS.md)，任务流水见 [docs/TASK_HISTORY.md](docs/TASK_HISTORY.md)。

## 上下文压缩失败时怎么办（写给后续 AI）

本会话的 `compress` 工具会按「消息引用」（`mNNNNN`）折叠历史。引用是**按顺序生成、不可预分配**的，因此以下情况会连续报错，并且**重试同一批区间一定继续失败**：

- 报错 `startId="mXXXXX" is above this session's highest allocated ref (mYYYYY)` —— 说明引用的区间来自**上一代会话**（模型/上游切换会让引用从 m00001 重新计数）或已被折叠；当前会话根本不存在这些消息。
- 报错 `overlaps an earlier range in the batch` —— 同一批里区间互相重叠；区间必须**互不重叠**。
- 报错 `folded nothing — every message in it is already covered by active block(s)` —— 该区间早已被折叠，属于重复提交。
- 连续 3–5 次失败后会出现 **COMPRESS CIRCUIT BREAKER**：本会话后续 `compress` 全部拒绝。

**正确做法（按顺序）**：

1. **立刻停止**，不要重试同一区间，也不要换一个区间碰运气——熔断后所有调用都会失败。
2. 调用 `acp_status` 看**当前这一代**的可压缩区间与最高引用；只压缩它列出的、`startId` 与 `endId` 都不超过最高引用的区间。
3. 一次 `compress` 调用里放**多个互不重叠**的区间（每块以 `mNNNNN–mNNNNN 主题` 开头），不要一个区间一次调用。
4. 如果 `acp_status` 显示「无可压缩区间」，就**直接继续任务**，不要再调 `compress`、也不要轮询 `acp_status`。压缩失败不会损坏会话或文件，只是白费一次调用。
5. 每次模型/上游切换后，`mNNNNN` 引用会重置；**切换后的第一件事是重新 `acp_status`**，不要沿用旧引用。
6. 熔断通常在**新会话/新生成**后自动解除；若换新会话仍复现，按工具描述到 `github.com/ranxianglei/billion-context` 反馈，并附完整报错（含越界区间）。
