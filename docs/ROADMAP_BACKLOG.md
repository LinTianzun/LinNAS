# LinNAS 路线图与任务清单（ROADMAP BACKLOG）

> 更新：2026-11-26 ｜ 基线：Windows 服务端 **1.7.0** / Android **1.8.2**（versionCode 11）
> 本文件把「还能做什么」整理成可执行任务：优先级、最小可交付、验收标准、涉及文件、依赖与建议排期。
> 方向性构想见 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md)；已完成历史见 [PLAN.md](../PLAN.md) 与 [TASK_HISTORY.md](TASK_HISTORY.md)；当前状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。

## 0. 使用说明

- **优先级**：**P0** 发布门槛 ｜ **P1** 高价值功能 ｜ **P2** 质量 / 体验 ｜ **P3** 可选
- **状态**：待办 / 进行中 / 已完成 / 暂缓（本文件初版全部为「待办」）
- **工作量**（单人粗估）：`S` ≤ 1 天 ｜ `M` 2–4 天 ｜ `L` 1–2 周 ｜ `XL` > 2 周
- **完成定义（DoD）**：代码 + 对应测试 + 文档（README / PLAN / CHANGELOG 按需）+ 验收标准逐条通过；完成后在 [TASK_HISTORY.md](TASK_HISTORY.md) 记一条，并把本项状态改为「已完成」。

## 1. 当前基线

| 项 | 现状 |
|---|---|
| 服务端 | 1.7.0（`server/config.go` 的 `Version`），单 exe、内嵌 Web UI、纯 Go SQLite |
| Android | 1.8.2 / versionCode 11（`android/app/build.gradle.kts`），debug 签名 |
| 测试 | Go 15 个 `*_test.go`；Android 3 个单测 + 3 个仪器测试 |
| CI | `.github/workflows/ci.yml`：`go-windows` / `go-race` / `android-emulator` |
| 已知缺口 | 无 release 签名；完整多机型 / 系统版本矩阵未覆盖；`linnas-data` 备份不含媒体 / 保险箱文件 |

## 2. Backlog 总览

| ID | 优先级 | 事项 | 工作量 | 状态 |
|---|---|---|---|---|
| BL-001 | P0 | Android 真机端到端验收（M10） | M | 已完成（单机基本回归） |
| BL-002 | P0 | release 签名与构建加固 | M | 待办 |
| BL-003 | P0 | CI 发布自动化（tag → Release） | S–M | 待办 |
| BL-010 | P1 | 对话空间 MVP（个人设备收件箱） | L–XL | 待办 |
| BL-011 | P1 | ZIP 预览与安全解压 | M–L | 待办 |
| BL-012 | P1 | 上传收集箱 | M | 待办 |
| BL-013 | P1 | 跨设备播放进度 | S | 待办 |
| BL-014 | P1 | 内容搜索与自动整理 | L | 已完成（批次 3、批次 4 均交付） |
| BL-015 | P1 | 存储空间分析增强 | M | 待办 |
| BL-016 | P1 | 自动化异地备份 | M–L | 待办 |
| BL-017 | P1 | 分享能力增强 | S–M | 待办 |
| BL-018 | P2 | 通知与首次启动向导 | M | 待办 |
| BL-020 | P2 | 测试补齐 | M | 待办 |
| BL-021 | P2 | 可观测性与日志 | M | 待办 |
| BL-022 | P2 | 性能与容量 | M | 待办 |
| BL-023 | P2 | 安全加固 | M | 待办 |
| BL-024 | P2 | 国际化与无障碍 | M | 待办 |
| BL-025 | P2 | 发布工程（版本 / CHANGELOG 自动化） | S | 待办 |

## 3. P0 — 发布门槛

### BL-001 Android 真机端到端验收（M10）

