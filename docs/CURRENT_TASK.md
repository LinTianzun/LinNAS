# 当前任务（CURRENT TASK）

> 这是一个「活」文件：记录此刻正在做 / 紧接着要做的事。稳定后的结论应迁移到 [TASK_HISTORY.md](TASK_HISTORY.md)，整体状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。更新时间：2026-11-26。

## 进行中

- （当前无进行中的代码或文档任务；M10 真机基本回归已通过。）

## 下一步（待办）

1. 完成 release 签名与 CI 发布自动化（见 [ROADMAP_BACKLOG.md](ROADMAP_BACKLOG.md) BL-002 / BL-003）。
2. （可选）在 Android 7 / 14 双版本补测关键路径；M10 单机基本回归已通过。
3. 按 [RELEASING.md](../RELEASING.md) 流程发布（若递增版本号，同步更新 `server/config.go` / `android/app/build.gradle.kts` 与 README、PLAN、CHANGELOG）。

## 阻塞 / 风险

- 单机基本回归已通过；完整多机型 / 系统版本矩阵未覆盖。
- APK 为 debug 签名，不是生产签名包。

## 最近完成

- 2026-11-26 M10 真机基本回归通过（单设备，未发现阻断问题）。
- 2026-11-26 服务端 1.7.0：Web UI 总览仪表盘（六项 KPI + 容量 / 内容 / 快捷入口 / 近期活动 / 规划中五卡）与侧栏分组重构；主区全宽消除宽屏左侧死区；新增 `GET /api/overview` 与单测；已构建并部署到本机 52700（工作区未提交）。
- 2026-11-26 服务端 1.7.0 修补：媒体库自动分页加载、回收站网格/列表缩略图、媒体库隐藏状态的睁眼/闭眼图标与徽标；已构建并部署到本机 52700（提交 `d9590bf`）。
- 文档补全与对齐：commit `f72a517`、`faefea0`，已推送 GitHub `origin/main`。
- M13：Android v1.8.0 体验升级（传输中心 / 多选批量 / 生物识别 / 版本检查 / 播放器重做 / 媒体分组 / 视觉打磨）。
- M12：Android v1.7.0 对齐服务端 v1.7.0。
- M11：服务端 v1.7.0（索引增量 + 对账、安全加固、多媒体增强、存储分析、媒体库排除）。