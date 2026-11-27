# 对话空间（Chat Spaces）设计方案

> 状态：**草案 v0.1（设计启动）** ｜ 日期：2026-11-27
> 对应 backlog：**BL-010 对话空间 MVP（P1，L–XL）**，产品构想见 [FEATURE_ROADMAP.md](../FEATURE_ROADMAP.md)。
> 本文是**设计文档**，不是实现。实现前需用户确认关键决策点（见文末「待确认决策」）。

## 1. 定位与目标

以 NAS 为**消息与文件的私有中转站**，让同一账号的电脑浏览器与 Android 客户端互发文字与文件，离线不丢、重连按游标补齐。

**首期（MVP）只做「个人设备收件箱」**：数据按账号隔离，一个人在多台自己的设备间对话与传文件。

**非目标（首期明确不做）**：
- 群组 / 多人一对一会话（留到「家庭会话」阶段）。
- 端到端加密——服务端中转意味着服务端能访问消息内容；若未来引入 E2EE，需单独设计密钥备份、换机恢复、多设备加入流程。
- 匿名公开访问、自动生成公开文件链接。
- 替代通用即时通讯软件。

## 2. 核心设计原则（对齐现有架构）

| 原则 | 在本方案中的体现 |
|---|---|
| 服务端权威 | 会话成员校验、权限、配额、游标全在服务端执行，不靠客户端隐藏界面 |
| 复用而非新造 | 附件复用现有分块上传（`upload.go`）；鉴权复用 `authorize`；账号复用现有 users/session |
| 每账号自有数据 | 与 `playback` 同类——任意登录角色可读写**自己那份**，按账号隔离 |
| 离线可靠优先 | 消息服务端持久化；同步以「游标补齐」为准，不把实时连接当作可靠性前提 |
| 附件与消息分离 | 附件用服务端生成的 ID 关联；断点续传完成并确认权限后，才把附件挂到消息上 |
| 不混入普通文件库 | 附件存在服务端管理的独立会话目录，不进入用户的存储库虚拟路径 |

## 3. 服务端设计

### 3.1 持久化选型

对话消息是**关系型、需按游标分页、按账号隔离**的数据，因此走 **SQLite**（`modernc.org/sqlite`，纯 Go 单 exe），与 `index.go` 的 `files/content` 同一套。

- 独立库文件 `DataDir/chat.db`（WAL + busy_timeout + `SetMaxOpenConns(4)`），**不与 `index.db` 混用**，避免大库索引与消息互相影响，也便于单独备份/清理。
- 建表用 `CREATE TABLE IF NOT EXISTS`，幂等；新增表追加到 `openChatDB` 的建表列表。

### 3.2 数据模型（首期）

首期只有「一个账号一个默认会话」，但仍保留 `spaces` 表，为后续「家庭会话」留出扩展位，避免将来改表。

```sql
-- 会话空间。首期每账号自动拥有一个 space(role='personal')。
CREATE TABLE IF NOT EXISTS spaces (
  id         TEXT PRIMARY KEY,          -- 服务端生成的 ID
  owner      TEXT NOT NULL,             -- 账号名，对齐 users
  role       TEXT NOT NULL,             -- 'personal'（首期固定）
  title      TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL           -- unix 秒
);
CREATE INDEX IF NOT EXISTS idx_spaces_owner ON spaces(owner);

-- 消息。游标分页靠 (space_id, id) 单调递增排序。
CREATE TABLE IF NOT EXISTS messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,  -- 全局单调，作游标
  space_id   TEXT NOT NULL,
  sender     TEXT NOT NULL,            -- 账号名
  kind       TEXT NOT NULL,            -- 'text' | 'file'
  body       TEXT NOT NULL DEFAULT '', -- 文本内容；file 消息为说明文字（可空）
  created_at INTEGER NOT NULL,         -- unix 秒
  edited_at  INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER NOT NULL DEFAULT 0,  -- 软删（tombstone），用于同步删除
  seq        INTEGER NOT NULL          -- 空间内单调序号，见 3.5 游标
);
CREATE INDEX IF NOT EXISTS idx_messages_space_seq ON messages(space_id, seq);

-- 附件。附件先上传、后关联到消息。
CREATE TABLE IF NOT EXISTS attachments (
  id          TEXT PRIMARY KEY,        -- 服务端生成附件 ID
  message_id  INTEGER NOT NULL DEFAULT 0,  -- 0 = 已上传未关联
  space_id    TEXT NOT NULL,
  uploader    TEXT NOT NULL,
  name        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  mime        TEXT NOT NULL DEFAULT '',
  sha256      TEXT NOT NULL DEFAULT '',
  stored_rel  TEXT NOT NULL,           -- 相对会话附件根目录的存储路径
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_attachments_msg ON attachments(message_id);
```

