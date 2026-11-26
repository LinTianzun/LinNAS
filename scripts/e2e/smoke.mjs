#!/usr/bin/env node
// linNas 浏览器冒烟测试 — 零依赖 (Node 内置 fetch / WebSocket / child_process)
// 通过 Chrome DevTools Protocol 驱动本机 Chrome/Edge，对 http://127.0.0.1:52701/ui/ 做 6 个 DOM 断言场景。
//
// 用法:
//   node scripts/e2e/smoke.mjs
//   node scripts/e2e/smoke.mjs --base=http://127.0.0.1:52701/ui/ --chrome="C:\Program Files\Google\Chrome\Application\chrome.exe"
//   node scripts/e2e/smoke.mjs --verbose            # 打印每一步细节
//   node scripts/e2e/smoke.mjs --keep-open           # 跑完不关浏览器/临时目录，便于手工调试
//
// 退出码: 全部 PASS -> 0; 任意 FAIL -> 1

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, existsSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

/* ------------------------------------------------------------------ 参数 */

function parseArgs(argv) {
  const out = {
    base: "http://127.0.0.1:52701/ui/",
    chrome: "",           // 空 = 自动探测
    keepOpen: false,
    verbose: false,
  };
  for (const a of argv.slice(2)) {
    if (a === "--keep-open") out.keepOpen = true;
    else if (a === "--verbose" || a === "-v") out.verbose = true;
    else if (a.startsWith("--base=")) out.base = a.slice("--base=".length);
    else if (a.startsWith("--chrome=")) out.chrome = a.slice("--chrome=".length);
    else if (a === "--help" || a === "-h") {
      console.log("用法: node scripts/e2e/smoke.mjs [--base=URL] [--chrome=EXE] [--keep-open] [--verbose]");
      process.exit(0);
    } else {
      console.error(`未知参数: ${a}`);
      process.exit(2);
    }
  }
  if (!out.base.endsWith("/")) out.base += "/";
  return out;
}

const ARGS = parseArgs(process.argv);
const vlog = (...m) => { if (ARGS.verbose) console.error("[verbose]", ...m); };

/* ----------------------------------------------------------- Chrome 探测 */

function findChrome() {
  const candidates = [
    ARGS.chrome,
    process.env.CHROME_PATH,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ].filter(Boolean);
  for (const c of candidates) {
    try { if (existsSync(c)) return c; } catch { /* ignore */ }
  }
  throw new Error(
    "找不到 Chrome/Edge。请用 --chrome=<exe路径> 指定，或设置环境变量 CHROME_PATH。\n尝试过:\n  " +
    candidates.join("\n  ")
  );
}

/* ------------------------------------------------------------- CDP 客户端 */