- **目标**：用真实设备 + 真实服务端证明可发布。
- **最小可交付**：按 [ANDROID_TESTING.md](../ANDROID_TESTING.md) 在 Android 7（API 24）/ 14（API 34）+ 小屏或低内存设备各一台执行并留记录。
- **验收标准**：
  - 离线下载：大文件暂停 / 恢复、切网恢复、服务端返回 200 时不追加、取消后临时文件清理、失败重试。
  - 播放器：MP4 与需转码格式、拖动 / 续播、字幕、播放列表连播、耳机拔出 / 音频焦点、画中画、错误重试。
  - 主题与无障碍：6 套主题对比度、切换后导航页保持、字体 200%、TalkBack 焦点与描述。
  - 真实服务端：配对登录、上传 / 下载、只读账号限制、离线缓存播放、断点续传。
- **涉及**：`android/`（验证为主）、`ANDROID_TESTING.md`、`docs/DEVELOPMENT_STATUS.md`。
- **依赖**：无。
- **产出**：验收记录（设备型号 / 系统 / 服务端版本 / 结果 / 日志）。
- **进展（2026-11-26）**：用户在 realme GT7 Pro（Android 16）完成基本回归，未发现阻断问题（单设备，服务端 1.7.0）；完整 Android 7 / 14 多机型矩阵与只读账号场景列为可选补测。

### BL-002 release 签名与构建加固

- **现状**：`android/app/build.gradle.kts` 仅有 debug 签名，`isMinifyEnabled = false`。
- **最小可交付**：`signingConfigs.release`（keystore 走环境变量 / CI secret）、`buildTypes.release` 开 `isMinifyEnabled` + ProGuard/R8 规则、产出 `assembleRelease` / `bundleRelease`。
- **验收标准**：release 包可安装登录；混淆后播放器 / 反射 / 序列化无崩溃；构建可重复；密钥不入库。
- **涉及**：`android/app/build.gradle.kts`、`android/app/proguard-rules.pro`（新建）、`.gitignore`、CI。
- **依赖**：可与 BL-001 并行。

### BL-003 CI 发布自动化

- **最小可交付**：tag（如 `v*`）触发 workflow：`go test` / `vet` + Android 单测 / 构建 + 生成带版本号产物与 `.sha256` + 创建 GitHub Release 并附产物。
- **验收标准**：打 tag 后 Release 自动出现两个组件产物与校验和；失败不产出半成品。
- **涉及**：`.github/workflows/`、`scripts/release.ps1`。
- **依赖**：BL-002。

## 4. P1 — 高价值功能

### BL-010 对话空间 MVP（个人设备收件箱）

- **目标**：同一账号的 Web 与 Android 互发文字 / 文件，离线不丢。
- **最小可交付**：
  - 服务端：会话空间表（`spaces` / `messages` / `attachments` 或等价）、`GET/POST /api/chat/messages`（游标分页）、附件上传复用分块上传、附件与消息关联、权限校验在服务端。
  - Web：对话视图 + 发送文字 / 文件 + 历史分页 + 未读 / 失败态。
  - Android：对话页 + 发送文字 / 文件 + 前后台同步。
  - 同步：先轮询（简单可靠）或 SSE；断线后按游标补齐。
- **验收标准**：Web ↔ Android 同账号互发文字与文件；历史一致；大文件中断后续传；对方离线重连后能收到；越权访问被拒。
- **非目标**：群组、端到端加密、公开链接。
- **涉及**：`server/chat*.go`、`server/api.go`、Web UI、`android` 新页面、`PLAN.md`。
- **依赖**：无（复用现有鉴权 / 分块上传）。
- **风险**：附件配额 / 保留 / 清理策略需先定，避免无限增长。

### BL-011 ZIP 预览与安全解压

- **最小可交付**：`GET /api/archive/list?vpath=` 只读列目录；`POST /api/archive/extract` 选择性解压到指定目录；拒绝 `../`、绝对路径、越界符号链接；限制条目数 / 解压总大小 / 压缩比；同名策略；大任务后台 + 进度 / 取消。
- **验收标准**：恶意 zip（穿越 / 炸弹 / 符号链接）被拒；正常 zip 可选文件解压正确；超大任务不阻塞服务。
- **涉及**：`server/zip.go` 或新 `archive.go`、Web UI、Android 文件菜单。
- **依赖**：无。
- **风险**：压缩炸弹阈值需可配置；解压目标必须在库内。