**关键点**：`messages.id`（AUTOINCREMENT）天然单调，作为**全局游标**；`seq` 是空间内单调序号，用于「这个空间拉取 id > cursor 的增量」。软删（`deleted_at`）让「对方删除消息」也能被其他设备同步到。

### 3.3 附件存储

- 附件落盘到 `DataDir/chat-attachments/<spaceID>/<attachmentID>__<safeName>`，**不走** `Config.Resolve` 的存储库虚拟路径，天然与普通文件隔离。
- 复用 `upload.go` 的分块上传，但**落地目标指向会话附件目录**而非存储库（见 3.4 的接口设计）。
- 上传完成后服务端计算 sha256 存入 `attachments.sha256`，供后续完整性校验与去重。

### 3.4 API 设计

所有接口挂进现有鉴权中间件（`api.go:291` 的 `authorize` → `withUser(ctx, sess)`）。**对话空间属「每账号自有数据」，与 playback 同类**：在 `authorize` 中放行任意登录角色（含 viewer）访问 `/api/chat`，但 handler 内部严格按 `space.owner == 当前账号` 校验，越权返回 403。

| 方法与路径 | 说明 | 请求 / 响应要点 |
|---|---|---|
| `GET /api/chat/spaces` | 列出当前账号的会话 | 响应 `[{id,title,role,unread,lastSeq}]`；首期通常一条 |
| `POST /api/chat/messages` | 发送消息 | 请求 `{spaceId, kind:'text', body}` 或 `{spaceId, kind:'file', body, attachmentIds:[]}`；响应 `{id, seq, createdAt}` |
| `GET /api/chat/messages` | 拉取消息（游标分页） | 查询 `spaceId, cursor(默认0=最新), limit(默认50,上限200), dir=before/after`；响应 `{messages:[...], nextCursor, hasMore}` |
| `POST /api/chat/attachments/init` | 发起附件上传 | 复用分块上传语义，返回 `{uploadId, attachmentId, received, chunkSize}` |
| `POST /api/chat/attachments/complete` | 完成并关联附件 | 请求 `{uploadId, attachmentId, messageId?}`；完成 sha256、落库；如带 messageId 则关联 |
| `GET /api/chat/attachments/raw` | 下载/预览附件 | 查询 `id`；校验归属后回源文件，带 `Content-Type` |
| `DELETE /api/chat/messages` | 删除消息（软删） | 请求 `{ids:[]}`；仅本人消息可删；附件是否同删见决策点 |

**同步协议（首期：轮询）**：客户端定期 `GET /api/chat/messages?dir=after&cursor=<lastSeq>` 拉增量；断线重连后从本地最大 `seq` 续拉，天然补齐。删除靠 `deleted_at` tombstone 同步。首期不做 SSE/WebSocket（backlog 明确「先轮询，简单可靠」），接口形状已为将来加 SSE 预留（`dir=after` + 长轮询可平滑升级）。

### 3.5 游标与一致性

- 用 `messages.id`（AUTOINCREMENT 单调）作游标，`seq` 作空间内展示序号。
- `dir=after&cursor=X` 返回 `seq > X` 的增量（新消息）；`dir=before&cursor=X` 返回更早历史（翻页）。
- 分页用 `limit` 截断，返回 `nextCursor` 与 `hasMore`，客户端据此决定是否继续拉。
- 写入在单条 `INSERT` 内完成，靠 SQLite 事务保证 `id/seq` 一致；`SetMaxOpenConns(4)` + WAL 足以承载首期单用户负载。

### 3.6 权限模型

- 首期会话成员 = 账号本人。每个 chat handler 先取 `withUser` 注入的当前账号，再校验 `space.owner == 当前账号`，否则 403。
- `viewer` 角色：可读写**自己的**对话（与 playback 一致）。若产品上希望 viewer 不可发消息，在 `authorize` 对 `POST /api/chat/*` 单独拒绝即可——**待确认决策点 D3**。
- 附件下载 `raw` 同样校验 `attachments.uploader/space.owner`，不提供公开链接。

### 3.7 容量、配额与保留

