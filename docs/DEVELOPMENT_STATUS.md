# 开发状态（DEVELOPMENT STATUS）

> 快照时间：2026-11-27（Asia/Shanghai）。
> 本文件是**当前状态**快照；任务流水见 [TASK_HISTORY.md](TASK_HISTORY.md)，正在做的事见 [CURRENT_TASK.md](CURRENT_TASK.md)。

## 版本

| 组件 | 版本 | 说明 |
|---|---|---|
| Windows 服务端 | 1.9.1 | `server/config.go` 的 `const Version` |
| Android 客户端 | 1.8.15（versionCode 24） | `android/app/build.gradle.kts` |

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

- 2026-11-28 服务端 **1.9.1 键盘操作与保险箱预览**：Web UI 新增成体系键盘快捷键（文件浏览方向键/Home/End/PgUp/PgDn 选择、Shift 扩选、空格预览、`u` 上传、`n` 新建文件夹、`r` 刷新、`v` 切视图、`Alt+←` 上一级；保险箱方向键选择、Enter/空格预览、Delete 删除、Backspace 上一级、`u/n/m/l` 上传/新建/加密迁移/锁定、`e` 导出、`d` 下载；播放器 `j/l` ±10s、`k` 播放暂停、`f` 全屏、`p` 画中画、`n/b` 上下集、`[ ]` 调速；顶栏 `?` 按钮与 `?` 弹出帮助）；**选择交互**：单击选中、`Ctrl/⌘+单击` 切换、`Shift+单击`/`Shift+↑↓` 范围选择、`Ctrl+A` 全选、`Esc`/点空白清空，保险箱支持多选后批量删除/导出；**所有对话框输入框按 Enter 直接确认**（登录/解锁/保险箱密码/输入/分享），Esc 取消；保险箱列表图片/视频显示缩略图（`GET /api/vault/thumb`，解密流→ffmpeg 管道、仅内存缓存、锁定时清空），点击行/缩略图或空格/回车直接预览。新增 `server/vault_thumb.go` 与 `TestVaultThumb`；`go test`/`vet`/`node --check` 与 Playwright 浏览器实测（选择计数、多选删除、回车建目录/解锁、0 控制台错误）通过。版本 1.9.0 → **1.9.1**。

- 2026-11-28 服务端 **1.9.0（M23/M24）隐私保险箱静态加密**：`.lvault` 容器（AES-256-GCM、64KB 分块、512 字节固定头区、AAD 绑定块索引）与 `encryptedFile`（`io.ReaderAt`+`io.ReadSeeker`，Range/视频拖动按需解密）；**信封密钥层**——`vault.json` 改为只保存「口令 KEK 包裹的随机 VMK」、每文件 CEK 用 VMK 包裹，旧 v1 的 `hash`（=明文主密钥）解锁时自动升级清除；**文件名加密**——磁盘名 token、显示名加密存容器头；**一次性迁移** `POST /api/vault/migrate`（先统计后确认）与 Web「加密现有文件」按钮、未加密标记；Windows 保险箱目录自动「隐藏+系统」属性；上传/导入流式加密（不落明文临时文件），旧明文兼容读取。新增 `server/{vault_keystore,vault_paths,vault_transfer,vault_migrate,vault_hide_windows,vault_hide_other}.go` 与 `server/{vault_encryption_test,vault_hide_windows_test}.go`；`go build` / `go vet` / `go test ./...` 全绿，真实二进制隔离实例冒烟（文件名加密、Range、迁移、改密、v1 配置升级、隐藏属性）通过。版本 1.8.5 → **1.9.0**。