### BL-012 上传收集箱

- **最小可交付**：独立上传页 / 令牌（仅上传、不可浏览下载）、有效期 / 单文件大小 / 类型限制、写入隔离目录、管理员审核后移动到正式库。
- **验收标准**：匿名 / 授权访客只能上传；过期令牌失效；超限 / 超类型被拒；审核移动后进入正常库。
- **涉及**：`server/` 新 handler + 页面、`docs/`。
- **依赖**：建议与 BL-023（限速 / 防滥用）并行。

### BL-013 跨设备播放进度

- **最小可交付**：服务端 `playback(user_id, vpath, position, duration, updated_at)` + `GET/POST /api/playback`；Web 播放器读写；Android `PlaybackStore` 与服务端同步（离线先用本地，联网合并）。
- **验收标准**：A 端看到一半，B 端打开从该位置继续；按账号隔离；离线不丢本地进度。
- **涉及**：`server/`、Web player、`PlaybackStore.kt` / `PlayerActivity.kt`。
- **依赖**：无。
- **说明**：工作量 `S`，建议作为第一个「小而快」的落地项。

### BL-014 内容搜索与自动整理

- **现状**：已全部交付并部署（2026-11-26 / 2026-11-27）。内容搜索见 `server/content.go`（txt/md/log/csv/json/xml/html/htm/srt/ass/vtt 直读 + docx/xlsx/pptx 抽取，PDF 未索引）；自动整理见 `server/organize.go`（规则 CRUD / 只读预览 / 单飞执行 journal / 撤销 / 作业记录，全部 admin only、不删除文件）。
- **最小可交付**：~~先支持 `txt / md / pdf / docx` 等少量类型的正文提取入索引~~（已支持文本类 + Office，PDF 留 v2）；~~自动整理：规则（类型 / 扩展名 / 来源 / 日期）先预览 + 执行记录 + 撤销~~（已交付）。
- **验收标准**：正文关键词可搜到；排除目录不索引；规则执行可预览 / 撤销，不自动删除覆盖。（已满足）
- **涉及**：`server/content.go`、`server/organize.go`、`server/index.go`、`server/ui/dist/`。
- **依赖**：BL-022（性能）。
- **风险**：文档解析依赖体积 / 许可证；FTS5 可用性需先验证。

### BL-015 存储空间分析增强

- **现状**：概览 / 大文件 Top N / 重复候选已随 1.7.0 上线。
- **最小可交付**：容量趋势（定期快照）、校验巡检（抽样 SHA-256 变化告警）、磁盘健康 / 空间阈值告警（按 Windows 能力）。
- **验收标准**：趋势图可见；巡检异常提示；阈值告警可配置；不影响主服务。
- **涉及**：`server/analysis.go`、`housekeeping.go`、Web UI。
- **依赖**：无。

### BL-016 自动化异地备份

- **最小可交付**：在 `scripts/backup-data.ps1` 基础上做定时 / 增量 / 加密（如 age/gpg 或应用层 AES）、保留策略、失败告警、恢复演练脚本。
- **验收标准**：定时任务成功 / 失败可观测；备份可校验并成功恢复；保留「成功 ≠ 可恢复」的演练记录。
- **涉及**：`scripts/`、`server` 配置、`docs/DATA_BACKUP.md`。
- **依赖**：BL-021。

### BL-017 分享能力增强

- **最小可交付**：下载次数上限、单次链接、批量撤销、访问统计；沿用现有密码 / 有效期 / 审计。
- **验收标准**：达到次数后失效；批量撤销即时生效；统计可查。
- **涉及**：`server/security.go`（shareStore）、Web UI。
- **依赖**：无。
- **说明**：工作量 `S–M`，适合与 BL-013 同批做。

## 5. P2 — 工程质量

### BL-020 测试补齐

- **现状**：Go 15 个测试文件；Android 3 个单测 + 3 个仪器测试。
- **最小可交付**（按风险排序）：
  - Go：`authorize` 角色矩阵（admin/user/viewer × 读写接口）、上传异常 / 回滚 / 同名冲突、WebDAV 边界（Range、只读、越权）、Zip 路径穿越、索引对账并发。
  - Android：传输中心暂停 / 续传 / 取消、生物识别降级路径、版本检查 404 / 降级、筛选组合。
