# 开发状态（DEVELOPMENT STATUS）

> 快照时间：2026-11-27（Asia/Shanghai）。
> 本文件是**当前状态**快照；任务流水见 [TASK_HISTORY.md](TASK_HISTORY.md)，正在做的事见 [CURRENT_TASK.md](CURRENT_TASK.md)。

## 版本

| 组件 | 版本 | 说明 |
|---|---|---|
| Windows 服务端 | 1.8.1 | `server/config.go` 的 `const Version` |
| Android 客户端 | 1.8.9（versionCode 18） | `android/app/build.gradle.kts` |

## 里程碑进度

- **M1–M13 均已交付**（逐项见 [PLAN.md](../PLAN.md) 与 [TASK_HISTORY.md](TASK_HISTORY.md)）：
  - 服务端：核心文件 API / Range、媒体库索引与增量 + 对账、转码、WebDAV、安全（多用户 / 会话 / 分享 / 审计）、Zip、WoL、音乐、存储分析、媒体库排除。
  - Android：连接 / 发现、浏览 / 上传 / 下载、播放器、主题、保险箱、回收站、分享、多用户、离线缓存、传输中心、生物识别、版本检查。
- **M10（Android 端到端验收）已通过真机基本回归**：2026-11-26 用户单设备实测未发现阻断问题；完整 Android 7 / 14 多机型矩阵与只读账号场景列为可选补测。
- **M15 已全部交付**：批次 1（趋势快照 / 磁盘健康 / 空间告警）与批次 2（校验巡检 + 磁盘健康按存储库过滤），2026-11-26 部署到本机 52700；**M16 已全部交付**：批次 3（正文索引与内容搜索）2026-11-26、批次 4（自动整理规则：预览 → 执行 → 记录 → 撤销）2026-11-27 均部署到本机 52700，服务端随之升至 **1.8.0**（文本类 UTF-8 优先 / GB18030 兜底，PDF 未索引；磁盘健康默认只显示承载已添加存储库的磁盘）。

## 测试与 CI

- 服务端：`go test ./...`、`go vet ./...`、`go test -race ./...` 在本地与 CI 通过；新增索引增量、登录防爆破 / 会话、维护清理、Zip、WoL、媒体库排除、App 更新、增长趋势 / 磁盘健康 / 空间告警、校验巡检等单测。
- Android：`:app:testDebugUnitTest` 通过（当前 11 个单测，含 `FileFiltersTest`）；`:app:assembleDebug` BUILD SUCCESSFUL（镜像 Gradle 8.7 + JDK 21）。
- CI：`.github/workflows/ci.yml` 三个任务 —— `go-windows`（测试 / vet / 构建 + Android 单测与 debug 构建并上传产物）、`go-race`（竞态检测）、`android-emulator`（API 34 模拟器跑 `connectedDebugAndroidTest`）。

## 最近改动