- 2026-11-27 Android **1.8.15**（M13.15）：无选集时收起选集区——新增 pplyEpisodeAreaVisibility()，playlist.isNotEmpty() && !isLandscape() 才显示 episodeArea，单集视频不再空出半屏；多集仍上下分栏。:app:testDebugUnitTest 15/15、ssembleDebug 成功。
- 2026-11-27 Android **1.8.14**（M13.14）：选集列表从详情 ScrollView 里移出，成为同级独立滚动区（episodeArea + v_episodes 0dp/weight=1/自身滚动），滑动选集只动列表，上方标题与简介不再被顶走、不会被视频盖住。:app:testDebugUnitTest 15/15、ssembleDebug 成功。
- 2026-11-27 Android **1.8.13**（M13.13）：修复竖屏详情页选集列表「只能看到前 5 集、划不动」——v_episodes 在 ScrollView 里被 wrap_content 测量成可见几行且自身不滚动，现超过 5 集时改为固定高度（最多屏高 55%）并开启列表自身滚动。:app:testDebugUnitTest 15/15、ssembleDebug 成功。
- 2026-11-27 服务端 **1.8.5**（M22 / BL-017 分享增强）：分享链接支持**下载次数上限**（`maxDownloads`，`dl=1` 下载前 `shareStore.ConsumeDownload`，耗尽后 `Disabled` 并返回 403「下载次数已用完」，`X-Downloads-Remaining` 告知剩余）、**单次链接**（`oneTime`，下载 1 次即失效）、**批量撤销**（`DELETE /api/shares?ids=a,b,c`，权限沿用 admin 或创建者）与**访问统计**（`RecordVisit` 记录 visits / lastVisitAt / lastVisitIp；`List()` 返回 downloads / remaining / visits 等）；Web 创建对话框加次数上限与单次开关，管理列表行内显示统计并支持勾选批量撤销，分享页显示剩余下载次数。新增 `server/share_enhance_test.go`（7 个单测全绿）；隔离实例端到端实测：2 次下载后第 3 次 403，列表显示 disabled:true / downloads:2 / remaining:0 / visits:1。本版本同时含 M21 跨设备播放进度。
- 2026-11-27 Android **1.8.12**（M13.12）：保险箱解锁后密码弹层自动关闭；指纹凭据与保险箱密码按服务器地址分开保存（换服务器先密码登录、换回原服务器指纹可用）；视频镜像翻转生效（PlayerView 改 texture_view）；媒体文件夹模式选集只取当前文件夹、切类型回根目录；竖屏选集可滚动、打开对齐到播放中集并加粗显示。:app:testDebugUnitTest 15/15、assembleDebug 成功。
- 2026-11-27 跨设备播放进度（M21 / BL-013）：新增 `server/playback.go` 与 `playback_test.go`（按账号隔离、`DataDir/playback.json` 原子写、`GET/POST /api/playback` 与 `GET /api/playback/recent`，viewer 可读写自己的进度）；Web 播放器读取远端/本地较新位置并每 10s 回写；Android `LinNasClient.getPlayback/putPlayback` + `PlayerActivity` 启动恢复与每 15s/暂停/结束同步。`go test/vet` 与 `node --check` 通过，Android `:app:testDebugUnitTest` / `:app:assembleDebug` 通过。
- 2026-11-27 发布工程（M20 / BL-002、BL-003）：`android/app/build.gradle.kts` 增加 `signingConfigs.release`（环境变量或 `android/keystore.properties`）与 R8 `isMinifyEnabled`，新增 `android/app/proguard-rules.pro`；未配置密钥时回退 debug 签名仅供本地验证。新增 `.github/workflows/release.yml`：`v*` 标签触发双端构建 + `.sha256` + GitHub Release（密钥走 Secrets）。本地 `:app:assembleRelease` 通过，release APK 4.85 MB；`:app:testDebugUnitTest` 通过。
- 2026-11-27 服务端 **1.8.4**（M19）：修开机自启仍拉起旧版 1.7.0——`server/autostart.go` 拆出 `autostartScript` / `autostartExeFromScript` / `autostartTargetAt` / `autostartSetAt` / `samePath` / `autostartStaleTarget` / `autostartHeal`，`main.go` 启动时自愈脚本（旧路径→当前 exe 并记日志），`GET /api/autostart` 新增 `target` / `stale`；端口被旧实例占用时托盘弹窗提示；新增 `server/autostart_test.go`；本机已把旧 `linNas.exe` 退役，52700 由 `linNas-server.exe` 提供服务。
- 2026-11-27 服务端 **1.8.3** + Android **1.8.11**（M13.11）：添加存储库支持**路径自动创建**——`POST /api/libs` 对不存在的绝对路径 `os.MkdirAll` 自动建目录；新增 `POST /api/fs/mkdir`（`fsops.go`，按绝对路径建目录、补建父目录、拒绝盘符根，仅管理员）与 `fsops_mkdir_test.go`；Android 侧「手动输入路径」改为先创建再进入、新增**「新建文件夹」**入口（`LinNasClient.fsMkdir`）；`go test/vet` 全绿、Android 单测 15/15、出包 `release/linNas-1.8.11-debug.apk`。
- 2026-11-27 服务端 **1.8.2**：播放器与登录体验——音乐三态播放模式（列表/单曲循环/随机，记忆）、睡眠定时剩余时间显示与自定义 1–600 分钟、侧栏存储库行高与选中竖条修复（`.lib-list` flex 压缩根因）、登录会话持久化（`sessions.json`，重启免登录、停机不计闲置超时）；`go test/vet` 与 `node --check` 全绿，已重建部署 52700。
- 2026-11-27 服务端 **1.8.1**：界面与体验改进（用户 8 项反馈）——磁盘健康容量进度条 + 按卷容量（`totalGB`/`freeGB`，修复多分区显示整盘容量）、双击 exe 静默启动（隐藏控制台 + 自动开浏览器 + 托盘，`LINNAS_QUIET=1`，日志写 `linnas.log`）、图片上一张/下一张（同组图片播放列表 + 方向键）、音乐音量记忆与播放器增强（均衡器/睡眠/静音/收藏/扩展名徽标）、服务器自定义名称（`serverName` → 标题/品牌/扫码）、移除总览「规划中」占位卡、侧栏选中态竖条；`go build/vet/test` 全绿，已重建部署 52700。
- 2026-11-27 服务端 **1.8.0**：M16（内容搜索 + 自动整理）全部交付后版本升级（`server/config.go` `const Version` 1.7.0 → 1.8.0），同步 README / PLAN / RELEASING / CHANGELOG 等；重新构建并部署到本机 52700。
- 2026-11-27 自动整理（批次 4）：新增 `server/organize.go` 与 `server/organize_test.go`；规则 CRUD（`organize.json`，admin only）、`POST /api/organize/preview` 只读预览、`/run` 单飞执行（`os.Rename`、同名加序号、跨库/只读/保险箱/回收站/排除目录跳过、单次上限 5000、`journal-<jobID>.jsonl`、完成后调 `Index.UpdatePaths` 增量刷新）、`/undo` 按 journal 倒序回搬、`/jobs` 最近 50 条、`/cancel`；撤销后整理作业不再提供撤销。UI 存储空间页新增「自动整理」页签（规则列表 / 编辑对话框 / 预览前 50 条 / 执行确认 / 进度 / 作业历史与撤销）。真实环境端到端验收通过；`go build/vet/test` 全绿。已构建并部署到本机 52700（已提交）。
- 2026-11-26 内容搜索（批次 3）：新增 `server/content.go`（`content` / `content_meta` 表、后台抽取并发 2、单文件 ≤2MB、文本截断 1MB、UTF-8 优先 / GB18030 兜底；txt/md/log/csv/json/xml/html/htm/srt/ass/vtt 直读 + docx/xlsx/pptx 抽取，PDF 未索引）与 `GET /api/search/content`、`GET /api/content/status`；索引扫描 / 对账完成后异步增量入队；UI 搜索范围切换（文件名 / 内容 / 全部）、片段高亮与索引状态提示；磁盘健康默认只显示承载已添加存储库的磁盘（`lib=all` 才返回全部）。新增 `content_test.go`，扩充 `disk_health_test.go`；`go build/vet/test` 全绿。已构建并部署到本机 52700（已提交）。
- 2026-11-26 存储空间分析增强（批次 2）：新增**校验巡检** `server/patrol.go`（`file_sig` 表、quick/deep 签名、单飞可取消、只报告不修改、`patrol.json` 保留 20 条）与 `POST/GET /api/storage/patrol/{start,status,cancel,last}`；`GET /api/disks/health` 支持 `?lib=` 按存储库过滤磁盘（`driveOfPath` / `filterDisksByDrive`，库不存在 404）。UI 存储空间页新增「巡检」页签（快速/深度、进度与取消、内容已变化 / 文件缺失 / 读取失败三组、上次巡检摘要）与磁盘健康「存储库」下拉。新增 `patrol_test.go`，扩充 `disk_health_test.go`；`go build/vet/test` 全绿，Playwright 实测巡检 125/125 与缺失项、下拉切库后仅显示 E:。已构建并部署到本机 52700。
- 2026-11-26 存储空间分析增强（批次 1）：`GET /api/storage/trend` 增长趋势与总览 sparkline、`GET /api/disks/health` 磁盘健康（5 分钟缓存）、`storage_snapshots` 表与 `Index.Snapshot()`、空间告警阈值 `spaceWarnPercent`/`spaceCriticalPercent` 可配置、告警存储与已读（`GET /api/alerts`、`POST /api/alerts/ack`）；新增 `server/{trend,disk_health,alerts}.go` 与三个单测文件；`go build/vet/test` 全绿，Playwright 实测趋势图 3 张、磁盘健康 7 行、告警「全部已读」。已构建并部署到本机 52700。
- 2026-11-26 M10 真机基本回归通过（单设备，未发现阻断问题）。
- 2026-11-26 Web UI 总览页与侧栏分组重构（已提交 `b70a9f8` / `3beb836`）：主区改全宽消除宽屏左侧死区，侧栏拆为总览 / 媒体 / 存储 / 设备与网络 / 分享与管理；新增总览仪表盘（六项 KPI + 容量 / 内容 / 快捷入口 / 近期活动 / 规划中五张卡片）；后端新增 `server/overview.go` 与 `GET /api/overview`（子查询失败只降级字段），新增 `server/overview_test.go`。`go build/vet/test`、`node --check` 通过；Playwright 1586px 与 800px 两档验证通过；已重新构建并部署到本机 52700。
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