（backlog 风险项：「附件配额 / 保留 / 清理策略需先定，避免无限增长」）

- 单文件大小上限、单会话总容量、保留期限——**可配置**，存 `chat_meta` 表或 `config.json`（决策点 D4）。
- 超限行为：上传 `init` 阶段即拒绝并返回明确错误码。
- 清理：提供管理端「清理会话附件」入口（人工确认后执行，对齐「破坏性操作需人工确认」约定）；不做自动静默删除。

### 3.8 新增文件清单（服务端）

| 文件 | 职责 |
|---|---|
| `server/chat.go` | `chatStore`（建表、增删查、游标分页）、HTTP handlers |
| `server/chat_test.go` | 单测：账号隔离、游标分页边界、附件关联、软删同步、越权拒绝、配额 |
| `server/api.go`（改动） | `Server` 增加 `chat *chatStore` 字段；`NewServer` 里 `newChatStore(cfg.DataDir)`；注册 `/api/chat/*` 路由；`authorize` 放行 `/api/chat`（按账号自有数据） |

## 4. 客户端设计

### 4.1 Android 客户端

Android 是**传统多 Activity 工程**：无 Jetpack Compose / ViewModel / Room / Navigation 组件，页面为独立 `AppCompatActivity`（二级页统一继承 `ScreenScaffold`），离线状态用 **SharedPreferences 存 JSON**（`OfflineStore.kt` 等 6 个 Store 同范式），网络统一走 `LinNasClient`（OkHttp4 + `org.json`，鉴权自动带 `X-Token` / `?token=`）。

| 关注点 | 方案 | 复用 |
|---|---|---|
| 页面 | 新建 `ConversationActivity`，继承 `ScreenScaffold` 拿统一二级页骨架（顶栏返回+标题+内容区） | `ScreenScaffold.kt` |
| 入口 | **起步放设置页 / 工具入口 `startActivity` 跳入**（改动最小）；若将来要进底部导航，需改 `activity_main.xml` 加 `pageXxx` 容器 + `setupBottomNav()` 加分支（决策点 D6） | `MainActivity.kt` 入口范式 |
| 网络 | `LinNasClient` 加一组对话 fun（列会话 / 拉消息 / 发消息 / 附件），鉴权自动复用 `withSession()` | 仿 `getPlayback/putPlayback` |
| 附件上传 | 直接调 `client.upload(...)` 或 `UploadService.startUpload(...)`——分块 + 断点续传现成 | `LinNasClient.kt:861-915` |
| 消息持久化 | 新建 `ConversationStore`，**沿用 SP+JSON**（`data class Message` + 编码进独立 SP 如 `"linnas_chat"` + `synchronized` 锁 + 乐观更新）；**不引入 Room**，对齐工程「无数据库、轻量单 APK」一致性（决策点 D7） | 仿 `OfflineStore.kt` |
| 在屏刷新 | `Handler.postDelayed` 轮询 `dir=after&cursor=lastSeq` | 仿 `MainActivity.kt:141-146` |
| 后台同步 / 通知 | 阶段 2 再评估 `CoroutineWorker`（仿 `OfflineDownloader`）+ 通知渠道（Android 13+ 需 `POST_NOTIFICATIONS` 运行时权限） | 现有 3 个前台服务范式 |
| 主题 / 无障碍 | 只用语义 attr（`?attr/lInk` 等），禁写死 `#RRGGBB`；字号用 `@dimen/text_*` 禁裸 `sp`；图标必带 `contentDescription`；`ThemeContrastTest` 是新页面上色守门员 | `ThemeColors.kt` / `DESIGN.md` |
| 测试 | 纯逻辑抽成可单测函数（仿 `OfflineDownloadProtocolTest`）；网络层用 MockWebServer（仿 `OfflineDownloadWorkerTest`，注意保存还原 `ServerConfig` 全局态） | 现有测试范式 |

**客户端缓存策略**：服务端是消息**真相源**。Android 端 `ConversationStore` 只做**最近消息的轻量本地缓存 + 待发队列**，翻页历史直接向服务端按游标请求，避免在客户端复制一套完整索引。这样既不引入 Room，又能离线看到最近消息、断线重连续拉补齐。

### 4.2 Web 前端

