# 任务历史（TASK HISTORY）

> 记录已完成的任务 / 里程碑与近期提交。详细逐项见 [PLAN.md](../PLAN.md)；当前状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)，正在做的事见 [CURRENT_TASK.md](CURRENT_TASK.md)。

## 里程碑演进

| 里程碑 | 日期 | 组件 / 版本 | 主要内容 |
|---|---|---|---|
| M17 | 2026-11-27 | Windows 服务端 1.8.1 | 界面与体验改进（用户 8 项）：磁盘健康容量进度条 + 按卷容量、双击 exe 静默启动、图片上一张/下一张、音乐音量记忆与播放器增强、服务器自定义名称、移除总览「规划中」、侧栏选中态竖条 |
| M16.3 | 2026-11-27 | 服务端 **1.8.0** | 版本升级：M16（内容搜索 + 自动整理）全部交付后，`server/config.go` 的 `const Version` 由 1.7.0 升至 1.8.0，同步 README / PLAN / RELEASING / CHANGELOG / ARCHITECTURE / DEVELOPMENT_STATUS / TASK_HISTORY；重新构建并部署本机 52700 |
| M16.2 | 2026-11-27 | 服务端 1.7.0 | 自动整理规则：`server/organize.go`（规则 CRUD `organize.json`、`POST /api/organize/preview` 只读预览、`/run` 单飞执行 `os.Rename` + 同名加序号 + 跨库/只读/保险箱/回收站/排除跳过 + 上限 5000 + `journal-<jobID>.jsonl` + `Index.UpdatePaths`、`/undo` 倒序回搬、`/jobs` 最近 50 条、`/cancel`）；全部 admin only，不删除文件；UI「自动整理」页签（规则列表 / 编辑 / 预览前 50 条 / 执行确认 / 作业历史与撤销）；真实环境端到端验收通过 |
| M16.1 | 2026-11-26 | 服务端 1.7.0 | 正文索引与内容搜索：`server/content.go`（`content` / `content_meta` 表、后台抽取并发 2、单文件 ≤2MB、文本截断 1MB、UTF-8 优先 / GB18030 兜底、txt/md/log/csv/json/xml/html/htm/srt/ass/vtt 直读 + docx/xlsx/pptx 抽取、PDF 未索引）、`GET /api/search/content` / `GET /api/content/status`；索引扫描 / 对账后异步增量入队；UI 搜索范围切换与片段高亮；磁盘健康默认只显示承载已添加存储库的磁盘 |
| M15.2 | 2026-11-26 | 服务端 1.7.0 | 校验巡检：`server/patrol.go`（quick 抽样 / deep 全文件 SHA-256、单飞可取消、只报告不修改）、`file_sig` 表、`patrol.json` 保留 20 条、`POST/GET /api/storage/patrol/{start,status,cancel,last}`；`GET /api/disks/health?lib=` 按存储库过滤磁盘；UI 巡检页签 + 磁盘健康存储库下拉 |
| M15.1 | 2026-11-26 | 服务端 1.7.0 | 存储空间分析增强（批次 1）：`GET /api/storage/trend` 增长趋势、`GET /api/disks/health` 磁盘健康、`storage_snapshots` 与 `Index.Snapshot()`、空间告警阈值可配置 + `GET /api/alerts` / `POST /api/alerts/ack`；新增 `server/{trend,disk_health,alerts}.go` 与单测 |
| M14 | 2026-11-26 | 服务端 1.7.0（Web UI） | 总览仪表盘（`GET /api/overview`：容量 / 内容 / 系统状态 / 近期活动 / 快捷入口 / 规划中）；侧栏按总览 / 媒体 / 存储 / 设备与网络 / 分享与管理分组；主区全宽消除宽屏左侧死区 |
| M13.7 | 2026-11-27 | Android 1.8.7 (vc16) | 应用内更新装成旧包修复（更新包按版本号命名 + 装前 `getPackageArchiveInfo` 校验包名/versionCode + 安装后清理旧包 + 授权返回自动续装）；退出登录后指纹登录可用（Keystore 加密保存账号密码、退登只清 token、指纹通过后旧会话失效则密码换新 token）；新增应用内图片浏览器 `ImageActivity`（缩放/双击/保存/分享/详情，媒体页·文件页·离线点图均内置）；添加存储库改单弹层逐级浏览（面包屑/返回上级/就选这个目录，seq 丢弃过期回调消除闪烁） |
| M13.6 | 2026-11-27 | Android 1.8.6 (vc15) | 指纹入口常显（不再依赖已存会话）+ 进入前 uthMe() 校验修复「验证通过却进不去」；切换服务器改 Sheet 并支持局域网扫描发现；保险箱去勾选、密码一次后自动指纹（5 分钟免密）；修复 g_sheet_tile layer-list alpha 失效导致的图标同色隐形（统一改动态圆底 + tint 双保险），设备图标改实心镂空、按 Android/桌面/命令行/未知区分 |
| M13.5 | 2026-11-26 | Android 1.8.5 (vc14) | 播放器「更多」3×2 图标网格、横屏右侧滑出并与选集互斥、修复进度条被手势条/按钮行遮挡、倍速/字幕/错误面板统一玻璃样式（AlertDialog 归零）；青瓷与云白换序 + 一次性迁移；设备管理按平台区分图标；设置页 10 个新图标去重；生物识别总开关；保险箱指纹解锁 + 5 分钟免密（新建后直接进入）；登录去 Autofill 黄高亮；存储库容量显示；Sheet 分段/勾选选中态重做 |
| M13.4 | 2026-11-26 | Android 1.8.4 (vc13) | 应用内更新修复（REQUEST_INSTALL_PACKAGES + 未知来源授权引导 + 一次性重试 + 安装包校验，Android 16 可拉起安装器）、浏览器更新入口与直链复制；登录页美化 + 会话加密持久化与旧版迁移（重启/更新不再重复登录、指纹一键/自动登录）；播放器「更多」面板玻璃渐变更透明；主题「月白」→「青瓷」；回收站/保险箱/分享/用户/会话/WoL/存储库/操作日志/维护 26 处弹窗统一 Sheet |
| M13.3 | 2026-11-26 | Android 1.8.3 (vc12) | 浅色主题「月白」（原纯黑）；播放页 B 站式重做（横屏右下倍速/字幕/选集、选集面板半透明可关闭、三点菜单三段式、竖屏简介可收起）；`Sheet` 主题化底部弹窗统一重做二级弹窗；传输页删除已下载/已缓存（含 SAF 副本）；传输效率（共享 `TransferHttp`、256KB 缓冲、写盘缓冲） |
| M13.2 | 2026-11-26 | Android 1.8.2 (vc11) | `SpeedMeter` 实时速度（上传/缓存/下载）、修复进度条闪烁、缓存自动复制到下载目录、应用内下载更新、横屏右侧选集带缩略图、播放器图标网格更多菜单、长按 2x 加速、竖屏滑动进度；二级页 `ScreenScaffold` 统一视觉重做 |
| M13.1 | 2026-11-26 | Android 1.8.1 (vc10) | 媒体库双模式（全部/文件夹）、横屏播放与退出全屏、主题色块双色预览、面包屑主题色与省略、⋮ 改底部动作面板、缓存完成隐藏进度条并可打开/另存、添加存储库解析磁盘 JSON、相册备份逐文件指纹去重 |
| M13 | 2026-11-26 | Android 1.8.0 (vc9) | 统一传输中心（下载 / ZIP 前台服务、暂停续传 / 重试 / 取消 / SAF 目录）、文件多选批量操作、生物识别、版本检查、收藏 / 最近 / 搜索历史与筛选、播放器 B 站式重做、媒体库按文件夹分组、设置页重排、`DESIGN.md` 视觉规范 |
| M12 | 2026-11-26 | Android 1.7.0 (vc8) | 对齐服务端 v1.7.0：活跃会话、用户管理、维护、网络唤醒、存储库管理、Zip 下载、保险箱增强、429 倒计时 |
| M11 | 2026-11-25 | 服务端 1.7.0 | 索引增量更新 + 定时对账、登录防爆破、活跃会话、回收站 / 转码缓存自动清理、Zip 打包下载、WoL、在线音乐播放器；存储空间分析、媒体库排除 |
| M10 | 2026-11-26 | Android | 离线下载协议加固、播放器音频焦点 / 恢复、主题无障碍与对比度检查、下载 / 主题单测、CI 模拟器；真机基本回归通过（单设备，未发现阻断问题） |
| M9 | 2026-11-25 | 服务端 1.6.0 | WebDAV、上传并发加固、索引事务化、备份 / 恢复脚本、GitHub Actions |
| M8 | 2026-11-25 | 服务端 1.5.0 / Android 1.4.0、1.5.0 | Tailscale 远程访问、托盘 / 自启；Android 多用户 / 分享 / 回收站 / 离线缓存 / 画中画、播放器与设置重做、6 套主题 |
| M7 | 2026-11-25 | 服务端 1.4.0 | 多用户与角色、空闲自动锁定、分享链接、操作日志、登录会话 |
| M6 | 2026-11-24 | 服务端 1.3.0 | 文件 / 媒体详情（ffprobe）、字幕、转码缓存、可编辑路径、播放列表、媒体分组 |
| M5 / M5.1 | 2026-11-24 | 服务端 1.1.0 / 1.1.1、Android 1.1.x | mDNS + UDP 52701 + 二维码配对；Web UI「手机连接」入口 |
| M4 – M4.5 | 2026-11-24 | Android 0.5.0 – 1.0.0 | 相册自动备份、界面重构、局域网发现、主题 / 排序 / 磁盘、功能对齐、崩溃加固、播放体验包 |
| M1 – M3.1 | 2026-11-24 | 服务端 0.2.0 – 1.2.0、Android 0.2.0 – 0.4.0 | 核心：文件 API / Range 206、分块上传、媒体库 SQLite 索引、缩略图、ffmpeg 转码、效率与传输增强（筛选 / 快捷键 / 跨库拖拽） |