class CDP {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this._id = 0;
    this._pending = new Map();     // id -> {resolve, reject, method}
    this._listeners = [];          // (msg) => void
  }

  connect() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.wsUrl);
      this.ws = ws;
      const onErr = (e) => reject(new Error("WebSocket 连接失败: " + (e?.message || e)));
      ws.addEventListener("open", () => { ws.removeEventListener("error", onErr); resolve(); });
      ws.addEventListener("error", onErr);
      ws.addEventListener("message", (ev) => {
        let msg;
        try { msg = JSON.parse(typeof ev.data === "string" ? ev.data : Buffer.from(ev.data).toString("utf8")); }
        catch { return; }
        if (msg.id !== undefined && this._pending.has(msg.id)) {
          const p = this._pending.get(msg.id);
          this._pending.delete(msg.id);
          if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message} (code ${msg.error.code})`));
          else p.resolve(msg.result);
        } else {
          for (const fn of this._listeners) { try { fn(msg); } catch { /* ignore */ } }
        }
      });
      ws.addEventListener("close", () => {
        for (const [, p] of this._pending) p.reject(new Error("WebSocket 已关闭: " + p.method));
        this._pending.clear();
      });
    });
  }

  on(fn) { this._listeners.push(fn); }

  send(method, params = {}, sessionId) {
    const id = ++this._id;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      this._pending.set(id, { resolve, reject, method });
      try { this.ws.send(JSON.stringify(payload)); }
      catch (e) { this._pending.delete(id); reject(e); }
    });
  }

  close() { try { this.ws && this.ws.close(); } catch { /* ignore */ } }
}

/* --------------------------------------------------------- 日志采集状态 */

const consoleErrors = [];  // [{ text, source, ts }]
let PAGE_SESSION = null;

function collectFromEvent(msg) {
  if (msg.sessionId !== PAGE_SESSION) return;
  const p = msg.params || {};
  if (msg.method === "Runtime.exceptionThrown") {
    const d = p.exceptionDetails || {};
    const text = d.exception?.description || d.exception?.value || d.text || "Unknown exception";
    consoleErrors.push({ text: String(text), source: "exception", ts: Date.now() });
  } else if (msg.method === "Runtime.consoleAPICalled") {
    if (p.type !== "error" && p.type !== "assert") return;
    const text = (p.args || []).map((a) => a.value !== undefined ? a.value : (a.description || a.type)).join(" ");
    consoleErrors.push({ text: String(text), source: "console." + p.type, ts: Date.now() });
  } else if (msg.method === "Log.entryAdded") {
    const e = p.entry || {};
    if (e.level !== "error") return;
    consoleErrors.push({ text: String(e.text || ""), source: "log", ts: Date.now() });
  }
}

/* -------------------------------------------------------------- 浏览器启动 */

let CHROME_PROC = null;
let TEMP_DIR = null;
let cdp = null;

async function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function withTimeout(promise, ms, what) {
  let t;
  const timeout = new Promise((_, rej) => { t = setTimeout(() => rej(new Error(`超时(${ms}ms): ${what}`)), ms); });
  try { return await Promise.race([promise, timeout]); }
  finally { clearTimeout(t); }
}

async function launchBrowser() {
  const chrome = findChrome();
  vlog("chrome:", chrome);

  TEMP_DIR = mkdtempSync(path.join(tmpdir(), "linnas-e2e-"));
  const args = [
    "--headless=new",
    "--remote-debugging-port=0",
    `--user-data-dir=${TEMP_DIR}`,
    "--remote-allow-origins=*",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-gpu",
    "--hide-scrollbars",
    "--mute-audio",
    "--autoplay-policy=no-user-gesture-required",
    "--disable-background-networking",
    "--disable-features=Translate,BackForwardCache",
    "--window-size=1200,900",
    "about:blank",
  ];
  vlog("spawn chrome with user-data-dir:", TEMP_DIR);
  // stdio:"ignore" —— 本环境沙箱禁止管道捕获(pipe 会 EPERM)
  CHROME_PROC = spawn(chrome, args, { stdio: "ignore", windowsHide: true });
  CHROME_PROC.on("error", (e) => { vlog("chrome spawn error:", e.message); });

  const portFile = path.join(TEMP_DIR, "DevToolsActivePort");
  const deadline = Date.now() + 15000;
  let port = 0;
  while (Date.now() < deadline) {
    if (existsSync(portFile)) {
      const raw = readFileSync(portFile, "utf8").split(/\r?\n/).filter(Boolean);
      if (raw[0]) { port = parseInt(raw[0], 10); if (port) break; }
    }
    await sleep(120);
  }
  if (!port) throw new Error("Chrome 未能在 15s 内打开调试端口 (DevToolsActivePort 未就绪)");
  vlog("devtools port:", port);

  let wsUrl;
  try {
    const res = await withTimeout(fetch(`http://127.0.0.1:${port}/json/version`), 5000, "GET /json/version");
    const info = await res.json();
    wsUrl = info.webSocketDebuggerUrl;
  } catch (e) {
    // 回退：DevToolsActivePort 第二行是 browser ws 路径
    const raw = readFileSync(portFile, "utf8").split(/\r?\n/).filter(Boolean);
    if (raw[1]) wsUrl = `ws://127.0.0.1:${port}${raw[1]}`;
  }
  if (!wsUrl) throw new Error("拿不到 webSocketDebuggerUrl");
  vlog("browser ws:", wsUrl);

  cdp = new CDP(wsUrl);
  cdp.on(collectFromEvent);
  await withTimeout(cdp.connect(), 8000, "连接 browser WebSocket");
}

