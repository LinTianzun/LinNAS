# 当前任务（CURRENT TASK）

> 这是一个「活」文件：记录此刻正在做 / 紧接着要做的事。稳定后的结论应迁移到 [TASK_HISTORY.md](TASK_HISTORY.md)，整体状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。更新时间：2026-11-26。

## 进行中

- M15 批次 2（校验巡检 + 磁盘健康按存储库过滤）已实现、验证并部署到本机 52700；M15 全部完成，下一步进入 M16 批次 3（正文索引与内容搜索）。

## 下一步（待办）

1. 按 [PLAN.md](../PLAN.md) M16 开始**批次 3 正文索引与内容搜索**（txt/md/csv/json/html/srt + docx/xlsx/pptx 抽取，PDF 暂不支持）。
2. 之后推进批次 4（自动整理规则：预览 → 执行 → 记录 → 撤销）。
3. 完成 release 签名与 CI 发布自动化（见 [ROADMAP_BACKLOG.md](ROADMAP_BACKLOG.md) BL-002 / BL-003）。
4. （可选）在 Android 7 / 14 双版本补测关键路径；M10 单机基本回归已通过。
5. 按 [RELEASING.md](../RELEASING.md) 流程发布（若递增版本号，同步更新 `server/config.go` / `android/app/build.gradle.kts` 与 README、PLAN、CHANGELOG）。

## 阻塞 / 风险

- 单机基本回归已通过；完整多机型 / 系统版本矩阵未覆盖。
- APK 为 debug 签名，不是生产签名包。

## 最近完成

- 2026-11-26 服务端 1.7.0：存储空间分析增强（批次 2）——校验巡检 `server/patrol.go`（quick/deep 签名、单飞可取消、只报告不修改、`file_sig` 表、`patrol.json` 历史 20 条）与 `POST/GET /api/storage/patrol/{start,status,cancel,last}`；磁盘健康 `GET /api/disks/health?lib=` 按存储库过滤；UI 新增「巡检」页签与磁盘健康「存储库」下拉；已构建并部署到本机 52700。
- 2026-11-26 服务端 1.7.0：存储空间分析增强（批次 1）——增长趋势 `GET /api/storage/trend`（7/30/90 天 + 总览 sparkline）、磁盘健康 `GET /api/disks/health`、空间告警阈值可配置与 `GET /api/alerts` / `POST /api/alerts/ack`；新增 `server/{trend,disk_health,alerts}.go` 与单测；已构建并部署到本机 52700。
- 2026-11-26 M10 真机基本回归通过（单设备，未发现阻断问题）。
- 2026-11-26 服务端 1.7.0：Web UI 总览仪表盘（六项 KPI + 容量 / 内容 / 快捷入口 / 近期活动 / 规划中五卡）与侧栏分组重构；主区全宽消除宽屏左侧死区；新增 `GET /api/overview` 与单测；已构建并部署到本机 52700（工作区未提交）。
- 2026-11-26 服务端 1.7.0 修补：媒体库自动分页加载、回收站网格/列表缩略图、媒体库隐藏状态的睁眼/闭眼图标与徽标；已构建并部署到本机 52700（提交 `d9590bf`）。
- 文档补全与对齐：commit `f72a517`、`faefea0`，已推送 GitHub `origin/main`。
- M13：Android v1.8.0 体验升级（传输中心 / 多选批量 / 生物识别 / 版本检查 / 播放器重做 / 媒体分组 / 视觉打磨）。
- M12：Android v1.7.0 对齐服务端 v1.7.0。
- M11：服务端 v1.7.0（索引增量 + 对账、安全加固、多媒体增强、存储分析、媒体库排除）。