（M2 / M3 等为早期 Android 构建阶段，逐项记录见 [PLAN.md](../PLAN.md)。）

## 近期提交（git log）
- 工作区（待提交）：服务端 **1.8.1** —— M17 界面与体验改进（`server/disk_health.go` 卷容量 `totalGB`/`freeGB`、`server/main.go` 静默启动 + `linnas.log`、`server/tray_windows.go` `launchedFromTerminal`、`server/config.go` `ServerName`/`DisplayName()` + 版本 1.8.1、`server/settings.go` `serverName`；UI 磁盘健康容量条 / 图片翻页 / 音乐音量记忆与增强 / 服务器名称 / 移除规划中卡 / 侧栏选中竖条）。## 近期提交（git log）
- 工作区（待提交）：服务端 **1.8.0** —— 版本升级（`server/config.go` `const Version` 1.7.0 → 1.8.0；同步 README / PLAN / RELEASING / CHANGELOG / ARCHITECTURE / DEVELOPMENT_STATUS / CURRENT_TASK / TASK_HISTORY）。
- 工作区（待提交）：服务端 1.7.0 —— M16 批次 4（自动整理 `server/organize.go` + `organize_test.go`、`/api/organize/{rules,preview,run,undo,status,cancel,jobs}`、`organize.json` / `jobs.json` / `journal-<jobID>.jsonl`、UI「自动整理」页签）。
- 工作区（待提交）：服务端 1.7.0 —— M16 批次 3（正文索引 `server/content.go` + `content` / `content_meta` 表 + `GET /api/search/content` / `GET /api/content/status`；UI 搜索范围切换与片段高亮；磁盘健康默认只显示已添加存储库所在磁盘）。

