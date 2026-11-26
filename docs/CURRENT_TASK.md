# 当前任务（CURRENT TASK）

> 这是一个「活」文件：记录此刻正在做 / 紧接着要做的事。稳定后的结论应迁移到 [TASK_HISTORY.md](TASK_HISTORY.md)，整体状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。更新时间：2026-11-27。

## 进行中

- M16 两个批次（正文索引与内容搜索、自动整理规则）均已交付，服务端已升至 **1.8.0** 并部署到本机 52700。
- 2026-11-27 服务端 **1.8.2**（M18 播放器与登录体验）：音乐三态播放模式、睡眠定时剩余时间显示与自定义时间、侧栏存储库行高/选中竖条修复、登录会话持久化（重启免登录）；已测试、构建并部署到本机 52700，待提交推送。
- 2026-11-27 服务端 **1.8.1**（M17 界面与体验改进）：按用户 8 项反馈完成磁盘健康容量进度条与按卷容量、双击 exe 静默启动、图片上一张/下一张、音乐音量记忆与播放器增强、服务器自定义名称、移除总览「规划中」占位卡、侧栏选中态竖条；已测试、构建并部署到本机 52700，已提交推送。

## 下一步（待办）

1. 完成 release 签名与 CI 发布自动化（见 [ROADMAP_BACKLOG.md](ROADMAP_BACKLOG.md) BL-002 / BL-003）。
2. （可选）在 Android 7 / 14 双版本补测关键路径；M10 单机基本回归已通过。
3. 按 [RELEASING.md](../RELEASING.md) 流程发布正式产物（`scripts/release.ps1`，若再递增版本号，同步更新 `server/config.go` / `android/app/build.gradle.kts` 与 README、PLAN、CHANGELOG）。

## 阻塞 / 风险

- 单机基本回归已通过；完整多机型 / 系统版本矩阵未覆盖。
- APK 为 debug 签名，不是生产签名包。

## 最近完成

- 2026-11-27 服务端 **1.8.2（M18）播放器与登录体验**：音乐播放模式改为三态单键（列表 ⥁ / 单曲循环 ⥂ / 随机 ⤨，点击循环切换并记忆，移除右侧重复/随机按钮）；睡眠定时显示 `🌙 mm:ss` 剩余并每秒刷新，菜单支持 15/30/45/60 分钟与自定义 1–600 分钟、关闭定时；侧栏存储库行高 ≥40px、选中/悬停竖条贯穿整行（修复 `.lib-list` 被 flex 压缩导致的裁切与内部滚动条）；登录会话写入 `sessions.json`，服务重启后无需重新登录（停机不计闲置超时，吊销/退出/删用户即时失效）。`go test/vet`、`node --check` 全绿，已重建部署 52700。
- 2026-11-27 服务端 **1.8.1（M17）体验改进**：`GET /api/disks/health` 增加 `totalGB` / `freeGB`（卷容量）与 UI 容量进度条；双击 exe 静默启动（隐藏控制台、自动开浏览器、托盘、`LINNAS_QUIET=1`、日志 `linnas.log`）；图片预览上一张/下一张与方向键；音乐音量记忆 + 均衡器/睡眠/静音/收藏；服务器自定义名称（`serverName`，作用于标题/品牌/扫码）；移除总览「规划中」占位卡；侧栏选中竖条。`go test/vet/build` 全绿，已重建部署 52700。