async function openPage() {
  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  PAGE_SESSION = sessionId;
  const S = (method, params) => cdp.send(method, params, sessionId);
  await S("Page.enable");
  await S("Runtime.enable");
  await S("Log.enable");
  // 固定 CSS 视口，避免不同机器/缩放下网格列数漂移（分组折叠断言依赖"一行放不下"）
  await S("Emulation.setDeviceMetricsOverride", { width: 1200, height: 900, deviceScaleFactor: 1, mobile: false });
  return { targetId, sessionId, S };
}

/* ------------------------------------------------------------- 页面工具 */

let S = null; // page session send

async function evaluate(expression) {
  const r = await S("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  });
  if (r && r.exceptionDetails) {
    const d = r.exceptionDetails;
    const msg = d.exception?.description || d.exception?.value || d.text || "页面执行异常";
    throw new Error("Runtime.evaluate 抛出: " + String(msg).split("\n")[0]);
  }
  return r?.result?.value;
}

// 轮询直到表达式返回真值；返回该真值。超时抛出。
async function waitFor(expression, timeout = 8000, interval = 100, label = "") {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await evaluate(expression);
      if (last) return last;
    } catch (e) { last = "ERR:" + e.message; }
    await sleep(interval);
  }
  throw new Error(`等待超时(${timeout}ms)${label ? " [" + label + "]" : ""}: ${expression}\n  最后一次值: ${JSON.stringify(last)}`);
}

function assert(cond, expr, actual) {
  if (cond) return;
  const e = new Error(`断言失败: ${expr}`);
  e.expr = expr;
  e.actual = actual;
  throw e;
}

async function navigate(url) {
  vlog("navigate:", url);
  await S("Page.navigate", { url });
  await waitFor(`document.readyState === 'complete'`, 15000, 100, "navigate readyState");
}

/* --------------------------------------------------------------- 场景框架 */

const results = []; // {name, ok, ms}

async function scenario(name, fn) {
  const errStart = consoleErrors.length;
  const t0 = Date.now();
  try {
    await fn();
    const ms = Date.now() - t0;
    results.push({ name, ok: true, ms });
    console.log(`PASS  ${name}  (${ms})`);
  } catch (e) {
    const ms = Date.now() - t0;
    results.push({ name, ok: false, ms });
    console.log(`FAIL  ${name}  (${ms})`);
    console.log(`  ✗ ${e.expr ? "断言: " + e.expr : e.message}`);
    if (e.actual !== undefined) console.log(`    实际: ${e.actual}`);
    const errs = consoleErrors.slice(errStart);
    if (errs.length) {
      console.log(`    本场景期间 console error (${errs.length}):`);
      for (const er of errs.slice(0, 12)) {
        const oneLine = er.text.replace(/\r?\n/g, " ").slice(0, 400);
        console.log(`      - [${er.source}] ${oneLine}`);
      }
      if (errs.length > 12) console.log(`      ... 其余 ${errs.length - 12} 条省略`);
    } else {
      console.log(`    本场景期间 console error: 无`);
    }
  }
}

/* ================================================================ 场景 1 */