- **验收标准**：新增用例在 CI 稳定通过；关键路径无回归。
- **涉及**：`server/*_test.go`、`android/app/src/test|androidTest`。

### BL-021 可观测性与日志

- **最小可交付**：请求 ID、结构化日志（级别 / 耗时 / 状态）、健康检查细分（磁盘 / ffmpeg / 索引 / 队列）、审计日志轮转（现上限 5000）。
- **验收标准**：一次请求可串起来；异常可定位；日志不泄敏。
- **涉及**：`server/api.go`、`main.go`、`security.go`。

### BL-022 性能与容量

- **最小可交付**：大库索引 / 对账耗时基线、搜索分页与查询计划、缩略图 / 转码缓存淘汰验证、DB 体积与 WAL 检查点。
- **验收标准**：24.5 万文件场景有基线数据；无长尾卡顿；缓存上限生效。
- **涉及**：`server/index.go`、`media.go`、`transcode.go`。

### BL-023 安全加固

- **最小可交付**：会话 token 存储与过期策略复核、分享 / 上传限速、登录锁定状态持久化（如需要）、依赖升级（govulncheck 等）、Web UI CSP 评估。
- **验收标准**：无已知高危依赖；暴力 / 滥用有速率限制；锁定重启后仍生效（如需要）。
- **涉及**：`server/security.go`、`go.mod`、Web UI。

### BL-024 国际化与无障碍

- **最小可交付**：Android `strings.xml` 抽取、Web 文案集中；TalkBack / 键盘焦点主流程；对比度检查纳入 CI（已有 `ThemeContrastTest` 基础）。
- **验收标准**：主流程可读屏操作；文案可替换；对比度达标。
- **涉及**：`android/app/src/main/res/values/`、Web UI、CI。

### BL-025 发布工程

- **最小可交付**：版本号自动递增脚本（服务端 `config.go` + Android `build.gradle.kts` 同步提示）、由 git log 生成 CHANGELOG 草稿、产物校验和。
- **验收标准**：一条命令产出待发布版本与变更草稿；不误改版本。
- **涉及**：`scripts/`、`CHANGELOG.md`。

## 6. 建议排期（粗略，非承诺）

| 阶段 | 内容 | 建议周期 |
|---|---|---|
| Sprint 1 | BL-001 真机验收、BL-002 release 签名、BL-003 CI 发布 | 1 周 |
| Sprint 2 | BL-013 跨设备进度、BL-017 分享增强、BL-020 部分测试 | 1 周 |
| Sprint 3–4 | BL-010 对话空间 MVP | 2 周 |
| Sprint 5 | BL-011 ZIP 预览 / 解压、BL-015 存储分析增强 | 1–2 周 |
| Sprint 6 | BL-012 上传收集箱、BL-016 异地备份、BL-021 / BL-023 | 1–2 周 |
| 持续 | BL-014 内容搜索、BL-018 通知 / 向导、BL-022 / BL-024 / BL-025 | 按需 |

## 7. 暂不做 / 明确排除

- 端到端加密、群组聊天 / 完整 IM、公开社交能力（见 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) 的非目标）。
- iOS 客户端、第三方云盘双向同步。
- 未经审查的外部命令行工具依赖（解压 / 转码等优先标准库或已审计库）。

## 8. 依赖与风险汇总

- 压缩炸弹 / 路径穿越阈值需可配置，默认保守。
- 文档正文索引依赖解析库体积与许可证；FTS5 可用性需先验证。
- 对话空间需先定附件配额 / 保留 / 清理策略，避免无限增长。
- 发布签名密钥必须走 CI secret，不能入库。
- 所有破坏性操作（重复文件清理、解压覆盖）必须人工确认 + 可回滚（回收站）。

## 9. 变更记录

- 2026-11-26：初版，基于 v1.7.0 / v1.8.0 基线与 [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) 整理。