- 2026-11-27 服务端 **1.8.1**：界面与体验改进（用户 8 项反馈）——磁盘健康容量进度条 + 按卷容量（`totalGB`/`freeGB`，修复多分区显示整盘容量）、双击 exe 静默启动（隐藏控制台 + 自动开浏览器 + 托盘，`LINNAS_QUIET=1`，日志写 `linnas.log`）、图片上一张/下一张（同组图片播放列表 + 方向键）、音乐音量记忆与播放器增强（均衡器/睡眠/静音/收藏/扩展名徽标）、服务器自定义名称（`serverName` → 标题/品牌/扫码）、移除总览「规划中」占位卡、侧栏选中态竖条；`go build/vet/test` 全绿，已重建部署 52700。
- 2026-11-27 服务端 **1.8.0**：M16（内容搜索 + 自动整理）全部交付后版本升级（`server/config.go` `const Version` 1.7.0 → 1.8.0），同步 README / PLAN / RELEASING / CHANGELOG 等；重新构建并部署到本机 52700。
- 2026-11-27 自动整理（批次 4）：新增 `server/organize.go` 与 `server/organize_test.go`；规则 CRUD（`organize.json`，admin only）、`POST /api/organize/preview` 只读预览、`/run` 单飞执行（`os.Rename`、同名加序号、跨库/只读/保险箱/回收站/排除目录跳过、单次上限 5000、`journal-<jobID>.jsonl`、完成后调 `Index.UpdatePaths` 增量刷新）、`/undo` 按 journal 倒序回搬、`/jobs` 最近 50 条、`/cancel`；撤销后整理作业不再提供撤销。UI 存储空间页新增「自动整理」页签（规则列表 / 编辑对话框 / 预览前 50 条 / 执行确认 / 进度 / 作业历史与撤销）。真实环境端到端验收通过；`go build/vet/test` 全绿。已构建并部署到本机 52700（工作区待提交）。
- 2026-11-26 内容搜索（批次 3）：新增 `server/content.go`（`content` / `content_meta` 表、后台抽取并发 2、单文件 ≤2MB、文本截断 1MB、UTF-8 优先 / GB18030 兜底；txt/md/log/csv/json/xml/html/htm/srt/ass/vtt 直读 + docx/xlsx/pptx 抽取，PDF 未索引）与 `GET /api/search/content`、`GET /api/content/status`；索引扫描 / 对账完成后异步增量入队；UI 搜索范围切换（文件名 / 内容 / 全部）、片段高亮与索引状态提示；磁盘健康默认只显示承载已添加存储库的磁盘（`lib=all` 才返回全部）。新增 `content_test.go`，扩充 `disk_health_test.go`；`go build/vet/test` 全绿。已构建并部署到本机 52700（工作区待提交）。
- 2026-11-26 存储空间分析增强（批次 2）：新增**校验巡检** `server/patrol.go`（`file_sig` 表、quick/deep 签名、单飞可取消、只报告不修改、`patrol.json` 保留 20 条）与 `POST/GET /api/storage/patrol/{start,status,cancel,last}`；`GET /api/disks/health` 支持 `?lib=` 按存储库过滤磁盘（`driveOfPath` / `filterDisksByDrive`，库不存在 404）。UI 存储空间页新增「巡检」页签（快速/深度、进度与取消、内容已变化 / 文件缺失 / 读取失败三组、上次巡检摘要）与磁盘健康「存储库」下拉。新增 `patrol_test.go`，扩充 `disk_health_test.go`；`go build/vet/test` 全绿，Playwright 实测巡检 125/125 与缺失项、下拉切库后仅显示 E:。已构建并部署到本机 52700。
- 2026-11-26 存储空间分析增强（批次 1）：`GET /api/storage/trend` 增长趋势与总览 sparkline、`GET /api/disks/health` 磁盘健康（5 分钟缓存）、`storage_snapshots` 表与 `Index.Snapshot()`、空间告警阈值 `spaceWarnPercent`/`spaceCriticalPercent` 可配置、告警存储与已读（`GET /api/alerts`、`POST /api/alerts/ack`）；新增 `server/{trend,disk_health,alerts}.go` 与三个单测文件；`go build/vet/test` 全绿，Playwright 实测趋势图 3 张、磁盘健康 7 行、告警「全部已读」。已构建并部署到本机 52700。
- 2026-11-26 M10 真机基本回归通过（单设备，未发现阻断问题）。
- 2026-11-26 Web UI 总览页与侧栏分组重构（工作区）：主区改全宽消除宽屏左侧死区，侧栏拆为总览 / 媒体 / 存储 / 设备与网络 / 分享与管理；新增总览仪表盘（六项 KPI + 容量 / 内容 / 快捷入口 / 近期活动 / 规划中五张卡片）；后端新增 `server/overview.go` 与 `GET /api/overview`（子查询失败只降级字段），新增 `server/overview_test.go`。`go build/vet/test`、`node --check` 通过；Playwright 1586px 与 800px 两档验证通过；已重新构建并部署到本机 52700。
- 2026-11-26 服务端 1.7.0 修补：媒体库自动分页加载（每页 500，自动拉完剩余页，去掉必须手动「加载更多」）；回收站新增 `GET /api/trash/thumb`，网格/列表显示图片与视频缩略图；「不在媒体库展示」改用睁眼/闭眼图标并即时显示「已隐藏」徽标。新增 `server/trash_thumb_test.go`；`go build/vet/test` 与 `node --check` 通过。已重新构建并部署到本机 52700（提交 `d9590bf`）。
- 已完成文档补全并对齐，提交并推送到 GitHub `origin/main`：
  - `f72a517` 新增 `AGENTS.md`、`CHANGELOG.md`、`docs/{ARCHITECTURE,DEVELOPMENT_STATUS,TASK_HISTORY,CURRENT_TASK}.md`，README 补充存储空间分析 / 媒体库排除 / 自动对账（并删除拼写错误的 `RELEGSING.md`）。
  - `faefea0` 对齐 `README.md`、`android/README.md`、`server/README.md`、`docs/FEATURE_ROADMAP.md` 的版本与功能描述。

## 风险 / 已知限制

- **发布门槛**：真机基本回归已通过（2026-11-26，单设备）；建议在 Android 7 / 14 双版本与只读账号场景补测 [ANDROID_TESTING.md](../ANDROID_TESTING.md) 后再发布。
- APK 为 **debug 签名**，仅适合安装测试，不是生产签名包。
- `linnas-data` 备份不含媒体文件与保险箱实际文件，迁移 / 备份需另行处理。
- Windows 上 5353（mDNS）可能被系统 DNS Client 占用，发现依赖 UDP 52701 / 网段扫描兜底。
- `server/linnas-data/` 内有本地运行数据与日志（已被忽略），勿提交。

## 下一步（候选，未排期）

1. 完成 release 签名与 CI 发布自动化（见 [ROADMAP_BACKLOG.md](ROADMAP_BACKLOG.md) BL-002 / BL-003）。
2. （可选）在 Android 7 / 14 双版本补测关键路径；单机基本回归已通过。
3. 后续功能方向见 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) 与 [ROADMAP_BACKLOG.md](ROADMAP_BACKLOG.md)（M15 / M16 已排定实施计划）。
