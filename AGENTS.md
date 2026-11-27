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
| Windows 服务端 | 1.8.4 | `server/config.go` 的 `const Version` |
| Android 客户端 | 1.8.11（versionCode 20） | `android/app/build.gradle.kts` |

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