async function scenarioPageLoad() {
  await scenario("页面加载", async () => {
    const t = Date.now();
    await navigate(ARGS.base);
    await waitFor(
      `!!document.querySelector('.topbar') && !!document.querySelector('#view-home')`,
      8000, 100, "topbar/view-home"
    );
    // #ver-text 由 /api/health 填充（形如 v1.7.0）
    const ver = await waitFor(
      `(()=>{const e=document.querySelector('#ver-text');const s=e&&e.textContent;return s&&/v1\\./.test(s)?s:null;})()`,
      8000, 100, "#ver-text 含 v1."
    );
    assert(/v1\./.test(ver), `#ver-text.textContent 含 "v1."`, JSON.stringify(ver));
    vlog("页面加载耗时", Date.now() - t, "ms");

    // 本场景 console error 中不允许出现 Uncaught / TypeError
    const startIdx = consoleErrors.length; // 之后不该再有；用整体校验
    const bad = consoleErrors.filter((e) => /Uncaught|TypeError/.test(e.text));
    assert(
      bad.length === 0,
      `本场景 console error 不含 Uncaught/TypeError`,
      JSON.stringify(bad.map((b) => b.text.slice(0, 200)))
    );
  });
}

/* ================================================================ 场景 2 */

async function scenarioGlobalSearch() {
  await scenario("全局搜索", async () => {
    await evaluate(
      `document.dispatchEvent(new KeyboardEvent('keydown',{key:'k',ctrlKey:true,bubbles:true}))`
    );
    await waitFor(
      `(()=>{const v=document.querySelector('#view-search');return v&&!v.hidden;})()`,
      5000, 80, "#view-search 非 hidden"
    );
    const active = await waitFor(
      `document.activeElement && document.activeElement.id === 'in-global-search' ? 'in-global-search' : null`,
      4000, 80, "activeElement=in-global-search"
    );
    assert(active === "in-global-search", `document.activeElement.id === 'in-global-search'`, JSON.stringify(active));
  });
}

/* ================================================================ 场景 3 */

// 测量「第一个分组」：返回该组 frow 总数、当前可见 frow 数、组内是否已有展开磁贴
const MEASURE_FIRST_GROUP = `(()=>{
  const grid = document.querySelector('#media-grid');
  if (!grid) return { ok:false, reason:'no #media-grid' };
  const kids = [...grid.children];
  const hi = kids.findIndex(k => k.classList && k.classList.contains('mgroup-head'));
  if (hi < 0) return { ok:false, reason:'no .mgroup-head' };
  let rows = [], more = false;
  for (let i = hi + 1; i < kids.length; i++) {
    const k = kids[i];
    if (k.classList && k.classList.contains('mgroup-head')) break;
    if (k.classList && k.classList.contains('frow')) rows.push(k);
    if (k.classList && k.classList.contains('mgroup-more')) more = true;
  }
  return { ok:true, total: rows.length, visible: rows.filter(r => !r.hidden).length, more };
})()`;

async function scenarioMediaGroup() {
  await scenario("媒体库分组折叠", async () => {
    await evaluate(`document.querySelector('#btn-media').click()`);
    await waitFor(`(()=>{const v=document.querySelector('#view-media');return v&&!v.hidden;})()`, 5000, 80, "view-media");
    await evaluate(`document.querySelector('#media-tabs [data-type="image"]').click()`);
    await waitFor(
      `(()=>{const s=document.querySelector('#media-sub');return s&&s.textContent.startsWith('图片')&&document.querySelectorAll('#media-grid .frow').length>0;})()`,
      15000, 120, "图片图墙渲染完成"
    );

    // 打开按文件夹分组
    await evaluate(`document.querySelector('#media-group').click()`);
    await waitFor(`document.querySelectorAll('#media-grid .mgroup-head').length > 0`, 6000, 80, "mgroup-head");
    // 等两帧，确保 requestAnimationFrame 里的布局应用与 offsetTop 稳定
    await evaluate(`new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>r(true))))`);

    const before = await evaluate(MEASURE_FIRST_GROUP);
    assert(before && before.ok, `能定位第一个分组`, JSON.stringify(before));
    assert(before.visible > 0, `收起前第一组可见 .frow 数 > 0`, JSON.stringify(before));
    vlog("第一组收起前:", before);

    // 点该组折叠按钮
    await evaluate(
      `(()=>{const h=document.querySelector('#media-grid .mgroup-head');h.querySelector('.mgroup-toggle').click();})()`
    );
    await waitFor(`document.querySelectorAll('#media-grid .mgroup-more').length > 0`, 4000, 80, "mgroup-more 磁贴");

    const collapsed = await evaluate(MEASURE_FIRST_GROUP);
    assert(collapsed && collapsed.more === true, `收起后出现 .mgroup-more 磁贴`, JSON.stringify(collapsed));
    assert(
      collapsed.visible > 0 && collapsed.visible < before.visible,
      `收起后第一组可见 frow 数 >0 且 < 收起前(${before.visible})（留了一行）`,
      JSON.stringify(collapsed)
    );
    vlog("第一组收起后:", collapsed);

    // 点磁贴展开
    await evaluate(`document.querySelector('#media-grid .mgroup-more').click()`);
    await waitFor(`document.querySelectorAll('#media-grid .mgroup-more').length === 0`, 4000, 80, "磁贴消失");

    const restored = await evaluate(MEASURE_FIRST_GROUP);
    assert(restored && restored.more === false, `展开后 .mgroup-more 磁贴消失`, JSON.stringify(restored));
    assert(
      restored.visible === before.visible,
      `展开后第一组可见 frow 数恢复为 ${before.visible}`,
      JSON.stringify(restored)
    );
    vlog("第一组展开后:", restored);
  });
}