- 2026-11-27 服务端 **1.8.0 发布**：M16（内容搜索 + 自动整理）全部交付后，把 `server/config.go` 的 `const Version` 从 1.7.0 升到 1.8.0，并同步 README / PLAN / RELEASING / CHANGELOG / ARCHITECTURE / DEVELOPMENT_STATUS / TASK_HISTORY；重新构建 `linNas-server.exe` 并部署到本机 52700（启动信息与 `/api/health` 返回 1.8.0）。
- 2026-11-27 服务端 1.7.0：自动整理（批次 4）——新增 `server/organize.go` 与 `server/organize_test.go`；规则模型 `organizeRule{match{matchPathPrefix,exts,nameContains,kind,minSizeMB,maxSizeMB,olderThanDays}, action{moveTo,groupBy}}` 存 `organize.json`，`GET/POST/PUT/DELETE /api/organize/rules`；`POST /api/organize/preview` 只读预览、`/run` 单飞执行（`os.Rename`、同名加序号、跨库/只读/保险箱/回收站/排除跳过、上限 5000、`journal-<jobID>.jsonl`、完成后 `Index.UpdatePaths`）、`/undo` 倒序回搬、`/jobs` 最近 50 条、`/cancel`；全部接口 admin only，不删除文件。UI 存储空间页新增「自动整理」页签（规则列表 / 编辑对话框 / 预览前 50 条 / 执行确认 / 进度 / 作业历史与撤销）。真实环境端到端验收通过（6 文件预览=执行、索引即时可见、撤销完全回原位）。已构建并部署到本机 52700。
- 2026-11-26 服务端 1.7.0：内容搜索（批次 3）——正文索引 `server/content.go`（`content` / `content_meta` 表、后台抽取并发 2、单文件 ≤2MB、文本截断 1MB、UTF-8 优先 / GB18030 兜底；txt/md/log/csv/json/xml/html/htm/srt/ass/vtt 直读、docx/xlsx/pptx 抽取，PDF 未索引）与 `GET /api/search/content`、`GET /api/content/status`；索引扫描 / 对账完成后异步增量入队；UI 搜索范围切换（文件名 / 内容 / 全部）、片段高亮与索引状态提示；磁盘健康默认只显示承载已添加存储库的磁盘（`lib=all` 才返回全部）。已构建并部署到本机 52700。
- 2026-11-26 服务端 1.7.0：存储空间分析增强（批次 2）——校验巡检 `server/patrol.go`（quick/deep 签名、单飞可取消、只报告不修改、`file_sig` 表、`patrol.json` 历史 20 条）与 `POST/GET /api/storage/patrol/{start,status,cancel,last}`；磁盘健康 `GET /api/disks/health?lib=` 按存储库过滤；UI 新增「巡检」页签与磁盘健康「存储库」下拉；已构建并部署到本机 52700。
- 2026-11-26 服务端 1.7.0：存储空间分析增强（批次 1）——增长趋势 `GET /api/storage/trend`（7/30/90 天 + 总览 sparkline）、磁盘健康 `GET /api/disks/health`、空间告警阈值可配置与 `GET /api/alerts` / `POST /api/alerts/ack`；新增 `server/{trend,disk_health,alerts}.go` 与单测；已构建并部署到本机 52700。
- 2026-11-26 M10 真机基本回归通过（单设备，未发现阻断问题）。
- 2026-11-26 服务端 1.7.0：Web UI 总览仪表盘（六项 KPI + 容量 / 内容 / 快捷入口 / 近期活动 / 规划中五卡）与侧栏分组重构；主区全宽消除宽屏左侧死区；新增 `GET /api/overview` 与单测；已构建并部署到本机 52700（工作区未提交）。
- 2026-11-26 服务端 1.7.0 修补：媒体库自动分页加载、回收站网格/列表缩略图、媒体库隐藏状态的睁眼/闭眼图标与徽标；已构建并部署到本机 52700（提交 `d9590bf`）。
- 文档补全与对齐：commit `f72a517`、`faefea0`，已推送 GitHub `origin/main`。
- M13：Android v1.8.0 体验升级（传输中心 / 多选批量 / 生物识别 / 版本检查 / 播放器重做 / 媒体分组 / 视觉打磨）。
- M12：Android v1.7.0 对齐服务端 v1.7.0。
- M11：服务端 v1.7.0（索引增量 + 对账、安全加固、多媒体增强、存储分析、媒体库排除）。