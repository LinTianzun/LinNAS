# linNas 浏览器冒烟测试（零依赖）

`scripts/e2e/smoke.mjs` 是一个**零第三方依赖**的浏览器冒烟脚本：直接通过 Chrome DevTools Protocol (CDP) 驱动本机已安装的 Chrome / Edge，对 linNas 前端做 6 个基于 DOM 的端到端断言。不需要 `npm install`，不需要 Playwright / Puppeteer / Electron。

- 运行时只用 Node 内置能力：`fetch`、全局 `WebSocket`（Node 22+）、`child_process.spawn`。
- 每个场景输出一行 `PASS/FAIL`，末尾给出 `N passed / M failed  total Xs`；任一 FAIL 时进程退出码为 `1`。

---

## 1. 前置条件

- **Node.js ≥ 22**（本机为 v24.20.0；需要全局 `fetch` 与 `WebSocket`）。
  ```bash
  node --version
  ```
- **本机已安装 Chrome 或 Edge**。脚本会自动探测以下路径，也可用 `--chrome=` 显式指定：
  - `C:\Program Files\Google\Chrome\Application\chrome.exe`
  - `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`
  - `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`
  - `C:\Program Files\Microsoft\Edge\Application\msedge.exe`
- **被测服务已启动**并可从本机访问。默认目标是开放模式实例 `http://127.0.0.1:52701/ui/`（后端 52701，静态文件从 `server/ui/dist` 读取，改前端即生效）。
  > 冒烟脚本只读取该实例，**不会重启/停止它**，也不会触碰正式服务 `52700`。

---

## 2. 怎么跑

在仓库根目录（`E:\linNas`）执行：

```bash
# 用默认目标 http://127.0.0.1:52701/ui/（自动探测浏览器）
node scripts/e2e/smoke.mjs

# 指定 base（例如对 QA / 正式环境）
node scripts/e2e/smoke.mjs --base=http://127.0.0.1:52700/ui/

# 指定浏览器可执行文件
node scripts/e2e/smoke.mjs --chrome="C:\Program Files\Google\Chrome\Application\chrome.exe"

# 调试：打印每一步细节（含第一组行数、保险箱状态等）
node scripts/e2e/smoke.mjs --verbose

# 调试：跑完不关闭浏览器与临时目录，方便手工连 DevTools
node scripts/e2e/smoke.mjs --keep-open
```

参数说明：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--base=<url>` | `http://127.0.0.1:52701/ui/` | 被测前端入口；末尾缺 `/` 会自动补 |
| `--chrome=<exe>` | 自动探测 | Chrome/Edge 可执行文件完整路径 |
| `--verbose` / `-v` | 关 | 输出内部细节到 stderr |
| `--keep-open` | 关 | 不关闭浏览器、不删临时目录，便于排查 |
| `--help` / `-h` | — | 打印用法 |

**隔离与可重复**：每次运行都会新建一个临时 `--user-data-dir`（`%TEMP%\linnas-e2e-*`），结束后关闭浏览器并删除该临时目录，因此不污染本机浏览器配置，可反复运行。

**退出码**：全部 PASS → `0`；任一 FAIL → `1`。可直接用于 CI。

---

## 3. 每个场景断言什么

所有断言均基于 DOM，不依赖截图。

### 场景 1 · 页面加载
- 导航到 `base`，等待 `.topbar` 与 `#view-home` 出现（`document.readyState === 'complete'`）。
- 断言 `document.querySelector('#ver-text').textContent` 匹配 `v1.`（该文本由 `/api/health` 的 `version` 填充，形如 `v1.7.0`）。
- 收集本场景期间的 console error，若出现 `Uncaught` / `TypeError` → FAIL。

### 场景 2 · 全局搜索
- 在 `document` 上派发 `keydown`（`Ctrl+K`）：`document.dispatchEvent(new KeyboardEvent('keydown',{key:'k',ctrlKey:true,bubbles:true}))`。
- 断言 `#view-search` 不为 `hidden`，且 `document.activeElement.id === 'in-global-search'`。