Web UI 是**无构建步骤的手写 SPA**：源码即 `server/ui/dist/{index.html, app.js, style.css}`（`app.js:1` 注明「单页应用(无构建步骤)」），经 `//go:embed ui` 内嵌进 exe——**改完必须重新 `go build` 才生效**（开发期可用 `LINNAS_UI_DIR` 免重建）。统一 `api()` 封装（`app.js:196`，自动拼 token、处理 401/错误）；路由是 hash-free 的 `VIEWS` 数组 + `showView()`（`app.js:483`）。注意：根目录 `DESIGN.md` 仅约束 Android，**Web 规范在 `style.css` 的 CSS 变量里**（`--surface-1/2/3`、`--accent`、`--line` 等，深/浅两套主题）。

| 关注点 | 方案 | 复用 |
|---|---|---|
| 顶层页 | 侧栏加 `<button id="btn-chat">` + 新增 `<section id="view-chat" hidden>`；`VIEWS` 加入 `"view-chat"`；写 `showChat()`；绑 `$("#btn-chat").onclick`（决策点 D8） | `index.html:61-75` / `app.js:483,488-500` |
| API 调用 | 直接 `await api("/api/chat/...", {method:"POST", body:{...}})`，鉴权/错误自动复用 | `api()` `app.js:196` |
| 附件上传 | 复用 `/api/upload/*` 三段式协议与 `runUpload`（`init→chunk→complete`）+ 进度条 UI | `app.js:1765-1856` |
| 新消息轮询 | `pollOrganize` 范式：递归 `setTimeout` + `state.chatTimer` + **离开页面即停守卫** + 切走时 `stopChatPoll()` | `pollOrganize` `app.js:4406` |
| 样式 | 只引用 `style.css` 的 CSS 变量，勿写死色值；卡片用 `--surface-*` 渐变 + `--line` 描边惯例 | `style.css` |
| 组件 | 图标 `data-icon` + `svgIcon()`；空状态 `emptyState()`；对话框 `<dialog>` + `askConfirm/askInput`；轻提示 `toast()` | `app.js:466,476,230` |

**对话视图构成（首期）**：消息列表（按 `seq` 正序、气泡区分发送/接收）+ 底部输入区（文本框 + 附件按钮）+ 历史翻页（滚动到顶拉 `dir=before`）+ 未读/失败态标记。发送文件走分块上传，完成后 `POST /api/chat/messages`（`kind:'file'` + `attachmentIds`）落消息。

> ⚠️ 调研提示：`index.html` 侧栏/`view-*` section 的完整结构与 `style.css` 的 `:root` 完整变量块在调研时受工具自动审查间歇拦截未整段通读，**实现前需直接打开这两个文件核对精确插入位置**。

## 5. 分阶段验收（对齐 FEATURE_ROADMAP）

1. **个人设备收件箱（MVP）**：同账号 Web ↔ Android 互发文字与文件；历史一致；大文件中断后可续传；对方离线重连后仍能收到。
2. **可靠性与易用性**：未读状态、失败重试、附件管理、Android 通知；配额 / 过期清理 / 异常断网场景。
3. **家庭会话**：扩展到不同授权用户的一对一会话，再评估群组与更细权限；不得绕过现有多用户安全模型。

## 6. 待确认决策点（需用户拍板）

- **D1 附件删除语义**：删除消息时附件是否同时物理删除？建议：消息软删，附件默认保留（可被清理任务回收），删除时明确告知用户。
- **D2 数据库文件**：独立 `chat.db`（本方案默认）还是并入 `index.db`？建议独立，隔离与备份更清晰。
- **D3 viewer 权限**：viewer 能否使用对话空间？建议首期允许读写自己的（对齐 playback），若需收紧再单独拒绝写。
- **D4 配额默认值**：单文件 / 单会话容量 / 保留期限的默认值与是否可配置。
- **D5 同步方式**：首期确认用轮询（本方案默认）还是直接上 SSE。
- **D6 Android 入口**（来自客户端调研）：起步放设置页 / 工具入口跳独立 `ConversationActivity`（改动最小，推荐），还是直接加进底部导航 tab（需改 `activity_main.xml` + `setupBottomNav()`）。
- **D7 Android 消息持久化**（来自客户端调研）：沿用 SP+JSON（与现有 6 个 Store 一致、无数据库，推荐），还是为消息分页/索引引入 Room（架构级变更，会破坏工程「轻量单 APK」一致性）。本方案默认 SP+JSON + 服务端为真相源。
- **D8 Web 入口**（来自前端调研）：做成侧栏独立顶层页 `view-chat`（推荐，对话是高频独立功能），还是塞进「存储空间」页当子页签。本方案默认独立顶层页。
