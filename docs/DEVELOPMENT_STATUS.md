# 开发状态（DEVELOPMENT STATUS）

> 快照时间：2026-11-26（Asia/Shanghai）。
> 本文件是**当前状态**快照；任务流水见 [TASK_HISTORY.md](TASK_HISTORY.md)，正在做的事见 [CURRENT_TASK.md](CURRENT_TASK.md)。

## 版本

| 组件 | 版本 | 说明 |
|---|---|---|
| Windows 服务端 | 1.7.0 | `server/config.go` 的 `const Version` |
| Android 客户端 | 1.8.0（versionCode 9） | `android/app/build.gradle.kts` |

## 里程碑进度

- **M1–M13 均已交付**（逐项见 [PLAN.md](../PLAN.md) 与 [TASK_HISTORY.md](TASK_HISTORY.md)）：
  - 服务端：核心文件 API / Range、媒体库索引与增量 + 对账、转码、WebDAV、安全（多用户 / 会话 / 分享 / 审计）、Zip、WoL、音乐、存储分析、媒体库排除。
  - Android：连接 / 发现、浏览 / 上传 / 下载、播放器、主题、保险箱、回收站、分享、多用户、离线缓存、传输中心、生物识别、版本检查。
- **M10（Android 端到端验收）仍在收尾**：自动化与验收清单已就位（[ANDROID_TESTING.md](../ANDROID_TESTING.md)），但**真实设备 + 真实服务端**的发布验收记录尚需补齐，见下方「风险」。

## 测试与 CI

- 服务端：`go test ./...`、`go vet ./...`、`go test -race ./...` 在本地与 CI 通过；新增索引增量、登录防爆破 / 会话、维护清理、Zip、WoL、媒体库排除、App 更新等单测。
- Android：`:app:testDebugUnitTest` 通过（当前 11 个单测，含 `FileFiltersTest`）；`:app:assembleDebug` BUILD SUCCESSFUL（镜像 Gradle 8.7 + JDK 21）。
- CI：`.github/workflows/ci.yml` 三个任务 —— `go-windows`（测试 / vet / 构建 + Android 单测与 debug 构建并上传产物）、`go-race`（竞态检测）、`android-emulator`（API 34 模拟器跑 `connectedDebugAndroidTest`）。

## 最近改动

- 2026-11-26 服务端 1.7.0 修补（工作区，未提交）：媒体库自动分页加载（每页 500，自动拉完剩余页，去掉必须手动「加载更多」）；回收站新增 `GET /api/trash/thumb`，网格/列表显示图片与视频缩略图；「不在媒体库展示」改用睁眼/闭眼图标并即时显示「已隐藏」徽标。新增 `server/trash_thumb_test.go`；`go build/vet/test` 与 `node --check` 通过。已重新构建并部署到本机 52700。
- 已完成文档补全并对齐，提交并推送到 GitHub `origin/main`：
  - `f72a517` 新增 `AGENTS.md`、`CHANGELOG.md`、`docs/{ARCHITECTURE,DEVELOPMENT_STATUS,TASK_HISTORY,CURRENT_TASK}.md`，README 补充存储空间分析 / 媒体库排除 / 自动对账（并删除拼写错误的 `RELEGSING.md`）。
  - `faefea0` 对齐 `README.md`、`android/README.md`、`server/README.md`、`docs/FEATURE_ROADMAP.md` 的版本与功能描述。

## 风险 / 已知限制

- **发布门槛**：Android 仍需在 Android 7（API 24）与 Android 14（API 34）真机、以及真实服务端上完成 [ANDROID_TESTING.md](../ANDROID_TESTING.md) 清单，方可视为可发布。
- APK 为 **debug 签名**，仅适合安装测试，不是生产签名包。
- `linnas-data` 备份不含媒体文件与保险箱实际文件，迁移 / 备份需另行处理。
- Windows 上 5353（mDNS）可能被系统 DNS Client 占用，发现依赖 UDP 52701 / 网段扫描兜底。
- `server/linnas-data/` 内有本地运行数据与日志（已被忽略），勿提交。

## 下一步（候选，未排期）

1. 完成 M10 真机端到端验收并记录结果。
2. 提交本次 README 与文档改动。
3. 后续功能方向见 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md)（优先候选：对话空间 / ZIP 预览与安全解压 / 上传收集箱）。