### 场景 3 · 媒体库分组折叠
- 点 `#btn-media` → 点 `#media-tabs [data-type="image"]` → 等 `#media-grid .frow` 出现。
- 点 `#media-group` → 等 `.mgroup-head` 出现 → 记录**第一个分组**当前可见的 `.frow` 数量（记为 `before`）。
- 点该组 `.mgroup-toggle` 收起 → 断言：出现 `.mgroup-more` 磁贴；该组可见 `.frow` 数 `> 0` 且 `< before`（即收起后只留一行）。
- 点 `.mgroup-more` 展开 → 断言磁贴消失、该组可见 `.frow` 数恢复为 `before`。

### 场景 4 · 迷你播放器
- 媒体库切到「音乐」tab（`#media-tabs [data-type="audio"]`）→ 点第一个 `.frow`。
- 等 `#mini-player` 非 `hidden`；断言 `#mp-title` 非空、`#mp-time` 匹配 `/^\d+:\d{2} \/ \d+:\d{2}$/`。
- 点 `#mp-next` → 断言 `#mp-title` 发生变化（换歌）。
- 点 `#mp-close` → 断言 `#mini-player` 变为 `hidden`。

### 场景 5 · 音乐页控件
- 点 `#btn-music`；断言 `#music-shuffle`、`#music-repeat`、`#music-vol`、`#music-filter` 都存在。
- 点 `#music-shuffle` → 断言 `aria-pressed="true"`。
- 在 `#music-filter` 输入 `zzz` → 断言列表出现「没有匹配的歌名」。
- 清空筛选；点 `#music-repeat` 两次 → 断言其 `title` 变为「单曲循环」。

### 场景 6 · 保险箱解锁闭环
- 点 `#btn-vault`。
- 若出现 `.vault-hero .btn.primary`（未创建 / 已锁定）就点它；在 `#dlg-vaultpw` 中：
  - 若 `#vaultpw-pw2-wrap` 可见（新保险箱）→ 填密码 + 确认密码（固定测试密码见脚本内 `const PW`），
  - 否则（已存在、只解锁）→ 只填 `#in-vault-pw`；
  - 点 `#btn-vaultpw-ok`。
- 等 `#vault-chip` 文本含「已解锁」。
- 断言 `#vault-actions` 的按钮文案同时含「修改密码」和「导出全部」。

> 场景 6 是有状态的：空环境走「新建保险箱」分支，已有保险箱走「解锁」分支。两种分支都用同一固定密码，可重复跑。

---

## 4. 失败怎么排查

1. **先看脚本输出**：每个 FAIL 行下方会打印
   - `✗ 断言: ...` —— 失败的断言表达式；
   - `实际: ...` —— 断言拿到的实际值（JSON）；
   - `本场景期间 console error (N)` —— 该场景执行期间浏览器抛出的 exception / `console.error` / `Log` 错误（各一行，过长会截断）。
2. **加 `--verbose`** 重跑：会额外打印浏览器路径、调试端口、第一组展开前后行数、保险箱初始状态等，便于定位是「元素没渲染」还是「值不对」。
3. **加 `--keep-open` 重跑**：浏览器保持打开，终端会打印 `user-data-dir` 路径（其中 `DevToolsActivePort` 文件第一行是调试端口）。用 Chrome 打开 `chrome://inspect` 或访问 `http://127.0.0.1:<端口>/json` 手工检查页面。
4. **常见原因**：
   - **服务没起 / base 写错**：场景 1 会直接超时或 `#ver-text` 不含 `v1.`。先确认 `--base` 能打开、`/api/health` 正常。
   - **视口太小导致网格列数不足**：场景 3 依赖 `.file-list.grid` 的 `auto-fill minmax(158px,1fr)` 让第一组「一行放不下」。脚本通过 `Emulation.setDeviceMetricsOverride` 固定 1200×900，一般不触发；若改写样式需复核。
   - **媒体索引为空**：场景 3/4 需要 `/api/media?type=image` 与 `type=audio` 有数据；索引未建好时先等索引完成。
   - **无头浏览器音频策略**：脚本已带 `--autoplay-policy=no-user-gesture-required --mute-audio`，迷你播放器不依赖真正出声（DOM 状态即可）。