/* ================================================================ 场景 4 */

async function scenarioMiniPlayer() {
  await scenario("迷你播放器", async () => {
    // 进入媒体库（若已在媒体库则不必重复点击，避免并发 loadMedia 竞态把图墙换成别的类型）
    const inMedia = await evaluate(`(()=>{const v=document.querySelector('#view-media');return !!(v&&!v.hidden);})()`);
    if (!inMedia) {
      await evaluate(`document.querySelector('#btn-media').click()`);
      await waitFor(`(()=>{const v=document.querySelector('#view-media');return v&&!v.hidden;})()`, 5000, 80, "view-media");
    }
    // 若上一场景留下的分组模式仍开启，关掉它，保证 .frow 直接可用
    await evaluate(
      `(()=>{const b=document.querySelector('#media-group');if(b&&b.classList.contains('primary'))b.click();})()`
    );
    // 切到「音乐」tab；用 #media-sub 的标签文本兜底，确保「本次音频加载」是最终渲染结果
    await evaluate(`document.querySelector('#media-tabs [data-type="audio"]').click()`);
    await waitFor(
      `(()=>{const s=document.querySelector('#media-sub');return s&&s.textContent.startsWith('音乐')&&document.querySelectorAll('#media-grid .frow').length>0;})()`,
      15000, 120, "音频图墙渲染完成"
    );

    // 点第一首歌 -> 后台播放 + 迷你播放器出现
    await evaluate(`document.querySelector('#media-grid .frow').click()`);
    await waitFor(
      `(()=>{const m=document.querySelector('#mini-player');return m&&!m.hidden;})()`,
      10000, 100, "#mini-player 非 hidden"
    );

    const title1 = await waitFor(
      `(()=>{const e=document.querySelector('#mp-title');const s=e&&e.textContent&&e.textContent.trim();return s&&s!=='未选择歌曲'?s:null;})()`,
      5000, 80, "mp-title 非空"
    );
    assert(title1 && title1.trim().length > 0, `#mp-title 非空`, JSON.stringify(title1));

    const time1 = await evaluate(`document.querySelector('#mp-time').textContent`);
    assert(
      /^\d+:\d{2} \/ \d+:\d{2}$/.test(String(time1 || "")),
      `#mp-time 匹配 /^\\d+:\\d{2} \\/ \\d+:\\d{2}$/`,
      JSON.stringify(time1)
    );

    // 下一首 -> 标题变化
    await evaluate(`document.querySelector('#mp-next').click()`);
    const title2 = await waitFor(
      `(()=>{const t=(document.querySelector('#mp-title').textContent||'').trim();return t&&t!==${JSON.stringify(title1)}?t:null;})()`,
      6000, 100, "mp-title 变化"
    );
    assert(title2 && title2 !== title1, `点击 #mp-next 后 #mp-title 变化`, JSON.stringify({ before: title1, after: title2 }));

    // 关闭 -> 收起的迷你播放器
    await evaluate(`document.querySelector('#mp-close').click()`);
    await waitFor(`document.querySelector('#mini-player').hidden === true`, 4000, 80, "#mini-player hidden");
    assert(true, `点击 #mp-close 后 #mini-player hidden`, "hidden=true");
  });
}