- 工作区（待提交）：服务端 1.7.0 —— M15 批次 2（校验巡检 `server/patrol.go` + `file_sig` 表 + 4 个 patrol API；`GET /api/disks/health?lib=` 过滤；`ui/dist` 巡检页签与磁盘健康存储库下拉）。
- 工作区（未提交）：服务端 1.7.0 —— M15 批次 1（趋势 / 磁盘健康 / 空间告警；`server/{trend,disk_health,alerts}.go`、`storage_snapshots` 表、设置阈值、`ui/dist` 趋势 tab / sparkline / 磁盘健康卡 / 告警「全部已读」）。
- 工作区（未提交）：服务端 1.7.0 —— Web UI 总览仪表盘与侧栏分组重构（`server/overview.go`、`GET /api/overview`、`overview_test.go`、`ui/dist/{index.html,app.js,style.css}`）；主区全宽、五张总览卡片、规划中两张路线图。
- 工作区（未提交）：服务端 1.7.0 修补 —— 媒体库自动分页加载、回收站缩略图（`GET /api/trash/thumb`）、「不在媒体库展示」睁眼/闭眼图标与即时徽标；补 `trash_thumb_test.go`。
- `12b5c2a` feat(android): v1.8.0 体验升级（传输中心 / 多选批量 / 生物识别 / 版本检查 / 播放器重做 / 收藏筛选 / 媒体分组 / 视觉打磨）
- `03efa0d` feat(web): 媒体库分组折叠留一行 + 音乐播放器优化；补 vault/ui-dir 单测与 e2e 冒烟脚本
- `22bcb1c` docs: PLAN/RELEASING 更新（M12 Android 对齐 v1.7.0）；忽略 release/ 构建产物
- `d3c1166` feat(android): v1.7.0 会话 / 用户 / 维护 / WoL / 存储库管理页与 ZIP 打包下载、保险箱增强
- `61e458f` feat(server): 热点信息归一化 + Web UI 对齐 v1.7.0（会话 / 维护 / WoL / 音乐等入口）
- `082ce2f` feat(server): v1.7.0 索引增量更新、登录防爆破与会话管理、回收站 / 转码缓存清理、Zip 打包下载、WoL 与在线音乐播放器
- `4671933` docs: 新增后续功能方向文档（对话空间等候选构想）
- `54ce0f4` feat(android): v1.5.0 体验改版（播放器重做 / 设置页分区 / 6 套主题 / 保险箱界面）