5. **保险箱重置（仅在需要干净复现时）**：删除被测实例的 `DataDir/vault.json`（及保险箱目录 `VaultDir`），令 `/api/vault/status` 返回 `{"exists":false,...}`，即可重新走「新建」分支。
   - 本机 52701 开发实例：`DataDir = %TEMP%\linnas-dev`，即删除 `%TEMP%\linnas-dev\vault.json`。
   - 其它实例：删除其配置里 `DataDir\vault.json`（`VaultDir` 默认是第一个可写存储库的 `.linNasVault`）。

---

## 5. 换 base（QA / 正式环境）

其它环境只是入口地址不同，通常仍提供 `/api/*`：

```bash
# QA
node scripts/e2e/smoke.mjs --base=http://127.0.0.1:52700/ui/
# 局域网/远程
node scripts/e2e/smoke.mjs --base=http://192.168.1.10:52700/ui/
```

注意：

- 若目标服务**启用了登录/口令**（非开放模式），6 个场景里需要权限的操作（如保险箱）可能因未登录而失败；冒烟脚本面向**开放模式**实例设计。
- `--base` 只改前端入口；脚本通过 `fetch`/CDP 访问的 `/api/*` 都基于同源 `<base>` 的 origin。

---

## 6. 实现说明（便于维护）

- 启动 Chrome：`--headless=new --remote-debugging-port=0 --remote-allow-origins=* --user-data-dir=<临时目录> ...`，从临时目录里的 `DevToolsActivePort`（配合 `/json/version`）取得浏览器级 WebSocket 地址。
- 用浏览器级 CDP：`Target.createTarget` → `Target.attachToTarget{flatten:true}` 得到页面 session，再发 `Page.enable` / `Runtime.enable` / `Log.enable`，以及 `Emulation.setDeviceMetricsOverride` 固定视口。
- 断言用 `Runtime.evaluate`（`awaitPromise:true, returnByValue:true`）轮询 DOM 状态；控制台错误来自 `Runtime.exceptionThrown` / `Runtime.consoleAPICalled(type=error|assert)` / `Log.entryAdded(level=error)`。
- 进程退出时关闭浏览器并删除临时目录；`--keep-open` 时保留。

---

## 7. 已知问题

- **首次新建保险箱后 UI 状态未同步（前端缺陷，非脚本问题）**：当前 `server/ui/dist/app.js` 的 `vaultSetup()` 在 `/api/vault/setup` 成功后直接调用 `vaultUnlock()`，但**没有把 `state.vault.exists` 置为 `true`**（该字段只在 `loadVaultStatus()` 里更新）。因此「全新环境首次创建保险箱」时，服务端 `setup`/`unlock`/`ls` 均返回 200 且弹出 toast「已解锁」，但 `#vault-chip` 仍停留在「未创建」（`renderVault()` 走 `if (!v.exists)` 分支提前返回）。此时场景 6 会按断言 FAIL，证据为：`#vault-chip` 实际值 `"未创建"`（期望含「已解锁」）。
  - 修复方向（前端）：在 `vaultSetup()` 里 `await api("/api/vault/setup", ...)` 成功后、调用 `vaultUnlock()` 前，补一句 `state.vault.exists = true;`（或 `await loadVaultStatus();`）。
  - 一旦保险箱已存在（再次运行走「解锁」分支），场景 6 即可正常通过，说明脚本与解锁链路本身无误。