/* ================================================================ 场景 5 */

async function scenarioMusicControls() {
  await scenario("音乐页控件", async () => {
    await evaluate(`document.querySelector('#btn-music').click()`);
    await waitFor(`(()=>{const v=document.querySelector('#view-music');return v&&!v.hidden;})()`, 5000, 80, "view-music");
    // 等歌单加载完（排除"加载中/暂无/加载失败"这类空态）
    await waitFor(
      `document.querySelectorAll('#music-list li').length > 0 && !document.querySelector('#music-list li.music-empty')`,
      10000, 120, "音乐列表加载"
    );

    const exist = await evaluate(
      `['#music-shuffle','#music-repeat','#music-vol','#music-filter'].map(s=>!!document.querySelector(s))`
    );
    assert(
      Array.isArray(exist) && exist.every(Boolean),
      `#music-shuffle / #music-repeat / #music-vol / #music-filter 都存在`,
      JSON.stringify(exist)
    );

    // 随机播放
    await evaluate(`document.querySelector('#music-shuffle').click()`);
    const pressed = await evaluate(`document.querySelector('#music-shuffle').getAttribute('aria-pressed')`);
    assert(pressed === "true", `点击 #music-shuffle 后 aria-pressed="true"`, JSON.stringify(pressed));

    // 筛选 zzz -> 无匹配
    await evaluate(
      `(()=>{const f=document.querySelector('#music-filter');f.value='zzz';f.dispatchEvent(new Event('input',{bubbles:true}));})()`
    );
    const emptyTxt = await waitFor(
      `(()=>{const li=document.querySelector('#music-list li.music-empty');return li&&li.textContent.includes('没有匹配的歌名')?li.textContent:null;})()`,
      4000, 80, "没有匹配的歌名"
    );
    assert(!!emptyTxt, `筛选 "zzz" 后列表出现「没有匹配的歌名」`, JSON.stringify(emptyTxt));

    // 清空筛选，列表恢复
    await evaluate(
      `(()=>{const f=document.querySelector('#music-filter');f.value='';f.dispatchEvent(new Event('input',{bubbles:true}));})()`
    );
    await waitFor(
      `document.querySelectorAll('#music-list li').length > 0 && !document.querySelector('#music-list li.music-empty')`,
      4000, 80, "清空筛选后恢复"
    );

    // 循环模式：off -> all -> one（点两次 -> 单曲循环）
    await evaluate(`document.querySelector('#music-repeat').click()`);
    await evaluate(`document.querySelector('#music-repeat').click()`);
    const rpTitle = await evaluate(`document.querySelector('#music-repeat').title`);
    assert(
      String(rpTitle || "").includes("单曲循环"),
      `点 #music-repeat 两次后 title 含「单曲循环」`,
      JSON.stringify(rpTitle)
    );
  });
}

/* ================================================================ 场景 6 */

async function scenarioVaultUnlock() {
  await scenario("保险箱解锁闭环", async () => {
    const PW = "e2e-pass-1234"; // 固定密码（新保险箱创建 / 已存在时的解锁密码）

    await evaluate(`document.querySelector('#btn-vault').click()`);
    await waitFor(`(()=>{const v=document.querySelector('#view-vault');return v&&!v.hidden;})()`, 5000, 80, "view-vault");

    // 判定状态：已解锁 / 需要操作(未创建或已锁定) / 其他
    const st = await waitFor(
      `(()=>{
         const c=document.querySelector('#vault-chip');
         if(c && !c.hidden && c.textContent.includes('已解锁')) return 'unlocked';
         const b=document.querySelector('.vault-hero .btn.primary');
         if(b) return 'needs-action';
         return null;
       })()`,
      10000, 100, "保险箱状态"
    );
    vlog("保险箱初始状态:", st);

    if (st === "needs-action") {
      await evaluate(`document.querySelector('.vault-hero .btn.primary').click()`);
      await waitFor(`!!document.querySelector('#dlg-vaultpw[open]')`, 5000, 80, "#dlg-vaultpw 打开");

      const needConfirm = await evaluate(
        `(()=>{const w=document.querySelector('#vaultpw-pw2-wrap');return !!(w&&!w.hidden&&getComputedStyle(w).display!=='none');})()`
      );
      vlog("需要确认密码(新保险箱):", needConfirm);

      await evaluate(`document.querySelector('#in-vault-pw').value = ${JSON.stringify(PW)};`);
      if (needConfirm) {
        await evaluate(`document.querySelector('#in-vault-pw2').value = ${JSON.stringify(PW)};`);
      }
      await evaluate(`document.querySelector('#btn-vaultpw-ok').click()`);
    }

    // 等解锁完成
    await waitFor(
      `(()=>{const c=document.querySelector('#vault-chip');return c&&!c.hidden&&c.textContent.includes('已解锁');})()`,
      15000, 120, "#vault-chip 含 已解锁"
    );

    const acts = await evaluate(`document.querySelector('#vault-actions').textContent`);
    assert(
      String(acts || "").includes("修改密码") && String(acts || "").includes("导出全部"),
      `#vault-actions 按钮文案含「修改密码」和「导出全部」`,
      JSON.stringify(acts)
    );
  });
}

/* ================================================================ 主流程 */

async function main() {
  const t0 = Date.now();
  console.log(`linNas smoke — base: ${ARGS.base}`);
  await launchBrowser();
  const page = await openPage();
  S = page.S;

  try { await S("Page.enable"); } catch { /* already */ }

  try {
    await scenarioPageLoad();
    await scenarioGlobalSearch();
    await scenarioMediaGroup();
    await scenarioMiniPlayer();
    await scenarioMusicControls();
    await scenarioVaultUnlock();
  } finally {
    const passed = results.filter((r) => r.ok).length;
    const failed = results.length - passed;
    const total = ((Date.now() - t0) / 1000).toFixed(1);
    console.log("");
    console.log(`${passed} passed / ${failed} failed  total ${total}s`);
    if (failed > 0) process.exitCode = 1;

    if (ARGS.keepOpen) {
      console.log(`\n[keep-open] 浏览器与临时目录保留:`);
      console.log(`  user-data-dir: ${TEMP_DIR}`);
      console.log(`  调试端口信息见 ${path.join(TEMP_DIR, "DevToolsActivePort")}`);
    } else {
      await cleanup();
    }
  }
}

async function cleanup() {
  try { if (cdp) await withTimeout(cdp.send("Browser.close"), 3000, "Browser.close").catch(() => {}); } catch { /* ignore */ }
  try { cdp && cdp.close(); } catch { /* ignore */ }
  try { if (CHROME_PROC && !CHROME_PROC.killed) CHROME_PROC.kill(); } catch { /* ignore */ }
  await sleep(400);
  if (TEMP_DIR) {
    for (let i = 0; i < 4; i++) {
      try { rmSync(TEMP_DIR, { recursive: true, force: true, maxRetries: 3, retryDelay: 150 }); break; }
      catch { await sleep(300); }
    }
  }
}

process.on("SIGINT", async () => { await cleanup(); process.exit(130); });

main().catch(async (e) => {
  console.error("致命错误:", e && e.stack ? e.stack : e);
  try { await cleanup(); } catch { /* ignore */ }
  process.exitCode = 1;
  // 若有已跑的场景结果，也补一行汇总
  if (results.length) {
    const passed = results.filter((r) => r.ok).length;
    console.log(`\n${passed} passed / ${results.length - passed} failed`);
  }
});
