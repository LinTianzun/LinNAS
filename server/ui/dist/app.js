// linNas Web UI v0.5 — 单页应用(无构建步骤)
"use strict";

/* ============================================================ 基础状态 */
const state = {
  token: localStorage.getItem("linnas_token") || "",
  version: "",
  libs: [],
  disks: [],
  view: "home",            // home | browse | vault
  curLib: null,
  curPath: "/",
  entries: [],
  filter: "",
  sort: localStorage.getItem("linnas_sort") || "name",
  sortDir: localStorage.getItem("linnas_sortdir") || "asc",
  typeFilter: localStorage.getItem("linnas_type") || "all",
  mode: localStorage.getItem("linnas_mode") || "list",
  theme: localStorage.getItem("linnas_theme") || "system",
  uploading: [],
  upHidden: false,
  selectMode: false,
  selected: new Set(),
  clipboard: null,         // {mode:'copy'|'cut', items:[{vpath,name,isDir}]}
  editLibId: null,
  dirPickCB: null,
  folderPickCB: null,
  settings: null,
  vault: {
    exists: false,
    unlocked: false,
    token: localStorage.getItem("linnas_vault_token") || "",
    entries: [],
    path: "",
  },
  mediaType: "video",
  mediaSort: "name",
  mediaOffset: 0,
  mediaItems: [],
  mediaCollapsed: Object.create(null), // 分组模式下已收起的目录
  searchResults: [],
  searchScope: "all",        // 搜索范围: file(文件名) | content(内容) | all(全部)
  searchContentStatus: null, // GET /api/content/status 的缓存结果
  searchStatusTimer: null,   // 内容索引状态轮询定时器
  searchSeq: 0,              // 防止并发搜索把旧结果覆盖新结果
  devices: [],
  trash: [],
  trashView: localStorage.getItem("linnas_trash_view") || "list",
  // 存储空间分析
  storageTab: "large",       // large | dups | trend
  storageLarge: [],
  storageDups: null,
  storageScanTimer: null,
  trendDays: 30,             // 趋势页签的时间范围(天)
  diskHealth: null,          // GET /api/disks/health 的缓存结果
  diskHealthSeq: 0,          // 防止并发请求把旧结果追加两遍
  diskLib: "libs",           // 磁盘健康按存储库过滤: libs(已添加的存储库,默认) | all(全部磁盘) | 库 id
  storageLibs: [],           // /api/storage/summary 返回的库列表(磁盘健康下拉用)
  // 校验巡检(批次 2)
  patrolMode: "quick",       // quick | deep
  patrolTimer: null,         // 巡检状态轮询定时器
  patrolStatus: null,        // 最近一次 GET /api/storage/patrol/status
  patrolLast: null,          // 最近一条 GET /api/storage/patrol/last 记录
  // 自动整理(批次 4)
  organizeRules: [],         // GET /api/organize/rules 的规则列表
  organizeStatus: null,      // 最近一次 GET /api/organize/status
  organizeJobs: [],          // GET /api/organize/jobs 的作业列表
  organizePreview: null,     // 最近一次 POST /api/organize/preview 的结果
  organizePreviewRule: null, // 预览对应的规则(用于执行按钮回查 ruleId)
  organizeTimer: null,       // 整理作业状态轮询定时器
  organizeDraft: null,       // 规则编辑抽屉当前编辑的规则副本
  // v1.4.0 D 安全
  auth: { multiUser: false, userCount: 0, idleLockMinutes: 30, username: "", role: "", session: false, locked: false },
  users: [],
  audit: [],
  shares: [],
  overview: null,          // GET /api/overview 的总览数据
  overviewSeq: 0,          // 总览加载序号,用于丢弃过期的趋势补绘
};

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const enc = (s) => encodeURIComponent(s);

/* ============================================================ 图标 */
const ICONS = {
  plus: { d: '<path d="M12 5v14M5 12h14"/>' },
  "chevron-left": { d: '<path d="M15 18l-6-6 6-6"/>' },
  "chevron-right": { d: '<path d="M9 6l6 6-6 6"/>' },
  search: { d: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>' },
  x: { d: '<path d="M18 6L6 18M6 6l12 12"/>' },
  upload: { d: '<path d="M12 15V4M6 9l6-6 6 6"/><path d="M4 20h16"/>' },
  download: { d: '<path d="M12 4v11M6 11l6 6 6-6"/><path d="M4 20h16"/>' },
  play: { d: '<path d="M8 5v14l11-7z"/>', fill: true },
  pause: { d: '<path d="M8 5h3v14H8zM13 5h3v14h-3z"/>', fill: true },
  edit: { d: '<path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="M13 7l4 4"/>' },
  trash: { d: '<path d="M4 7h16"/><path d="M10 11v6M14 11v6"/><path d="M6 7l1 12h10l1-12"/><path d="M9 7V5h6v2"/>' },
  folder: { d: '<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>' },
  "folder-plus": { d: '<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><path d="M12 11v6M9 14h6"/>' },
  file: { d: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/>' },
  video: { d: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M10 9.5l5 2.5-5 2.5z"/>' },
  image: { d: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="M3 17l5-5 4 4 3-3 6 6"/>' },
  music: { d: '<path d="M9 18V6l11-2v12"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>' },
  archive: { d: '<path d="M4 4h16v4H4z"/><path d="M6 8v12h12V8"/><path d="M10 12h4"/>' },
  check: { d: '<path d="M20 6L9 17l-5-5"/>' },
  grid: { d: '<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>' },
  list: { d: '<path d="M8 6h12M8 12h12M8 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01" stroke-width="2.6"/>' },
  drive: { d: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="12" cy="12" r="3"/><path d="M7 17h.01M17 7h.01"/>' },
  home: { d: '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h5v-6h4v6h5V10"/>' },
  lock: { d: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>' },
  unlock: { d: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 7.5-2"/>' },
  "check-square": { d: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M8 12l3 3 5-6"/>' },
  move: { d: '<path d="M12 3v18M3 12h18"/><path d="M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3"/>' },
  copy: { d: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>' },
  cut: { d: '<circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M8 16L19 4M16 16L5 4"/>' },
  paste: { d: '<rect x="8" y="3" width="8" height="4" rx="1"/><path d="M8 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/>' },
  settings: { d: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>' },
  sun: { d: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>' },
  moon: { d: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>' },
  refresh: { d: '<path d="M21 12a9 9 0 1 1-3-6.7M21 4v5h-5"/>' },
  "arrow-up": { d: '<path d="M12 19V5M6 11l6-6 6 6"/>' },
  "arrow-down": { d: '<path d="M12 5v14M6 13l6 6 6-6"/>' },
  logout: { d: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5M21 12H9"/>' },
  eye: { d: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>' },
  "eye-off": { d: '<path d="M3 3l18 18"/><path d="M10.6 10.6a2 2 0 0 0 2.8 2.8"/><path d="M9.9 5.2A10.5 10.5 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.2"/><path d="M6.6 6.6A17.6 17.6 0 0 0 2 12s3.5 7 10 7a10.5 10.5 0 0 0 4.1-.8"/>' },
  film: { d: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5"/>' },
  monitor: { d: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>' },
  smartphone: { d: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M12 18h.01"/>' },
  tablet: { d: '<rect x="4" y="2" width="16" height="20" rx="2"/><path d="M12 18h.01"/>' },
  tv: { d: '<rect x="2" y="7" width="20" height="13" rx="2"/><path d="M8 2l4 5 4-5"/>' },
  pip: { d: '<rect x="2" y="4" width="20" height="16" rx="2"/><rect x="12" y="12" width="8" height="6" rx="1"/>' },
  maximize: { d: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/>' },
  info: { d: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>' },
  prev: { d: '<path d="M18 5v14l-10-7z"/><path d="M6 5v14"/>' },
  next: { d: '<path d="M6 5v14l10-7z"/><path d="M18 5v14"/>' },
  user: { d: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>' },
  key: { d: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="M10.7 12.3L20 3l1.5 1.5L19 6l1.5 1.5L19 9l1 1-8.3 8.3"/>' },
  list: { d: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3 6h.01M3 12h.01M3 18h.01"/>' },
  link: { d: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>' },
  globe: { d: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 0 18 15 15 0 0 1 0-18z"/>' },
  wifi: { d: '<path d="M5 12.5a10 10 0 0 1 14 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><path d="M12 19.5h.01"/>' },
};

function svgIcon(name, cls = "ic") {
  const it = ICONS[name];
  if (!it) return "";
  const fill = it.fill ? 'fill="currentColor" stroke="none"' : 'fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';
  return `<svg class="${cls}" viewBox="0 0 24 24" ${fill} aria-hidden="true">${it.d}</svg>`;
}
function applyIcons(root = document) {
  root.querySelectorAll("[data-icon]").forEach((el) => { el.innerHTML = svgIcon(el.dataset.icon); });
}

/* ============================================================ 工具 */
function fmtSize(n) {
  if (n === null || n === undefined) return "-";
  const u = ["B", "KB", "MB", "GB", "TB", "PB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (n >= 100 || i === 0 ? Math.round(n) : n.toFixed(1)) + " " + u[i];
}
function fmtTime(ts) {
  if (!ts) return "";
  const d = new Date(ts * 1000);
  const p = (x) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
let toastTimer = null;
function toast(msg, ms = 2600) {
  const el = $("#toast");
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, ms);
}
const VIDEO_EXT = ["mp4","mkv","mov","avi","wmv","flv","webm","m4v","ts","m2ts","rmvb","rm","mpg","mpeg","vob","3gp","ogv","asf"];
const AUDIO_EXT = ["mp3","flac","wav","aac","ogg","m4a","wma","opus"];
const IMAGE_EXT = ["jpg","jpeg","png","gif","webp","bmp","heic","avif","svg","tiff"];
const ARCHIVE_EXT = ["zip","rar","7z","tar","gz","bz2","xz"];
function ext(name) { const i = name.lastIndexOf("."); return i >= 0 ? name.slice(i + 1).toLowerCase() : ""; }
function kindOf(name) {
  const x = ext(name);
  if (VIDEO_EXT.includes(x)) return "video";
  if (AUDIO_EXT.includes(x)) return "audio";
  if (IMAGE_EXT.includes(x)) return "image";
  if (ARCHIVE_EXT.includes(x)) return "archive";
  return "file";
}

/* ============================================================ API */
function apiUrl(path) {
  if (!state.token) return path;
  const sep = path.includes("?") ? "&" : "?";
  return path + sep + "token=" + enc(state.token);
}
async function api(path, opts = {}, retry = 0) {
  const res = await fetch(apiUrl(path), {
    method: opts.method || "GET",
    headers: opts.body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401) {
    // 多用户模式:弹登录框/锁屏;单用户(全局口令)模式:弹口令框
    if (state.auth.multiUser) {
      if (retry >= 3) throw new Error("需要登录");
      const creds = state.auth.session ? await askUnlock() : await askLogin();
      if (!creds) throw new Error("需要登录");
      const ok = await doLogin(creds);
      if (!ok) throw new Error("登录失败");
      if (state.auth.session) settleUnlock(null);
      return api(path, opts, retry + 1);
    }
    if (retry >= 3) throw new Error("访问口令不正确");
    const t = await askToken();
    if (t === null) throw new Error("需要访问口令");
    state.token = t; localStorage.setItem("linnas_token", t);
    return api(path, opts, retry + 1);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

/* ============================================================ 对话框封装 */
let inputResolver = null;
function askInput(title, value = "", placeholder = "") {
  return new Promise((resolve) => {
    inputResolver = resolve;
    $("#dlg-input-title").textContent = title;
    const inp = $("#in-input");
    inp.value = value; inp.placeholder = placeholder;
    $("#dlg-input").showModal();
    setTimeout(() => { inp.focus(); inp.select(); }, 30);
  });
}
function settleInput(v) { if (inputResolver) { const r = inputResolver; inputResolver = null; r(v); } }
$("#btn-input-ok").onclick = () => { settleInput($("#in-input").value.trim() || null); $("#dlg-input").close(); };
$("#btn-input-cancel").onclick = () => { settleInput(null); $("#dlg-input").close(); };
$("#dlg-input").addEventListener("cancel", (e) => { e.preventDefault(); settleInput(null); $("#dlg-input").close(); });

let confirmResolver = null;
function askConfirm(title, msg, okText = "删除") {
  return new Promise((resolve) => {
    confirmResolver = resolve;
    $("#confirm-title").textContent = title;
    $("#confirm-msg").textContent = msg;
    $("#btn-confirm-ok").textContent = okText;
    $("#dlg-confirm").showModal();
  });
}
function settleConfirm(v) { if (confirmResolver) { const r = confirmResolver; confirmResolver = null; r(v); } }
$("#btn-confirm-ok").onclick = () => { settleConfirm(true); $("#dlg-confirm").close(); };
$("#btn-confirm-cancel").onclick = () => { settleConfirm(false); $("#dlg-confirm").close(); };
$("#dlg-confirm").addEventListener("cancel", (e) => { e.preventDefault(); settleConfirm(false); $("#dlg-confirm").close(); });

let tokenResolver = null;
function askToken() {
  return new Promise((resolve) => {
    tokenResolver = resolve;
    $("#in-token").value = "";
    $("#dlg-token").showModal();
    setTimeout(() => $("#in-token").focus(), 30);
  });
}
function settleToken(v) { if (tokenResolver) { const r = tokenResolver; tokenResolver = null; r(v); } }
$("#btn-token-ok").onclick = () => { settleToken($("#in-token").value.trim() || null); $("#dlg-token").close(); };
$("#btn-token-cancel").onclick = () => { settleToken(null); $("#dlg-token").close(); };
$("#dlg-token").addEventListener("cancel", (e) => { e.preventDefault(); settleToken(null); $("#dlg-token").close(); });

let vaultPwResolver = null;
let vaultPwWithConfirm = false;
let vaultPwWithOld = false;
function askVaultPw({ title, hint, withConfirm, withOld }) {
  return new Promise((resolve) => {
    vaultPwResolver = resolve; vaultPwWithConfirm = !!withConfirm; vaultPwWithOld = !!withOld;
    $("#vaultpw-title").textContent = title;
    $("#vaultpw-hint").textContent = hint || "";
    $("#in-vault-pw").value = ""; $("#in-vault-pw2").value = ""; $("#in-vault-old").value = "";
    $("#vaultpw-pw2-wrap").hidden = !withConfirm;
    $("#vaultpw-old-wrap").hidden = !withOld;
    $("#in-vault-pw").type = "password"; $("#in-vault-pw2").type = "password"; $("#in-vault-old").type = "password";
    $("#dlg-vaultpw").showModal();
    setTimeout(() => (withOld ? $("#in-vault-old") : $("#in-vault-pw")).focus(), 30);
  });
}
function settleVaultPw(v) { if (vaultPwResolver) { const r = vaultPwResolver; vaultPwResolver = null; r(v); } }
$("#btn-vaultpw-ok").onclick = () => {
  const pw = $("#in-vault-pw").value;
  const oldPw = $("#in-vault-old").value;
  if (vaultPwWithOld && !oldPw) { toast("请输入原密码"); return; }
  if (vaultPwWithConfirm) {
    const pw2 = $("#in-vault-pw2").value;
    if (pw.length < 4) { toast("密码至少 4 位"); return; }
    if (pw !== pw2) { toast("两次输入的密码不一致"); return; }
  } else if (!pw) { toast("请输入密码"); return; }
  settleVaultPw({ password: pw, oldPassword: oldPw });
  $("#dlg-vaultpw").close();
};
$("#dlg-vaultpw-cancel").onclick = () => { settleVaultPw(null); $("#dlg-vaultpw").close(); };
$("#dlg-vaultpw").addEventListener("cancel", (e) => { e.preventDefault(); settleVaultPw(null); $("#dlg-vaultpw").close(); });

/* ============================================================ 主题 */
function resolvedTheme() {
  if (state.theme === "system") return matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  return state.theme;
}
function applyTheme() {
  const r = resolvedTheme();
  document.documentElement.dataset.theme = r;
  document.querySelector('meta[name="theme-color"]').setAttribute("content", r === "light" ? "#f5f3ee" : "#0b0d0c");
  const btn = $("#btn-theme");
  if (btn) { btn.innerHTML = svgIcon(r === "light" ? "moon" : "sun"); btn.title = r === "light" ? "切换到深色" : "切换到浅色"; }
}
function setTheme(t) { state.theme = t; localStorage.setItem("linnas_theme", t); applyTheme(); }
$("#btn-theme").onclick = () => setTheme(resolvedTheme() === "light" ? "dark" : "light");
matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => { if (state.theme === "system") applyTheme(); });

/* ============================================================ 连接状态 */
// 把自定义服务器名应用到标题、品牌、锁屏等处
function applyServerName() {
  const nm = state.serverName || "LinNAS";
  document.title = nm;
  const bn = document.querySelector(".brand-name"); if (bn) bn.textContent = nm;
  const lb = document.querySelector(".lock-brand");
  if (lb) lb.innerHTML = '<span class="brand-led"></span> ' + nm;
  const cb = document.querySelector(".brand"); if (cb) cb.title = "回到主页 · " + nm;
}
async function checkHealth() {
  const conn = $("#conn");
  try {
    const h = await api("/api/health");
    state.version = h.version;
    state.serverName = h.name || "LinNAS";
    conn.className = "conn ok";
    $("#conn-text").textContent = "在线";
    conn.title = `${state.serverName} v${h.version}`;
    $("#ver-text").textContent = `v${h.version}`;
    applyServerName();
    return true;
  } catch (e) {
    conn.className = "conn err";
    $("#conn-text").textContent = "未连接";
    return false;
  }
}

/* ============================================================ 存储库 */
async function loadLibs() { state.libs = await api("/api/libs"); renderSidebar(); renderHome(); }
async function loadDisks() {
  try { state.disks = await api("/api/disks"); } catch (e) { state.disks = []; }
  renderHome(); renderSidebar();
}
function diskOf(id) { return state.disks.find((d) => d.id === id) || null; }

function renderSidebar() {
  const wrap = $("#lib-list");
  wrap.innerHTML = "";
  for (const lib of state.libs) {
    const d = diskOf(lib.id);
    const btn = document.createElement("button");
    btn.className = "lib-item";
    if (state.view === "browse" && state.curLib && state.curLib.id === lib.id) btn.classList.add("active");
    const free = d && d.totalMB ? fmtSize(d.freeMB * 1e6) : "—";
    btn.innerHTML = `<span class="dot"></span><span class="nm"></span><span class="sz"></span>`;
    btn.querySelector(".nm").textContent = lib.name;
    btn.querySelector(".sz").textContent = free;
    btn.onclick = () => openLib(lib.id);
    // 作为跨库拖拽的放置目标(按住 Ctrl 复制,否则移动)
    btn.addEventListener("dragover", (ev) => {
      if (!isDraggingItems()) return;
      ev.preventDefault();
      if (ev.dataTransfer) ev.dataTransfer.dropEffect = ev.ctrlKey ? "copy" : "move";
      btn.classList.add("drop-target");
    });
    btn.addEventListener("dragleave", () => btn.classList.remove("drop-target"));
    btn.addEventListener("drop", (ev) => {
      const vps = getDragVPaths(ev);
      if (!vps.length) return;
      ev.preventDefault(); ev.stopPropagation();
      btn.classList.remove("drop-target");
      doTransfer(vps, "/" + lib.id, ev.ctrlKey);
    });
    wrap.appendChild(btn);
  }
  $("#btn-vault").classList.toggle("active", state.view === "vault");
  $("#btn-overview").classList.toggle("active", state.view === "overview");
  $("#btn-media").classList.toggle("active", state.view === "media");
  $("#btn-music").classList.toggle("active", state.view === "music");
  $("#btn-search").classList.toggle("active", state.view === "search");
  $("#btn-devices").classList.toggle("active", state.view === "devices");
  $("#btn-trash").classList.toggle("active", state.view === "trash");
  $("#btn-storage").classList.toggle("active", state.view === "storage");
  $("#btn-audit").classList.toggle("active", state.view === "audit");
  $("#btn-shares").classList.toggle("active", state.view === "shares");
  $("#btn-remote").classList.toggle("active", state.view === "remote");
  $("#btn-pair").classList.toggle("active", state.view === "pair");
  // v1.4.0:按角色显示管理入口
  const isAdm = isAdmin();
  const logged = !!state.auth.username;
  $("#btn-audit").hidden = !isAdm;
  $("#btn-shares").hidden = !(isAdm || (logged && canWrite()));
}

function renderHome() {
  const cards = $("#lib-cards");
  const sub = $("#home-sub");
  cards.innerHTML = "";
  const libs = state.libs;
  if (!libs.length) {
    sub.textContent = "";
    cards.appendChild(emptyState("drive", "还没有存储库", "添加 H:\\、I:\\ 等目录,手机和浏览器就能开始上传、播放。", true));
    return;
  }
  let totalFree = 0, totalAll = 0, diskCount = 0;
  for (const d of state.disks) { if (d.totalMB) { totalFree += d.freeMB * 1e6; totalAll += d.totalMB * 1e6; diskCount++; } }
  sub.textContent = diskCount
    ? `${libs.length} 个存储库 · 共 ${fmtSize(totalAll)} 容量,剩余 ${fmtSize(totalFree)} 可用`
    : `${libs.length} 个存储库 · 磁盘信息暂不可用`;

  for (const lib of libs) {
    const d = diskOf(lib.id);
    const card = document.createElement("div");
    card.className = "lib-card" + (d ? "" : " offline");
    const pct = d && d.totalMB ? Math.min(100, Math.round((d.freeMB / d.totalMB) * 100)) : 0;
    card.innerHTML = `
      <div class="lc-top">
        <div class="lc-disc">${svgIcon("drive")}</div>
        <div class="lc-titles"><div class="lc-name"></div><div class="lc-path"></div></div>
        <span class="lc-badge"></span>
      </div>
      <div class="bar${pct < 8 ? " hot" : ""}"><i style="width:${pct}%"></i></div>
      <div class="lc-meta"><span class="mono"></span><span class="mono"></span></div>
      <div class="lc-acts">
        <button class="btn primary lc-open">${svgIcon("folder")} 浏览</button>
        <button class="btn lc-edit" title="设置">${svgIcon("settings")} 设置</button>
        <button class="btn ghost lc-del">移除</button>
      </div>`;
    const badge = card.querySelector(".lc-badge");
    badge.textContent = d ? (lib.readOnly ? "只读" : "可读写") : "离线";
    if (d && lib.readOnly) badge.classList.add("ro");
    card.querySelector(".lc-name").textContent = lib.name;
    card.querySelector(".lc-path").textContent = lib.path || "";
    card.querySelector(".lc-meta .mono:first-child").textContent = d && d.totalMB ? `已用 ${100 - pct}%` : "未挂载";
    card.querySelector(".lc-meta .mono:last-child").textContent = d && d.totalMB
      ? `可用 ${fmtSize(d.freeMB * 1e6)} / ${fmtSize(d.totalMB * 1e6)}` : "";
    card.querySelector(".lc-open").onclick = () => openLib(lib.id);
    const isAdm = isAdmin();
    const editBtn = card.querySelector(".lc-edit");
    const delBtn = card.querySelector(".lc-del");
    if (!isAdm) { editBtn.hidden = true; delBtn.hidden = true; }
    editBtn.onclick = () => openLibDialog(lib);
    delBtn.onclick = async () => {
      const ok = await askConfirm("移除存储库", `从列表中移除「${lib.name}」?不会删除磁盘上的任何文件。`, "移除");
      if (!ok) return;
      try { await api("/api/libs?id=" + enc(lib.id), { method: "DELETE" }); state.curLib = null; showHome(); await loadLibs(); await loadDisks(); toast("已移除"); }
      catch (e) { toast("失败: " + e.message); }
    };
    cards.appendChild(card);
  }
}

function emptyState(icon, title, hint, withAdd) {
  const div = document.createElement("div");
  div.className = "empty";
  div.innerHTML = `<div class="em-ic">${svgIcon(icon)}</div><div><b></b><br><span class="hint"></span></div>`;
  div.querySelector("b").textContent = title;
  div.querySelector(".hint").textContent = hint;
  if (withAdd) {
    const b = document.createElement("button");
    b.className = "btn primary";
    b.innerHTML = svgIcon("plus") + " 添加目录";
    b.onclick = () => openLibDialog(null);
    div.appendChild(b);
  }
  return div;
}

/* ============================================================ 视图切换 */
const VIEWS = ["view-overview", "view-home", "view-browse", "view-vault", "view-media", "view-music", "view-search", "view-devices", "view-trash", "view-storage", "view-audit", "view-shares", "view-remote", "view-pair"];
function showView(target) {
  VIEWS.forEach((v) => { const el = document.getElementById(v); if (el) el.hidden = v !== target; });
  if (typeof updateMiniPlayer === "function") updateMiniPlayer();
}
function showHome() { state.view = "home"; showView("view-home"); renderSidebar(); renderHome(); }
function showBrowse() { state.view = "browse"; showView("view-browse"); renderSidebar(); }
function showVault() { state.view = "vault"; showView("view-vault"); renderSidebar(); }
function showMedia() { state.view = "media"; showView("view-media"); renderSidebar(); }
function showMusic() { state.view = "music"; showView("view-music"); renderSidebar(); }
function showSearch() {
  state.view = "search"; showView("view-search"); renderSidebar();
  syncSearchScope();
  loadSearchContentStatus();
  setTimeout(() => $("#in-global-search").focus(), 60);
}
function showDevices() { state.view = "devices"; showView("view-devices"); renderSidebar(); }
function showTrash() { state.view = "trash"; showView("view-trash"); renderSidebar(); }

/* ============================================================ 总览(overview) */
function ovEl(tag, cls, text) {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (text !== undefined && text !== null) el.textContent = text;
  return el;
}

function showOverview() {
  state.view = "overview";
  showView("view-overview");
  renderSidebar();
  loadOverview();
}

async function loadOverview() {
  const sub = $("#ov-sub");
  const alerts = $("#ov-alerts");
  if (sub) sub.textContent = "正在读取总览数据…";
  if (alerts) alerts.innerHTML = "";
  state.overviewSeq = (state.overviewSeq || 0) + 1;
  const seq = state.overviewSeq;
  try {
    const ov = await api("/api/overview");
    state.overview = ov || {};
    renderOverview(state.overview);
    loadOverviewSparkline(seq); // 异步补绘迷你趋势;接口不可用时静默跳过
  } catch (e) {
    state.overview = null;
    if (sub) sub.textContent = "无法读取总览数据";
    if (alerts) {
      const el = ovEl("div", "ov-alert warn");
      el.appendChild(ovEl("span", "ov-alert-ic", "!"));
      el.appendChild(ovEl("span", "ov-alert-txt", "总览数据加载失败:" + (e && e.message ? e.message : "未知错误")));
      const retry = document.createElement("button");
      retry.className = "btn small";
      retry.textContent = "重试";
      retry.onclick = () => loadOverview();
      el.appendChild(retry);
      alerts.appendChild(el);
    }
  }
}

function renderOverview(ov) {
  ov = ov || {};
  const totals = ov.totals || {};
  const libs = Array.isArray(ov.libs) ? ov.libs : [];
  const kinds = ov.kinds || {};
  const idx = ov.index || null;
  const trash = ov.trash || {};
  const shares = ov.shares || {};
  const devices = ov.devices || {};
  const recent = Array.isArray(ov.recent) ? ov.recent : [];
  const alerts = Array.isArray(ov.alerts) ? ov.alerts : [];

  const totalB = (totals.totalMB || 0) * 1e6;
  const freeB = (totals.freeMB || 0) * 1e6;
  const usedB = Math.max(0, totalB - freeB);
  let usedPct = Number(totals.usedPct);
  if (!isFinite(usedPct)) usedPct = totalB ? Math.round((usedB / totalB) * 100) : 0;
  usedPct = Math.max(0, Math.min(100, Math.round(usedPct)));

  // 副标题
  const sub = $("#ov-sub");
  if (sub) sub.textContent = `${libs.length} 个存储库 · ${totals.files || 0} 个文件 · 已用 ${usedPct}%`;

  // 告警条(逐条)
  const alertBox = $("#ov-alerts");
  if (alertBox) {
    alertBox.innerHTML = "";
    for (const a of alerts) {
      const el = ovEl("div", "ov-alert " + (a && a.level === "warn" ? "warn" : "info"));
      el.appendChild(ovEl("span", "ov-alert-ic", a && a.level === "warn" ? "!" : "i"));
      el.appendChild(ovEl("span", "ov-alert-txt", (a && a.text) || ""));
      alertBox.appendChild(el);
    }
    // 有未读告警时,在条内右侧提供「全部已读」
    if ((Number(ov.unreadAlerts) || 0) > 0) {
      const ackBtn = document.createElement("button");
      ackBtn.className = "btn small";
      ackBtn.textContent = "全部已读";
      ackBtn.onclick = async () => {
        ackBtn.disabled = true;
        try {
          await api("/api/alerts/ack", { method: "POST", body: { ids: [], all: true } });
          await loadOverview();
        } catch (e) {
          ackBtn.disabled = false;
          toast("失败: " + (e && e.message ? e.message : "未知错误"));
        }
      };
      let host = alertBox.lastElementChild;
      if (!host) {
        host = ovEl("div", "ov-alert info");
        host.appendChild(ovEl("span", "ov-alert-ic", "i"));
        host.appendChild(ovEl("span", "ov-alert-txt", "有未读的空间告警"));
        alertBox.appendChild(host);
      }
      host.appendChild(ackBtn);
    }
  }

  // KPI 磁贴
  const kpis = $("#ov-kpis");
  if (kpis) {
    kpis.innerHTML = "";
    const items = [
      { v: fmtSize(totalB), l: "总容量" },
      { v: fmtSize(usedB), l: "已用" },
      { v: fmtSize(freeB), l: "可用" },
      { v: String(totals.files || 0), l: "文件总数" },
      { v: idx ? String(idx.fileCount || 0) : "—", l: "索引文件" },
      { v: String(libs.length), l: "存储库" },
    ];
    for (const it of items) {
      const k = ovEl("div", "ov-kpi");
      k.appendChild(ovEl("div", "ov-kpi-v", it.v));
      k.appendChild(ovEl("div", "ov-kpi-l", it.l));
      kpis.appendChild(k);
    }
  }

  // 存储容量概览
  const cap = $("#ov-capacity-body");
  if (cap) {
    cap.innerHTML = "";
    if (!libs.length) {
      cap.appendChild(ovEl("div", "ov-empty", "还没有存储库"));
    }
    for (const lib of libs) {
      const lt = lib.totalMB || 0;
      const lf = lib.freeMB || 0;
      const pct = lt ? Math.max(0, Math.min(100, Math.round(((lt - lf) / lt) * 100))) : 0;
      const row = ovEl("div", "ov-lib");
      const head = ovEl("div", "ov-lib-head");
      head.appendChild(ovEl("span", "ov-lib-name", lib.name || lib.id || "存储库"));
      head.appendChild(ovEl("span", "ov-lib-pct", pct + "%"));
      row.appendChild(head);
      if (lib.path) row.appendChild(ovEl("div", "ov-lib-path", lib.path));
      const bar = ovEl("div", "ov-bar");
      const fill = document.createElement("i");
      fill.style.width = pct + "%";
      bar.appendChild(fill);
      row.appendChild(bar);
      const meta = ovEl("div", "ov-lib-meta");
      meta.appendChild(ovEl("span", null, "可用 " + fmtSize(lf * 1e6)));
      meta.appendChild(ovEl("span", null, "共 " + fmtSize(lt * 1e6)));
      row.appendChild(meta);
      cap.appendChild(row);
    }
    const tot = ovEl("div", "ov-lib ov-lib-total");
    const thead = ovEl("div", "ov-lib-head");
    thead.appendChild(ovEl("span", "ov-lib-name", "合计"));
    thead.appendChild(ovEl("span", "ov-lib-pct", usedPct + "%"));
    tot.appendChild(thead);
    const tbar = ovEl("div", "ov-bar");
    const tfill = document.createElement("i");
    tfill.style.width = usedPct + "%";
    tbar.appendChild(tfill);
    tot.appendChild(tbar);
    const tmeta = ovEl("div", "ov-lib-meta");
    tmeta.appendChild(ovEl("span", null, "可用 " + fmtSize(freeB)));
    tmeta.appendChild(ovEl("span", null, "共 " + fmtSize(totalB)));
    tot.appendChild(tmeta);
    cap.appendChild(tot);
  }

  // 媒体与内容统计 + 系统状态
  const content = $("#ov-content-body");
  if (content) {
    content.innerHTML = "";
    const kindBox = ovEl("div", "ov-kinds");
    const kindItems = [
      { l: "视频", v: kinds.video || 0 },
      { l: "图片", v: kinds.image || 0 },
      { l: "音乐", v: kinds.audio || 0 },
      { l: "其他", v: kinds.other || 0 },
      { l: "文件夹", v: kinds.dir || 0 },
    ];
    for (const k of kindItems) {
      const cell = ovEl("div", "ov-kind");
      cell.appendChild(ovEl("div", "ov-kind-v", String(k.v)));
      cell.appendChild(ovEl("div", "ov-kind-l", k.l));
      kindBox.appendChild(cell);
    }
    content.appendChild(kindBox);

    const idxState = idx ? (idx.scanning ? "扫描中" : (idx.lastScan ? "已就绪" : "未扫描")) : "未启用";
    const refreshTxt = idx && idx.refreshSeconds ? idx.refreshSeconds + " 秒" : "已关闭";
    const sys = ovEl("div", "ov-sys");
    const rows = [
      ["服务版本", ov.version || state.version || "—"],
      ["索引状态", idxState],
      ["上次索引", idx && idx.lastScan ? fmtTime(idx.lastScan) : "—"],
      ["自动对账间隔", refreshTxt],
      ["活跃设备", String(devices.count || 0) + " 台"],
      ["分享链接", String(shares.count || 0) + " 个"],
      ["回收站", String(trash.count || 0) + " 项 · " + fmtSize(trash.bytes || 0)],
    ];
    for (const r of rows) {
      const line = ovEl("div", "ov-sys-row");
      line.appendChild(ovEl("span", "k", r[0]));
      line.appendChild(ovEl("span", "v", r[1]));
      sys.appendChild(line);
    }
    content.appendChild(sys);
  }

  // 快捷入口
  const actGrid = $("#ov-act-grid");
  if (actGrid) {
    actGrid.innerHTML = "";
    const acts = [
      { icon: "upload", label: "上传文件", run: () => {
          if (!state.curLib) { toast("请先打开一个存储库"); return; }
          showBrowse();
          $("#file-input").click();
        } },
      { icon: "search", label: "扫描重复文件", run: () => { showStorage(); setStorageTab("dups"); } },
      { icon: "trash", label: "查看回收站", run: () => { $("#btn-trash").click(); } },
      { icon: "link", label: "分享链接管理", run: () => { $("#btn-shares").click(); }, needShare: true },
      { icon: "lock", label: "隐私保险箱", run: () => { $("#btn-vault").click(); } },
    ];
    for (const a of acts) {
      if (a.needShare && !isAdmin() && !(state.auth.username && canWrite())) continue;
      const b = document.createElement("button");
      b.className = "ov-act";
      b.appendChild(ovEl("span", "ov-act-ic", ""));
      b.querySelector(".ov-act-ic").innerHTML = svgIcon(a.icon);
      b.appendChild(ovEl("span", "ov-act-t", a.label));
      b.onclick = a.run;
      actGrid.appendChild(b);
    }
  }

  // 近期活动
  const actBox = $("#ov-activity-body");
  if (actBox) {
    actBox.innerHTML = "";
    if (!recent.length) {
      actBox.appendChild(ovEl("div", "ov-empty", "暂无记录"));
    } else {
      const list = ovEl("div", "ov-log");
      for (const r of recent) {
        const row = ovEl("div", "ov-log-row");
        row.appendChild(ovEl("span", "ov-log-time", r.time ? fmtTime(r.time) : "—"));
        row.appendChild(ovEl("span", "ov-log-user", r.user || "未登录"));
        row.appendChild(ovEl("span", "ov-log-what", (r.action || "访问") + (r.target ? " · " + r.target : "")));
        list.appendChild(row);
      }
      actBox.appendChild(list);
    }
  }

  // 规划中的功能均已交付,M16 完成后不再展示占位卡。
}

// 总览:存储容量卡片底部的迷你趋势行(sparkline + 较上周)
async function loadOverviewSparkline(seq) {
  let r;
  try { r = await api("/api/storage/trend?days=30"); } catch (e) { return; }
  if (seq !== state.overviewSeq) return; // 总览已重新加载,丢弃旧结果
  const cap = $("#ov-capacity-body");
  if (!cap) return;
  const total = (r && r.total) || {};
  const pts = Array.isArray(total.points) ? total.points.slice(-14) : [];
  if (!pts.length) return;
  const old = $("#ov-spark-row");
  if (old) old.remove();
  const row = ovEl("div", "tr-spark-row");
  row.id = "ov-spark-row";
  const svg = trSvgChart(pts, "tr-spark", 120, 32, true);
  if (svg) row.appendChild(svg);
  let delta = Number(total.weekDeltaBytes);
  if (!isFinite(delta)) delta = trWeekDeltaOf(pts).bytes;
  const txt = ovEl("span", "tr-spark-txt", "较上周 " + trSignedSize(delta));
  txt.classList.add(delta > 0 ? "up" : delta < 0 ? "down" : "flat");
  row.appendChild(txt);
  cap.appendChild(row);
}

$("#btn-overview").onclick = () => showOverview();
$("#btn-overview-refresh").onclick = () => loadOverview();
const sideLibsHead = document.querySelector('.side-head-btn[data-nav="libs"]');
if (sideLibsHead) {
  const goLibs = () => showHome();
  sideLibsHead.addEventListener("click", goLibs);
  sideLibsHead.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); goLibs(); }
  });
}
function openLib(id) {
  const lib = state.libs.find((l) => l.id === id);
  if (!lib) return;
  state.curLib = lib; state.curPath = "/" + id;
  state.filter = ""; $("#in-search").value = "";
  clearSelection(); state.clipboard = state.clipboard; // keep clipboard across navigation
  showBrowse(); loadDir(); updatePasteBar();
}
function openPath(vp) { state.curPath = vp; showBrowse(); loadDir(); updatePasteBar(); }

/* ============================================================ 添加/编辑存储库 */
function openLibDialog(lib) {
  state.editLibId = lib ? lib.id : null;
  $("#dlg-lib-title").textContent = lib ? "存储库设置" : "添加存储库";
  $("#in-lib-path").value = lib ? lib.path : "";
  $("#in-lib-name").value = lib ? lib.name : "";
  $("#in-lib-ro").checked = lib ? lib.readOnly : false;
  $("#in-lib-exclude").value = lib && lib.exclude ? lib.exclude.join("\n") : "";
  $("#dlg-lib-ok").textContent = lib ? "保存" : "添加";
  $("#dlg-lib").showModal();
}
$("#btn-add-lib").onclick = () => { if (!canWrite()) { toast("只读用户不能添加存储库"); return; } openLibDialog(null); };
$("#btn-add-lib2").onclick = () => { if (!canWrite()) { toast("只读用户不能添加存储库"); return; } openLibDialog(null); };
$("#dlg-lib-cancel").onclick = () => $("#dlg-lib").close();
$("#dlg-lib").addEventListener("cancel", () => $("#dlg-lib").close());
$("#btn-pick-dir").onclick = async () => {
  const p = await openDirPicker($("#in-lib-path").value || "");
  if (p) {
    $("#in-lib-path").value = p;
    if (!$("#in-lib-name").value.trim()) $("#in-lib-name").value = p.replace(/[\\/]+$/, "").split(/[\\/]/).pop() || p;
  }
};
$("#dlg-lib-ok").onclick = async () => {
  const path = $("#in-lib-path").value.trim();
  if (!path) { toast("请选择目录"); return; }
  const exclude = $("#in-lib-exclude").value.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
  const body = { name: $("#in-lib-name").value.trim() || path, path, readOnly: $("#in-lib-ro").checked, exclude };
  try {
    if (state.editLibId) {
      await api("/api/libs/update", { method: "POST", body: { id: state.editLibId, ...body, enabled: true } });
      toast("已保存");
    } else {
      await api("/api/libs", { method: "POST", body });
      toast("已添加");
    }
    $("#dlg-lib").close();
    await loadLibs(); await loadDisks();
  } catch (e) { toast("失败: " + e.message); }
};

/* ============================================================ 服务器目录选择器 */
function openDirPicker(startPath) {
  return new Promise((resolve) => {
    state.dirPickCB = resolve;
    $("#dlg-dirpick").showModal();
    loadDirPicker(startPath);
  });
}
async function loadDirPicker(path) {
  let data;
  try { data = await api("/api/fs/dirs?path=" + enc(path || "")); }
  catch (e) { toast("读取失败: " + e.message); return; }
  state.dirPickPath = data.path || "";
  $("#dir-path").textContent = data.path || "(选择磁盘)";
  // 盘符
  const dr = $("#dir-drives");
  dr.innerHTML = "";
  for (const d of data.drives || []) {
    const b = document.createElement("div");
    b.className = "drive";
    b.textContent = d.letter + (d.type === "removable" ? " 移动盘" : d.type === "network" ? " 网络" : "");
    b.onclick = () => loadDirPicker(d.letter);
    dr.appendChild(b);
  }
  // 子目录
  const list = $("#dir-list");
  list.innerHTML = "";
  if (!data.path) { list.innerHTML = '<div class="pempty">请选择一个磁盘</div>'; }
  else {
    for (const d of data.dirs || []) {
      const it = document.createElement("div");
      it.className = "pitem";
      it.innerHTML = `${svgIcon("folder")}<span class="nm"></span>`;
      it.querySelector(".nm").textContent = d.name;
      it.onclick = () => loadDirPicker(d.path);
      list.appendChild(it);
    }
    if (!(data.dirs || []).length) list.innerHTML = '<div class="pempty">此目录下没有子文件夹</div>';
  }
  $("#btn-dir-up").disabled = !data.parent;
  $("#btn-dir-up").onclick = () => { if (data.parent) loadDirPicker(data.parent); };
}
$("#btn-dir-ok").onclick = () => {
  const p = state.dirPickPath;
  if (!p) { toast("请先选择磁盘"); return; }
  const cb = state.dirPickCB; state.dirPickCB = null;
  $("#dlg-dirpick").close();
  if (cb) cb(p);
};
$("#dlg-dirpick-cancel").onclick = () => { const cb = state.dirPickCB; state.dirPickCB = null; $("#dlg-dirpick").close(); if (cb) cb(null); };
$("#dlg-dirpick").addEventListener("cancel", () => { const cb = state.dirPickCB; state.dirPickCB = null; if (cb) cb(null); });

/* ============================================================ 目标文件夹选择器(虚拟路径) */
function openFolderPicker(title, cb, startVpath = "/") {
  state.folderPickCB = cb;
  $("#fpick-title").textContent = title || "选择目标文件夹";
  $("#dlg-folderpick").showModal();
  loadFolderPicker(startVpath || "/");
  return;
}
async function loadFolderPicker(vp) {
  state.fpickPath = vp;
  $("#fpick-path").textContent = vp;
  const list = $("#fpick-list");
  list.innerHTML = "";
  if (vp === "/") {
    for (const lib of state.libs) {
      const it = document.createElement("div");
      it.className = "pitem";
      it.innerHTML = `${svgIcon("drive")}<span class="nm"></span>`;
      it.querySelector(".nm").textContent = lib.name;
      it.onclick = () => loadFolderPicker("/" + lib.id);
      list.appendChild(it);
    }
    if (!state.libs.length) list.innerHTML = '<div class="pempty">没有存储库</div>';
    $("#btn-fpick-up").disabled = true;
    return;
  }
  // 当前目录内容(只要子目录)
  let data;
  try { data = await api("/api/ls?vpath=" + enc(vp)); }
  catch (e) { toast("读取失败: " + e.message); return; }
  const dirs = (data.entries || []).filter((e) => e.isDir);
  for (const d of dirs) {
    const it = document.createElement("div");
    it.className = "pitem";
    it.innerHTML = `${svgIcon("folder")}<span class="nm"></span>`;
    it.querySelector(".nm").textContent = d.name;
    it.onclick = () => loadFolderPicker(vp.replace(/\/$/, "") + "/" + d.name);
    list.appendChild(it);
  }
  if (!dirs.length) list.innerHTML = '<div class="pempty">此目录下没有子文件夹</div>';
  // 上级:库根 → "/"
  const seg = vp.replace(/^\/+|\/+$/g, "").split("/");
  const parent = seg.length <= 1 ? "/" : "/" + seg.slice(0, -1).join("/");
  $("#btn-fpick-up").disabled = false;
  $("#btn-fpick-up").onclick = () => loadFolderPicker(parent);
}
$("#btn-fpick-ok").onclick = () => {
  const p = state.fpickPath || "/";
  if (p === "/") { toast("请选择具体文件夹"); return; }
  const cb = state.folderPickCB; state.folderPickCB = null;
  $("#dlg-folderpick").close();
  if (cb) cb(p);
};
$("#dlg-fpick-cancel").onclick = () => { state.folderPickCB = null; $("#dlg-folderpick").close(); };
$("#dlg-folderpick").addEventListener("cancel", () => { state.folderPickCB = null; });

/* ============================================================ 文件浏览 */
function renderCrumbs() {
  const el = $("#crumbs"); el.innerHTML = "";
  const lib = state.curLib; if (!lib) return;
  const homeA = document.createElement("a");
  homeA.href = "#"; homeA.innerHTML = svgIcon("home"); homeA.title = "主页";
  homeA.onclick = (e) => { e.preventDefault(); showHome(); };
  el.appendChild(homeA);
  const mk = (label, target, isCur) => {
    const sep = document.createElement("span"); sep.className = "sep"; sep.textContent = "/"; el.appendChild(sep);
    const a = document.createElement("a"); a.href = "#"; a.textContent = label;
    if (isCur) a.className = "cur";
    a.onclick = (e) => { e.preventDefault(); if (isCur) return; openPath(target()); };
    // 作为拖拽放置目标(移动到该目录)
    a.addEventListener("dragover", (ev) => { if (isDraggingItems()) { ev.preventDefault(); a.classList.add("drop-target"); } });
    a.addEventListener("dragleave", () => a.classList.remove("drop-target"));
    a.addEventListener("drop", (ev) => { const vps = getDragVPaths(ev); if (!vps.length) return; ev.preventDefault(); a.classList.remove("drop-target"); doTransfer(vps, target(), ev.ctrlKey); });
    el.appendChild(a);
  };
  mk(lib.name, () => "/" + lib.id, state.curPath === "/" + lib.id);
  const parts = state.curPath.slice(("/" + lib.id).length).split("/").filter(Boolean);
  let acc = "/" + lib.id;
  parts.forEach((p, i) => { acc += "/" + p; const target = acc; mk(p, () => target, i === parts.length - 1); });
}

async function loadDir() {
  const list = $("#file-list");
  list.innerHTML = ""; list.appendChild(emptyState("folder", "加载中…", "", false));
  renderCrumbs();
  try {
    const r = await api("/api/ls?vpath=" + enc(state.curPath));
    state.entries = r.entries || [];
    state.curLib = state.libs.find((l) => l.id === r.lib.id) || r.lib;
    renderCrumbs(); renderEntries();
  } catch (e) {
    list.innerHTML = ""; list.appendChild(emptyState("folder", "无法打开目录", e.message, false));
  }
}

function sortedEntries() {
  const q = state.filter.trim().toLowerCase();
  let list = state.entries;
  if (q) list = list.filter((e) => e.name.toLowerCase().includes(q));
  const tf = state.typeFilter;
  if (tf && tf !== "all") {
    if (tf === "dir") list = list.filter((e) => e.isDir);
    else list = list.filter((e) => !e.isDir && kindOf(e.name) === tf);
  }
  const dir = state.sortDir === "desc" ? -1 : 1;
  const arr = list.slice();
  arr.sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    let r;
    if (state.sort === "size") r = (a.size || 0) - (b.size || 0);
    else if (state.sort === "time") r = (a.modTime || 0) - (b.modTime || 0);
    else r = a.name.localeCompare(b.name, "zh-Hans-CN", { numeric: true, sensitivity: "base" });
    return r * dir;
  });
  return arr;
}

function thumbUrl(vp, w) { return apiUrl("/api/thumb?w=" + (w || 320) + "&vpath=" + enc(vp)); }

/* 角色:admin 全部;user 可读写文件;viewer 只读。
   未启用多用户(免登录访问)时,按管理员处理。 */
function isAdmin() { return !state.auth.multiUser || state.auth.role === "admin"; }
function canWrite() { return !state.auth.multiUser || state.auth.role !== "viewer"; }
function updateRoleUI() {
  const w = canWrite();
  for (const id of ["#btn-upload", "#btn-mkdir", "#btn-sel-move", "#btn-sel-copy", "#btn-sel-cut", "#btn-sel-delete", "#btn-sel-vault"]) {
    const el = $(id);
    if (el) el.hidden = !w;
  }
  const add = $("#btn-add-lib");
  if (add) add.hidden = !isAdmin();
}

function renderEntries() {
  const list = $("#file-list");
  list.className = "file-list" + (state.mode === "grid" ? " grid" : "") + (state.selectMode ? " selmode" : "");
  list.innerHTML = "";
  if (!state.entries.length) { list.appendChild(emptyState("folder", "这个文件夹是空的", "把文件拖进窗口,或点右上角「上传」添加文件。", false)); updateStatusbar(); return; }
  const rows = sortedEntries();
  if (!rows.length) { list.appendChild(emptyState("search", "没有匹配的文件", `当前目录没有包含「${state.filter.trim()}」的文件或文件夹。`, false)); updateStatusbar(); return; }

  for (const e of rows) {
    const vp = state.curPath.replace(/\/$/, "") + "/" + e.name;
    const kind = e.isDir ? "dir" : kindOf(e.name);
    const row = document.createElement("div");
    row.className = "frow" + (e.isDir ? " is-dir" : "");
    row.dataset.vpath = vp;
    if (state.selected.has(vp)) row.classList.add("selected");

    // 多选框
    const chk = document.createElement("div");
    chk.className = "fcheck";
    chk.innerHTML = svgIcon("check");
    chk.onclick = (ev) => { ev.stopPropagation(); toggleSelect(vp); };

    // 图标 / 缩略图
    const iconMap = { dir: "folder", video: "video", image: "image", audio: "music", archive: "archive", file: "file" };
    const icon = document.createElement("div");
    icon.className = "fi t-" + kind;
    if ((kind === "image" || kind === "video")) {
      icon.classList.add("thumb");
      const img = document.createElement("img");
      img.loading = "lazy"; img.decoding = "async"; img.alt = ""; img.draggable = false;
      img.src = thumbUrl(vp, state.mode === "grid" ? 420 : 160);
      img.onerror = () => { icon.classList.remove("thumb"); icon.innerHTML = svgIcon(iconMap[kind]); };
      icon.appendChild(img);
    } else {
      icon.innerHTML = svgIcon(iconMap[kind]);
    }

    const meta = document.createElement("div");
    meta.className = "fmain";
    const name = document.createElement("div");
    name.className = "fname"; name.textContent = e.name;
    const sub = document.createElement("div");
    sub.className = "fsub";
    sub.textContent = (e.isDir ? "文件夹" : fmtSize(e.size)) + (e.modTime ? "  ·  " + fmtTime(e.modTime) : "");
    meta.appendChild(name); meta.appendChild(sub);

    const acts = document.createElement("div");
    acts.className = "facts";
    const addBtn = (iconName, title, fn, extra) => {
      const b = document.createElement("button");
      b.className = "btn icon-btn " + (extra || "");
      b.title = title; b.setAttribute("aria-label", title); b.innerHTML = svgIcon(iconName);
      b.onclick = (ev) => { ev.stopPropagation(); fn(); };
      acts.appendChild(b);
    };
    if (!e.isDir) {
      const kv = kindOf(e.name);
      if (kv === "video" || kv === "audio" || kv === "image") addBtn("play", "预览", () => openPreview(kv, vp, e.name));
      addBtn("download", "下载", () => { location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(vp)); });
      if (canWrite()) addBtn("lock", "设为私密(存入保险箱)", () => setPrivate(vp));
    }
    if (canWrite()) addBtn("link", "分享链接", () => openShareDialog(vp, e.name));
    addBtn("info", "详情", () => showInfo(vp));
    if (canWrite()) {
      addBtn("edit", "改名", () => renameEntry(vp, e.name));
      addBtn("trash", "删除", () => deleteEntry(vp, e), "del");
    }

    // 「不在媒体库展示」开关：文件夹或媒体文件且可写时显示
    if ((e.isDir || kind === "video" || kind === "image" || kind === "audio") && canWrite()) {
      const mhBtn = document.createElement("button");
      mhBtn.className = "btn icon-btn";
      const syncMh = () => {
        const hid = !!e.mediaHidden;
        mhBtn.classList.toggle("media-hidden", hid);
        mhBtn.title = hid ? "已隐藏于媒体库（点击恢复显示）" : "不在媒体库展示（文件浏览中仍可见）";
        mhBtn.setAttribute("aria-label", mhBtn.title);
        mhBtn.innerHTML = svgIcon(hid ? "eye-off" : "eye");
        row.classList.toggle("media-hidden-row", hid);
      };
      syncMh();
      mhBtn.onclick = async (ev) => {
        ev.stopPropagation();
        const next = !e.mediaHidden;
        try {
          await api("/api/libs/media-hide", { method: "POST", body: { vpath: vp, hidden: next } });
          e.mediaHidden = next;
          syncMh();
          toast(next ? "已设为不在媒体库展示" : "已显示于媒体库");
        } catch (err) { toast("失败: " + err.message); }
      };
      acts.appendChild(mhBtn);
    }

    row.appendChild(chk); row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);

    const open = () => {
      if (state.selectMode) { toggleSelect(vp); return; }
      if (e.isDir) { openPath(vp); return; }
      const kv = kindOf(e.name);
      if (kv === "video" || kv === "audio" || kv === "image") openPreview(kv, vp, e.name);
      else location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(vp));
    };
    if (e.isDir || state.mode === "grid") row.onclick = open;
    else row.addEventListener("dblclick", open);

    // 拖拽:把文件/文件夹拖到同目录(或任意)文件夹上即移动进去
    row.draggable = true;
    row.addEventListener("dragstart", (ev) => {
      dragVPaths = (state.selected.size > 0 && state.selected.has(vp)) ? Array.from(state.selected) : [vp];
      if (ev.dataTransfer) {
        try { ev.dataTransfer.setData("application/x-linnas-drag", JSON.stringify(dragVPaths)); } catch (e) {}
        try { ev.dataTransfer.setData("text/plain", dragVPaths.join("\n")); } catch (e) {}
        ev.dataTransfer.effectAllowed = "move";
      }
      row.classList.add("dragging");
    });
    row.addEventListener("dragend", () => { row.classList.remove("dragging"); $$(".drop-target").forEach((x) => x.classList.remove("drop-target")); setTimeout(() => { dragVPaths = []; }, 400); });
    if (e.isDir) {
      row.addEventListener("dragover", (ev) => {
        if (!isDraggingItems()) return;
        ev.preventDefault();
        if (ev.dataTransfer) ev.dataTransfer.dropEffect = "move";
        row.classList.add("drop-target");
      });
      row.addEventListener("dragleave", (ev) => { if (!row.contains(ev.relatedTarget)) row.classList.remove("drop-target"); });
      row.addEventListener("drop", (ev) => {
        const vps = getDragVPaths(ev).filter((p) => p !== vp && !p.startsWith(vp + "/"));
        ev.preventDefault(); ev.stopPropagation(); row.classList.remove("drop-target");
        if (vps.length) doTransfer(vps, vp, ev.ctrlKey);
      });
    }
    list.appendChild(row);
  }
  updateStatusbar();
}

/* ============================================================ 多选 + 操作 */
function toggleSelect(vp) {
  if (state.selected.has(vp)) state.selected.delete(vp); else state.selected.add(vp);
  const row = $(`.frow[data-vpath="${cssEscape(vp)}"]`);
  if (row) row.classList.toggle("selected", state.selected.has(vp));
  updateSelBar();
}
function cssEscape(s) { return s.replace(/["\\]/g, "\\$&"); }
function clearSelection() { state.selected.clear(); state.selectMode = false; $$(".frow.selected").forEach((r) => r.classList.remove("selected")); $("#file-list").classList.remove("selmode"); $("#btn-select").classList.remove("primary"); updateSelBar(); }
function updateSelBar() {
  const n = state.selected.size;
  $("#selbar").hidden = n === 0;
  $("#sel-count").textContent = String(n);
  updateStatusbar();
}
function updatePasteBar() {
  const c = state.clipboard;
  $("#pastebar").hidden = !c || !c.items.length || state.view !== "browse";
  if (c && c.items.length) {
    $("#paste-count").textContent = String(c.items.length);
    $("#paste-mode").textContent = c.mode === "copy" ? "(复制)" : "(剪切)";
  }
}

/* 底部状态栏 */
function updateStatusbar() {
  const el = $("#stat-info"); if (!el) return;
  if (state.view !== "browse" || !state.curLib) { el.textContent = ""; return; }
  const rows = sortedEntries();
  const files = rows.filter((e) => !e.isDir);
  let bytes = 0; files.forEach((e) => { bytes += e.size || 0; });
  const dirs = rows.length - files.length;
  const nSel = state.selected.size;
  el.innerHTML = `共 <b>${rows.length}</b> 项(文件夹 ${dirs} · 文件 ${files.length}) · 文件合计 <b>${fmtSize(bytes)}</b>` + (nSel ? ` · 已选 <b>${nSel}</b> 项` : "");
}

/* 操作进度面板 + 批量执行 */
let opCanceled = false;
function showOp(title, sub) {
  $("#op-panel").hidden = false;
  $("#op-title").textContent = title;
  $("#op-sub").textContent = sub || "";
  const bar = $("#op-bar"); bar.parentElement.classList.remove("indet"); bar.style.width = "0%";
  $("#op-cancel").disabled = false;
}
function setOpIndeterminate(on) {
  const bar = $("#op-bar");
  if (on) { bar.parentElement.classList.add("indet"); }
  else { bar.parentElement.classList.remove("indet"); bar.style.width = "0%"; }
}
function setOp(pct, sub) {
  $("#op-bar").style.width = Math.max(0, Math.min(100, pct)) + "%";
  if (sub !== undefined) $("#op-sub").textContent = sub;
}
function hideOp() { $("#op-panel").hidden = true; }
$("#op-cancel").onclick = () => { opCanceled = true; $("#op-cancel").disabled = true; $("#op-sub").textContent = "正在取消…"; };
async function runBatch(title, items, worker) {
  opCanceled = false;
  showOp(title, `0 / ${items.length}`);
  let done = 0, errs = 0;
  for (const it of items) {
    if (opCanceled) break;
    try { await worker(it); } catch (e) { errs++; }
    done++;
    setOp(items.length ? (done / items.length) * 100 : 100, `${done} / ${items.length}` + (errs ? ` · ${errs} 项失败` : ""));
  }
  const canceled = opCanceled;
  hideOp();
  return { done, errs, canceled };
}
function entryIsDir(vp) {
  const base = state.curPath.replace(/\/$/, "");
  const en = state.entries.find((x) => base + "/" + x.name === vp);
  return en ? en.isDir : false;
}
async function doTransfer(vps, dest, copy) {
  if (!vps || !vps.length) return;
  const items = vps.map((vp) => ({ vpath: vp }));
  const label = copy ? "复制" : "移动";
  const destName = dest.split("/").filter(Boolean).pop() || dest;
  const endpoint = copy ? "/api/copy" : "/api/move";
  if (items.length === 1) {
    opCanceled = false;
    showOp(`正在${label} → ${destName}`, entryIsDir(vps[0]) ? "文件夹较大时可能需要一会儿…" : "处理中…");
    setOpIndeterminate(true);
    try {
      const r = await api(endpoint, { method: "POST", body: { vpaths: vps, dest } });
      toast(r.errors && r.errors.length ? `${label}完成,${r.errors.length} 项失败` : `已${label}到「${destName}」`);
    } catch (e) { toast("失败: " + e.message); }
    setOpIndeterminate(false); hideOp();
  } else {
    const res = await runBatch(`正在${label} ${items.length} 项 → ${destName}`, items, async (it) => {
      const r = await api(endpoint, { method: "POST", body: { vpaths: [it.vpath], dest } });
      if (r.errors && r.errors.length) throw new Error(r.errors[0]);
    });
    toast(res.canceled ? `已取消,完成 ${res.done} 项` : res.errs ? `完成 ${res.done} 项,${res.errs} 项失败` : `已${label} ${res.done} 项`);
  }
  if (state.view === "browse") loadDir();
}
function selectAll() {
  state.selectMode = true;
  $("#btn-select").classList.add("primary");
  $("#file-list").classList.add("selmode");
  state.selected.clear();
  const base = state.curPath.replace(/\/$/, "");
  sortedEntries().forEach((e) => state.selected.add(base + "/" + e.name));
  $$(".frow").forEach((r) => { if (state.selected.has(r.dataset.vpath)) r.classList.add("selected"); });
  updateSelBar();
}

$("#btn-select").onclick = () => {
  state.selectMode = !state.selectMode;
  $("#btn-select").classList.toggle("primary", state.selectMode);
  $("#file-list").classList.toggle("selmode", state.selectMode);
  if (!state.selectMode) state.selected.clear(), $$(".frow.selected").forEach((r) => r.classList.remove("selected"));
  updateSelBar();
};

$("#btn-sel-cancel").onclick = () => { state.selectMode = false; $("#btn-select").classList.remove("primary"); clearSelection(); };

$("#btn-sel-delete").onclick = async () => {
  const vps = Array.from(state.selected);
  if (!vps.length) return;
  const ok = await askConfirm("删除", `确定删除选中的 ${vps.length} 项?(文件夹将递归删除,可在回收站恢复)`);
  if (!ok) return;
  clearSelection();
  const res = await runBatch(`正在删除 ${vps.length} 项`, vps.map((vp) => ({ vpath: vp })), async (it) => {
    await api("/api/delete", { method: "POST", body: { vpath: it.vpath, recursive: true } });
  });
  toast(res.canceled ? `已取消,删除 ${res.done} 项` : res.errs ? `删除 ${res.done} 项,${res.errs} 项失败` : `已删除 ${res.done} 项`);
  loadDir();
};

$("#btn-sel-move").onclick = () => {
  const vps = Array.from(state.selected);
  if (!vps.length) return;
  openFolderPicker(`移动 ${vps.length} 项到…`, async (dest) => {
    clearSelection();
    await doTransfer(vps, dest, false);
  }, state.curPath);
};

$("#btn-sel-copy").onclick = () => { setClipboard("copy"); };
$("#btn-sel-cut").onclick = () => { setClipboard("cut"); };

/* 打包下载:选中项 / 当前文件夹 */
function zipFileName(vpath) {
  const base = (vpath || "").split("/").filter(Boolean).pop() || "linNas";
  return base + ".zip";
}
$("#btn-sel-zip").onclick = () => {
  const vps = Array.from(state.selected);
  if (!vps.length) return;
  location.href = apiUrl("/api/zip?vpaths=" + encodeURIComponent(vps.join(",")) + "&name=" + encodeURIComponent(zipFileName(state.curPath)));
};
$("#btn-zip-dir").onclick = () => {
  if (!state.curPath) return;
  location.href = apiUrl("/api/zip?vpaths=" + encodeURIComponent(state.curPath) + "&name=" + encodeURIComponent(zipFileName(state.curPath)));
};
function setClipboard(mode) {
  const items = Array.from(state.selected).map((vp) => ({ vpath: vp, name: vp.split("/").pop(), isDir: false }));
  state.clipboard = { mode, items };
  clearSelection(); updatePasteBar();
  toast(`已${mode === "copy" ? "复制" : "剪切"} ${items.length} 项,到目标文件夹点「粘贴」`);
}
$("#btn-paste").onclick = async () => {
  const c = state.clipboard;
  if (!c || !c.items.length) return;
  const vps = c.items.map((i) => i.vpath);
  const copy = c.mode === "copy";
  state.clipboard = null; updatePasteBar();
  await doTransfer(vps, state.curPath, copy);
};
$("#btn-paste-cancel").onclick = () => { state.clipboard = null; updatePasteBar(); };

/* 拖拽辅助 */
let dragVPaths = [];
function isDraggingItems() { return dragVPaths.length > 0; }
function getDragVPaths(ev) {
  if (dragVPaths.length) return dragVPaths.slice();
  try { return JSON.parse(ev.dataTransfer.getData("application/x-linnas-drag") || "[]"); } catch (e) { return []; }
}
async function doMove(vps, dest) { return doTransfer(vps, dest, false); }

/* ============================================================ 改名/删除 */
async function renameEntry(vp, name) {
  const next = await askInput("重命名", name, "新名称");
  if (!next || next === name) return;
  try { await api("/api/rename", { method: "POST", body: { vpath: vp, newName: next } }); loadDir(); toast("已重命名"); }
  catch (e) { toast("失败: " + e.message); }
}
async function deleteEntry(vp, e) {
  const ok = await askConfirm("删除" + (e.isDir ? "文件夹" : "文件"), e.isDir ? `将递归删除文件夹「${e.name}」及其全部内容,不可恢复。` : `将删除「${e.name}」,不可恢复。`);
  if (!ok) return;
  try { await api("/api/delete", { method: "POST", body: { vpath: vp, recursive: true } }); loadDir(); toast("已删除"); }
  catch (err) { toast("失败: " + err.message); }
}

/* ============================================================ 预览 / 播放器 */
let curPlay = { key: "", kind: "", el: null, vp: "" };
state.playlist = state.playlist || [];
state.playIndex = -1;
state.subTracks = [];
state.subIdx = -1;
state.playlistOpen = false;

function srcForItem(it) {
  return it.kind === "video" ? apiUrl("/api/stream?vpath=" + enc(it.vp)) : apiUrl("/api/raw?vpath=" + enc(it.vp));
}

function buildPlaylist(kind, vp) {
  state.playlist = [];
  state.playlistOpen = false;
  // 视频/音频混合成播放列表;图片单独成组,支持同组内上一张/下一张。
  if (kind === "video" || kind === "audio" || kind === "image") {
    const match = kind === "image"
      ? (k) => k === "image"
      : (k) => k === "video" || k === "audio";
    let items = [];
    if (state.view === "media") {
      items = state.mediaItems.filter((x) => match(x.kind)).map((x) => ({ kind: x.kind, vp: x.path, name: x.name }));
    } else if (state.view === "search") {
      items = state.searchResults.filter((x) => match(x.kind)).map((x) => ({ kind: x.kind, vp: x.path, name: x.name }));
    } else {
      const base = state.curPath.replace(/\/$/, "");
      items = state.entries.filter((e) => !e.isDir).map((e) => ({ kind: kindOf(e.name), vp: base + "/" + e.name, name: e.name })).filter((x) => match(x.kind));
    }
    if (items.length) {
      const i = items.findIndex((x) => x.vp === vp);
      state.playlist = i >= 0 ? items : [{ kind, vp, name: vp.split("/").pop() }];
    }
  }
  if (!state.playlist.length) state.playlist = [{ kind, vp, name: vp.split("/").pop() }];
}

function openPreview(kind, vp, name) {
  buildPlaylist(kind, vp);
  let idx = state.playlist.findIndex((x) => x.vp === vp);
  if (idx < 0) idx = 0;
  playItem(idx, name);
}

function playItem(idx, overrideName) {
  const it = state.playlist[idx];
  if (!it) return;
  state.playIndex = idx;
  openPreviewSrc(it.kind, srcForItem(it), overrideName || it.name, "vp:" + it.vp, it.vp);
}

let playbackLastPost = 0;
// 跨设备播放进度：服务端只接受虚拟路径（/libN/...），本地/离线播放不参与同步。
async function fetchPlayback(vp) {
  if (!vp || !String(vp).startsWith("/")) return null;
  try { return await api("/api/playback?vpath=" + enc(vp)); } catch (e) { return null; }
}
function postPlayback(vp, position, duration, force) {
  if (!vp || !String(vp).startsWith("/") || !(duration > 0) || !(position >= 0)) return;
  const now = Date.now();
  if (!force && now - playbackLastPost < 10000) return;
  playbackLastPost = now;
  api("/api/playback", { method: "POST", body: { vpath: vp, position, duration } }).catch(() => {});
}

function openPreviewSrc(kind, src, name, key, vp) {
  if (!vp) { state.playlist = [{ kind, vp: "", name }]; state.playIndex = 0; state.playlistOpen = false; }
  const modal = $("#player-modal");
  const v = $("#player-video"), a = $("#player-audio"), img = $("#player-img");
  v.pause(); a.pause();
  v.removeAttribute("src"); a.removeAttribute("src"); img.removeAttribute("src");
  v.querySelectorAll("track").forEach((t) => t.remove());
  v.hidden = true; a.hidden = true; img.hidden = true;
  curPlay = { key: key || src, kind, el: null, vp: vp || "" };
  if (kind === "video") { v.src = src; v.hidden = false; curPlay.el = v; }
  else if (kind === "audio") { a.src = src; a.hidden = false; curPlay.el = a; }
  else { img.src = src; img.hidden = false; }
  const n = state.playlist.length;
  $("#player-title").textContent = name + (n > 1 && state.playIndex >= 0 ? "  (" + (state.playIndex + 1) + "/" + n + ")" : "");
  $("#player-speed").textContent = "1×";
  if (kind === "video") v.playbackRate = 1;
  const isVid = kind === "video";
  $("#player-speed").hidden = !isVid; // 播放速度/画中画只对视频有意义
  $("#player-pip").hidden = !isVid;
  modal.hidden = false;
  renderPlaylist(); applyPlaylistVisibility();
  if (kind === "video") { loadSubtitles(v); }
  else { $("#player-sub").hidden = true; state.subTracks = []; state.subIdx = -1; }
  if (kind === "video" || kind === "audio") {
    const el = curPlay.el;
    const vp = curPlay.vp;
    el.onloadedmetadata = () => {
      const local = parseFloat(localStorage.getItem("linnas_pos:" + curPlay.key) || "0");
      const apply = (saved) => {
        if (saved > 3 && isFinite(el.duration) && saved < el.duration - 3) { try { el.currentTime = saved; } catch (e) {} }
        el.play().catch(() => {});
      };
      if (!vp) { apply(local); return; }
      fetchPlayback(vp).then((srv) => {
        const remote = srv && srv.found ? Number(srv.position) || 0 : 0;
        apply(remote > local ? remote : local);
      }).catch(() => apply(local));
    };
    el.ontimeupdate = () => {
      if (!isFinite(el.currentTime) || el.currentTime <= 0) return;
      try { localStorage.setItem("linnas_pos:" + curPlay.key, String(el.currentTime)); } catch (e) {}
      postPlayback(vp, el.currentTime, el.duration);
    };
    el.onpause = () => postPlayback(vp, el.currentTime, el.duration, true);
    el.onended = () => {
      postPlayback(vp, 0, el.duration, true);
      if (state.playIndex >= 0 && state.playIndex < state.playlist.length - 1) playItem(state.playIndex + 1);
    };
  }
}

async function loadSubtitles(v) {
  const subBtn = $("#player-sub");
  state.subTracks = []; state.subIdx = -1;
  subBtn.hidden = true; subBtn.classList.add("off"); subBtn.textContent = "字幕";
  v.querySelectorAll("track").forEach((t) => t.remove());
  if (!curPlay.vp) return;
  try {
    const tracks = await api("/api/subtitle?vpath=" + enc(curPlay.vp));
    if (!tracks || !tracks.length) return;
    state.subTracks = tracks;
    tracks.forEach((tr, i) => {
      const t = document.createElement("track");
      t.kind = "subtitles"; t.label = tr.label || "字幕"; if (tr.lang) t.srclang = tr.lang;
      t.src = apiUrl(tr.url);
      v.appendChild(t);
    });
    subBtn.hidden = false;
    setSubtitle(0);
  } catch (e) {}
}

function setSubtitle(i) {
  const v = $("#player-video");
  const tracks = v.querySelectorAll("track");
  const btn = $("#player-sub");
  state.subIdx = i;
  if (i < 0) {
    tracks.forEach((t) => { t.track.mode = "disabled"; });
    btn.classList.add("off"); btn.textContent = "字幕";
    return;
  }
  tracks.forEach((t, idx) => { t.track.mode = idx === i ? "showing" : "disabled"; });
  btn.classList.remove("off");
  btn.textContent = (state.subTracks[i] && (state.subTracks[i].label || state.subTracks[i].lang)) || "字幕";
}

function renderPlaylist() {
  const panel = $("#player-playlist");
  panel.innerHTML = "";
  if (state.playlist.length < 2) return;
  state.playlist.forEach((it, i) => {
    const d = document.createElement("div");
    d.className = "pl-item" + (i === state.playIndex ? " on" : "");
    const idx = document.createElement("span"); idx.className = "pl-idx"; idx.textContent = String(i + 1);
    const nm = document.createElement("span"); nm.className = "pl-name"; nm.textContent = it.name;
    d.appendChild(idx); d.appendChild(nm);
    d.onclick = () => playItem(i);
    panel.appendChild(d);
  });
}
function applyPlaylistVisibility() {
  const panel = $("#player-playlist");
  panel.hidden = !(state.playlistOpen && state.playlist.length > 1);
}
$("#player-playlist").onclick = () => { state.playlistOpen = !state.playlistOpen; applyPlaylistVisibility(); };
$("#player-sub").onclick = () => {
  const n = state.subTracks.length; if (!n) return;
  let i = state.subIdx + 1; if (i >= n) i = -1;
  setSubtitle(i);
};
$("#player-prev").onclick = () => { if (state.playIndex > 0) playItem(state.playIndex - 1); };
$("#player-next").onclick = () => { if (state.playIndex >= 0 && state.playIndex < state.playlist.length - 1) playItem(state.playIndex + 1); };

function savePos() {
  const el = curPlay.el;
  if (el && el.currentTime > 0) { try { localStorage.setItem("linnas_pos:" + curPlay.key, String(el.currentTime)); } catch (e) {} }
}

function closePreview() {
  savePos();
  if (curPlay.el) postPlayback(curPlay.vp, curPlay.el.currentTime || 0, curPlay.el.duration || 0, true);
  const v = $("#player-video"), a = $("#player-audio"), img = $("#player-img");
  v.pause(); a.pause();
  v.onloadedmetadata = null; v.ontimeupdate = null; a.onloadedmetadata = null; a.ontimeupdate = null;
  v.onended = null; a.onended = null;
  v.querySelectorAll("track").forEach((t) => t.remove());
  v.removeAttribute("src"); a.removeAttribute("src"); img.removeAttribute("src");
  if (v.load) v.load(); if (a.load) a.load();
  $("#player-modal").hidden = true;
  $("#player-playlist").hidden = true; state.playlistOpen = false; state.subIdx = -1;
  curPlay = { key: "", kind: "", el: null, vp: "" };
}

$("#player-close").onclick = closePreview;
$("#player-modal").onclick = (e) => { if (e.target === $("#player-modal")) closePreview(); };

const SPEEDS = [0.75, 1, 1.25, 1.5, 2];
$("#player-speed").onclick = () => {
  const v = $("#player-video");
  if (v.hidden) return;
  const cur = v.playbackRate || 1;
  const i = SPEEDS.findIndex((x) => Math.abs(x - cur) < 0.01);
  const next = SPEEDS[(i + 1) % SPEEDS.length];
  v.playbackRate = next;
  $("#player-speed").textContent = next + "×";
};
$("#player-pip").onclick = async () => {
  const v = $("#player-video");
  if (v.hidden) return;
  try { if (document.pictureInPictureElement) await document.exitPictureInPicture(); else await v.requestPictureInPicture(); }
  catch (e) { toast("画中画不可用"); }
};
$("#player-full").onclick = () => {
  const v = $("#player-video");
  const target = (!v.hidden) ? v : $("#player-modal");
  try { if (document.fullscreenElement) document.exitFullscreen(); else target.requestFullscreen(); } catch (e) {}
};
document.addEventListener("keydown", (e) => {
  const modal = $("#player-modal");
  if (modal.hidden) return;
  const v = $("#player-video");
  if (e.key === "Escape") { closePreview(); return; }
  if (v.hidden) {
    // 图片预览:左右方向键切换上一张 / 下一张
    const img = $("#player-img");
    if (img && !img.hidden) {
      if (e.key === "ArrowLeft" && state.playIndex > 0) { e.preventDefault(); playItem(state.playIndex - 1); }
      else if (e.key === "ArrowRight" && state.playIndex >= 0 && state.playIndex < state.playlist.length - 1) { e.preventDefault(); playItem(state.playIndex + 1); }
    }
    return;
  }
  if (e.code === "Space") { e.preventDefault(); v.paused ? v.play().catch(() => {}) : v.pause(); }
  else if (e.key === "ArrowLeft") { v.currentTime = Math.max(0, v.currentTime - 5); }
  else if (e.key === "ArrowRight") { v.currentTime = Math.min(v.duration || 1e9, v.currentTime + 5); }
  else if (e.key === "m" || e.key === "M") { v.muted = !v.muted; }
});

/* 全局搜索快捷键:Ctrl/⌘+K 或 / 随时唤起搜索 */
document.addEventListener("keydown", (e) => {
  if (!$("#player-modal").hidden) return;
  const t = e.target;
  const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
  if (typing) return;
  const mod = e.ctrlKey || e.metaKey;
  if (mod && (e.key === "k" || e.key === "K")) { e.preventDefault(); $("#btn-search").click(); }
  else if (e.key === "/" && !document.querySelector("dialog[open]")) { e.preventDefault(); $("#btn-search").click(); }
});

/* 全局快捷键(仅在文件浏览视图、且无播放器/对话框/输入框聚焦时生效) */
document.addEventListener("keydown", (e) => {
  if (!$("#player-modal").hidden) return;
  if (document.querySelector("dialog[open]")) return;
  const t = e.target;
  const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
  if (typing) { if (e.key === "Escape") t.blur(); return; }
  if (state.view !== "browse") return;
  const mod = e.ctrlKey || e.metaKey;
  const findEntry = (vp) => state.entries.find((x) => state.curPath.replace(/\/$/, "") + "/" + x.name === vp);
  if (e.key === "Delete" || e.key === "Backspace") {
    if (state.selected.size) { e.preventDefault(); $("#btn-sel-delete").click(); }
  } else if (e.key === "F2") {
    if (state.selected.size === 1) {
      e.preventDefault();
      const vp = Array.from(state.selected)[0]; const en = findEntry(vp);
      if (en) renameEntry(vp, en.name);
    }
  } else if (mod && (e.key === "c" || e.key === "C")) {
    if (state.selected.size) { e.preventDefault(); setClipboard("copy"); }
  } else if (mod && (e.key === "x" || e.key === "X")) {
    if (state.selected.size) { e.preventDefault(); setClipboard("cut"); }
  } else if (mod && (e.key === "v" || e.key === "V")) {
    if (state.clipboard) { e.preventDefault(); $("#btn-paste").click(); }
  } else if (mod && (e.key === "a" || e.key === "A")) {
    e.preventDefault(); selectAll();
  } else if (e.key === "Enter") {
    const vps = Array.from(state.selected);
    if (vps.length === 1) {
      e.preventDefault();
      const vp = vps[0]; const en = findEntry(vp);
      if (en) {
        if (en.isDir) openPath(vp);
        else { const kv = kindOf(en.name); if (kv === "video" || kv === "audio" || kv === "image") openPreview(kv, vp, en.name); else location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(vp)); }
      }
    }
  } else if (e.key === "Escape") {
    clearSelection();
  }
});

/* ============================================================ 工具栏 */
$("#btn-viewmode").onclick = () => {
  state.mode = state.mode === "grid" ? "list" : "grid";
  localStorage.setItem("linnas_mode", state.mode);
  $("#btn-viewmode").innerHTML = svgIcon(state.mode === "grid" ? "list" : "grid");
  $("#btn-viewmode").title = state.mode === "grid" ? "切换为列表" : "切换为网格";
  if (state.entries.length) renderEntries();
};
$("#in-search").oninput = (e) => { state.filter = e.target.value; renderEntries(); };
$("#sel-sort").value = state.sort;
$("#sel-sort").onchange = (e) => { state.sort = e.target.value; localStorage.setItem("linnas_sort", state.sort); renderEntries(); };
$("#sel-type").value = state.typeFilter;
$("#sel-type").onchange = (e) => { state.typeFilter = e.target.value; localStorage.setItem("linnas_type", state.typeFilter); renderEntries(); };
function sortDirIcon() {
  const b = $("#btn-sortdir"); if (!b) return;
  b.innerHTML = svgIcon(state.sortDir === "desc" ? "arrow-down" : "arrow-up");
  b.title = state.sortDir === "desc" ? "当前:降序(点击升序)" : "当前:升序(点击降序)";
}
$("#btn-sortdir").onclick = () => {
  state.sortDir = state.sortDir === "desc" ? "asc" : "desc";
  localStorage.setItem("linnas_sortdir", state.sortDir);
  sortDirIcon(); renderEntries();
};
$("#btn-dirsize").onclick = async () => {
  if (!state.curLib) { toast("先打开一个存储库"); return; }
  const btn = $("#btn-dirsize"); const old = btn.textContent;
  btn.disabled = true; btn.textContent = "计算中…";
  try {
    const r = await api("/api/size?vpath=" + enc(state.curPath));
    $("#stat-info").innerHTML = `本目录合计 <b>${fmtSize(r.bytes)}</b> · 文件 <b>${r.files}</b> 个 · 文件夹 <b>${r.dirs}</b> 个`;
    toast(`本目录共 ${r.files} 个文件、${r.dirs} 个文件夹,合计 ${fmtSize(r.bytes)}`);
  } catch (e) { toast("失败: " + e.message); }
  finally { btn.disabled = false; btn.textContent = old; }
};
$("#btn-back").onclick = () => {
  if (!state.curLib) { showHome(); return; }
  const root = "/" + state.curLib.id;
  if (state.curPath === root || state.curPath === "/") { showHome(); return; }
  const i = state.curPath.lastIndexOf("/");
  openPath(state.curPath.slice(0, i) || root);
};
$("#btn-brand").onclick = showOverview;
$("#btn-mkdir").onclick = async () => {
  if (!state.curLib) { toast("先打开一个存储库"); return; }
  const name = await askInput("新建文件夹", "", "文件夹名称");
  if (!name) return;
  try { await api("/api/mkdir", { method: "POST", body: { vpath: state.curPath.replace(/\/$/, "") + "/" + name } }); loadDir(); toast("已创建"); }
  catch (e) { toast("失败: " + e.message); }
};

/* ============================================================ 上传(分块+断点续传,支持并发/暂停/取消/重试) */
const CHUNK = 5 * 1024 * 1024;
const MAX_PARALLEL = 3;   // 同时上传的文件数
const RETRY_LIMIT = 3;   // 单个文件最多自动重试次数

$("#btn-upload").onclick = () => { if (!state.curLib) { toast("先打开一个存储库"); return; } $("#file-input").click(); };
$("#file-input").onchange = (e) => { const files = Array.from(e.target.files || []); e.target.value = ""; files.forEach(enqueueUpload); };
function enqueueUpload(file) {
  if (state.view !== "browse" || !state.curLib) { toast("请先打开一个存储库再上传"); return; }
  const task = {
    file, id: null, sent: 0, size: file.size, state: "排队中",
    speed: 0, lastT: performance.now(), lastB: 0,
    dir: state.curPath, paused: false, canceled: false, retries: 0, ctrl: null,
  };
  state.uploading.push(task);
  state.upHidden = false;
  renderUploads();
  pumpUploads();
}
// pumpUploads 启动排队中的任务(最多 MAX_PARALLEL 个并行)
function pumpUploads() {
  const running = state.uploading.filter((t) => t.state === "上传中" || t.state === "准备中").length;
  let free = MAX_PARALLEL - running;
  for (const t of state.uploading) {
    if (free <= 0) break;
    if (t.state === "排队中" && !t.paused && !t.canceled) { free--; runUpload(t, t.dir); }
  }
}
async function runUpload(task, dir) {
  task.ctrl = new AbortController();
  try {
    task.state = "准备中"; renderUploads();
    const init = await api("/api/upload/init", { method: "POST", body: { vpath: dir, name: task.file.name, size: task.file.size } });
    task.id = init.uploadId; task.sent = init.received || 0;
    const chunkSize = init.chunkSize || CHUNK;
    task.state = "上传中"; task.lastT = performance.now(); task.lastB = task.sent; renderUploads();
    while (task.sent < task.file.size) {
      if (task.canceled) { task.state = "已取消"; renderUploads(); return; }
      if (task.paused) { task.state = "已暂停"; renderUploads(); await waitResume(task); continue; }
      const start = task.sent, end = Math.min(start + chunkSize, task.file.size);
      const blob = task.file.slice(start, end);
      const res = await fetch(apiUrl(`/api/upload/chunk?id=${enc(task.id)}&offset=${start}`), {
        method: "POST", body: blob, signal: task.ctrl.signal,
      });
      if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d.error || `HTTP ${res.status}`); }
      const d = await res.json(); task.sent = d.received;
      const now = performance.now(), dt = (now - task.lastT) / 1000;
      if (dt > 0.25) { task.speed = (task.sent - task.lastB) / dt; task.lastT = now; task.lastB = task.sent; }
      renderUploads();
    }
    await api("/api/upload/complete", { method: "POST", body: { uploadId: task.id } });
    task.state = "完成"; task.speed = 0; renderUploads();
    if (state.view === "browse" && state.curPath === dir) loadDir();
  } catch (e) {
    if (task.canceled) { task.state = "已取消"; abortUpload(task); }
    else if (e.name === "AbortError") task.state = task.paused ? "已暂停" : "已取消";
    else {
      task.retries++;
      if (task.retries <= RETRY_LIMIT) {
        task.state = `失败(${task.retries}/${RETRY_LIMIT} 自动重试中): ${e.message}`;
        task.sent = 0; task.id = null;
        renderUploads();
        setTimeout(() => { if (task.canceled) return; task.state = "排队中"; renderUploads(); pumpUploads(); }, 1000 * task.retries);
      } else {
        task.state = "失败: " + e.message;
      }
    }
    renderUploads();
  } finally {
    task.ctrl = null;
    pumpUploads();
  }
}
function abortUpload(task) {
  if (!task.id) return;
  const id = task.id;
  task.id = null;
  api("/api/upload/abort", { method: "POST", body: { uploadId: id } }).catch(() => {});
}
function waitResume(task) {
  return new Promise((resolve) => {
    const iv = setInterval(() => {
      if (!task.paused || task.canceled) { clearInterval(iv); resolve(); }
    }, 200);
  });
}
function renderUploads() {
  const panel = $("#up-panel"), list = $("#up-list");
  const active = state.uploading.filter((t) => t.state !== "完成" && !t.state.startsWith("失败") && t.state !== "已取消").length;
  $("#up-count").textContent = state.uploading.length ? `${active}/${state.uploading.length}` : "";
  panel.hidden = state.upHidden || !state.uploading.length;
  list.innerHTML = "";
  for (const t of state.uploading) {
    const pct = t.size ? Math.min(100, Math.round((t.sent / t.size) * 100)) : 0;
    const div = document.createElement("div");
    div.className = "up-item";
    if (t.state === "完成") div.classList.add("done");
    else if (t.state.startsWith("失败")) div.classList.add("failed");
    const speedTxt = t.speed > 0 ? " · " + fmtSize(t.speed) + "/s" : "";
    div.innerHTML = `<div class="un"></div>
      <div class="us"><span class="state"></span><span class="prog"></span></div>
      <div class="bar"><i style="width:${pct}%"></i></div>
      <div class="uacts"></div>`;
    div.querySelector(".un").textContent = t.file.name;
    div.querySelector(".state").textContent = t.state + speedTxt;
    div.querySelector(".prog").textContent = `${fmtSize(t.sent)} / ${fmtSize(t.size)}`;
    const acts = div.querySelector(".uacts");
    const ab = (label, fn, cls) => { const b = document.createElement("button"); b.className = "btn uact " + (cls || ""); b.textContent = label; b.onclick = fn; acts.appendChild(b); };
    if (t.state === "上传中" || t.state === "准备中") {
      ab("暂停", () => { t.paused = true; if (t.ctrl) t.ctrl.abort(); renderUploads(); });
      ab("取消", () => { t.canceled = true; t.paused = false; if (t.ctrl) t.ctrl.abort(); abortUpload(t); renderUploads(); }, "u-cancel");
    } else if (t.state === "已暂停") {
      ab("继续", () => { t.paused = false; t.state = "上传中"; runUpload(t, t.dir); renderUploads(); });
      ab("取消", () => { t.canceled = true; t.state = "已取消"; abortUpload(t); renderUploads(); }, "u-cancel");
    } else if (t.state === "排队中") {
      ab("取消", () => { t.canceled = true; t.state = "已取消"; renderUploads(); }, "u-cancel");
    } else if (t.state.startsWith("失败") && t.retries > RETRY_LIMIT) {
      ab("重试", () => { t.retries = 0; t.sent = 0; t.id = null; t.state = "排队中"; renderUploads(); pumpUploads(); });
      ab("移除", () => { state.uploading = state.uploading.filter((x) => x !== t); renderUploads(); }, "u-cancel");
    }
    list.appendChild(div);
  }
}
$("#up-clear").onclick = () => {
  state.uploading = state.uploading.filter((t) => t.state !== "完成" && !t.state.startsWith("失败") && t.state !== "已取消");
  renderUploads();
};
$("#up-close").onclick = () => { state.upHidden = true; renderUploads(); };

/* 外部文件拖拽上传 */
let dragDepth = 0;
const isFileDrag = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes("Files");
window.addEventListener("dragenter", (e) => {
  if (!isFileDrag(e)) return;
  dragDepth++;
  if (state.view === "browse") { $("#drop-path").textContent = state.curPath; $("#drop-overlay").hidden = false; }
});
window.addEventListener("dragleave", () => { dragDepth = Math.max(0, dragDepth - 1); if (dragDepth === 0) $("#drop-overlay").hidden = true; });
window.addEventListener("dragover", (e) => { if (isFileDrag(e)) e.preventDefault(); });
window.addEventListener("drop", (e) => {
  if (!isFileDrag(e)) return;
  e.preventDefault(); dragDepth = 0; $("#drop-overlay").hidden = true;
  const files = Array.from(e.dataTransfer.files || []);
  if (!files.length) return;
  if (state.view !== "browse") { toast("先打开一个存储库再上传"); return; }
  files.forEach(enqueueUpload);
});

/* ============================================================ 隐私保险箱 */
async function loadVaultStatus() {
  try {
    const st = await api("/api/vault/status" + (state.vault.token ? "?vt=" + enc(state.vault.token) : ""));
    state.vault.exists = st.exists;
    state.vault.unlocked = st.unlocked;
    if (!st.unlocked) { state.vault.token = ""; localStorage.removeItem("linnas_vault_token"); }
  } catch (e) { /* ignore */ }
}
$("#btn-vault").onclick = async () => { state.vault.path = ""; showVault(); await loadVaultStatus(); if (state.vault.unlocked) await loadVaultList(); renderVault(); };

/* 手机连接:独立页面(实时二维码 + 地址选择 + 热点) */
const pairState = { info: null, ip: "", hs: null, lastHsReason: "" };
async function showPair() { state.view = "pair"; showView("view-pair"); renderSidebar(); await loadPair(); }
async function loadPair() {
  const cap = $("#pair-qr-cap"); if (cap) cap.textContent = "正在读取本机地址…";
  try {
    const info = await api("/api/pair");
    pairState.info = info;
    if (!pairState.ip || !(info.ips || []).includes(pairState.ip)) {
      pairState.ip = (info.ips && info.ips[0]) || location.hostname;
    }
    $("#pair-port").textContent = "端口 " + info.port;
    renderPairAddrs();
    setPairQR();
  } catch (e) {
    if (cap) cap.textContent = "无法获取本机地址:" + e.message;
  }
  renderHotspot();
}
function setPairQR() {
  const ip = pairState.ip;
  $("#pair-qr").src = apiUrl("/api/pair/qr?size=340&ip=" + enc(ip));
  const port = pairState.info ? pairState.info.port : "";
  $("#pair-qr-cap").textContent = "扫码连接 · " + (state.serverName ? state.serverName + " · " : "") + ip + ":" + port;
}
function renderPairAddrs() {
  const box = $("#pair-addrs"); box.innerHTML = "";
  const ips = (pairState.info && pairState.info.ips) || [];
  if (!ips.length) { box.innerHTML = '<div class="addr-empty">未检测到局域网地址</div>'; return; }
  ips.forEach((ip, i) => {
    const row = document.createElement("button");
    row.className = "addr-item" + (ip === pairState.ip ? " on" : "");
    row.innerHTML = '<span class="ai-main"><b></b><em></em></span><span class="ai-tag"></span>';
    row.querySelector("b").textContent = ip;
    row.querySelector("em").textContent = "http://" + ip + ":" + pairState.info.port;
    if (i === 0) row.querySelector(".ai-tag").textContent = "推荐";
    row.onclick = () => { pairState.ip = ip; renderPairAddrs(); setPairQR(); };
    box.appendChild(row);
  });
}
const HS_REASON = {
  "wifi-off": "无线网卡未开启:请先在系统里打开 Wi-Fi(或用笔记本的无线开关),再回来点「开启热点」。",
  "disabled": "这台电脑的无线网卡不支持或已关闭移动热点。",
  "no-internet": "当前没有可共享的上网连接(先连上网络再开热点)。",
  "band-unavailable": "所选网络波段不可用(2.4GHz 与 5GHz 都尝试过)。请确认无线网卡支持热点,或先在 Windows「移动热点」里手动开一次。",
  "op-operationinprogress": "热点正在切换中,请等几秒再点一次。",
  "op-entitlementcheckfailure": "运营商/网络策略不允许开热点。",
  "op-bluetoothdeviceoff": "请先打开蓝牙(系统要求蓝牙协处理器可用)。",
  "unknown": "无法识别热点能力。",
  "error": "热点功能暂不可用(可能是服务端版本较旧,重建后生效)。",
};
function hsChip(text, on) { const c = $("#hs-chip"); c.textContent = text; c.className = "pn-chip" + (on === true ? " on" : on === false ? " off" : ""); }
let hsSeq = 0;
async function renderHotspot() {
  const my = ++hsSeq;
  const body = $("#hs-body");
  hsChip("检测中…");
  let hs;
  try { hs = await api("/api/hotspot"); }
  catch (e) { hs = { supported: false, reason: "error", error: e.message }; }
  if (my !== hsSeq) return;
  pairState.hs = hs;
  if (!hs || !hs.supported) {
    hsChip("不可用", false);
    const why = HS_REASON[hs && hs.reason] || "热点不可用";
    body.innerHTML = '<p class="hs-tip"></p><div class="hs-actions"><button class="btn" id="hs-open"><span data-icon="settings"></span>打开 Windows 移动热点设置</button></div>' +
      '<p class="pn-hint">打开后开启「移动热点」,记住热点名称与密码;手机连上该 Wi-Fi 后,再回到本页扫描上方配对二维码。</p>';
    body.querySelector(".hs-tip").textContent = why;
    applyIcons(body);
    $("#hs-open").onclick = openHotspotSettings;
    return;
  }
  if (hs.running) {
    pairState.lastHsReason = "";
    hsChip("已开启", true);
    const wifi = "WIFI:T:" + (hs.auth === "Open" ? "nopass" : "WPA") + ";S:" + (hs.ssid || "") + ";P:" + (hs.password || "") + ";;";
    body.innerHTML =
      '<div class="hs-on"><div class="hs-qr"><img alt="Wi-Fi 二维码" src="' + apiUrl("/api/qr?size=260&text=" + enc(wifi)) + '"></div>' +
      '<div class="hs-oninfo"><div class="hs-ssid"><b></b></div>' +
      '<div class="hs-pw">密码 <code></code> <button class="btn ghost small" id="hs-copy">复制</button></div>' +
      '<div class="pn-hint">手机扫这个 Wi-Fi 二维码就能自动加入热点。<span id="hs-clients"></span></div></div></div>' +
      '<div class="hs-actions"><button class="btn" id="hs-stop"><span data-icon="logout"></span>关闭热点</button></div>';
    body.querySelector(".hs-ssid b").textContent = hs.ssid || "(未命名)";
    body.querySelector(".hs-pw code").textContent = hs.password || "—";
    const cl = $("#hs-clients"); if (cl) cl.textContent = " 已连接 " + (hs.clients || 0) + "/" + (hs.maxClients || "?") + " 台";
    applyIcons(body);
    $("#hs-copy").onclick = () => copyText(hs.password || "");
    $("#hs-stop").onclick = () => hotspotAction("stop");
    return;
  }
  hsChip("可用", true);
  const hsReason = (hs.reason || pairState.lastHsReason || "");
  const hsWarn = (hsReason && HS_REASON[hsReason]) ? `<p class="hs-warn">${HS_REASON[hsReason]}</p>` : "";
  body.innerHTML =
    hsWarn +
    '<p class="hs-tip">手机和电脑不在同一网络时,让电脑开一个热点:手机先连上这个热点,再扫上面的配对码即可。</p>' +
    '<label class="hs-field"><span>热点名称</span><input id="hs-ssid" maxlength="32" autocomplete="off"></label>' +
    '<label class="hs-field"><span>热点密码</span><input id="hs-pw" maxlength="63" placeholder="至少 8 位" autocomplete="off"></label>' +
    '<div class="hs-actions"><button class="btn primary" id="hs-start"><span data-icon="wifi"></span>开启热点</button>' +
    '<button class="btn ghost" id="hs-open2">系统设置</button></div>' +
    '<p class="pn-hint">开启后本页会显示 Wi-Fi 二维码,手机扫一下即可自动连上。</p>';
  applyIcons(body);
  $("#hs-ssid").value = hs.ssid || "linNas-Hotspot";
  var hsDefaultPw = "linNas" + "8888";
  $("#hs-pw").value = (hs.password && hs.password.length >= 8) ? hs.password : hsDefaultPw;
  $("#hs-start").onclick = () => hotspotAction("start", $("#hs-ssid").value.trim(), $("#hs-pw").value);
  $("#hs-open2").onclick = openHotspotSettings;
}
async function hotspotAction(action, ssid, password) {
  hsChip(action === "start" ? "开启中…" : "关闭中…");
  let res = null;
  try { res = await api("/api/hotspot", { method: "POST", body: { action: action, ssid: ssid || "", password: password || "" } }); }
  catch (e) { toast("热点操作失败:" + e.message); return await renderHotspot(); }
  if (res && action === "start") {
    if (!res.running) {
      pairState.lastHsReason = res.reason || "unknown";
      toast(HS_REASON[res.reason] || (res.error ? "开启失败:" + res.error : "热点未能开启"));
    } else {
      pairState.lastHsReason = "";
    }
  }
  await renderHotspot();
}
async function openHotspotSettings() {
  try { await api("/api/system/open", { method: "POST", body: { target: "mobilehotspot" } }); toast("已在电脑上打开「移动热点」设置"); }
  catch (e) { toast("无法打开系统设置:" + e.message); }
}
function copyText(t) {
  if (!t) return;
  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(t).then(() => toast("已复制"), () => toast("复制失败"));
  else toast(t);
}
$("#btn-pair").onclick = () => showPair();
$("#pair-refresh").onclick = () => loadPair();

function joinRel(dir, name) {
  dir = (dir || "").replace(/^\/+|\/+$/g, "");
  return dir ? dir + "/" + name : name;
}

function vaultOpen(rel) {
  state.vault.path = rel || "";
  loadVaultList().then(renderVault);
}

function vaultChip(text, kind) { const c = $("#vault-chip"); if (!c) return; c.hidden = false; c.textContent = text; c.className = "vchip " + (kind || ""); }
function vaultSub(html) { const s = $("#vault-sub"); if (s) s.innerHTML = html; }

function renderVault() {
  const body = $("#vault-body");
  const acts = $("#vault-actions");
  body.innerHTML = ""; acts.innerHTML = "";
  const v = state.vault;
  if (!v.exists) {
    vaultChip("未创建", "off");
    vaultSub("保险箱内容存放在<b>存储库内的隐藏目录</b>(如 H:\\NAS\\.linNasVault),普通浏览和搜索都看不到;需密码解锁后访问。");
    body.innerHTML = `<div class="vault-hero vh-tall"><div class="vlock">${svgIcon("lock")}</div>` +
      `<div class="vh-text"><b>还没有保险箱</b><span>设置一个密码即可创建。之后存放进来的文件不会出现在普通浏览和搜索中,只有输入密码才能访问。</span></div></div>`;
    const b = document.createElement("button"); b.className = "btn primary"; b.innerHTML = svgIcon("lock") + " 设置保险箱密码"; b.onclick = vaultSetup;
    body.querySelector(".vault-hero").appendChild(b);
    return;
  }
  if (!v.unlocked) {
    vaultChip("已锁定", "off");
    vaultSub("保险箱已锁定。输入密码后即可查看、上传、导出其中的文件;解锁状态在 <b>2 小时</b>后自动失效。");
    body.innerHTML = `<div class="vault-hero vh-tall"><div class="vlock locked">${svgIcon("lock")}</div>` +
      `<div class="vh-text"><b>保险箱已锁定</b><span>其中的文件受密码保护,不会出现在普通浏览与搜索里。点击下方解锁。</span></div></div>`;
    const b = document.createElement("button"); b.className = "btn primary"; b.innerHTML = svgIcon("unlock") + " 解锁保险箱"; b.onclick = () => vaultUnlock();
    body.querySelector(".vault-hero").appendChild(b);
    return;
  }
  vaultChip("已解锁", "on");
  // 已解锁:操作按钮
  const up = document.createElement("button"); up.className = "btn primary"; up.innerHTML = svgIcon("upload") + " 上传"; up.onclick = () => $("#vault-file-input").click();
  const mk = document.createElement("button"); mk.className = "btn"; mk.innerHTML = svgIcon("folder-plus") + " 新建文件夹"; mk.onclick = vaultMkdir;
  const imp = document.createElement("button"); imp.className = "btn"; imp.innerHTML = svgIcon("folder") + " 导入文件"; imp.onclick = vaultImportFiles;
  const chpw = document.createElement("button"); chpw.className = "btn"; chpw.innerHTML = svgIcon("key") + " 修改密码"; chpw.onclick = vaultChangePw;
  const expAll = document.createElement("button"); expAll.className = "btn"; expAll.innerHTML = svgIcon("download") + " 导出全部"; expAll.onclick = vaultExportAll;
  const lock = document.createElement("button"); lock.className = "btn"; lock.innerHTML = svgIcon("logout") + " 锁定"; lock.onclick = vaultLock;
  acts.appendChild(up); acts.appendChild(mk); acts.appendChild(imp); acts.appendChild(chpw); acts.appendChild(expAll); acts.appendChild(lock);

  // 面包屑
  const crumb = document.createElement("div");
  crumb.className = "crumbs";
  crumb.style.marginBottom = "8px";
  const rootA = document.createElement("a"); rootA.href = "#"; rootA.textContent = "保险箱";
  rootA.onclick = (e) => { e.preventDefault(); vaultOpen(""); };
  crumb.appendChild(rootA);
  const parts = (v.path || "").split("/").filter(Boolean);
  let acc = "";
  parts.forEach((p, i) => {
    acc = joinRel(acc, p);
    const sep = document.createElement("span"); sep.className = "sep"; sep.textContent = "/"; crumb.appendChild(sep);
    const a = document.createElement("a"); a.href = "#"; a.textContent = p;
    if (i === parts.length - 1) a.className = "cur";
    a.onclick = (e) => { e.preventDefault(); vaultOpen(acc); };
    crumb.appendChild(a);
  });
  body.appendChild(crumb);

  let vTotal = 0, vFiles = 0, vDirs = 0;
  for (const e of v.entries) { if (e.isDir) vDirs++; else { vFiles++; vTotal += (e.size || 0); } }
  vaultSub(`已解锁 · 共 <b>${v.entries.length}</b> 项(文件夹 ${vDirs} / 文件 ${vFiles}) · 合计 <b>${fmtSize(vTotal)}</b>;解锁状态在 2 小时后自动失效。`);

  if (!v.entries.length) {
    body.insertAdjacentHTML("beforeend", `<div class="vault-hero vh-tall"><div class="vlock">${svgIcon("folder")}</div><div class="vh-text"><b>这个文件夹是空的</b><span>点右上角「上传」放文件,或用「新建文件夹」「导入文件」。</span></div></div>`);
    return;
  }
  body.insertAdjacentHTML("beforeend", `<div class="vault-stat"><span>当前文件夹 <b>${v.entries.length}</b> 项</span><span>合计 <b>${fmtSize(vTotal)}</b></span><span class="vs-tip">已解锁 · 2 小时后自动锁定</span></div>`);
  const list = document.createElement("div");
  list.className = "vault-list";
  for (const e of v.entries) {
    const kind = e.isDir ? "dir" : kindOf(e.name);
    const row = document.createElement("div");
    row.className = "vrow";
    if (e.isDir) row.style.cursor = "pointer";
    const iconMap = { dir: "folder", video: "video", image: "image", audio: "music", archive: "archive", file: "file" };
    const icon = document.createElement("div");
    icon.className = "fi t-" + kind; icon.innerHTML = svgIcon(iconMap[kind]);
    const meta = document.createElement("div");
    meta.className = "vm";
    meta.innerHTML = `<div class="vname"></div><div class="vsub"></div>`;
    meta.querySelector(".vname").textContent = e.name;
    meta.querySelector(".vsub").textContent = (e.isDir ? "文件夹" : fmtSize(e.size)) + (e.modTime ? "  ·  " + fmtTime(e.modTime) : "");
    const acts2 = document.createElement("div");
    acts2.className = "facts";
    const ab = (ic, title, fn, extra) => { const b = document.createElement("button"); b.className = "btn icon-btn " + (extra || ""); b.title = title; b.innerHTML = svgIcon(ic); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; acts2.appendChild(b); };
    if (e.isDir) {
      row.onclick = () => vaultOpen(e.path);
      ab("move", "导出整个文件夹到存储库…", () => vaultExport(e.path));
    } else {
      const kv = kindOf(e.name);
      if (kv === "video" || kv === "audio" || kv === "image") ab("play", "预览", () => openPreviewSrc(kv, vaultRawUrl(e.path), e.name));
      ab("download", "下载", () => { location.href = vaultRawUrl(e.path, true); });
      ab("move", "导出到存储库…", () => vaultExport(e.path));
    }
    ab("trash", "删除", () => vaultDelete(e.path, e.name, e.isDir), "del");
    row.appendChild(icon); row.appendChild(meta); row.appendChild(acts2);
    list.appendChild(row);
  }
  body.appendChild(list);
}

async function vaultMkdir() {
  const name = await askInput("新建文件夹", "", "文件夹名称");
  if (!name) return;
  try {
    await api("/api/vault/mkdir?vt=" + enc(state.vault.token), { method: "POST", body: { path: joinRel(state.vault.path, name) } });
    await loadVaultList(); renderVault(); toast("已创建文件夹");
  } catch (e) { toast("失败: " + e.message); }
}

function vaultRawUrl(rel, dl) { return apiUrl("/api/vault/raw?path=" + enc(rel) + (dl ? "&dl=1" : "") + "&vt=" + enc(state.vault.token)); }

async function vaultSetup() {
  const r = await askVaultPw({ title: "设置保险箱密码", hint: "至少 4 位;请记牢,忘记无法找回。", withConfirm: true });
  if (!r) return;
  try {
    await api("/api/vault/setup", { method: "POST", body: { password: r.password } });
    // setup 后必须标记 exists,否则 renderVault() 仍走「还没有保险箱」分支,界面停在校验前
    state.vault.exists = true;
    toast("保险箱已创建");
    await vaultUnlock(r.password);
  } catch (e) { toast("失败: " + e.message); }
}
async function vaultUnlock(preset) {
  let pw = (preset && typeof preset === "string") ? preset : "";
  if (!pw) { const r = await askVaultPw({ title: "解锁保险箱", hint: "输入保险箱密码。", withConfirm: false }); if (!r) return; pw = r.password; }
  try {
    const res = await api("/api/vault/unlock", { method: "POST", body: { password: pw } });
    state.vault.token = res.token; state.vault.unlocked = true;
    localStorage.setItem("linnas_vault_token", res.token);
    await loadVaultList(); renderVault();
    toast("已解锁");
  } catch (e) { toast("解锁失败: " + e.message); }
}
async function vaultLock() {
  try { await api("/api/vault/lock?vt=" + enc(state.vault.token), { method: "POST", body: { token: state.vault.token } }); } catch (e) { /* ignore */ }
  state.vault.token = ""; state.vault.unlocked = false; state.vault.entries = [];
  localStorage.removeItem("linnas_vault_token");
  renderVault(); toast("已锁定");
}
async function loadVaultList() {
  const q = "/api/vault/ls?vt=" + enc(state.vault.token) + (state.vault.path ? "&path=" + enc(state.vault.path) : "");
  try { state.vault.entries = await api(q); }
  catch (e) { state.vault.entries = []; if (/401|未解锁/.test(e.message)) state.vault.unlocked = false; }
}
$("#vault-file-input").onchange = async (e) => {
  const files = Array.from(e.target.files || []); e.target.value = "";
  if (!files.length) return;
  let okN = 0;
  for (const f of files) {
    try {
      const url = apiUrl("/api/vault/upload?name=" + enc(f.name) + "&path=" + enc(state.vault.path || "") + "&vt=" + enc(state.vault.token));
      const res = await fetch(url, { method: "POST", body: f });
      if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d.error || res.status); }
      okN++;
    } catch (err) { toast(`「${f.name}」上传失败: ` + err.message); }
  }
  await loadVaultList(); renderVault();
  if (okN) toast(`已上传 ${okN} 个文件`);
};
async function vaultDelete(rel, name, isDir) {
  const ok = await askConfirm("删除", `从保险箱删除${isDir ? "文件夹「" + name + "」" : "「" + name + "」"}?不可恢复。`);
  if (!ok) return;
  try { await api("/api/vault/delete?vt=" + enc(state.vault.token), { method: "POST", body: { paths: [rel] } }); await loadVaultList(); renderVault(); toast("已删除"); }
  catch (e) { toast("失败: " + e.message); }
}
function vaultExport(rel) {
  openFolderPicker("导出到…", async (dest) => {
    try {
      const r = await api("/api/vault/export?vt=" + enc(state.vault.token), { method: "POST", body: { paths: [rel], dest } });
      toast(r.errors && r.errors.length ? "导出失败: " + r.errors[0] : "已导出");
      await loadVaultList(); renderVault();
    } catch (e) { toast("失败: " + e.message); }
  }, "/");
}

/* 导出当前文件夹的全部内容到存储库(导出后从保险箱移除,在普通浏览中可见) */
function vaultExportAll() {
  const ents = (state.vault.entries || []).filter((e) => e.path);
  if (!ents.length) { toast("当前文件夹没有可导出的内容"); return; }
  const paths = ents.map((e) => e.path);
  openFolderPicker("导出全部到…", async (dest) => {
    try {
      const r = await api("/api/vault/export?vt=" + enc(state.vault.token), { method: "POST", body: { paths, dest } });
      if (r.errors && r.errors.length) toast(`已导出 ${r.count} 项,${r.errors.length} 项失败: ` + r.errors[0]);
      else toast(`已导出 ${r.count} 项到存储库`);
      await loadVaultList(); renderVault();
    } catch (e) { toast("失败: " + e.message); }
  }, "/");
}

/* 修改保险箱密码:成功后服务端会清空所有解锁会话,需要用新密码重新解锁 */
async function vaultChangePw() {
  const r = await askVaultPw({
    title: "修改保险箱密码",
    hint: "输入原密码与新密码。新密码至少 4 位;修改后需用新密码重新解锁。",
    withConfirm: true, withOld: true,
  });
  if (!r) return;
  try {
    await api("/api/vault/password", { method: "POST", body: { oldPassword: r.oldPassword, newPassword: r.password } });
    toast("保险箱密码已更新");
    await vaultUnlock(r.password);
  } catch (e) { toast("修改失败: " + e.message); }
}

/* ---- 导入文件到保险箱:从存储库挑文件(可多选) ---- */
let vaultPickPath = "/", vaultPickSel = new Set();
function vaultImportFiles() { vaultPickSel = new Set(); $("#dlg-vaultpick").showModal(); loadVaultPick("/"); }
async function loadVaultPick(vp) {
  vaultPickPath = vp;
  $("#vaultpick-path").textContent = vp;
  const list = $("#vaultpick-list"); list.innerHTML = "";
  if (vp === "/") {
    for (const lib of state.libs) {
      const it = document.createElement("div"); it.className = "pitem";
      it.innerHTML = `${svgIcon("drive")}<span class="nm"></span>`;
      it.querySelector(".nm").textContent = lib.name;
      it.onclick = () => loadVaultPick("/" + lib.id);
      list.appendChild(it);
    }
    $("#btn-vpick-up").disabled = true;
    return;
  }
  let data; try { data = await api("/api/ls?vpath=" + enc(vp)); } catch (e) { toast("读取失败: " + e.message); return; }
  const ents = data.entries || [];
  for (const d of ents.filter((e) => e.isDir)) {
    const it = document.createElement("div"); it.className = "pitem";
    it.innerHTML = `${svgIcon("folder")}<span class="nm"></span>`;
    it.querySelector(".nm").textContent = d.name;
    it.onclick = () => loadVaultPick(vp.replace(/\/$/, "") + "/" + d.name);
    list.appendChild(it);
  }
  for (const f of ents.filter((e) => !e.isDir)) {
    const full = vp.replace(/\/$/, "") + "/" + f.name;
    const it = document.createElement("div"); it.className = "pitem";
    const box = document.createElement("span"); box.className = "fcheck"; box.style.display = "grid"; box.innerHTML = svgIcon("check");
    it.appendChild(box);
    const nm = document.createElement("span"); nm.className = "nm"; nm.textContent = f.name; it.appendChild(nm);
    if (vaultPickSel.has(full)) it.classList.add("pick-on");
    it.onclick = () => {
      if (vaultPickSel.has(full)) { vaultPickSel.delete(full); it.classList.remove("pick-on"); }
      else { vaultPickSel.add(full); it.classList.add("pick-on"); }
      $("#btn-vaultpick-ok").textContent = `导入选中 ${vaultPickSel.size} 项`;
    };
    list.appendChild(it);
  }
  if (!ents.length) list.innerHTML = '<div class="pempty">空目录</div>';
  const seg = vp.replace(/^\/+|\/+$/g, "").split("/");
  const parent = seg.length <= 1 ? "/" : "/" + seg.slice(0, -1).join("/");
  $("#btn-vpick-up").disabled = false;
  $("#btn-vpick-up").onclick = () => loadVaultPick(parent);
  $("#btn-vaultpick-ok").textContent = `导入选中 ${vaultPickSel.size} 项`;
}
$("#btn-vaultpick-ok").onclick = async () => {
  const vps = Array.from(vaultPickSel);
  if (!vps.length) { toast("请选择文件"); return; }
  $("#dlg-vaultpick").close();
  await importToVault(vps, state.vault.path || "");
};
$("#dlg-vaultpick-cancel").onclick = () => $("#dlg-vaultpick").close();
$("#dlg-vaultpick").addEventListener("cancel", () => {});

async function importToVault(vps, dest) {
  try {
    const r = await api("/api/vault/import?vt=" + enc(state.vault.token), { method: "POST", body: { vpaths: vps, dest: dest || "" } });
    toast(r.errors && r.errors.length ? `导入 ${r.count} 项,${r.errors.length} 项失败` : `已导入保险箱 ${r.count} 项`);
    if (state.view === "vault") { await loadVaultList(); renderVault(); }
    else if (state.view === "browse") loadDir();
  } catch (e) { toast("失败: " + e.message); }
}

/* ---- 保险箱目标文件夹选择器 ---- */
let vaultDestResolver = null, vaultDestPath = "";
function openVaultDestPicker(start) {
  return new Promise((resolve) => { vaultDestResolver = resolve; vaultDestPath = start || ""; $("#dlg-vaultdest").showModal(); loadVaultDest(vaultDestPath); });
}
async function loadVaultDest(rel) {
  vaultDestPath = rel || "";
  $("#vaultdest-path").textContent = "保险箱 / " + vaultDestPath;
  const list = $("#vaultdest-list"); list.innerHTML = "";
  let data = [];
  try { data = await api("/api/vault/ls?vt=" + enc(state.vault.token) + (vaultDestPath ? "&path=" + enc(vaultDestPath) : "")); } catch (e) {}
  for (const d of data.filter((e) => e.isDir)) {
    const it = document.createElement("div"); it.className = "pitem";
    it.innerHTML = `${svgIcon("folder")}<span class="nm"></span>`;
    it.querySelector(".nm").textContent = d.name;
    it.onclick = () => loadVaultDest(d.path);
    list.appendChild(it);
  }
  if (!data.some((e) => e.isDir)) list.innerHTML = '<div class="pempty">(此层没有子文件夹,可直接存入)</div>';
  const seg = vaultDestPath.replace(/^\/+|\/+$/g, "").split("/").filter(Boolean);
  const parent = seg.length <= 1 ? "" : seg.slice(0, -1).join("/");
  $("#btn-vdest-up").disabled = !vaultDestPath;
  $("#btn-vdest-up").onclick = () => loadVaultDest(parent);
}
$("#btn-vaultdest-ok").onclick = () => { const cb = vaultDestResolver; vaultDestResolver = null; $("#dlg-vaultdest").close(); if (cb) cb(vaultDestPath); };
$("#dlg-vaultdest-cancel").onclick = () => { const cb = vaultDestResolver; vaultDestResolver = null; $("#dlg-vaultdest").close(); if (cb) cb(null); };
$("#dlg-vaultdest").addEventListener("cancel", () => { const cb = vaultDestResolver; vaultDestResolver = null; if (cb) cb(null); });

async function setPrivate(vp) {
  if (!(await ensureVaultUnlocked())) return;
  const dest = await openVaultDestPicker(state.vault.path || "");
  if (dest === null) return;
  await importToVault([vp], dest);
}

/* ============================================================ 系统设置 */
$("#btn-settings").onclick = () => { openSettings(); };
$("#dlg-settings-close").onclick = () => $("#dlg-settings").close();
$("#dlg-settings").addEventListener("cancel", () => $("#dlg-settings").close());

async function openSettings() {
  $("#settings-body").innerHTML = '<div class="pempty">加载中…</div>';
  $("#dlg-settings").showModal();
  let s;
  try { s = await api("/api/settings"); } catch (e) { $("#settings-body").innerHTML = `<div class="pempty">加载失败: ${e.message}</div>`; return; }
  state.settings = s;
  const idx = s.index || {};
  const stats = (idx.stats || {});
  const body = $("#settings-body");
  body.innerHTML = "";

  const group = (title, rows) => {
    const g = document.createElement("div"); g.className = "set-group";
    const h = document.createElement("h4"); h.textContent = title; g.appendChild(h);
    for (const r of rows) g.appendChild(r);
    body.appendChild(g);
    return g;
  };
  const row = (k, vNode) => {
    const r = document.createElement("div"); r.className = "set-row";
    const kk = document.createElement("span"); kk.className = "k"; kk.textContent = k;
    const vv = document.createElement("span"); vv.className = "v";
    if (typeof vNode === "string") vv.textContent = vNode; else vv.appendChild(vNode);
    r.appendChild(kk); r.appendChild(vv); return r;
  };

  // 关于
  const aboutGroup = group("关于", [
    row("版本", "v" + s.version),
    row("监听端口", String(s.port)),
    row("数据目录", s.dataDir),
    row("Go 运行时", s.goos + "/" + s.goarch),
    row("ffmpeg", s.ffmpeg ? "已检测到(缩略图/转码可用)" : "未安装(仅图标)"),
    row("存储库", String(s.libCount) + " 个"),
  ]);
  // 服务器名称:自定义显示名(替代默认的 linNas / IP)
  const nameInput = document.createElement("input");
  nameInput.type = "text"; nameInput.value = s.serverName || "";
  nameInput.placeholder = "默认 LinNAS"; nameInput.maxLength = 40;
  nameInput.style.cssText = "padding:6px 9px;background:var(--bg-soft);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit;font-size:13px;width:180px";
  if (!isAdmin()) nameInput.disabled = true;
  const nameSave = document.createElement("button"); nameSave.className = "btn primary"; nameSave.textContent = "保存"; nameSave.disabled = !isAdmin();
  nameSave.onclick = async () => {
    try {
      const r = await api("/api/settings", { method: "POST", body: { serverName: nameInput.value } });
      state.serverName = r.serverName || "LinNAS"; applyServerName(); toast("服务器名称已更新");
    } catch (e) { toast("失败: " + e.message); }
  };
  const nameWrap = document.createElement("div"); nameWrap.style.cssText = "display:flex;gap:8px;align-items:center;flex-wrap:wrap";
  nameWrap.appendChild(nameInput); nameWrap.appendChild(nameSave);
  aboutGroup.appendChild(row("服务器名称", nameWrap));

  // 索引
  const idxGroup = group("文件索引", [
    row("已索引文件", String(idx.fileCount != null ? idx.fileCount : (stats.other || 0) + (stats.video || 0) + (stats.image || 0) + (stats.audio || 0))),
    row("视频 / 图片 / 音频", `${stats.video || 0} / ${stats.image || 0} / ${stats.audio || 0}`),
    row("状态", idx.scanning ? "扫描中…" : (idx.note || "—")),
  ]);
  const reBtn = document.createElement("button"); reBtn.className = "btn"; reBtn.innerHTML = svgIcon("refresh") + " 重建索引";
  reBtn.onclick = async () => { try { await api("/api/reindex", { method: "POST", body: {} }); toast("已开始后台重建索引"); } catch (e) { toast("失败: " + e.message); } };
  idxGroup.appendChild(reBtn);

  // 存储与维护:回收站保留天数 + 转码缓存上限 + 立即清理
  const numInput = (val, min, max, w) => {
    const i = document.createElement("input");
    i.type = "number"; i.min = String(min); i.max = String(max); i.value = String(val);
    i.style.cssText = "padding:6px 9px;background:var(--bg-soft);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit;font-size:13px;width:" + (w || 90) + "px";
    if (!isAdmin()) i.disabled = true;
    return i;
  };
  // 索引自动刷新:每 N 秒增量对账一次(0 = 关闭) + 立即刷新索引
  const refreshInput = numInput(s.indexRefreshSeconds != null ? s.indexRefreshSeconds : 120, 0, 86400, 90);
  const refreshWrap = document.createElement("div"); refreshWrap.style.cssText = "display:flex;gap:8px;align-items:center;flex-wrap:wrap";
  const refreshUnit = document.createElement("span"); refreshUnit.textContent = "秒自动对账一次(0 = 关闭)"; refreshUnit.style.cssText = "font-size:12.5px;color:var(--muted)";
  const refreshSave = document.createElement("button"); refreshSave.className = "btn primary"; refreshSave.textContent = "保存"; refreshSave.disabled = !isAdmin();
  refreshSave.onclick = async () => {
    const v = Math.max(0, Math.min(86400, parseInt(refreshInput.value, 10) || 0));
    try { await api("/api/settings", { method: "POST", body: { indexRefreshSeconds: v } }); toast(v > 0 ? `索引每 ${v} 秒自动刷新` : "已关闭索引自动刷新"); }
    catch (e) { toast("失败: " + e.message); }
  };
  const refreshNow = document.createElement("button"); refreshNow.className = "btn"; refreshNow.textContent = "立即刷新索引";
  refreshNow.onclick = async () => {
    try { await api("/api/index/refresh", { method: "POST", body: {} }); toast("已触发索引刷新"); }
    catch (e) { toast("失败: " + e.message); }
  };
  refreshWrap.appendChild(refreshInput); refreshWrap.appendChild(refreshUnit); refreshWrap.appendChild(refreshSave); refreshWrap.appendChild(refreshNow);
  idxGroup.appendChild(row("自动刷新", refreshWrap));

  const daysInput = numInput(s.trashRetentionDays != null ? s.trashRetentionDays : 30, 0, 3650, 90);
  const daysWrap = document.createElement("div"); daysWrap.style.cssText = "display:flex;gap:8px;align-items:center";
  daysWrap.appendChild(daysInput);
  const daysUnit = document.createElement("span"); daysUnit.textContent = "天后自动清理(0 = 不自动清理)"; daysUnit.style.cssText = "font-size:12.5px;color:var(--muted)";
  daysWrap.appendChild(daysUnit);

  const mbInput = numInput(s.transcodeCacheMB != null ? s.transcodeCacheMB : 2048, 0, 1048576, 100);
  const mbWrap = document.createElement("div"); mbWrap.style.cssText = "display:flex;gap:8px;align-items:center";
  mbWrap.appendChild(mbInput);
  const mbUnit = document.createElement("span"); mbUnit.textContent = "MB 上限,超出按最久未用淘汰(0 = 不限)"; mbUnit.style.cssText = "font-size:12.5px;color:var(--muted)";
  mbWrap.appendChild(mbUnit);

  // 空间告警阈值(批次 1):可用空间百分比低于阈值时在总览提示
  const warnInput = numInput(s.spaceWarnPercent != null ? s.spaceWarnPercent : 10, 1, 50, 70);
  const warnWrap = document.createElement("div"); warnWrap.style.cssText = "display:flex;gap:8px;align-items:center;flex-wrap:wrap";
  warnWrap.appendChild(warnInput);
  const warnUnit = document.createElement("span"); warnUnit.textContent = "% 可用空间低于此值时提醒(1-50)"; warnUnit.style.cssText = "font-size:12.5px;color:var(--muted)";
  warnWrap.appendChild(warnUnit);

  const critInput = numInput(s.spaceCriticalPercent != null ? s.spaceCriticalPercent : 3, 1, 20, 70);
  const critWrap = document.createElement("div"); critWrap.style.cssText = "display:flex;gap:8px;align-items:center;flex-wrap:wrap";
  critWrap.appendChild(critInput);
  const critUnit = document.createElement("span"); critUnit.textContent = "% 可用空间低于此值时严重提醒(1-20)"; critUnit.style.cssText = "font-size:12.5px;color:var(--muted)";
  critWrap.appendChild(critUnit);
  const spaceSave = document.createElement("button"); spaceSave.className = "btn primary"; spaceSave.textContent = "保存"; spaceSave.disabled = !isAdmin();
  spaceSave.onclick = async () => {
    const w = Math.max(1, Math.min(50, parseInt(warnInput.value, 10) || 10));
    const c = Math.max(1, Math.min(20, parseInt(critInput.value, 10) || 3));
    try { await api("/api/settings", { method: "POST", body: { spaceWarnPercent: w, spaceCriticalPercent: c } }); toast(`已保存:空间告警阈值 ${w}%,严重阈值 ${c}%`); }
    catch (e) { toast("失败: " + e.message); }
  };
  critWrap.appendChild(spaceSave);

  const maintWrap = document.createElement("div"); maintWrap.style.cssText = "display:flex;gap:8px;flex-wrap:wrap";
  const maintSave = document.createElement("button"); maintSave.className = "btn primary"; maintSave.textContent = "保存"; maintSave.disabled = !isAdmin();
  maintSave.onclick = async () => {
    const d = Math.max(0, Math.min(3650, parseInt(daysInput.value, 10) || 0));
    const mb = Math.max(0, Math.min(1048576, parseInt(mbInput.value, 10) || 0));
    try { await api("/api/settings", { method: "POST", body: { trashRetentionDays: d, transcodeCacheMB: mb } }); toast("已保存:回收站保留 " + d + " 天,转码缓存上限 " + mb + " MB"); }
    catch (e) { toast("失败: " + e.message); }
  };
  const cleanNow = document.createElement("button"); cleanNow.className = "btn"; cleanNow.textContent = "立即清理";
  cleanNow.onclick = async () => {
    try {
      const r = await api("/api/maintenance/cleanup", { method: "POST", body: {} });
      toast(`已清理:回收站 ${r.trashRemoved} 项,转码缓存 ${r.transcodeRemoved} 个(释放 ${(r.freedMB || 0).toFixed(1)} MB)`);
    } catch (e) { toast("失败: " + e.message); }
  };
  maintWrap.appendChild(maintSave); maintWrap.appendChild(cleanNow);
  group("存储与维护", [
    row("回收站", daysWrap),
    row("转码缓存", mbWrap),
    row("空间告警阈值 %", warnWrap),
    row("严重阈值 %", critWrap),
    row("维护", maintWrap),
  ]);

  // 外观
  const themeSeg = document.createElement("div"); themeSeg.className = "seg";
  [["system", "跟随系统"], ["dark", "深色"], ["light", "浅色"]].forEach(([val, label]) => {
    const b = document.createElement("button"); b.textContent = label;
    if (state.theme === val) b.classList.add("on");
    b.onclick = () => { setTheme(val); Array.from(themeSeg.children).forEach((x) => x.classList.remove("on")); b.classList.add("on"); };
    themeSeg.appendChild(b);
  });
  const viewSeg = document.createElement("div"); viewSeg.className = "seg";
  [["list", "列表"], ["grid", "网格"]].forEach(([val, label]) => {
    const b = document.createElement("button"); b.textContent = label;
    if (state.mode === val) b.classList.add("on");
    b.onclick = () => { state.mode = val; localStorage.setItem("linnas_mode", val); $("#btn-viewmode").innerHTML = svgIcon(val === "grid" ? "list" : "grid"); Array.from(viewSeg.children).forEach((x) => x.classList.remove("on")); b.classList.add("on"); if (state.entries.length) renderEntries(); };
    viewSeg.appendChild(b);
  });
  group("外观", [row("主题", themeSeg), row("默认视图", viewSeg)]);

  // 访问口令
  const pwWrap = document.createElement("div"); pwWrap.style.display = "flex"; pwWrap.style.gap = "8px"; pwWrap.style.alignItems = "center";
  const pwInput = document.createElement("input"); pwInput.type = "text"; pwInput.placeholder = s.tokenSet ? "已启用,输入新口令(留空=关闭)" : "留空=不启用口令";
  pwInput.style.cssText = "padding:6px 9px;background:var(--bg-soft);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit;font-size:13px;width:200px;max-width:44vw";
  const pwSave = document.createElement("button"); pwSave.className = "btn primary"; pwSave.textContent = "保存";
  pwSave.onclick = async () => {
    try {
      const t = pwInput.value;
      await api("/api/settings", { method: "POST", body: { token: t } });
      state.token = t; if (t) localStorage.setItem("linnas_token", t); else localStorage.removeItem("linnas_token");
      toast(t ? "已启用访问口令" : "已关闭访问口令"); pwInput.value = "";
    } catch (e) { toast("失败: " + e.message); }
  };
  pwWrap.appendChild(pwInput); pwWrap.appendChild(pwSave);
  group("访问口令", [row("保护", pwWrap), row("说明", "启用后手机/浏览器首次访问需输入口令")]);

  // 保险箱
  const vc = document.createElement("div"); vc.style.display = "flex"; vc.style.gap = "8px"; vc.style.flexWrap = "wrap";
  if (s.vaultExists) {
    const ch = document.createElement("button"); ch.className = "btn"; ch.textContent = "修改密码";
    ch.onclick = async () => {
      const old = await askVaultPw({ title: "修改保险箱密码", hint: "先输入原密码。", withConfirm: false });
      if (!old) return;
      const np = await askVaultPw({ title: "设置新密码", hint: "至少 4 位。", withConfirm: true });
      if (!np) return;
      try { await api("/api/vault/password", { method: "POST", body: { oldPassword: old.password, newPassword: np.password } }); toast("密码已修改,请重新解锁"); state.vault.token = ""; state.vault.unlocked = false; localStorage.removeItem("linnas_vault_token"); $("#dlg-settings").close(); showVault(); renderVault(); }
      catch (e) { toast("失败: " + e.message); }
    };
    vc.appendChild(ch);
    const open = document.createElement("button"); open.className = "btn"; open.textContent = "打开保险箱"; open.onclick = () => { $("#dlg-settings").close(); $("#btn-vault").click(); };
    vc.appendChild(open);
  } else {
    const cr = document.createElement("button"); cr.className = "btn primary"; cr.textContent = "创建保险箱";
    cr.onclick = async () => { $("#dlg-settings").close(); const r = await askVaultPw({ title: "设置保险箱密码", hint: "至少 4 位;请记牢,忘记无法找回。", withConfirm: true }); if (!r) return; try { await api("/api/vault/setup", { method: "POST", body: { password: r.password } }); toast("保险箱已创建"); } catch (e) { toast("失败: " + e.message); } };
    vc.appendChild(cr);
  }
  group("隐私保险箱", [row("状态", s.vaultExists ? "已创建" : "未创建"), row("位置", s.vaultDir || "—"), row("操作", vc)]);

  // v1.4.0 安全
  const secWrap = document.createElement("div"); secWrap.style.display = "flex"; secWrap.style.gap = "8px"; secWrap.style.flexWrap = "wrap";
  const isAdm = isAdmin();
  if (isAdm) {
    const ub = document.createElement("button"); ub.className = "btn primary"; ub.textContent = "用户管理";
    ub.onclick = () => { $("#dlg-settings").close(); openUsersDialog(); };
    secWrap.appendChild(ub);
  }
  if (state.auth.username) {
    const pb = document.createElement("button"); pb.className = "btn"; pb.textContent = "修改我的密码";
    pb.onclick = () => { $("#dlg-settings").close(); $("#btn-um-pw").click(); };
    secWrap.appendChild(pb);
  }
  const sb = document.createElement("button"); sb.className = "btn"; sb.textContent = "分享链接管理";
  sb.onclick = () => { $("#dlg-settings").close(); showShares(); };
  secWrap.appendChild(sb);
  if (isAdm) {
    const ab2 = document.createElement("button"); ab2.className = "btn"; ab2.textContent = "操作日志";
    ab2.onclick = () => { $("#dlg-settings").close(); showAudit(); };
    secWrap.appendChild(ab2);
  }
  const idleInput = document.createElement("input");
  idleInput.type = "number"; idleInput.min = "1"; idleInput.max = "1440";
  idleInput.value = String(s.idleLockMinutes || 30);
  idleInput.style.cssText = "padding:6px 9px;background:var(--bg-soft);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit;font-size:13px;width:90px";
  idleInput.disabled = !isAdm;
  const idleWrap = document.createElement("div"); idleWrap.style.display = "flex"; idleWrap.style.gap = "8px"; idleWrap.style.alignItems = "center";
  idleWrap.appendChild(idleInput);
  const unit = document.createElement("span"); unit.textContent = "分钟无操作后自动锁定"; unit.style.fontSize = "12.5px"; unit.style.color = "var(--muted)";
  idleWrap.appendChild(unit);
  const idleSave = document.createElement("button"); idleSave.className = "btn primary"; idleSave.textContent = "保存"; idleSave.disabled = !isAdm;
  idleSave.onclick = async () => {
    const m = Math.max(1, Math.min(1440, parseInt(idleInput.value, 10) || 30));
    try { await api("/api/settings", { method: "POST", body: { token: state.token, idleLockMinutes: m } }); toast("自动锁定已设为 " + m + " 分钟"); }
    catch (e) { toast("失败: " + e.message); }
  };
  idleWrap.appendChild(idleSave);
  group("安全与账号", [
    row("多用户", s.multiUser ? `已启用(${s.userCount || 0} 个账号)` : "未启用(免登录访问)"),
    row("当前身份", state.auth.username ? `${state.auth.username}(${state.auth.role})` : "未登录"),
    row("自动锁定", idleWrap),
    row("管理", secWrap),
  ]);

  // v1.5.0 远程访问
  const rmBtn = document.createElement("button"); rmBtn.className = "btn"; rmBtn.textContent = "远程访问设置";
  rmBtn.onclick = () => { $("#dlg-settings").close(); showRemote(); };
  group("远程访问", [
    row("Tailscale", "出门在外用 Tailscale 地址访问,不用公网 IP"),
    row("托盘常驻", "开机自启后以托盘方式后台运行,随时双击图标打开"),
    row("打开", rmBtn),
  ]);

  // 网络唤醒(Wake-on-LAN)
  const wolInputCss = "padding:6px 9px;background:var(--bg-soft);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit;font-size:13px";
  const wolWrap = document.createElement("div");
  wolWrap.style.cssText = "display:flex;flex-direction:column;gap:8px;min-width:280px";
  const wolList = document.createElement("div");
  wolList.style.cssText = "display:flex;flex-direction:column;gap:6px";
  wolWrap.appendChild(wolList);
  const renderWol = (hosts) => {
    wolList.innerHTML = "";
    if (!hosts.length) {
      const e = document.createElement("div");
      e.textContent = isAdm ? "还没有添加主机,可在下方添加" : "还没有添加主机";
      e.style.cssText = "font-size:12.5px;color:var(--muted)";
      wolList.appendChild(e); return;
    }
    hosts.forEach((h) => {
      const rowEl = document.createElement("div");
      rowEl.style.cssText = "display:flex;align-items:center;gap:8px;padding:6px 10px;border:1px solid var(--line);border-radius:8px;background:var(--bg-soft)";
      const nm = document.createElement("span"); nm.textContent = h.name;
      nm.style.cssText = "flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px";
      rowEl.appendChild(nm);
      const macEl = document.createElement("span"); macEl.textContent = h.mac;
      macEl.style.cssText = "font-size:11.5px;color:var(--faint);font-family:var(--font-mono)";
      rowEl.appendChild(macEl);
      if (isAdm) {
        const wake = document.createElement("button"); wake.className = "btn sm primary"; wake.textContent = "唤醒";
        wake.onclick = async () => { try { await api("/api/wol/send", { method: "POST", body: { mac: h.mac } }); toast("已发送唤醒包到 " + h.name); } catch (e) { toast("失败: " + e.message); } };
        rowEl.appendChild(wake);
        const del = document.createElement("button"); del.className = "btn sm del"; del.textContent = "删除";
        del.onclick = async () => { try { await api("/api/wol?id=" + enc(h.id), { method: "DELETE" }); toast("已删除"); loadWol(); } catch (e) { toast("失败: " + e.message); } };
        rowEl.appendChild(del);
      }
      wolList.appendChild(rowEl);
    });
  };
  const loadWol = async () => { try { const r = await api("/api/wol"); renderWol(r.hosts || []); } catch (e) { renderWol([]); } };
  if (isAdm) {
    const addWrap = document.createElement("div"); addWrap.style.cssText = "display:flex;gap:8px;flex-wrap:wrap;align-items:center";
    const nameIn = document.createElement("input"); nameIn.placeholder = "名称(如 客厅电脑)"; nameIn.style.cssText = wolInputCss + ";width:130px";
    const macIn = document.createElement("input"); macIn.placeholder = "MAC 地址"; macIn.style.cssText = wolInputCss + ";width:150px";
    const addBtn = document.createElement("button"); addBtn.className = "btn primary"; addBtn.textContent = "添加";
    addBtn.onclick = async () => {
      try { await api("/api/wol", { method: "POST", body: { name: nameIn.value, mac: macIn.value } }); nameIn.value = ""; macIn.value = ""; toast("已添加主机"); loadWol(); }
      catch (e) { toast("失败: " + e.message); }
    };
    addWrap.appendChild(nameIn); addWrap.appendChild(macIn); addWrap.appendChild(addBtn);
    wolWrap.appendChild(addWrap);
  }
  loadWol();
  group("网络唤醒 (Wake-on-LAN)", [
    row("说明", "记录主机的 MAC,一键发送唤醒包(需在目标机 BIOS/网卡开启 Wake-on-LAN)"),
    row("主机", wolWrap),
  ]);
}

/* ============================================================ 存入保险箱 / 批量导入 */
async function ensureVaultUnlocked() {
  if (!state.vault.exists) { toast("请先在「隐私保险箱」里设置密码"); showVault(); renderVault(); return false; }
  if (!state.vault.unlocked) { await vaultUnlock(); return state.vault.unlocked; }
  return true;
}
$("#btn-sel-vault").onclick = async () => {
  const vps = Array.from(state.selected);
  if (!vps.length) return;
  if (!(await ensureVaultUnlocked())) return;
  const dest = await openVaultDestPicker(state.vault.path || "");
  if (dest === null) return;
  await importToVault(vps, dest);
  clearSelection();
};
function vaultImport() { /* 兼容占位:改用 vaultImportFiles / 存入保险箱 */ }

/* ============================================================ 音乐播放器 */
/* 播放模式:三态循环切换(列表播放 → 单曲循环 → 随机播放),替代原来的「随机」+「列表循环」两个按钮 */
const MUSIC_MODES = ["all", "one", "shuffle"];
const MUSIC_MODE_META = {
  all: { icon: "⥁", label: "列表播放", title: "列表播放(播完最后一首回到第一首)" },
  one: { icon: "⥂", label: "单曲循环", title: "单曲循环(当前这首反复播放)" },
  shuffle: { icon: "⤨", label: "随机播放", title: "随机播放(随机挑选下一首)" }
};
function savedMusicMode() {
  try { const m = localStorage.getItem("linnas_music_mode"); if (MUSIC_MODES.indexOf(m) >= 0) return m; } catch (e) {}
  return "all";
}
function musicIsShuffle() { return musicState.mode === "shuffle"; }
function musicIsRepeatOne() { return musicState.mode === "one"; }

const musicState = { list: [], index: -1, playing: false, miniClosed: false, mode: savedMusicMode(), filter: "", saveAt: 0, pendingPos: 0, favOnly: false, sleepMin: 0, sleepEnd: 0, sleepTimer: null };

function fmtClock(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  return Math.floor(sec / 60) + ":" + String(sec % 60).padStart(2, "0");
}
function musicAudio() { return $("#music-audio"); }

/* ---- 音量:记忆 + 百分比 + 静音 ---- */
function savedMusicVolume() {
  try { const v = parseFloat(localStorage.getItem("linnas_music_vol")); if (isFinite(v)) return Math.min(1, Math.max(0, v)); } catch (e) {}
  return 0.7;
}
function applyMusicVolume() {
  const a = musicAudio();
  const v = savedMusicVolume();
  if (a) a.volume = v;
  const mv = $("#music-vol"); if (mv) mv.value = String(v);
  const pct = $("#music-vol-pct"); if (pct) pct.textContent = Math.round(v * 100) + "%";
  const mb = $("#music-mute");
  if (mb) {
    const m = !!(a && a.muted);
    mb.classList.toggle("on", m);
    mb.textContent = m ? "🔇" : "🔊";
    mb.title = m ? "取消静音" : "静音";
    mb.setAttribute("aria-pressed", m ? "true" : "false");
  }
}
/* ---- 收藏(localStorage 持久化) ---- */
function musicFavSet() {
  try { const arr = JSON.parse(localStorage.getItem("linnas_music_favs") || "[]"); return new Set(Array.isArray(arr) ? arr : []); } catch (e) { return new Set(); }
}
function toggleMusicFav(path) {
  const s = musicFavSet();
  if (s.has(path)) s.delete(path); else s.add(path);
  try { localStorage.setItem("linnas_music_favs", JSON.stringify([...s])); } catch (e) {}
  renderMusicList();
}
/* ---- 睡眠定时(到点暂停播放;按钮旁显示剩余时间,支持自定义分钟) ---- */
function renderSleepLeft() {
  const chip = $("#music-sleep-left");
  const sl = $("#music-sleep");
  if (musicState.sleepMin > 0 && musicState.sleepEnd > 0) {
    const left = Math.max(0, musicState.sleepEnd - Date.now());
    const text = "🌙 " + fmtClock(left / 1000);
    if (chip) { chip.hidden = false; chip.textContent = text; chip.title = "睡眠定时剩余 " + fmtClock(left / 1000) + "(点击可修改)"; }
    if (sl) sl.title = "睡眠定时:" + fmtClock(left / 1000) + " 后暂停(点击修改)";
  } else {
    if (chip) { chip.hidden = true; chip.textContent = ""; }
    if (sl) sl.title = "睡眠定时:关(点击设置)";
  }
}
function setSleepTimer(min) {
  min = Math.max(0, Math.floor(Number(min) || 0));
  musicState.sleepMin = min;
  if (musicState.sleepTimer) { clearInterval(musicState.sleepTimer); musicState.sleepTimer = null; }
  musicState.sleepEnd = min > 0 ? Date.now() + min * 60000 : 0;
  if (min > 0) {
    musicState.sleepTimer = setInterval(() => {
      const left = musicState.sleepEnd - Date.now();
      if (left <= 0) {
        clearInterval(musicState.sleepTimer); musicState.sleepTimer = null;
        const a = musicAudio(); if (a) a.pause();
        setMusicPlaying(false);
        musicState.sleepMin = 0; musicState.sleepEnd = 0;
        syncMusicToggles();
        toast("睡眠定时:已暂停播放");
        return;
      }
      renderSleepLeft();
    }, 1000);
    toast("睡眠定时:" + min + " 分钟后暂停播放");
  } else {
    toast("睡眠定时:已关闭");
  }
  renderSleepLeft();
  syncMusicToggles();
}

/* 睡眠定时下拉菜单:预设 + 自定义 */
let sleepMenuEl = null;
function closeSleepMenu() {
  if (sleepMenuEl && sleepMenuEl.parentNode) sleepMenuEl.parentNode.removeChild(sleepMenuEl);
  sleepMenuEl = null;
  document.removeEventListener("pointerdown", sleepMenuOutside, true);
}
function sleepMenuOutside(ev) {
  if (sleepMenuEl && !sleepMenuEl.contains(ev.target)) closeSleepMenu();
}
function openSleepMenu(anchor) {
  if (sleepMenuEl) { closeSleepMenu(); return; }
  const menu = document.createElement("div");
  menu.className = "music-sleep-menu";
  const opts = [[15, "15 分钟"], [30, "30 分钟"], [45, "45 分钟"], [60, "60 分钟"], ["custom", "自定义…"], [0, "关闭定时"]];
  opts.forEach(([v, label]) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "msm-item" + (v !== "custom" && v !== 0 && v === musicState.sleepMin ? " on" : "");
    b.textContent = label;
    b.onclick = async (ev) => {
      ev.stopPropagation();
      closeSleepMenu();
      if (v === "custom") {
        const s = await askInput("自定义睡眠定时", String(musicState.sleepMin || 30), "分钟数(1-600)");
        if (s === null) return;
        const n = parseInt(s, 10);
        if (!isFinite(n) || n <= 0 || n > 600) { toast("请输入 1-600 之间的分钟数"); return; }
        setSleepTimer(n);
        return;
      }
      setSleepTimer(v);
    };
    menu.appendChild(b);
  });
  document.body.appendChild(menu);
  sleepMenuEl = menu;
  const r = anchor.getBoundingClientRect();
  const w = menu.offsetWidth || 132;
  const h = menu.offsetHeight || 0;
  menu.style.left = Math.max(8, Math.min(window.innerWidth - w - 8, r.left + r.width / 2 - w / 2)) + "px";
  menu.style.top = Math.max(8, r.top - h - 8) + "px";
  setTimeout(() => document.addEventListener("pointerdown", sleepMenuOutside, true), 0);
}

/* 唱片旋转交给 CSS 动画(停播就移除 class,避免每帧改 DOM) */
function startRotation() { const disc = $("#music-disc"); if (disc) disc.classList.add("spinning"); }
function stopRotation() { const disc = $("#music-disc"); if (disc) disc.classList.remove("spinning"); }

function setMusicPlaying(playing) {
  musicState.playing = playing;
  if (playing) musicState.miniClosed = false;
  const btn = $("#music-play");
  if (btn) btn.innerHTML = svgIcon(playing ? "pause" : "play");
  if (playing) startRotation(); else stopRotation();
  const eq = $("#music-eq"); if (eq) eq.classList.toggle("on", playing);
  const cur = $("#music-list li.playing .ml-idx");
  if (cur) cur.textContent = (playing && musicState.index >= 0) ? "♪" : String(musicState.index + 1);
  updateMiniPlayer();
}

/* ---- 右下角迷你播放器 ---- */
function updateMiniPlayer() {
  const box = $("#mini-player");
  if (!box) return;
  const it = musicState.list[musicState.index];
  const show = !!it && state.view !== "music" && !musicState.miniClosed;
  box.hidden = !show;
  if (!show) return;
  const t = $("#mp-title");
  if (t) t.textContent = it.name.replace(/\.[^.]+$/, "");
  const pb = $("#mp-play");
  if (pb) pb.innerHTML = svgIcon(musicState.playing ? "pause" : "play");
  const a = musicAudio();
  const bar = $("#mp-progress");
  const dur = a && isFinite(a.duration) ? a.duration : 0;
  const cur = a ? (a.currentTime || 0) : 0;
  if (bar && dur > 0) bar.style.width = ((cur / dur) * 100).toFixed(2) + "%";
  const mt = $("#mp-time");
  if (mt) mt.textContent = fmtClock(cur) + " / " + fmtClock(dur);
}

async function loadMusic() {
  const ul = $("#music-list");
  if (ul) ul.innerHTML = '<li class="music-empty">加载中…</li>';
  applyMusicVolume();
  try {
    const items = await api("/api/media?type=audio&sort=name&limit=500&offset=0");
    musicState.list = items || [];
    renderMusicList();
  } catch (e) {
    if (ul) ul.innerHTML = '<li class="music-empty">加载失败: ' + e.message + '</li>';
  }
}

function renderMusicList() {
  const ul = $("#music-list");
  if (!ul) return;
  const q = (musicState.filter || "").trim().toLowerCase();
  const favs = musicFavSet();
  let items = q ? musicState.list.filter((it) => it.name.toLowerCase().includes(q)) : musicState.list;
  if (musicState.favOnly) items = items.filter((it) => favs.has(it.path));
  const favCount = musicState.list.filter((it) => favs.has(it.path)).length;
  const fc = $("#music-fav-count"); if (fc) fc.textContent = favCount ? "(" + favCount + ")" : "";
  $("#music-count").textContent = musicState.list.length
    ? (q || musicState.favOnly ? items.length + " / " + musicState.list.length + " 首" : musicState.list.length + " 首") : "";
  ul.innerHTML = "";
  if (!items.length) {
    ul.innerHTML = '<li class="music-empty">' +
      (musicState.favOnly ? "还没有收藏的歌曲（点列表右侧 ♥ 即可收藏）" : musicState.list.length ? "没有匹配的歌名" : "暂无音乐文件(索引建好后才有)") + "</li>";
    return;
  }
  items.forEach((it) => {
    const i = musicState.list.indexOf(it);
    const li = document.createElement("li");
    if (i === musicState.index) li.className = "playing";
    li.innerHTML = '<span class="ml-idx"></span><span class="ml-main"><span class="ml-name"></span><span class="ml-sub"></span></span><span class="ml-ext"></span><span class="ml-fav" title="收藏 / 取消收藏">♥</span>';
    li.querySelector(".ml-idx").textContent = (i === musicState.index && musicState.playing) ? "♪" : String(i + 1);
    li.querySelector(".ml-name").textContent = it.name.replace(/\.[^.]+$/, "");
    li.querySelector(".ml-sub").textContent = fmtSize(it.size) + (it.modTime ? " · " + fmtTime(it.modTime) : "");
    const ext = (it.name.match(/\.([^.]+)$/) || [, ""])[1].toUpperCase();
    li.querySelector(".ml-ext").textContent = ext;
    const favEl = li.querySelector(".ml-fav");
    favEl.classList.toggle("on", favs.has(it.path));
    favEl.onclick = (ev) => { ev.stopPropagation(); toggleMusicFav(it.path); };
    li.title = it.name;
    li.onclick = () => playMusic(i);
    ul.appendChild(li);
  });
  const cur = ul.querySelector("li.playing");
  if (cur) { try { cur.scrollIntoView({ block: "nearest" }); } catch (e) {} }
}

function initMusicTrack() {
  const it = musicState.list[musicState.index];
  if (!it) return;
  const a = musicAudio();
  $("#music-title").textContent = it.name.replace(/\.[^.]+$/, "");
  let artist = (it.path || "").replace(/\/[^/]*$/, "");
  if (/^\/[^/]*$/.test(artist)) artist = ""; // 库根目录不显示为歌手
  $("#music-artist").textContent = artist;
  a.src = apiUrl("/api/raw?vpath=" + enc(it.path));
  // 音量(记忆) + 上次播放位置(记忆,>3s 才续播)
  applyMusicVolume();
  musicState.pendingPos = 0;
  try { const p = parseFloat(localStorage.getItem("linnas_mpos:" + it.path) || ""); if (isFinite(p) && p > 3) musicState.pendingPos = p; } catch (e) {}
  const seek = $("#music-seek");
  seek.max = "100"; seek.value = 0;
  $("#music-cur").textContent = "0:00";
  $("#music-dur").textContent = "0:00";
  renderMusicList();
  updateMiniPlayer();
}

async function playMusic(i) {
  musicState.index = i;
  initMusicTrack();
  const a = musicAudio();
  try { await a.play(); setMusicPlaying(true); }
  catch (e) { setMusicPlaying(false); toast("无法播放: " + (e && e.message ? e.message : e)); }
}

function toggleMusic() {
  const a = musicAudio();
  if (!musicState.list.length) { loadMusic(); return; }
  if (musicState.index < 0) { playMusic(0); return; }
  if (a.paused) { a.play().then(() => setMusicPlaying(true)).catch((e) => toast("无法播放: " + e.message)); }
  else { a.pause(); setMusicPlaying(false); }
}

function musicStep(delta) {
  const n = musicState.list.length;
  if (!n) return;
  if (musicIsShuffle() && n > 1 && delta > 0) {
    let k = musicState.index;
    for (let t = 0; t < 8; t++) { const r = Math.floor(Math.random() * n); if (r !== musicState.index) { k = r; break; } }
    if (k === musicState.index) k = (musicState.index + 1) % n;
    playMusic(k);
    return;
  }
  playMusic((musicState.index + delta + n) % n);
}

/* 播放模式 / 睡眠定时 / 音量 / 筛选 状态与按钮同步 */
function syncMusicToggles() {
  const md = $("#music-mode");
  if (md) {
    const meta = MUSIC_MODE_META[musicState.mode] || MUSIC_MODE_META.all;
    md.textContent = meta.icon;
    md.dataset.mode = musicState.mode;
    md.classList.toggle("on", musicState.mode !== "all");
    md.setAttribute("aria-pressed", musicState.mode !== "all" ? "true" : "false");
    md.title = meta.title + "(点击切换:列表播放 → 单曲循环 → 随机播放)";
  }
  const sl = $("#music-sleep");
  if (sl) {
    const on = musicState.sleepMin > 0;
    sl.classList.toggle("on", on);
    sl.setAttribute("aria-pressed", on ? "true" : "false");
  }
  renderSleepLeft();
}
function refreshMusicPos() {
  const a = musicAudio();
  if (!a || !isFinite(a.duration)) return;
  $("#music-cur").textContent = fmtClock(a.currentTime);
  $("#music-seek").value = String(a.currentTime || 0);
  try {
    const now = Date.now();
    if (now - musicState.saveAt > 3000) {
      musicState.saveAt = now;
      const it = musicState.list[musicState.index];
      if (it && a.currentTime > 0) localStorage.setItem("linnas_mpos:" + it.path, String(a.currentTime));
    }
  } catch (e) {}
}

$("#btn-music").onclick = () => { showMusic(); applyMusicVolume(); loadMusic(); };
$("#music-play").onclick = toggleMusic;
$("#music-prev").onclick = () => musicStep(-1);
$("#music-next").onclick = () => musicStep(1);
/* 迷你播放器控件 */
$("#mp-prev").onclick = () => musicStep(-1);
$("#mp-play").onclick = () => toggleMusic();
$("#mp-next").onclick = () => musicStep(1);
$("#mp-close").onclick = () => { const a = musicAudio(); if (a) a.pause(); setMusicPlaying(false); musicState.miniClosed = true; updateMiniPlayer(); };
$("#mp-disc").onclick = () => { showMusic(); loadMusic(); };

/* 媒体库里的音乐:点击即在音乐播放器中后台播放 */
async function playMediaAudio(it) {
  if (!musicState.list.length) await loadMusic();
  let idx = musicState.list.findIndex((x) => x.path === it.path);
  if (idx < 0) { musicState.list.push(it); idx = musicState.list.length - 1; renderMusicList(); }
  musicState.miniClosed = false;
  playMusic(idx);
}
(function bindMusicAudio() {
  const a = $("#music-audio");
  if (!a) return;
  a.addEventListener("loadedmetadata", () => {
    const d = isFinite(a.duration) ? a.duration : 0;
    $("#music-seek").max = String(d || 100);
    $("#music-dur").textContent = fmtClock(d);
    if (musicState.pendingPos > 0 && d > 0 && musicState.pendingPos < d - 3) {
      try { a.currentTime = musicState.pendingPos; } catch (e) {}
    }
    musicState.pendingPos = 0;
    updateMiniPlayer();
  });
  a.addEventListener("timeupdate", () => {
    if (!a.paused) refreshMusicPos();
    if (!$("#mini-player").hidden) updateMiniPlayer();
  });
  a.addEventListener("ended", () => {
    if (musicIsRepeatOne()) { try { a.currentTime = 0; } catch (e) {} a.play().catch(() => {}); return; }
    const n = musicState.list.length;
    if (musicIsShuffle() && n > 1) { musicStep(1); return; }
    if (musicState.index < n - 1) { musicStep(1); return; }
    if (musicState.mode === "all" && n > 0) { playMusic(0); return; }
    setMusicPlaying(false);
  });
  a.addEventListener("play", () => setMusicPlaying(true));
  a.addEventListener("pause", () => setMusicPlaying(false));
})();
(function bindMusicSeek() {
  const seek = $("#music-seek");
  if (!seek) return;
  seek.addEventListener("input", () => {
    const a = musicAudio();
    if (isFinite(a.duration)) $("#music-cur").textContent = fmtClock(seek.value);
  });
  seek.addEventListener("change", () => {
    const a = musicAudio();
    const t = parseFloat(seek.value) || 0;
    if (isFinite(a.duration)) a.currentTime = t;
    if (musicState.playing) a.play().catch(() => {});
  });
})();
window.addEventListener("beforeunload", () => stopRotation());

/* 播放模式 / 音量 / 筛选 / 睡眠定时 / 迷你播放器拖动进度 / 音乐页快捷键 */
(function bindMusicExtras() {
  const mode = $("#music-mode"), vol = $("#music-vol"), filter = $("#music-filter");
  if (mode) mode.onclick = () => {
    musicState.mode = MUSIC_MODES[(MUSIC_MODES.indexOf(musicState.mode) + 1) % MUSIC_MODES.length];
    try { localStorage.setItem("linnas_music_mode", musicState.mode); } catch (e) {}
    syncMusicToggles();
    toast("播放模式:" + MUSIC_MODE_META[musicState.mode].label);
  };
  if (vol) vol.oninput = () => {
    const a = musicAudio(); const v = parseFloat(vol.value);
    if (a) a.volume = isFinite(v) ? Math.min(1, Math.max(0, v)) : 1;
    const pct = $("#music-vol-pct"); if (pct) pct.textContent = Math.round((isFinite(v) ? v : 1) * 100) + "%";
    try { localStorage.setItem("linnas_music_vol", String(isFinite(v) ? v : 1)); } catch (e) {}
  };
  const mute = $("#music-mute");
  if (mute) mute.onclick = () => { const a = musicAudio(); if (a) a.muted = !a.muted; applyMusicVolume(); };
  const sleep = $("#music-sleep");
  if (sleep) sleep.onclick = (ev) => { ev.stopPropagation(); openSleepMenu(sleep); };
  document.querySelectorAll("#music-chips .music-chip").forEach((chip) => {
    chip.onclick = () => {
      musicState.favOnly = chip.dataset.fav === "1";
      document.querySelectorAll("#music-chips .music-chip").forEach((c) => c.classList.toggle("on", c === chip));
      renderMusicList();
    };
  });
  applyMusicVolume();
  if (filter) filter.oninput = () => { musicState.filter = filter.value; renderMusicList(); };
  syncMusicToggles();

  // 迷你播放器:点击/拖动进度条跳转
  const bar = $("#mp-bar");
  if (bar) {
    const seekTo = (ev) => {
      const a = musicAudio();
      if (!a || !isFinite(a.duration) || a.duration <= 0) return;
      const r = bar.getBoundingClientRect();
      const ratio = Math.min(Math.max((ev.clientX - r.left) / Math.max(1, r.width), 0), 1);
      try { a.currentTime = ratio * a.duration; } catch (e) {}
      updateMiniPlayer();
    };
    bar.addEventListener("pointerdown", (ev) => {
      ev.preventDefault();
      try { bar.setPointerCapture(ev.pointerId); } catch (e) {}
      seekTo(ev);
      const mv = (e2) => seekTo(e2);
      const up = () => { bar.removeEventListener("pointermove", mv); bar.removeEventListener("pointerup", up); };
      bar.addEventListener("pointermove", mv);
      bar.addEventListener("pointerup", up);
    });
  }

  // 音乐视图快捷键:空格播放/暂停,←/→ ±5 秒
  document.addEventListener("keydown", (e) => {
    if (state.view !== "music") return;
    if (!$("#player-modal").hidden || document.querySelector("dialog[open]")) return;
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    const a = musicAudio();
    if (!a) return;
    if (e.code === "Space") { e.preventDefault(); toggleMusic(); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); try { a.currentTime = Math.max(0, (a.currentTime || 0) - 5); } catch (er) {} }
    else if (e.key === "ArrowRight") { e.preventDefault(); try { a.currentTime = Math.min(a.duration || 1e9, (a.currentTime || 0) + 5); } catch (er) {} }
  });
})();

/* ============================================================ 媒体库 */
$("#btn-media").onclick = () => { showMedia(); state.mediaOffset = 0; loadMedia(true); };
document.querySelectorAll("#media-tabs button").forEach((b) => {
  b.onclick = () => {
    document.querySelectorAll("#media-tabs button").forEach((x) => x.classList.remove("on"));
    b.classList.add("on");
    state.mediaType = b.dataset.type; state.mediaOffset = 0; loadMedia(true);
  };
});
$("#media-sort").onchange = (e) => { state.mediaSort = e.target.value; state.mediaOffset = 0; loadMedia(true); };
$("#btn-media-more").onclick = () => loadMedia(false, true);

// 媒体库自动翻页:一次 500(后端上限),进入页面后自动拉完剩余页,
// 避免用户必须点「加载更多」才能看到同一文件夹里后面的图片。
const MEDIA_PAGE_SIZE = 500;
const MEDIA_AUTO_CAP = 2000; // 自动加载上限,防止超大库一次渲染过多卡片
let mediaLoadSeq = 0;

async function loadMedia(reset, manual) {
  const grid = $("#media-grid"), sub = $("#media-sub");
  if (manual && state.mediaLoading) return;
  if (reset) { state.mediaOffset = 0; state.mediaItems = []; grid.innerHTML = '<div class="pempty" style="grid-column:1/-1">加载中…</div>'; }
  const seq = ++mediaLoadSeq;
  state.mediaLoading = true;
  const label = { video: "视频", image: "图片", audio: "音乐" }[state.mediaType] || "媒体";
  let lastLen = 0;
  try {
    do {
      const items = await api(`/api/media?type=${enc(state.mediaType)}&sort=${enc(state.mediaSort)}&limit=${MEDIA_PAGE_SIZE}&offset=${state.mediaOffset}`);
      if (seq !== mediaLoadSeq) return; // 期间切换了分类/排序,丢弃旧结果
      lastLen = (items || []).length;
      state.mediaItems = state.mediaItems.concat(items || []);
      state.mediaOffset = state.mediaItems.length;
      const hasMore = lastLen >= MEDIA_PAGE_SIZE;
      sub.textContent = `${label} · 已加载 ${state.mediaItems.length} 项` +
        (hasMore && !manual && state.mediaItems.length < MEDIA_AUTO_CAP ? "…" : "");
      if (!hasMore) break;
      if (manual) break;                                    // 手动「加载更多」:一次一页
      if (state.mediaItems.length >= MEDIA_AUTO_CAP) break; // 已达自动上限
      if (state.view !== "media") break;                    // 用户离开了媒体库
      await new Promise((r) => setTimeout(r, 0));           // 让出主线程,避免界面卡住
    } while (true);
    if (seq !== mediaLoadSeq) return;
    renderMediaGrid();
    const capped = state.mediaItems.length >= MEDIA_AUTO_CAP && lastLen >= MEDIA_PAGE_SIZE;
    $("#btn-media-more").hidden = !capped;
    sub.textContent = `${label} · 已加载 ${state.mediaItems.length} 项` +
      (capped ? `（已到 ${MEDIA_AUTO_CAP} 项上限，可点「加载更多」继续）` : "");
  } catch (e) {
    if (seq === mediaLoadSeq) grid.innerHTML = `<div class="pempty" style="grid-column:1/-1">加载失败: ${e.message}</div>`;
  } finally {
    if (seq === mediaLoadSeq) state.mediaLoading = false;
  }
}

/* 分组折叠句柄（列数变化时重算「留一行」的边界） */
const mediaCollapseHandles = [];
let mediaResizeTimer = null;
window.addEventListener("resize", () => {
  if (!mediaCollapseHandles.length) return;
  clearTimeout(mediaResizeTimer);
  mediaResizeTimer = setTimeout(() => {
    mediaCollapseHandles.forEach((hd) => hd.apply(!!state.mediaCollapsed[hd.dir]));
  }, 120);
});

function renderMediaGrid() {
  const grid = $("#media-grid");
  grid.innerHTML = "";
  mediaCollapseHandles.length = 0;
  if (!state.mediaItems.length) { grid.innerHTML = '<div class="pempty" style="grid-column:1/-1">暂无媒体文件(索引建好后才有)</div>'; return; }
  const mkRow = (it) => {
    const kind = it.kind || "other";
    const row = document.createElement("div");
    row.className = "frow";
    const icon = document.createElement("div");
    icon.className = "fi t-" + kind;
    if (kind === "image" || kind === "video") {
      icon.classList.add("thumb");
      const img = document.createElement("img");
      img.loading = "lazy"; img.decoding = "async"; img.alt = ""; img.draggable = false;
      img.src = thumbUrl(it.path, 420);
      img.onerror = () => { icon.classList.remove("thumb"); icon.innerHTML = svgIcon(kind === "video" ? "video" : "image"); };
      icon.appendChild(img);
    } else { icon.innerHTML = svgIcon(kind === "audio" ? "music" : "file"); }
    const meta = document.createElement("div"); meta.className = "fmain";
    const nm = document.createElement("div"); nm.className = "fname"; nm.textContent = it.name;
    const sb = document.createElement("div"); sb.className = "fsub"; sb.textContent = fmtSize(it.size) + (it.modTime ? "  ·  " + fmtTime(it.modTime) : "");
    meta.appendChild(nm); meta.appendChild(sb);
    const acts = document.createElement("div"); acts.className = "facts";
    const ab = (ic, t, fn) => { const b = document.createElement("button"); b.className = "btn icon-btn"; b.title = t; b.innerHTML = svgIcon(ic); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; acts.appendChild(b); };
    const openIt = () => (kind === "audio" ? playMediaAudio(it) : openPreview(kind, it.path, it.name));
    ab("play", kind === "audio" ? "播放" : "预览", openIt);
    ab("info", "详情", () => showInfo(it.path));
    ab("download", "下载", () => { location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(it.path)); });
    ab("folder", "打开所在文件夹", () => { openPath(it.path.replace(/\/[^/]*$/, "") || "/"); });
    row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);
    row.onclick = () => (kind === "audio" ? playMediaAudio(it) : openPreview(kind, it.path, it.name));
    return row;
  };
  if (state.mediaGroup) {
    const groups = new Map();
    for (const it of state.mediaItems) {
      const dir = it.path.replace(/\/[^/]*$/, "") || "/";
      if (!groups.has(dir)) groups.set(dir, []);
      groups.get(dir).push(it);
    }
    const dirs = Array.from(groups.keys()).sort((a, b) => a.localeCompare(b));
    for (const d of dirs) {
      const h = document.createElement("div");
      h.className = "mgroup-head";
      const b = document.createElement("b"); b.textContent = d;
      const c = document.createElement("span"); c.className = "mgroup-count"; c.textContent = groups.get(d).length + " 项";
      const label = document.createElement("span"); label.className = "mgroup-label";
      label.appendChild(b); label.appendChild(c);
      const tg = document.createElement("button");
      tg.className = "btn icon-btn mgroup-toggle";
      tg.innerHTML = `<span class="mgroup-chev">${svgIcon("arrow-down")}</span>`;
      h.appendChild(label); h.appendChild(tg);
      grid.appendChild(h);
      const rows = [];
      groups.get(d).forEach((it) => { const r = mkRow(it); rows.push(r); grid.appendChild(r); });

      const handle = { dir: d, rows, tile: null };
      const setAttrs = (now) => {
        h.classList.toggle("collapsed", now);
        tg.title = now ? "展开该组" : "收起该组";
        tg.setAttribute("aria-expanded", now ? "false" : "true");
      };
      const expand = (ev) => {
        if (ev) { ev.stopPropagation(); ev.preventDefault(); }
        state.mediaCollapsed[d] = false;
        handle.apply(false);
      };
      // 收起 = 只留第一行卡片，末位放一个「+N 展开全部」磁贴
      handle.apply = (now) => {
        rows.forEach((r) => { r.hidden = false; });
        if (handle.tile) { handle.tile.remove(); handle.tile = null; }
        if (!now || !rows.length) { setAttrs(false); return; }
        const top = rows[0].offsetTop;
        let per = 0;
        for (const r of rows) { if (r.offsetTop !== top) break; per++; }
        if (per <= 0 || per >= rows.length) { state.mediaCollapsed[d] = false; setAttrs(false); return; }
        const keep = Math.max(0, per - 1);
        const tile = document.createElement("button");
        tile.type = "button";
        tile.className = "mgroup-more";
        tile.title = "展开该组全部 " + rows.length + " 项";
        tile.innerHTML = '<span class="mm-plus">+' + (rows.length - keep) + '</span><em>展开全部</em>';
        tile.onclick = expand;
        rows[keep].parentNode.insertBefore(tile, rows[keep]);
        rows.slice(keep).forEach((r) => { r.hidden = true; });
        handle.tile = tile;
        setAttrs(true);
      };
      tg.onclick = (ev) => {
        ev.stopPropagation();
        const now = !state.mediaCollapsed[d];
        state.mediaCollapsed[d] = now;
        handle.apply(now);
      };
      mediaCollapseHandles.push(handle);
    }
    // 布局完成后按真实列数应用（首次渲染时行高/列数还没定）
    requestAnimationFrame(() => mediaCollapseHandles.forEach((hd) => hd.apply(!!state.mediaCollapsed[hd.dir])));
    return;
  }
  for (const it of state.mediaItems) grid.appendChild(mkRow(it));
}

/* ============================================================ 全局搜索 */
let searchTimer = null;
$("#btn-search").onclick = () => { showSearch(); };
$("#in-global-search").oninput = (e) => {
  const q = e.target.value.trim();
  clearTimeout(searchTimer);
  if (!q) { clearSearchView(); return; }
  searchTimer = setTimeout(() => doGlobalSearch(q), 250);
};

// 搜索范围分段: 文件名 / 内容 / 全部
$$("#search-scope button").forEach((b) => {
  b.onclick = () => {
    const s = b.dataset.scope;
    state.searchScope = (s === "file" || s === "content") ? s : "all";
    syncSearchScope();
    const q = $("#in-global-search").value.trim();
    if (q) doGlobalSearch(q); else clearSearchView();
  };
});
function syncSearchScope() {
  const scope = state.searchScope || "all";
  $$("#search-scope button").forEach((b) => {
    b.classList.toggle("on", (b.dataset.scope || "all") === scope);
    b.setAttribute("aria-selected", (b.dataset.scope || "all") === scope ? "true" : "false");
  });
}

// 内容索引状态(搜索页顶部小字): GET /api/content/status
async function loadSearchContentStatus() {
  const el = $("#search-content-status");
  if (!el) return;
  clearTimeout(state.searchStatusTimer);
  let st = null;
  try { st = await api("/api/content/status"); } catch (e) { st = null; }
  state.searchContentStatus = st;
  renderSearchContentStatus();
  const running = st && Number(st.running) > 0;
  if (running && state.view === "search" && st.enabled !== false) {
    state.searchStatusTimer = setTimeout(() => { if (state.view === "search") loadSearchContentStatus(); }, 2000);
  }
}
function renderSearchContentStatus() {
  const el = $("#search-content-status");
  if (!el) return;
  const st = state.searchContentStatus;
  if (!st) { el.textContent = ""; return; }
  if (st.enabled === false) { el.textContent = "内容索引：未启用"; return; }
  const qn = Number(st.queued) || 0, rn = Number(st.running) || 0;
  el.textContent = `内容索引：队列 ${qn} / 进行中 ${rn}`;
}

function clearSearchView() {
  state.searchResults = [];
  const sub = $("#search-sub"); if (sub) sub.textContent = "";
  const list = $("#search-list");
  if (list) { list.className = "file-list"; list.innerHTML = ""; }
}

async function doGlobalSearch(q) {
  q = (q || "").trim();
  if (!q) { clearSearchView(); return; }
  const scope = state.searchScope || "all";
  const seq = (state.searchSeq || 0) + 1;
  state.searchSeq = seq;
  const list = $("#search-list");
  list.className = "file-list";
  list.innerHTML = '<div class="pempty">搜索中…</div>';
  $("#search-sub").textContent = "";
  try {
    if (scope === "file") {
      const items = await api("/api/search?q=" + enc(q) + "&limit=200");
      if (seq !== state.searchSeq) return;
      state.searchResults = Array.isArray(items) ? items : [];
      renderSearchResults();
      $("#search-sub").textContent = `找到 ${state.searchResults.length} 个文件`;
    } else if (scope === "content") {
      const r = await api("/api/search/content?q=" + enc(q) + "&limit=50");
      if (seq !== state.searchSeq) return;
      renderContentResults(q, r);
    } else {
      const both = await Promise.all([
        api("/api/search?q=" + enc(q) + "&limit=200").catch(() => []),
        api("/api/search/content?q=" + enc(q) + "&limit=50").catch(() => ({ hits: [], total: 0 })),
      ]);
      if (seq !== state.searchSeq) return;
      state.searchResults = Array.isArray(both[0]) ? both[0] : [];
      renderSearchAll(q, state.searchResults, both[1] || { hits: [], total: 0 });
    }
  } catch (e) {
    if (seq !== state.searchSeq) return;
    list.className = "file-list";
    list.innerHTML = "";
    list.appendChild(csEmpty("搜索失败：" + (e && e.message ? e.message : "未知错误")));
  }
}

// 单个文件名搜索结果行(复用现有样式)
function fileResultRow(it) {
  const kind = it.kind || "other";
  const row = document.createElement("div"); row.className = "frow sr-row";
  const icon = document.createElement("div"); icon.className = "fi t-" + kind;
  icon.innerHTML = svgIcon(kind === "video" ? "video" : kind === "image" ? "image" : kind === "audio" ? "music" : kind === "dir" ? "folder" : "file");
  const meta = document.createElement("div"); meta.className = "fmain";
  const nm = document.createElement("div"); nm.className = "fname"; nm.textContent = it.name;
  const sp = document.createElement("div"); sp.className = "spath"; sp.textContent = it.path;
  meta.appendChild(nm); meta.appendChild(sp);
  const acts = document.createElement("div"); acts.className = "facts";
  const ab = (ic, t, fn) => { const b = document.createElement("button"); b.className = "btn icon-btn"; b.title = t; b.innerHTML = svgIcon(ic); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; acts.appendChild(b); };
  if (kind === "video" || kind === "audio" || kind === "image") ab("play", "预览", () => openPreview(kind, it.path, it.name));
  ab("folder", "打开位置", () => { openPath(it.path.replace(/\/[^/]*$/, "") || "/"); });
  row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);
  if (kind === "video" || kind === "audio" || kind === "image") row.onclick = () => openPreview(kind, it.path, it.name);
  return row;
}

function renderSearchResults() {
  const list = $("#search-list"); list.className = "file-list"; list.innerHTML = "";
  if (!state.searchResults.length) { list.innerHTML = '<div class="pempty">没有找到匹配文件</div>'; return; }
  for (const it of state.searchResults) list.appendChild(fileResultRow(it));
}

// ---- 内容搜索结果(批次 3) ----
function escHtml(s) {
  return String(s === null || s === undefined ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
function csEmpty(text) {
  const d = document.createElement("div"); d.className = "cs-empty"; d.textContent = text; return d;
}
function csGroupHead(title, count) {
  const h = document.createElement("div"); h.className = "cs-group-head";
  const t = document.createElement("span"); t.className = "cs-group-title"; t.textContent = title; h.appendChild(t);
  if (count !== undefined && count !== null) {
    const c = document.createElement("span"); c.className = "cs-group-count"; c.textContent = count + " 项"; h.appendChild(c);
  }
  return h;
}
// 先 HTML 转义，再对命中区间用 <mark> 包裹，避免 XSS
function highlightSnippet(text, start, len, q) {
  text = String(text === null || text === undefined ? "" : text);
  const s = (typeof start === "number" && start >= 0 && start < text.length) ? start : -1;
  const L = (typeof len === "number" && len > 0) ? len : 0;
  if (s >= 0 && L > 0 && s + L <= text.length) {
    return escHtml(text.slice(0, s)) + "<mark>" + escHtml(text.slice(s, s + L)) + "</mark>" + escHtml(text.slice(s + L));
  }
  // 后端未给区间时的兜底: 按查询词首个命中位置高亮
  const sq = q ? String(q) : "";
  if (!sq) return escHtml(text);
  const idx = text.toLowerCase().indexOf(sq.toLowerCase());
  if (idx < 0) return escHtml(text);
  return escHtml(text.slice(0, idx)) + "<mark>" + escHtml(text.slice(idx, idx + sq.length)) + "</mark>" + escHtml(text.slice(idx + sq.length));
}
function contentHitEl(hit, q) {
  hit = hit || {};
  const el = document.createElement("div");
  el.className = "cs-hit"; el.tabIndex = 0; el.setAttribute("role", "button");
  el.title = "跳转到文件所在目录";
  const head = document.createElement("div"); head.className = "cs-file";
  const ic = document.createElement("div"); ic.className = "cs-ic"; ic.innerHTML = svgIcon("file");
  const meta = document.createElement("div"); meta.className = "cs-meta";
  const nm = document.createElement("div"); nm.className = "cs-name";
  nm.textContent = hit.name || (hit.path ? String(hit.path).split("/").pop() : "文件");
  const sub = document.createElement("div"); sub.className = "cs-path";
  const bits = [];
  if (hit.path) bits.push(String(hit.path));
  if (hit.size !== undefined && hit.size !== null) bits.push(fmtSize(hit.size));
  if (hit.modTime) bits.push(fmtTime(hit.modTime));
  sub.textContent = bits.join(" · ");
  meta.appendChild(nm); meta.appendChild(sub);
  head.appendChild(ic); head.appendChild(meta);
  el.appendChild(head);
  const snips = Array.isArray(hit.snippets) ? hit.snippets : [];
  if (snips.length) {
    const box = document.createElement("div"); box.className = "cs-snippets";
    for (const sn of snips) {
      const d = document.createElement("div"); d.className = "cs-snippet";
      d.innerHTML = highlightSnippet(sn && sn.text, sn && sn.start, sn && sn.len, q);
      box.appendChild(d);
    }
    el.appendChild(box);
  }
  el.onclick = () => openContentHit(hit);
  el.onkeydown = (ev) => { if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); openContentHit(hit); } };
  return el;
}
// 点击内容命中: 跳到所在目录(复用 openPath); 不可靠时复制路径 + toast
function openContentHit(hit) {
  if (!hit) { toast("无法定位该文件"); return; }
  let vp = hit.path ? String(hit.path) : "";
  if (/^[A-Za-z]:[\\/]/.test(vp)) { copyText(vp); toast("已复制路径，无法自动定位目录"); return; }
  if (vp && vp.charAt(0) !== "/" && hit.libId) vp = "/" + hit.libId + "/" + vp.replace(/^\/+/, "");
  if (vp && /^\/lib[^/]+/.test(vp)) {
    const dir = vp.replace(/\/[^/]*$/, "") || "/";
    openPath(dir);
    return;
  }
  if (vp) { copyText(vp); toast("已复制路径，无法自动定位目录"); return; }
  toast("无法定位该文件");
}
const CONTENT_EMPTY_HINT = "仅索引 UTF-8 文本类文档，PDF 未支持";
function renderContentResults(q, r) {
  const list = $("#search-list"); list.className = "file-list"; list.innerHTML = "";
  const hits = (r && Array.isArray(r.hits)) ? r.hits : [];
  const total = (r && typeof r.total === "number") ? r.total : hits.length;
  if (!hits.length) {
    $("#search-sub").textContent = "内容搜索：没有找到匹配";
    list.appendChild(csEmpty(CONTENT_EMPTY_HINT));
    return;
  }
  $("#search-sub").textContent = `内容搜索：${total} 个文件${(r && r.truncated) ? "（已截断）" : ""}`;
  for (const h of hits) list.appendChild(contentHitEl(h, q));
  if (r && r.truncated) list.appendChild(csEmpty("结果较多，仅显示前 " + hits.length + " 个文件"));
}
function renderSearchAll(q, fileItems, contentR) {
  const list = $("#search-list"); list.className = "file-list"; list.innerHTML = "";
  const files = Array.isArray(fileItems) ? fileItems : [];
  const hits = (contentR && Array.isArray(contentR.hits)) ? contentR.hits : [];
  const cTotal = (contentR && typeof contentR.total === "number") ? contentR.total : hits.length;

  const fg = document.createElement("div"); fg.className = "cs-group";
  fg.appendChild(csGroupHead("文件名匹配", files.length));
  if (files.length) { for (const it of files) fg.appendChild(fileResultRow(it)); }
  else fg.appendChild(csEmpty("没有匹配的文件名"));
  list.appendChild(fg);

  const cg = document.createElement("div"); cg.className = "cs-group";
  cg.appendChild(csGroupHead("内容匹配", cTotal));
  if (hits.length) { for (const h of hits) cg.appendChild(contentHitEl(h, q)); }
  else cg.appendChild(csEmpty(CONTENT_EMPTY_HINT));
  list.appendChild(cg);

  $("#search-sub").textContent = `文件名 ${files.length} 个 · 内容 ${cTotal} 个文件${(contentR && contentR.truncated) ? "（已截断）" : ""}`;
}

/* ============================================================ 设备管理 */
$("#btn-devices").onclick = async () => { showDevices(); await loadDevices(); };
$("#btn-dev-refresh").onclick = () => loadDevices();
$("#btn-dev-clear").onclick = async () => {
  const ok = await askConfirm("清空设备记录", "将清空设备列表(不影响正在连接的设备)。");
  if (!ok) return;
  try { await api("/api/devices/clear", { method: "POST", body: {} }); await loadDevices(); toast("已清空"); }
  catch (e) { toast("失败: " + e.message); }
};
async function loadDevices() {
  try { state.devices = await api("/api/devices"); } catch (e) { state.devices = []; }
  renderDevices();
}
function deviceName(ua) {
  ua = ua || "";
  let os = "未知系统";
  if (/Android/.test(ua)) os = "Android";
  else if (/iPhone|iPad|iOS/.test(ua)) os = "iOS";
  else if (/Windows NT 10/.test(ua)) os = "Windows 10/11";
  else if (/Windows/.test(ua)) os = "Windows";
  else if (/Mac OS X|Macintosh/.test(ua)) os = "macOS";
  else if (/Linux/.test(ua)) os = "Linux";
  let br = "";
  if (/Edg\//.test(ua)) br = "Edge";
  else if (/Chrome\//.test(ua)) br = "Chrome";
  else if (/Firefox\//.test(ua)) br = "Firefox";
  else if (/Safari\//.test(ua)) br = "Safari";
  else if (/curl|Wget|python|Go-http|okhttp/i.test(ua)) br = "程序";
  else if (ua) br = "浏览器";
  return br ? br + " · " + os : os;
}
function deviceIcon(ua) {
  ua = ua || "";
  if (/Android|iPhone|Mobile/.test(ua)) return "smartphone";
  if (/iPad|Tablet/.test(ua)) return "tablet";
  if (/SmartTV|TV|AppleTV/.test(ua)) return "tv";
  return "monitor";
}
function renderDevices() {
  const wrap = $("#device-list"); wrap.innerHTML = "";
  if (!state.devices.length) { wrap.innerHTML = '<div class="pempty">暂无设备记录</div>'; return; }
  state.devices.forEach((d) => {
    const row = document.createElement("div"); row.className = "devrow";
    const ic = document.createElement("div"); ic.className = "dicon"; ic.innerHTML = svgIcon(deviceIcon(d.ua));
    const meta = document.createElement("div"); meta.className = "dmeta";
    const nm = document.createElement("div"); nm.className = "dname"; nm.textContent = deviceName(d.ua);
    const sb = document.createElement("div"); sb.className = "dsub"; sb.textContent = `${d.ip} · 访问 ${d.count} 次 · 首次 ${fmtTime(d.first)}`;
    meta.appendChild(nm); meta.appendChild(sb);
    const when = document.createElement("div"); when.className = "dwhen"; when.textContent = "最近 " + fmtTime(d.last);
    row.title = d.ua || "";
    row.appendChild(ic); row.appendChild(meta); row.appendChild(when);
    wrap.appendChild(row);
  });
}

/* ============================================================ 回收站 */
$("#btn-trash").onclick = async () => { showTrash(); syncTrashViewButtons(); await loadTrash(); };
$("#btn-trash-refresh").onclick = () => loadTrash();
$("#btn-trash-empty").onclick = async () => {
  const ok = await askConfirm("清空回收站", "将彻底删除回收站中的所有文件,不可恢复。");
  if (!ok) return;
  try { const r = await api("/api/trash/empty", { method: "POST", body: {} }); await loadTrash(); toast(`已清理 ${r.count} 项`); }
  catch (e) { toast("失败: " + e.message); }
};
function syncTrashViewButtons() {
  $$("#trash-viewmode button").forEach((b) => b.classList.toggle("on", b.dataset.view === state.trashView));
}
$$("#trash-viewmode button").forEach((b) => {
  b.onclick = () => {
    state.trashView = b.dataset.view === "grid" ? "grid" : "list";
    localStorage.setItem("linnas_trash_view", state.trashView);
    syncTrashViewButtons();
    renderTrash();
  };
});
async function loadTrash() {
  const wrap = $("#trash-list");
  wrap.className = "";
  wrap.innerHTML = '<div class="pempty">加载中…</div>';
  let items = [];
  try { items = await api("/api/trash"); } catch (e) { wrap.innerHTML = `<div class="pempty">加载失败: ${e.message}</div>`; return; }
  state.trash = items || [];
  renderTrash();
}
function trashActions(it) {
  const acts = document.createElement("div"); acts.className = "facts";
  const ab = (ic, title, fn, extra) => { const b = document.createElement("button"); b.className = "btn icon-btn " + (extra || ""); b.title = title; b.setAttribute("aria-label", title); b.innerHTML = svgIcon(ic); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; acts.appendChild(b); };
  ab("refresh", "恢复", async () => {
    try { await api("/api/trash/restore", { method: "POST", body: { id: it.id, lib: it.libId } }); await loadTrash(); toast("已恢复"); }
    catch (e) { toast("失败: " + e.message); }
  });
  ab("trash", "彻底删除", async () => {
    const ok = await askConfirm("彻底删除", `永久删除「${it.name}」?不可恢复。`);
    if (!ok) return;
    try { await api("/api/trash/delete", { method: "POST", body: { id: it.id, lib: it.libId } }); await loadTrash(); toast("已删除"); }
    catch (e) { toast("失败: " + e.message); }
  }, "del");
  return acts;
}
function trashThumbUrl(it, w) {
  return apiUrl(`/api/trash/thumb?id=${enc(it.id)}&lib=${enc(it.libId)}&w=${w || 420}`);
}
function trashIconEl(it, kind, w) {
  const icon = document.createElement("div"); icon.className = "fi t-" + kind;
  if (kind === "image" || kind === "video") {
    icon.classList.add("thumb");
    const img = document.createElement("img");
    img.loading = "lazy"; img.decoding = "async"; img.alt = ""; img.draggable = false;
    img.src = trashThumbUrl(it, w);
    img.onerror = () => { icon.classList.remove("thumb"); icon.innerHTML = svgIcon(kind === "video" ? "video" : "image"); };
    icon.appendChild(img);
  } else {
    icon.innerHTML = svgIcon(kind === "dir" ? "folder" : "file");
  }
  return icon;
}
function renderTrash() {
  const wrap = $("#trash-list");
  wrap.className = "";
  wrap.innerHTML = "";
  if (!state.trash.length) { wrap.innerHTML = '<div class="pempty">回收站是空的</div>'; return; }
  const grid = state.trashView === "grid";
  if (grid) wrap.className = "file-list grid";
  for (const it of state.trash) {
    const kind = it.isDir ? "dir" : kindOf(it.name);
    if (grid) {
      const card = document.createElement("div");
      card.className = "frow" + (it.isDir ? " is-dir" : "");
      const icon = trashIconEl(it, kind, 420);
      const meta = document.createElement("div"); meta.className = "fmain";
      const nm = document.createElement("div"); nm.className = "fname"; nm.textContent = it.name;
      const sb = document.createElement("div"); sb.className = "fsub"; sb.textContent = (it.isDir ? "文件夹" : fmtSize(it.size)) + " · " + fmtTime(it.trashedAt);
      meta.appendChild(nm); meta.appendChild(sb);
      card.appendChild(icon); card.appendChild(meta); card.appendChild(trashActions(it));
      wrap.appendChild(card);
    } else {
      const row = document.createElement("div"); row.className = "vrow";
      const icon = trashIconEl(it, kind, 160);
      const meta = document.createElement("div"); meta.className = "vm";
      meta.innerHTML = `<div class="vname"></div><div class="vsub"></div>`;
      meta.querySelector(".vname").textContent = it.name;
      meta.querySelector(".vsub").textContent = (it.isDir ? "文件夹" : fmtSize(it.size)) + " · 原位置 " + (it.origPath || "") + " · " + fmtTime(it.trashedAt);
      row.appendChild(icon); row.appendChild(meta); row.appendChild(trashActions(it));
      wrap.appendChild(row);
    }
  }
}

/* ============================================================ 存储空间 */
$("#btn-storage").onclick = () => showStorage();
$("#btn-storage-refresh").onclick = () => { loadStorageSummary(); loadStorageTab(); };
$$("#storage-tabs button").forEach((b) => {
  b.onclick = () => setStorageTab(b.dataset.tab);
});

// 供总览「快捷入口」等外部调用：切换存储页签并加载对应数据
function setStorageTab(tab) {
  if (tab !== "dups" && tab !== "trend" && tab !== "patrol" && tab !== "organize") tab = "large";
  state.storageTab = tab;
  $$("#storage-tabs button").forEach((x) => {
    x.classList.toggle("on", x.dataset.tab === state.storageTab);
  });
  $("#storage-large").hidden = state.storageTab !== "large";
  $("#storage-dups").hidden = state.storageTab !== "dups";
  const trendEl = $("#storage-trend");
  if (trendEl) trendEl.hidden = state.storageTab !== "trend";
  const patrolEl = $("#storage-patrol");
  if (patrolEl) patrolEl.hidden = state.storageTab !== "patrol";
  const orgEl = $("#storage-organize");
  if (orgEl) orgEl.hidden = state.storageTab !== "organize";
  if (state.storageTab !== "patrol") stopPatrolPoll();
  if (state.storageTab !== "organize") stopOrganizePoll();
  loadStorageTab();
}

function showStorage() {
  state.view = "storage";
  showView("view-storage");
  renderSidebar();
  loadStorageSummary();
  loadStorageTab();
}
function loadStorageTab() {
  if (state.storageTab === "dups") loadDupsStatus();
  else if (state.storageTab === "trend") loadTrend();
  else if (state.storageTab === "patrol") loadPatrol();
  else if (state.storageTab === "organize") loadOrganize();
  else loadStorageLarge();
}

async function loadStorageSummary() {
  const el = $("#storage-overview");
  const sub = $("#storage-sub");
  el.innerHTML = "";
  try {
    const s = await api("/api/storage/summary");
    renderStorageSummary(s);
  } catch (e) {
    sub.textContent = "无法统计存储占用";
    el.innerHTML = `<div class="st-empty">${e.message || "索引未启用"}</div>`;
  }
  loadDiskHealth();
}
function renderStorageSummary(s) {
  const el = $("#storage-overview");
  const sub = $("#storage-sub");
  el.innerHTML = "";
  const libs = (s && s.libs) || [];
  state.storageLibs = libs;
  sub.textContent = `共 ${s.totalFiles || 0} 个文件 · 占用 ${fmtSize(s.totalBytes || 0)}`;
  if (!libs.length) { el.innerHTML = '<div class="st-empty">暂无索引数据（需先建立文件索引）</div>'; renderDiskHealth(); return; }
  for (const lib of libs) {
    const card = document.createElement("div"); card.className = "st-card";
    card.innerHTML = `
      <div class="st-top">
        <div class="lc-disc">${svgIcon("drive")}</div>
        <div class="st-titles"><div class="st-name"></div><div class="st-path"></div></div>
      </div>
      <div class="st-meta"><span class="st-files"></span><span class="st-size"></span></div>`;
    card.querySelector(".st-name").textContent = lib.name || lib.id;
    card.querySelector(".st-path").textContent = lib.path || "";
    card.querySelector(".st-files").textContent = `${lib.files || 0} 个文件`;
    card.querySelector(".st-size").textContent = fmtSize(lib.bytes || 0);
    el.appendChild(card);
  }
  renderDiskHealth();
}

async function loadStorageLarge() {
  const el = $("#storage-large");
  el.innerHTML = '<div class="st-empty">加载中…</div>';
  let r;
  try { r = await api("/api/storage/large?limit=100"); }
  catch (e) { el.innerHTML = `<div class="st-empty">加载失败: ${e.message}</div>`; return; }
  state.storageLarge = (r && r.items) || [];
  el.innerHTML = "";
  const head = document.createElement("div"); head.className = "st-toolbar";
  const h = document.createElement("span"); h.className = "st-hint";
  h.textContent = `最大的 ${state.storageLarge.length} 个文件 · 合计 ${fmtSize((r && r.totalSize) || 0)}`;
  head.appendChild(h);
  el.appendChild(head);
  if (!state.storageLarge.length) { const e2 = document.createElement("div"); e2.className = "st-empty"; e2.textContent = "没有找到文件（需先建立文件索引）"; el.appendChild(e2); return; }
  const iconMap = { video: "video", image: "image", audio: "music", archive: "archive", file: "file" };
  const list = document.createElement("div"); list.className = "st-list";
  for (const it of state.storageLarge) {
    const kind = it.kind || kindOf(it.name);
    const row = document.createElement("div"); row.className = "vrow";
    const icon = document.createElement("div"); icon.className = "fi t-" + kind;
    icon.innerHTML = svgIcon(iconMap[kind] || "file");
    const meta = document.createElement("div"); meta.className = "vm";
    meta.innerHTML = `<div class="vname"></div><div class="vsub"></div>`;
    meta.querySelector(".vname").textContent = it.name;
    meta.querySelector(".vsub").textContent = it.path + " · " + fmtSize(it.size) + (it.modTime ? " · " + fmtTime(it.modTime) : "");
    const acts = document.createElement("div"); acts.className = "facts";
    const ab = (ic, title, fn) => { const b = document.createElement("button"); b.className = "btn icon-btn"; b.title = title; b.setAttribute("aria-label", title); b.innerHTML = svgIcon(ic); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; acts.appendChild(b); };
    ab("folder", "打开所在文件夹", () => openPath(it.path.replace(/\/[^/]*$/, "") || "/"));
    ab("info", "详情", () => showInfo(it.path));
    ab("download", "下载", () => { location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(it.path)); });
    row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);
    list.appendChild(row);
  }
  el.appendChild(list);
}

function stopDupPoll() { if (state.storageScanTimer) { clearTimeout(state.storageScanTimer); state.storageScanTimer = null; } }
async function loadDupsStatus() {
  try {
    const d = await api("/api/storage/duplicates");
    renderStorageDups(d);
    if (d && d.scanning) pollDupScan();
  } catch (e) {
    $("#storage-dups").innerHTML = `<div class="st-empty">加载失败: ${e.message}</div>`;
  }
}
function pollDupScan() {
  stopDupPoll();
  state.storageScanTimer = setTimeout(async () => {
    state.storageScanTimer = null;
    if (state.view !== "storage") return;
    let d;
    try { d = await api("/api/storage/duplicates"); } catch (e) { return; }
    renderStorageDups(d);
    if (d && d.scanning) pollDupScan();
  }, 1000);
}
async function startDupScan() {
  let r;
  try { r = await api("/api/storage/duplicates/scan", { method: "POST", body: { minSizeMB: 1 } }); }
  catch (e) { toast("失败: " + e.message); return; }
  if (r && r.busy) toast("已有扫描在进行中");
  await loadDupsStatus();
}
function renderStorageDups(d) {
  const el = $("#storage-dups");
  state.storageDups = d || {};
  d = d || {};
  el.innerHTML = "";

  // 顶部工具栏
  const bar = document.createElement("div"); bar.className = "st-toolbar";
  const scanBtn = document.createElement("button"); scanBtn.className = "btn primary";
  if (d.scanning) { scanBtn.disabled = true; scanBtn.innerHTML = svgIcon("refresh") + " 扫描中…"; }
  else scanBtn.innerHTML = svgIcon("search") + " 扫描重复文件";
  scanBtn.onclick = () => startDupScan();
  bar.appendChild(scanBtn);
  const hint = document.createElement("span"); hint.className = "st-hint";
  if (d.scanning) hint.textContent = "正在扫描重复文件，请稍候…";
  else if (d.scannedAt) hint.textContent = `上次扫描 ${fmtTime(d.scannedAt)} · 最小 ${fmtSize(d.minSize || 0)} · ${d.candidateGroups || 0} 组 / ${d.candidateFiles || 0} 个文件 · 可释放 ${fmtSize(d.wastedBytes || 0)}`;
  else hint.textContent = "按大小分组 + 抽样哈希判定候选，删除前需人工确认；文件将移入回收站，可恢复。";
  bar.appendChild(hint);
  el.appendChild(bar);

  // 进度条
  if (d.scanning) {
    const pr = d.progress || { done: 0, total: 0 };
    const pct = pr.total ? Math.min(100, Math.round((pr.done / pr.total) * 100)) : 0;
    const pg = document.createElement("div"); pg.className = "st-progress";
    const track = document.createElement("div"); track.className = "st-bar";
    const fill = document.createElement("i");
    if (pr.total) fill.style.width = pct + "%"; else track.classList.add("indet");
    track.appendChild(fill);
    const pctTxt = document.createElement("span"); pctTxt.className = "st-pct";
    pctTxt.textContent = pr.total ? `${pr.done} / ${pr.total}（${pct}%）` : "扫描中…";
    pg.appendChild(track); pg.appendChild(pctTxt);
    el.appendChild(pg);
  }

  const groups = d.groups || [];
  if (!groups.length) {
    const e2 = document.createElement("div"); e2.className = "st-empty";
    e2.textContent = d.scanning ? "" : (d.scannedAt ? "未发现重复文件。" : "点击「扫描重复文件」开始检测。");
    if (e2.textContent) el.appendChild(e2);
    return;
  }

  // 分组
  groups.forEach((g) => {
    const files = g.files || [];
    const gWrap = document.createElement("div"); gWrap.className = "dup-group";
    const head = document.createElement("div"); head.className = "dup-head";
    const title = document.createElement("div"); title.className = "dh-title";
    title.textContent = `重复组 · ${files.length} 个文件 · 每个 ${fmtSize(g.size)}`;
    const meta = document.createElement("div"); meta.className = "dh-meta";
    const save = document.createElement("span"); save.className = "dh-save";
    save.textContent = `可节省 ${fmtSize((g.size || 0) * Math.max(0, files.length - 1))}`;
    meta.appendChild(save);
    head.appendChild(title); head.appendChild(meta);
    gWrap.appendChild(head);

    const filesWrap = document.createElement("div"); filesWrap.className = "dup-files";
    files.forEach((f, idx) => {
      const row = document.createElement("div"); row.className = "dup-file";
      const lab = document.createElement("label");
      const cb = document.createElement("input"); cb.type = "checkbox"; cb.dataset.vpath = f.path;
      cb.checked = idx !== 0; // 默认勾选除第一项外的副本，至少保留一份
      const inner = document.createElement("div"); inner.className = "df-inner";
      const nm = document.createElement("span"); nm.className = "df-name"; nm.textContent = f.name;
      const pth = document.createElement("span"); pth.className = "df-path"; pth.textContent = f.path;
      inner.appendChild(nm); inner.appendChild(pth);
      lab.appendChild(cb); lab.appendChild(inner);
      const keep = document.createElement("span"); keep.className = "df-keep";
      const updKeep = () => { keep.textContent = cb.checked ? "将删除" : "保留"; keep.classList.toggle("del", cb.checked); };
      cb.addEventListener("change", () => {
        const boxes = Array.from(filesWrap.querySelectorAll('input[type="checkbox"]'));
        if (boxes.every((x) => x.checked)) { cb.checked = false; toast("每组至少保留一份"); }
        updKeep(); updateDupDeleteBtn();
      });
      updKeep();
      row.appendChild(lab); row.appendChild(keep);
      filesWrap.appendChild(row);
    });
    gWrap.appendChild(filesWrap);
    el.appendChild(gWrap);
  });

  // 底部删除
  const acts = document.createElement("div"); acts.className = "dup-actions";
  const delBtn = document.createElement("button"); delBtn.className = "btn danger"; delBtn.id = "dup-delete-btn";
  delBtn.innerHTML = svgIcon("trash") + " 删除选中副本（移入回收站）";
  delBtn.onclick = deleteSelectedDups;
  const note = document.createElement("span"); note.className = "st-hint"; note.textContent = "选中项将移入各自存储库的回收站，可随时恢复。";
  acts.appendChild(delBtn); acts.appendChild(note);
  el.appendChild(acts);
  updateDupDeleteBtn();
}
function updateDupDeleteBtn() {
  const btn = $("#dup-delete-btn");
  if (!btn) return;
  const n = $$('#storage-dups input[type="checkbox"]:checked').length;
  btn.disabled = n === 0;
  btn.title = n === 0 ? "请先勾选要删除的副本" : `删除 ${n} 个副本`;
}
async function deleteSelectedDups() {
  const paths = $$('#storage-dups input[type="checkbox"]:checked').map((b) => b.dataset.vpath).filter(Boolean);
  if (!paths.length) { toast("未选中任何副本"); return; }
  const ok = await askConfirm("删除重复副本", `将把 ${paths.length} 个副本移入回收站（可恢复）。确定继续？`, "删除");
  if (!ok) return;
  let done = 0, errs = 0;
  for (const p of paths) {
    try { await api("/api/delete", { method: "POST", body: { vpath: p } }); done++; }
    catch (e) { errs++; }
  }
  toast(errs ? `已删除 ${done} 项，${errs} 项失败` : `已删除 ${done} 项，正在重新扫描…`);
  await startDupScan();
}

/* ============================================================ 存储趋势(批次 1) */
// 与后端 total 的口径一致:最后一点 - 7 天前最近一点;不足 7 天则与最早一点比
function trWeekDeltaOf(points) {
  const pts = (Array.isArray(points) ? points : []).filter((p) => p && isFinite(p.bytes));
  if (pts.length < 2) return { bytes: 0, pct: 0 };
  const last = pts[pts.length - 1];
  const cutoff = (Number(last.t) || 0) - 7 * 86400;
  let ref = pts[0];
  for (const p of pts) {
    if ((Number(p.t) || 0) >= cutoff) { ref = p; break; }
  }
  const bytes = (Number(last.bytes) || 0) - (Number(ref.bytes) || 0);
  const prev = Number(ref.bytes) || 0;
  const pct = prev > 0 ? (bytes / prev) * 100 : 0;
  return { bytes, pct };
}
function trSignedSize(n) {
  if (!isFinite(n)) n = 0;
  return (n > 0 ? "+" : n < 0 ? "-" : "") + fmtSize(Math.abs(n));
}
function trSignedPct(p) {
  if (!isFinite(p)) p = 0;
  const v = Math.round(p * 10) / 10;
  return (v > 0 ? "+" : v < 0 ? "-" : "") + Math.abs(v).toFixed(1) + "%";
}
// 手写 SVG 面积/折线图:X 轴按点等距,Y 轴默认 0..max(bytes);fitRange 时按 min..max 拉伸(用于迷你 sparkline)
function trSvgChart(points, cls, W, H, fitRange) {
  W = W || 600; H = H || 120;
  const NS = "http://www.w3.org/2000/svg";
  const raw = (Array.isArray(points) ? points : []).filter((p) => p && isFinite(p.bytes));
  const pts = raw.length === 1 ? [raw[0], raw[0]] : raw;
  if (!pts.length) return null;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 " + W + " " + H);
  svg.setAttribute("width", String(W));
  svg.setAttribute("height", String(H));
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("class", cls);
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "存储占用趋势");
  const pad = 4;
  const innerW = Math.max(1, W - pad * 2);
  const innerH = Math.max(1, H - pad * 2);
  const values = pts.map((p) => Math.max(0, Number(p.bytes) || 0));
  const maxB = Math.max(1, ...values);
  const minB = fitRange ? Math.min(...values) : 0;
  const range = Math.max(1, maxB - minB);
  const x = (i) => pad + (innerW * i) / (pts.length - 1);
  const y = (b) => pad + innerH - ((Math.max(0, Number(b) || 0) - minB) / range) * innerH;
  let d = "";
  pts.forEach((p, i) => { d += (i ? "L" : "M") + x(i).toFixed(1) + "," + y(p.bytes).toFixed(1); });
  const baseY = pad + innerH;
  const area = document.createElementNS(NS, "path");
  area.setAttribute("class", "tr-area");
  area.setAttribute("d", d + " L" + (pad + innerW) + "," + baseY + " L" + pad + "," + baseY + " Z");
  const line = document.createElementNS(NS, "path");
  line.setAttribute("class", "tr-line");
  line.setAttribute("d", d);
  line.setAttribute("vector-effect", "non-scaling-stroke");
  svg.appendChild(area);
  svg.appendChild(line);
  return svg;
}
function trTrendCard(name, points, delta, pct) {
  const pts = (Array.isArray(points) ? points : []).filter((p) => p && isFinite(p.bytes));
  const cur = pts.length ? fmtSize(Number(pts[pts.length - 1].bytes) || 0) : "0 B";
  const card = document.createElement("div"); card.className = "tr-card";
  const head = document.createElement("div"); head.className = "tr-head";
  head.appendChild(ovEl("span", "tr-name", name));
  head.appendChild(ovEl("span", "tr-now", cur));
  card.appendChild(head);
  const svg = trSvgChart(pts, "tr-chart", 600, 120);
  if (svg) card.appendChild(svg);
  const meta = document.createElement("div"); meta.className = "tr-meta";
  meta.textContent = "共 " + cur + " · 本周 " + trSignedSize(delta) + " · " + trSignedPct(pct);
  card.appendChild(meta);
  return card;
}
async function loadTrend() {
  const el = $("#storage-trend");
  if (!el) return;
  el.innerHTML = '<div class="st-empty">加载中…</div>';
  const days = state.trendDays || 30;
  let r;
  try { r = await api("/api/storage/trend?days=" + days); }
  catch (e) { el.innerHTML = `<div class="st-empty">加载失败: ${e.message}</div>`; return; }
  renderTrend(r || {}, days);
}
function renderTrend(r, days) {
  const el = $("#storage-trend");
  if (!el) return;
  el.innerHTML = "";
  days = days || r.days || state.trendDays || 30;

  // 7 / 30 / 90 天切换(复用 .seg 分段按钮)
  const bar = document.createElement("div"); bar.className = "st-toolbar";
  const seg = document.createElement("div"); seg.className = "seg";
  for (const d of [7, 30, 90]) {
    const b = document.createElement("button");
    b.textContent = d + " 天";
    if (d === days) b.classList.add("on");
    b.onclick = () => { if (state.trendDays !== d) { state.trendDays = d; loadTrend(); } };
    seg.appendChild(b);
  }
  bar.appendChild(seg);
  const hint = document.createElement("span"); hint.className = "st-hint";
  hint.textContent = "扫描完成后按整点记录各库占用快照(最多保留 400 天)。";
  bar.appendChild(hint);
  el.appendChild(bar);

  const libs = Array.isArray(r.libs) ? r.libs : [];
  const total = r.total || {};
  const totalPts = Array.isArray(total.points) ? total.points : [];
  const libsWithPts = libs.filter((l) => l && Array.isArray(l.points) && l.points.length);
  if (!libsWithPts.length && !totalPts.length) {
    el.appendChild(ovEl("div", "st-empty", "还没有足够的历史快照，扫描几次后即可看到趋势"));
    return;
  }

  // 全部库合计
  if (totalPts.length) {
    let tb = Number(total.weekDeltaBytes);
    let tp = Number(total.weekDeltaPct);
    const d = trWeekDeltaOf(totalPts);
    if (!isFinite(tb)) tb = d.bytes;
    if (!isFinite(tp)) tp = d.pct;
    el.appendChild(trTrendCard("全部存储库", totalPts, tb, tp));
  }
  // 每个库一条
  for (const lib of libsWithPts) {
    const d = trWeekDeltaOf(lib.points);
    el.appendChild(trTrendCard(lib.name || lib.id || "存储库", lib.points, d.bytes, d.pct));
  }
}

/* ============================================================ 磁盘健康(批次 1) */
async function loadDiskHealth(lib) {
  if (lib === undefined) lib = state.diskLib || "libs";
  state.diskLib = lib || "libs";
  const seq = (state.diskHealthSeq || 0) + 1;
  state.diskHealthSeq = seq;
  let h = null;
  // libs(默认): 不传参数 → 后端只返回承载已添加存储库的磁盘; all: 全部磁盘; 其它: 指定库
  let q = "";
  if (state.diskLib === "all") q = "?lib=all";
  else if (state.diskLib && state.diskLib !== "libs") q = "?lib=" + enc(state.diskLib);
  try { h = await api("/api/disks/health" + q); } catch (e) { h = null; }
  if (seq !== state.diskHealthSeq) return; // 已有更新的请求,丢弃旧结果
  state.diskHealth = h;
  renderDiskHealth();
}
function renderDiskHealth() {
  const host = $("#storage-overview");
  if (!host) return;
  const old = $("#storage-health");
  if (old) old.remove();
  const h = state.diskHealth;
  if (!h || h.supported !== true) return; // 不支持或接口不可用:整段隐藏
  const disks = Array.isArray(h.disks) ? h.disks : [];

  const card = document.createElement("div"); card.className = "dh-card"; card.id = "storage-health";
  const anyBad = disks.some((dk) => (dk.healthStatus || "") !== "Healthy");
  if (anyBad) card.classList.add("warn");
  const head = document.createElement("div"); head.className = "dh-head";
  head.appendChild(ovEl("span", "dh-h", "磁盘健康"));

  // 存储库下拉: 已添加的存储库(默认) + 各库 + 全部磁盘
  const tools = document.createElement("div"); tools.className = "dh-tools";
  const libs = (Array.isArray(state.storageLibs) && state.storageLibs.length)
    ? state.storageLibs
    : (Array.isArray(state.libs) ? state.libs : []);
  const known = state.diskLib === "libs" || state.diskLib === "all" || libs.some((l) => l && l.id === state.diskLib);
  if (!known) state.diskLib = "libs";
  const sel = document.createElement("select");
  sel.className = "dh-lib-select"; sel.title = "按存储库筛选磁盘";
  sel.setAttribute("aria-label", "按存储库筛选磁盘");
  const optLibs = document.createElement("option"); optLibs.value = "libs"; optLibs.textContent = "已添加的存储库"; sel.appendChild(optLibs);
  for (const lib of libs) {
    if (!lib || !lib.id) continue;
    const o = document.createElement("option"); o.value = lib.id; o.textContent = lib.name || lib.id; sel.appendChild(o);
  }
  const optAll = document.createElement("option"); optAll.value = "all"; optAll.textContent = "全部磁盘"; sel.appendChild(optAll);
  sel.value = state.diskLib || "libs";
  sel.onchange = () => { loadDiskHealth(sel.value); };
  tools.appendChild(sel);
  if (h.at) tools.appendChild(ovEl("span", "dh-at", "检测于 " + fmtTime(h.at)));
  head.appendChild(tools);
  card.appendChild(head);

  if (h.note) card.appendChild(ovEl("div", "dh-note", h.note));

  if (!disks.length) {
    if (!h.note) {
      const msg = state.diskLib === "libs"
        ? "尚未添加存储库，或存储库所在磁盘不在本机物理磁盘列表内"
        : (state.diskLib && state.diskLib !== "all")
          ? "该存储库所在磁盘不在本机物理磁盘列表内"
          : "未检测到本机物理磁盘信息";
      card.appendChild(ovEl("div", "dh-empty", msg));
    }
    host.appendChild(card);
    return;
  }

  const list = document.createElement("div"); list.className = "dh-list";
  for (const dk of disks) {
    const bad = (dk.healthStatus || "") !== "Healthy";
    const row = document.createElement("div"); row.className = "dh-item" + (bad ? " warn" : "");

    // 第一行:盘符 · 型号 · 介质 · 健康状态
    const top = document.createElement("div"); top.className = "dh-row";
    const parts = [
      ["dh-drive", dk.drive || "—"],
      ["dh-model", dk.model || "未知型号"],
      ["dh-media", dk.mediaType || "—"],
      ["dh-health", dk.healthStatus || "—"],
    ];
    parts.forEach(([cls, txt], i) => {
      if (i) top.appendChild(ovEl("span", "dh-sep", "·"));
      top.appendChild(ovEl("span", cls, txt));
    });
    row.appendChild(top);

    // 第二行:容量进度条 + 已用/可用/总量(与存储库卡片一致:绿条=剩余可用)
    const totalGB = Number(dk.totalGB), freeGB = Number(dk.freeGB);
    const hasCap = isFinite(totalGB) && totalGB > 0 && isFinite(freeGB) && freeGB >= 0;
    if (hasCap) {
      const freePct = Math.max(0, Math.min(100, Math.round((freeGB / totalGB) * 100)));
      const usedPct = 100 - freePct;
      const cap = document.createElement("div"); cap.className = "dh-cap";
      const bar = document.createElement("div"); bar.className = "bar" + (freePct < 8 ? " hot" : "");
      const fill = document.createElement("i"); fill.style.width = freePct + "%";
      bar.appendChild(fill);
      cap.appendChild(bar);
      cap.appendChild(ovEl("span", "dh-cap-text",
        `已用 ${usedPct}% · 可用 ${fmtSize(freeGB * 1073741824)} / ${fmtSize(totalGB * 1073741824)}`));
      row.appendChild(cap);
    } else {
      const sz = Number(dk.sizeGB);
      row.appendChild(ovEl("span", "dh-cap-text dh-cap-na",
        isFinite(sz) && sz > 0 ? `物理磁盘 ${Math.round(sz)} GB` : "容量未知"));
    }
    list.appendChild(row);
  }
  card.appendChild(list);
  host.appendChild(card);
}

/* ============================================================ 校验巡检(批次 2) */
function stopPatrolPoll() { if (state.patrolTimer) { clearTimeout(state.patrolTimer); state.patrolTimer = null; } }

function patrolElapsed(st) {
  if (!st || !st.startedAt) return "0:00";
  const end = st.running ? Math.floor(Date.now() / 1000) : (st.finishedAt || st.startedAt);
  let sec = end - st.startedAt;
  if (!isFinite(sec) || sec < 0) sec = 0;
  return fmtDur(sec) || "0:00";
}

async function loadPatrol() {
  const el = $("#storage-patrol");
  if (!el) return;
  if (!state.patrolStatus) el.innerHTML = '<div class="st-empty">读取巡检状态…</div>';
  let st = null;
  try { st = await api("/api/storage/patrol/status"); } catch (e) { st = null; }
  if (st) state.patrolStatus = st;
  renderPatrol(state.patrolStatus);
  if (state.patrolStatus && state.patrolStatus.running) pollPatrol();
  else loadPatrolLast();
}

function pollPatrol() {
  stopPatrolPoll();
  state.patrolTimer = setTimeout(async () => {
    state.patrolTimer = null;
    if (state.view !== "storage" || state.storageTab !== "patrol") return;
    let st = null;
    try { st = await api("/api/storage/patrol/status"); } catch (e) { st = null; }
    if (!st) return;
    state.patrolStatus = st;
    renderPatrol(st);
    if (st.running) pollPatrol();
    else loadPatrolLast();
  }, 1200);
}

async function loadPatrolLast() {
  let r = null;
  try { r = await api("/api/storage/patrol/last?limit=1"); } catch (e) { r = null; }
  const items = (r && r.items) || [];
  state.patrolLast = items[0] || null;
  renderPatrolLast();
}

async function startPatrol() {
  const mode = state.patrolMode || "quick";
  try { await api("/api/storage/patrol/start?mode=" + enc(mode), { method: "POST" }); }
  catch (e) {
    if (/already running|已有/i.test(e.message || "")) toast("已有巡检在进行");
    else toast("启动失败：" + (e.message || "未知错误"));
    return;
  }
  toast("巡检已开始（" + (mode === "deep" ? "深度" : "快速") + "）");
  await loadPatrol();
}

async function cancelPatrol() {
  try { await api("/api/storage/patrol/cancel", { method: "POST" }); toast("已请求取消巡检"); }
  catch (e) { toast("取消失败：" + (e.message || "未知错误")); }
  loadPatrol();
}

function patrolGroups(st) {
  const wrap = document.createElement("div"); wrap.className = "pt-groups";
  wrap.appendChild(patrolGroup("内容已变化", "changed", (st && st.changed) || []));
  wrap.appendChild(patrolGroup("文件缺失", "missing", (st && st.missing) || []));
  wrap.appendChild(patrolGroup("读取失败", "failed", (st && st.failed) || []));
  return wrap;
}

function patrolGroup(title, cls, items) {
  const arr = Array.isArray(items) ? items : [];
  const g = document.createElement("div"); g.className = "pt-group pt-" + cls;
  const head = document.createElement("div"); head.className = "pt-group-head";
  head.appendChild(ovEl("span", "pt-group-title", title));
  head.appendChild(ovEl("span", "pt-group-count", arr.length + " 项"));
  g.appendChild(head);
  if (!arr.length) { g.appendChild(ovEl("div", "pt-empty", "无")); return g; }
  const rows = document.createElement("div"); rows.className = "pt-rows";
  for (const it of arr) {
    const row = document.createElement("div"); row.className = "pt-row";
    row.title = "点击跳到文件所在目录";
    row.appendChild(ovEl("div", "pt-path", it.path || it.name || ""));
    if (it.reason) row.appendChild(ovEl("div", "pt-reason", it.reason));
    row.onclick = () => patrolJump(it);
    rows.appendChild(row);
  }
  g.appendChild(rows);
  return g;
}

function patrolJump(it) {
  let vp = it && it.path ? String(it.path) : "";
  if (vp && vp.charAt(0) !== "/" && it && it.libId) vp = "/" + it.libId + "/" + vp.replace(/^\/+/, "");
  if (vp && /^\/lib[^/]+/.test(vp)) {
    const dir = vp.replace(/\/[^/]*$/, "") || "/";
    openPath(dir);
    return;
  }
  if (vp) { copyText(vp); toast("已复制路径，无法自动定位目录"); return; }
  toast("无法定位该文件");
}

function renderPatrol(st) {
  const el = $("#storage-patrol");
  if (!el) return;
  el.innerHTML = "";
  const running = !!(st && st.running);
  const mode = running ? ((st && st.mode) || state.patrolMode || "quick") : (state.patrolMode || "quick");

  const card = document.createElement("div"); card.className = "pt-card";
  const bar = document.createElement("div"); bar.className = "pt-bar";

  // 模式分段(运行中禁用)
  const seg = document.createElement("div"); seg.className = "seg pt-mode";
  [["quick", "快速"], ["deep", "深度"]].forEach(([v, label]) => {
    const b = document.createElement("button"); b.type = "button"; b.textContent = label;
    if (v === mode) b.classList.add("on");
    b.disabled = running;
    b.onclick = () => { if (state.patrolMode !== v) { state.patrolMode = v; renderPatrol(state.patrolStatus); } };
    seg.appendChild(b);
  });
  bar.appendChild(seg);

  const startBtn = document.createElement("button"); startBtn.className = "btn primary pt-start";
  startBtn.innerHTML = svgIcon("search") + " 开始巡检";
  startBtn.disabled = running;
  startBtn.onclick = () => startPatrol();
  bar.appendChild(startBtn);

  if (running) {
    const cancelBtn = document.createElement("button"); cancelBtn.className = "btn danger pt-cancel";
    cancelBtn.textContent = "取消";
    cancelBtn.onclick = () => cancelPatrol();
    bar.appendChild(cancelBtn);
  }
  card.appendChild(bar);

  // 进度 + 状态行
  const done = Number((st && st.done) || 0);
  const total = Number((st && st.total) || 0);
  if (running || done || total) {
    const prog = document.createElement("div"); prog.className = "pt-progress";
    const track = document.createElement("div"); track.className = "pt-track";
    const fill = document.createElement("i"); fill.className = "pt-fill";
    const pct = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : (running ? 0 : 100);
    if (total > 0 || !running) fill.style.width = pct + "%"; else track.classList.add("indet");
    track.appendChild(fill);
    prog.appendChild(track);
    const stat = document.createElement("span"); stat.className = "pt-stat";
    stat.textContent = `${done}/${total || "?"} · ${pct}% · 已用时 ${patrolElapsed(st)}`;
    prog.appendChild(stat);
    card.appendChild(prog);
  }
  if (running && st && st.current) card.appendChild(ovEl("div", "pt-current", "当前：" + st.current));
  if (!running && st && st.lastError) card.appendChild(ovEl("div", "pt-current err", "上次错误：" + st.lastError));
  el.appendChild(card);

  el.appendChild(patrolGroups(st));

  const last = document.createElement("div"); last.className = "pt-last"; last.id = "pt-last";
  el.appendChild(last);
  renderPatrolLast();
}

function renderPatrolLast() {
  const el = $("#pt-last");
  if (!el) return;
  el.innerHTML = "";
  el.appendChild(ovEl("div", "pt-last-head", "上次巡检"));
  const it = state.patrolLast;
  if (!it) { el.appendChild(ovEl("div", "pt-last-empty", "暂无巡检记录")); return; }
  const bits = [];
  bits.push(it.startedAt ? fmtTime(it.startedAt) : "—");
  bits.push(it.mode === "deep" ? "深度" : "快速");
  if (it.cancelled) bits.push("已取消");
  if (it.durationSec !== undefined && it.durationSec !== null) bits.push("耗时 " + (fmtDur(it.durationSec) || Math.round(it.durationSec) + " 秒"));
  el.appendChild(ovEl("div", "pt-last-meta", bits.join(" · ")));
  const stats = document.createElement("div"); stats.className = "pt-last-stats";
  const add = (cls, label, n) => stats.appendChild(ovEl("span", "pt-last-stat pt-" + cls, label + " " + (Number(n) || 0)));
  add("changed", "内容已变化", it.changed);
  add("missing", "文件缺失", it.missing);
  add("failed", "读取失败", it.failed);
  el.appendChild(stats);
}

/* ============================================================ 自动整理(批次 4) */
const ORG_GROUP_LABELS = { ext: "按扩展名", kind: "按类型", "year-month": "按年月", none: "不分组" };
const ORG_KIND_LABELS = { video: "视频", image: "图片", audio: "音频", other: "其它" };

function stopOrganizePoll() {
  if (state.organizeTimer) { clearTimeout(state.organizeTimer); state.organizeTimer = null; }
}

async function loadOrganize() {
  if (!isAdmin()) {
    const el = $("#storage-organize");
    if (el) el.innerHTML = "";
    return;
  }
  try {
    const [rules, st] = await Promise.all([
      api("/api/organize/rules"),
      api("/api/organize/status"),
    ]);
    state.organizeRules = rules.rules || [];
    state.organizeStatus = st;
    renderOrganize();
    if (st && st.running) pollOrganize();
    else {
      stopOrganizePoll();
      const jobs = await api("/api/organize/jobs");
      state.organizeJobs = jobs.items || [];
      renderOrganizeJobs();
    }
  } catch (e) {
    toast("加载自动整理失败: " + e.message);
  }
}

function pollOrganize() {
  stopOrganizePoll();
  state.organizeTimer = setTimeout(async () => {
    if (state.view !== "storage" || state.storageTab !== "organize") return;
    try {
      const st = await api("/api/organize/status");
      state.organizeStatus = st;
      renderOrganize();
      if (st && st.running) pollOrganize();
      else {
        const jobs = await api("/api/organize/jobs");
        state.organizeJobs = jobs.items || [];
        renderOrganizeJobs();
        toast(st.kind === "undo" ? "撤销完成" : "整理完成");
      }
    } catch { pollOrganize(); }
  }, 1200);
}

function orgLibName(id) {
  const lib = (state.storageLibs || []).find((l) => l.id === id);
  if (lib) return lib.name || lib.id;
  const all = state.libs || [];
  const l2 = all.find((x) => x.id === id);
  return l2 ? (l2.name || l2.id) : (id || "—");
}

function orgRuleSummary(r) {
  const m = r.match || {}, a = r.action || {};
  const bits = [];
  if (m.matchPathPrefix) bits.push("目录 " + m.matchPathPrefix);
  if (m.exts && m.exts.length) bits.push("扩展名 " + m.exts.join("/"));
  if (m.nameContains) bits.push("名称含 " + m.nameContains);
  if (m.kind) bits.push("类型 " + (ORG_KIND_LABELS[m.kind] || m.kind));
  if (m.minSizeMB) bits.push("≥" + m.minSizeMB + "MB");
  if (m.maxSizeMB) bits.push("≤" + m.maxSizeMB + "MB");
  if (m.olderThanDays) bits.push("早于 " + m.olderThanDays + " 天");
  if (!bits.length) bits.push("全部文件");
  bits.push("→ " + (a.moveTo || "/") + (a.groupBy && a.groupBy !== "none" ? " · " + (ORG_GROUP_LABELS[a.groupBy] || a.groupBy) : ""));
  return bits.join(" · ");
}

function renderOrganize() {
  const el = $("#storage-organize");
  if (!el) return;
  el.innerHTML = "";
  const st = state.organizeStatus || {};
  const running = !!st.running;

  // 进度卡片(运行中)
  if (running) {
    const card = ovEl("div", "pt-card");
    const head = ovEl("div", "pt-last-head", st.kind === "undo" ? "正在撤销…" : "正在整理…");
    card.appendChild(head);
    const pct = st.total > 0 ? Math.round((st.done / st.total) * 100) : 0;
    const prog = ovEl("div", "pt-progress");
    const track = ovEl("div", "pt-track");
    const fill = ovEl("div", "pt-fill");
    fill.style.width = pct + "%";
    track.appendChild(fill);
    prog.appendChild(track);
    prog.appendChild(ovEl("div", "pt-stat", st.done + "/" + st.total + " · " + pct + "%"));
    card.appendChild(prog);
    if (st.current) card.appendChild(ovEl("div", "pt-current", st.current));
    if (st.lastError) card.appendChild(ovEl("div", "pt-current err", st.lastError));
    const bar = ovEl("div", "pt-bar");
    const cancelBtn = ovEl("button", "btn danger");
    cancelBtn.innerHTML = svgIcon("refresh") + " 请求取消";
    cancelBtn.onclick = async () => {
      try { await api("/api/organize/cancel", { method: "POST" }); toast("已请求取消"); }
      catch (e) { toast("取消失败: " + e.message); }
    };
    bar.appendChild(cancelBtn);
    card.appendChild(bar);
    el.appendChild(card);
  }

  // 规则列表卡片
  const card = ovEl("div", "pt-card");
  const bar = ovEl("div", "pt-bar");
  const title = ovEl("div", "pt-last-head", "整理规则");
  bar.appendChild(title);
  const addBtn = ovEl("button", "btn primary");
  addBtn.innerHTML = svgIcon("folder-plus") + " 新建规则";
  addBtn.disabled = running;
  addBtn.onclick = () => openOrganizeEditor(null);
  bar.appendChild(addBtn);
  card.appendChild(bar);
  if (!state.organizeRules.length) {
    card.appendChild(ovEl("div", "pt-empty", "还没有规则。新建一条规则,把指定文件自动移动到目标目录。"));
  } else {
    const groups = ovEl("div", "pt-groups");
    for (const r of state.organizeRules) {
      const g = ovEl("div", "pt-group" + (r.enabled ? "" : " pt-disabled"));
      const head = ovEl("div", "pt-group-head");
      const t = ovEl("div", "pt-group-title", (r.name || r.id) + "（" + orgLibName(r.libId) + "）");
      head.appendChild(t);
      head.appendChild(ovEl("div", "pt-group-count", r.enabled ? "启用" : "停用"));
      g.appendChild(head);
      const rows = ovEl("div", "pt-rows");
      const row = ovEl("div", "pt-row");
      row.appendChild(ovEl("div", "pt-path", orgRuleSummary(r)));
      row.appendChild(ovEl("div", "pt-reason", "目标 " + orgLibName(r.libId) + ":" + (r.action && r.action.moveTo || "/")));
      rows.appendChild(row);
      g.appendChild(rows);
      const ops = ovEl("div", "pt-bar org-ops");
      const editBtn = ovEl("button", "btn");
      editBtn.textContent = "编辑";
      editBtn.disabled = running;
      editBtn.onclick = () => openOrganizeEditor(r);
      const previewBtn = ovEl("button", "btn");
      previewBtn.textContent = "预览";
      previewBtn.disabled = running;
      previewBtn.onclick = () => runOrganizePreview(r.id);
      const runBtn = ovEl("button", "btn primary");
      runBtn.textContent = "执行";
      runBtn.disabled = running;
      runBtn.onclick = () => confirmOrganizeRun(r.id);
      const delBtn = ovEl("button", "btn danger");
      delBtn.textContent = "删除";
      delBtn.disabled = running;
      delBtn.onclick = async () => {
        const ok = await askConfirm("删除规则", `删除整理规则「${r.name || r.id}」?不会移动任何文件。`, "删除");
        if (!ok) return;
        try {
          await api("/api/organize/rules?id=" + enc(r.id), { method: "DELETE" });
          toast("已删除规则");
          loadOrganize();
        } catch (e) { toast("删除失败: " + e.message); }
      };
      ops.appendChild(editBtn); ops.appendChild(previewBtn); ops.appendChild(runBtn); ops.appendChild(delBtn);
      g.appendChild(ops);
      groups.appendChild(g);
    }
    card.appendChild(groups);
  }
  el.appendChild(card);

  // 作业历史卡片
  const jobsCard = ovEl("div", "pt-card");
  jobsCard.appendChild(ovEl("div", "pt-last-head", "整理作业"));
  if (!state.organizeJobs.length) {
    jobsCard.appendChild(ovEl("div", "pt-empty", "还没有作业记录。"));
  } else {
    const rows = ovEl("div", "pt-rows");
    for (const j of state.organizeJobs) {
      const row = ovEl("div", "pt-row");
      const bits = [];
      bits.push(j.kind === "undo" ? "撤销" : "整理");
      if (j.ruleName) bits.push(j.ruleName);
      bits.push(fmtTime(j.startedAt));
      bits.push("移动 " + (j.moved || 0) + (j.failed ? " · 失败 " + j.failed : "") + (j.skipped ? " · 跳过 " + j.skipped : "") + (j.cancelled ? " · 已取消" : ""));
      if (j.durationSec !== undefined && j.durationSec !== null) bits.push("耗时 " + (fmtDur(j.durationSec) || Math.round(j.durationSec) + " 秒"));
      row.appendChild(ovEl("div", "pt-path", bits.join(" · ")));
      if (j.canUndo) {
        const undoBtn = ovEl("button", "btn");
        undoBtn.textContent = "撤销";
        undoBtn.disabled = running;
        undoBtn.onclick = () => confirmOrganizeUndo(j.jobId, j);
        row.appendChild(undoBtn);
      }
      rows.appendChild(row);
    }
    jobsCard.appendChild(rows);
  }
  el.appendChild(jobsCard);

  // 预览结果卡片
  renderOrganizePreview(el, running);
}

function renderOrganizePreview(el, running) {
  const p = state.organizePreview;
  if (!p) return;
  const card = ovEl("div", "pt-card");
  const bits = [];
  bits.push("预览 " + (p.total || 0) + " 个文件");
  if (p.totalBytes) bits.push("共 " + fmtSize(p.totalBytes));
  if (p.skippedCount) bits.push("跳过 " + p.skippedCount);
  if (p.conflicts) bits.push("同名冲突 " + p.conflicts + "（自动加序号）");
  if (p.truncated) bits.push("仅显示前 " + (p.items || []).length + " 条");
  const head = ovEl("div", "pt-last-head", "预览结果");
  card.appendChild(head);
  card.appendChild(ovEl("div", "pt-last-meta", bits.join(" · ")));
  const groups = ovEl("div", "pt-groups");
  const items = (p.items || []).slice(0, 50);
  if (items.length) {
    const g = ovEl("div", "pt-group");
    g.appendChild(ovEl("div", "pt-group-head", ""));
    const rows = ovEl("div", "pt-rows");
    for (const it of items) {
      const row = ovEl("div", "pt-row");
      row.appendChild(ovEl("div", "pt-path", it.from));
      row.appendChild(ovEl("div", "pt-reason", "→ " + it.to));
      rows.appendChild(row);
    }
    g.appendChild(rows);
    groups.appendChild(g);
  }
  const sk = (p.skipped || []).slice(0, 20);
  if (sk.length) {
    const g = ovEl("div", "pt-group pt-missing");
    const rows = ovEl("div", "pt-rows");
    for (const it of sk) {
      const row = ovEl("div", "pt-row");
      row.appendChild(ovEl("div", "pt-path", it.from));
      row.appendChild(ovEl("div", "pt-reason", it.reason || "跳过"));
      rows.appendChild(row);
    }
    g.appendChild(rows);
    groups.appendChild(g);
  }
  card.appendChild(groups);
  const runBtn = ovEl("button", "btn primary");
  runBtn.textContent = "按此预览执行";
  runBtn.disabled = running || !(p.items || []).length;
  runBtn.onclick = () => confirmOrganizeRun(state.organizePreviewRule);
  card.appendChild(runBtn);
  if (el) el.appendChild(card);
}

async function runOrganizePreview(ruleId) {
  try {
    const p = await api("/api/organize/preview", { method: "POST", body: { ruleId } });
    state.organizePreview = p;
    state.organizePreviewRule = ruleId;
    renderOrganize();
    const el = $("#storage-organize");
    if (el) { const cards = el.querySelectorAll(".pt-card"); if (cards.length) cards[cards.length - 1].scrollIntoView({ behavior: "smooth", block: "nearest" }); }
  } catch (e) { toast("预览失败: " + e.message); }
}

async function confirmOrganizeRun(ruleId) {
  const r = (state.organizeRules || []).find((x) => x.id === ruleId) || {};
  const p = state.organizePreview;
  const same = p && state.organizePreviewRule === ruleId;
  let msg;
  if (same && p.total !== undefined) {
    msg = `将按规则「${r.name || ruleId}」移动 ${p.total} 个文件（共 ${fmtSize(p.totalBytes || 0)}${p.skippedCount ? "，另有 " + p.skippedCount + " 个跳过" : ""}）。移动会记入作业日志,可整体撤销。确定执行?`;
  } else {
    msg = `将执行规则「${r.name || ruleId}」（单次最多移动 5000 个文件）。移动会记入作业日志,可整体撤销。确定执行?`;
  }
  const ok = await askConfirm("执行整理", msg, "执行");
  if (!ok) return;
  try {
    const res = await api("/api/organize/run", { method: "POST", body: { ruleId } });
    toast(`开始整理,共 ${res.total} 个文件`);
    state.organizePreview = null;
    loadOrganize();
  } catch (e) {
    if (e.status === 409) toast("已有整理作业在运行");
    else toast("执行失败: " + e.message);
  }
}

async function confirmOrganizeUndo(jobId, job) {
  const ok = await askConfirm("撤销整理", `撤销作业「${(job && job.ruleName) || jobId}」的移动,把文件搬回原位置(目标被占用时会跳过并逐条报告)。确定撤销?`, "撤销");
  if (!ok) return;
  try {
    await api("/api/organize/undo", { method: "POST", body: { jobId } });
    toast("开始撤销");
    loadOrganize();
  } catch (e) {
    if (e.status === 409) toast("已有整理作业在运行");
    else toast("撤销失败: " + e.message);
  }
}

function renderOrganizeJobs() {
  // 作业列表在 renderOrganize 内随规则卡一起渲染;这里仅在非运行态刷新时重绘整页
  renderOrganize();
}

/* ---- 规则编辑抽屉 ---- */
function openOrganizeEditor(rule) {
  const isNew = !rule;
  const base = rule || { id: "", name: "", enabled: true, libId: (state.storageLibs || [])[0] && (state.storageLibs[0].id || state.storageLibs[0].ID) || "", match: { matchPathPrefix: "", exts: [], nameContains: "", kind: "", minSizeMB: 0, maxSizeMB: 0, olderThanDays: 0 }, action: { moveTo: "", groupBy: "none" }, intervalHours: 0 };
  state.organizeDraft = JSON.parse(JSON.stringify(base));
  state.organizeDraft._isNew = isNew;

  const dlg = document.createElement("dialog");
  dlg.className = "wide";
  dlg.innerHTML = `
    <h3>${isNew ? "新建整理规则" : "编辑整理规则"}</h3>
    <div class="org-form">
      <label class="org-fld"><span>规则名称</span><input id="org-name" maxlength="60" placeholder="例如:照片归档"></label>
      <label class="org-fld"><span>存储库</span><select id="org-lib"></select></label>
      <label class="org-fld"><span>只处理目录(可选,库内相对路径)</span><input id="org-prefix" placeholder="例如:Downloads"></label>
      <label class="org-fld"><span>扩展名(逗号分隔,可选)</span><input id="org-exts" placeholder="例如:jpg, png, mp4"></label>
      <label class="org-fld"><span>文件名包含(可选)</span><input id="org-namec" placeholder="例如:截图"></label>
      <label class="org-fld"><span>类型</span><select id="org-kind">
        <option value="">不限</option><option value="video">视频</option><option value="image">图片</option><option value="audio">音频</option><option value="other">其它</option>
      </select></label>
      <label class="org-fld org-2col"><span>大小下限(MB,0 不限)</span><input id="org-min" type="number" min="0" step="0.1"></label>
      <label class="org-fld org-2col"><span>大小上限(MB,0 不限)</span><input id="org-max" type="number" min="0" step="0.1"></label>
      <label class="org-fld org-2col"><span>早于(天,0 不限)</span><input id="org-days" type="number" min="0"></label>
      <label class="org-fld"><span>目标目录(库内相对路径)</span>
        <div class="org-move-row"><input id="org-move" placeholder="例如:归档/2024"><button class="btn" id="org-pick" type="button">选择…</button></div>
      </label>
      <label class="org-fld"><span>子目录分组</span><select id="org-group">
        <option value="none">不分组(直接放入目标目录)</option>
        <option value="ext">按扩展名(如 jpg/)</option>
        <option value="kind">按类型(video/ image/…)</option>
        <option value="year-month">按年月(2024-05/)</option>
      </select></label>
      <label class="org-fld org-switch"><input id="org-enabled" type="checkbox"><span>启用规则</span></label>
    </div>
    <div class="dlg-actions">
      <button class="btn" id="org-cancel">取消</button>
      <button class="btn primary" id="org-save">保存规则</button>
    </div>`;
  document.body.appendChild(dlg);

  const libSel = dlg.querySelector("#org-lib");
  const libs = state.storageLibs && state.storageLibs.length ? state.storageLibs : (state.libs || []);
  for (const l of libs) {
    const id = l.id || l.ID;
    const opt = document.createElement("option");
    opt.value = id;
    opt.textContent = (l.name || l.Name || id) + (l.readOnly ? "（只读）" : "");
    libSel.appendChild(opt);
  }
  const d = state.organizeDraft;
  dlg.querySelector("#org-name").value = d.name || "";
  libSel.value = d.libId || (libs[0] && (libs[0].id || libs[0].ID)) || "";
  dlg.querySelector("#org-prefix").value = (d.match && d.match.matchPathPrefix) || "";
  dlg.querySelector("#org-exts").value = ((d.match && d.match.exts) || []).join(", ");
  dlg.querySelector("#org-namec").value = (d.match && d.match.nameContains) || "";
  dlg.querySelector("#org-kind").value = (d.match && d.match.kind) || "";
  dlg.querySelector("#org-min").value = (d.match && d.match.minSizeMB) || 0;
  dlg.querySelector("#org-max").value = (d.match && d.match.maxSizeMB) || 0;
  dlg.querySelector("#org-days").value = (d.match && d.match.olderThanDays) || 0;
  dlg.querySelector("#org-move").value = (d.action && d.action.moveTo) || "";
  dlg.querySelector("#org-group").value = (d.action && d.action.groupBy) || "none";
  dlg.querySelector("#org-enabled").checked = d.enabled !== false;

  dlg.querySelector("#org-pick").onclick = () => {
    const libId = libSel.value;
    const start = "/" + libId + "/" + (dlg.querySelector("#org-move").value || "").replace(/^\/+|\/+$/g, "");
    openFolderPicker("选择目标目录（" + orgLibName(libId) + "）", (vp) => {
      if (!vp) return;
      const prefix = "/" + libId;
      let rel = vp.startsWith(prefix + "/") ? vp.slice(prefix.length + 1) : "";
      dlg.querySelector("#org-move").value = rel;
    }, start);
  };

  dlg.querySelector("#org-cancel").onclick = () => { dlg.close(); dlg.remove(); };
  dlg.addEventListener("cancel", () => { dlg.remove(); });
  dlg.querySelector("#org-save").onclick = async () => {
    const rule = {
      id: d.id || "",
      name: dlg.querySelector("#org-name").value.trim(),
      enabled: dlg.querySelector("#org-enabled").checked,
      libId: libSel.value,
      match: {
        matchPathPrefix: dlg.querySelector("#org-prefix").value.trim(),
        exts: dlg.querySelector("#org-exts").value.split(/[,，;；\s]+/).map((s) => s.trim().replace(/^\./, "").toLowerCase()).filter(Boolean),
        nameContains: dlg.querySelector("#org-namec").value.trim(),
        kind: dlg.querySelector("#org-kind").value,
        minSizeMB: Number(dlg.querySelector("#org-min").value) || 0,
        maxSizeMB: Number(dlg.querySelector("#org-max").value) || 0,
        olderThanDays: Math.max(0, Math.round(Number(dlg.querySelector("#org-days").value) || 0)),
      },
      action: {
        moveTo: dlg.querySelector("#org-move").value.trim(),
        groupBy: dlg.querySelector("#org-group").value || "none",
      },
      intervalHours: 0,
    };
    if (!rule.name) { toast("请填写规则名称"); return; }
    if (!rule.action.moveTo) { toast("请填写或选择目标目录"); return; }
    try {
      if (d._isNew) await api("/api/organize/rules", { method: "POST", body: rule });
      else await api("/api/organize/rules", { method: "PUT", body: rule });
      toast(d._isNew ? "已创建规则" : "已保存规则");
      dlg.close(); dlg.remove();
      loadOrganize();
    } catch (e) { toast("保存失败: " + e.message); }
  };
  dlg.showModal();
}

/* ============================================================ 启动 */
/* ============================================================ v1.3.0 详情 */
function infoRow(k, v) {
  const d = document.createElement("div"); d.className = "irow";
  const kk = document.createElement("div"); kk.className = "ik"; kk.textContent = k;
  const vv = document.createElement("div"); vv.className = "iv"; vv.textContent = v;
  d.appendChild(kk); d.appendChild(vv); return d;
}
function fmtDur(sec) {
  if (!sec || !isFinite(sec)) return "";
  sec = Math.round(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const p = (x) => String(x).padStart(2, "0");
  return (h ? h + ":" + p(m) : String(m)) + ":" + p(s);
}
async function showInfo(vp) {
  const body = $("#info-body");
  body.innerHTML = '<div class="irow"><div class="iv">读取中…</div></div>';
  $("#dlg-info").showModal();
  let inf;
  try { inf = await api("/api/info?vpath=" + enc(vp)); }
  catch (e) { body.innerHTML = ""; body.appendChild(infoRow("错误", e.message)); return; }
  body.innerHTML = "";
  const head = document.createElement("div"); head.className = "ihead";
  const ic = document.createElement("div"); ic.className = "fi t-" + (inf.kind || (inf.isDir ? "dir" : "file"));
  const im = { dir: "folder", video: "video", image: "image", audio: "music", archive: "archive", other: "file" };
  ic.innerHTML = svgIcon(im[inf.kind] || "file");
  const nm = document.createElement("div"); nm.className = "iname"; nm.textContent = inf.name;
  head.appendChild(ic); head.appendChild(nm); body.appendChild(head);
  body.appendChild(infoRow("类型", inf.isDir ? "文件夹" : (inf.kind === "video" ? "视频" : inf.kind === "image" ? "图片" : inf.kind === "audio" ? "音频" : (inf.ext ? inf.ext.toUpperCase() + " 文件" : "文件"))));
  body.appendChild(infoRow("位置", inf.path));
  body.appendChild(infoRow("修改时间", inf.modTime ? new Date(inf.modTime * 1000).toLocaleString() : "-"));
  if (inf.isDir) {
    body.appendChild(infoRow("包含", `${inf.dirs || 0} 个文件夹,${inf.files || 0} 个文件`));
    body.appendChild(infoRow("总大小", fmtSize(inf.totalBytes || 0)));
  } else {
    body.appendChild(infoRow("大小", fmtSize(inf.size)));
    if (inf.width && inf.height) body.appendChild(infoRow("分辨率", inf.width + " × " + inf.height));
    if (inf.duration) body.appendChild(infoRow("时长", fmtDur(inf.duration)));
    if (inf.fps) body.appendChild(infoRow("帧率", inf.fps + " fps"));
    if (inf.videoCodec) body.appendChild(infoRow("视频编码", inf.videoCodec));
    if (inf.audioCodec) body.appendChild(infoRow("音频编码", inf.audioCodec));
    if (inf.bitRate) body.appendChild(infoRow("总码率", Math.round(inf.bitRate / 1000) + " kbps"));
  }
}
$("#info-close").onclick = () => $("#dlg-info").close();
$("#dlg-info").addEventListener("click", (e) => { if (e.target === $("#dlg-info")) $("#dlg-info").close(); });

/* ============================================================ v1.3.0 可编辑路径 */
function resolveUserPath(raw) {
  if (!raw) return null;
  const s = raw.trim();
  if (!s) return null;
  if (s.startsWith("/")) return s;
  const norm = s.replace(/\//g, "\\").replace(/\\+$/, "");
  for (const lib of state.libs) {
    const lp = (lib.path || "").replace(/\//g, "\\").replace(/\\+$/, "");
    if (!lp) continue;
    if (norm.toLowerCase() === lp.toLowerCase()) return "/" + lib.id;
    if (norm.toLowerCase().startsWith(lp.toLowerCase() + "\\")) {
      const rel = norm.slice(lp.length + 1).split("\\").filter(Boolean).join("/");
      return "/" + lib.id + "/" + rel;
    }
  }
  return null;
}
function startPathEdit() {
  const inp = $("#in-path");
  inp.value = state.curPath;
  $("#crumbs").hidden = true; $("#btn-editpath").hidden = true;
  inp.hidden = false; inp.focus(); inp.select();
}
function endPathEdit() {
  const inp = $("#in-path");
  inp.hidden = true; $("#crumbs").hidden = false; $("#btn-editpath").hidden = false;
}
$("#btn-editpath").onclick = startPathEdit;
$("#in-path").addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    const v = resolveUserPath($("#in-path").value);
    if (v) { endPathEdit(); openPath(v); }
    else toast("无法识别的路径,请输入 /lib1/… 或存储库内的绝对路径");
  } else if (e.key === "Escape") { endPathEdit(); }
});
$("#in-path").addEventListener("blur", () => { if (!$("#in-path").hidden) endPathEdit(); });

/* ============================================================ v1.3.0 媒体分组 */
state.mediaGroup = false;
$("#media-group").onclick = () => {
  state.mediaGroup = !state.mediaGroup;
  $("#media-group").classList.toggle("primary", state.mediaGroup);
  if (!state.mediaGroup) state.mediaCollapsed = Object.create(null);
  renderMediaGrid();
};

/* ============================================================ v1.4.0 D 安全:登录 / 锁屏 / 用户 / 分享 / 日志 */
/* ---------- 登录与解锁 ---------- */
let loginResolver = null;
let unlockResolver = null;
function askLogin() {
  return new Promise((resolve) => {
    loginResolver = resolve;
    $("#in-login-user").value = state.auth.username || "";
    $("#in-login-pw").value = "";
    $("#login-hint").textContent = "本服务已启用多用户,请输入账号登录。";
    $("#dlg-login").showModal();
    setTimeout(() => $("#in-login-pw").focus(), 30);
  });
}
function settleLogin(v) { if (loginResolver) { const r = loginResolver; loginResolver = null; r(v); } }
$("#btn-login-ok").onclick = () => {
  const u = $("#in-login-user").value.trim();
  const p = $("#in-login-pw").value;
  if (!u || !p) { toast("请输入用户名和密码"); return; }
  settleLogin({ username: u, password: p });
  $("#dlg-login").close();
};
$("#btn-login-cancel").onclick = () => { settleLogin(null); $("#dlg-login").close(); };
$("#dlg-login").addEventListener("cancel", (e) => { e.preventDefault(); settleLogin(null); $("#dlg-login").close(); });

async function doLogin(creds) {
  const r = await fetch("/api/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: creds.username, password: creds.password }),
  }).catch(() => null);
  if (!r) { toast("无法连接服务器"); return false; }
  const d = await r.json().catch(() => ({}));
  if (!r.ok) { toast(d.error || "用户名或密码错误"); return false; }
  state.token = d.token || "";
  localStorage.setItem("linnas_token", state.token);
  state.auth.username = d.username || creds.username;
  state.auth.role = d.role || "user";
  state.auth.session = true;
  state.auth.locked = false;
  $("#lockscreen").hidden = true;
  renderUserMenu();
  updateRoleUI();
  toast("已登录:" + state.auth.username);
  try { await loadLibs(); await loadDisks(); } catch (e) { /* ignore */ }
  showOverview();
  return true;
}

function askUnlock() {
  return new Promise((resolve) => {
    unlockResolver = resolve;
    $("#in-lock-user").value = state.auth.username || "";
    $("#in-lock-pw").value = "";
    $("#lock-msg").textContent = "已自动锁定,请重新登录";
    $("#lockscreen").hidden = false;
    setTimeout(() => $("#in-lock-pw").focus(), 30);
  });
}
function settleUnlock(v) { if (unlockResolver) { const r = unlockResolver; unlockResolver = null; r(v); } }$("#btn-lock-ok").onclick = async () => {
  const u = $("#in-lock-user").value.trim();
  const p = $("#in-lock-pw").value;
  if (!u || !p) { toast("请输入用户名和密码"); return; }
  const ok = await doLogin({ username: u, password: p });
  if (ok) { settleUnlock(null); $("#in-lock-pw").value = ""; }
};
$("#in-lock-pw").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); $("#btn-lock-ok").click(); } });
$("#in-login-pw").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); $("#btn-login-ok").click(); } });

/* ---------- 顶部用户菜单 ---------- */
function renderUserMenu() {
  const multi = state.auth.multiUser;
  $("#btn-user").hidden = !multi && !state.auth.username;
  $("#um-name").textContent = state.auth.username || "未登录";
  const roleTxt = { admin: "管理员", user: "普通用户", viewer: "只读用户" }[state.auth.role] || "";
  $("#um-role").textContent = roleTxt;
  $("#um-role").hidden = !roleTxt;
  $("#btn-um-logout").hidden = !multi;
  $("#btn-um-lock").hidden = !multi;
  $("#um-role").style.display = roleTxt ? "" : "none";
}
$("#btn-user").onclick = (e) => { e.stopPropagation(); $("#usermenu").hidden = !$("#usermenu").hidden; };
document.addEventListener("click", (e) => {
  const m = $("#usermenu");
  if (m && !m.hidden && !m.contains(e.target) && e.target !== $("#btn-user")) m.hidden = true;
});
$("#btn-um-lock").onclick = () => {
  $("#usermenu").hidden = true;
  state.auth.locked = true;
  $("#lockscreen").hidden = false;
  $("#in-lock-user").value = state.auth.username || "";
  setTimeout(() => $("#in-lock-pw").focus(), 30);
};
$("#btn-um-logout").onclick = async () => {
  $("#usermenu").hidden = true;
  try { await api("/api/auth/logout", { method: "POST", body: {} }); } catch (e) { /* ignore */ }
  state.token = ""; localStorage.removeItem("linnas_token");
  state.auth.username = ""; state.auth.role = ""; state.auth.session = false;
  renderUserMenu();
  updateRoleUI();
  showHome();
  toast("已退出登录");
};
$("#btn-um-pw").onclick = async () => {
  $("#usermenu").hidden = true;
  const oldPw = await askVaultPw({ title: "修改密码", hint: "请输入当前密码。", withConfirm: false });
  if (!oldPw) return;
  const np = await askVaultPw({ title: "设置新密码", hint: "至少 4 位。", withConfirm: true });
  if (!np) return;
  try {
    await api("/api/users/password", { method: "POST", body: { oldPassword: oldPw.password, newPassword: np.password } });
    toast("密码已修改");
  } catch (e) { toast("失败: " + e.message); }
};

/* ---------- 活跃会话板 ---------- */
function fmtRel(ts) {
  if (!ts) return "—";
  const diff = Math.floor(Date.now() / 1000) - ts;
  if (diff < 60) return "刚刚";
  if (diff < 3600) return Math.floor(diff / 60) + " 分钟前";
  if (diff < 86400) return Math.floor(diff / 3600) + " 小时前";
  return fmtTime(ts);
}
$("#btn-um-sessions").onclick = () => { $("#usermenu").hidden = true; openSessionsDialog(); };
$("#btn-sessions-close").onclick = () => $("#dlg-sessions").close();
async function openSessionsDialog() {
  const box = $("#session-list");
  box.innerHTML = '<div class="pempty">加载中…</div>';
  $("#dlg-sessions").showModal();
  await loadSessions();
}
async function loadSessions() {
  const box = $("#session-list");
  let list = [];
  try {
    const r = await api("/api/auth/sessions");
    list = r.sessions || [];
  } catch (e) {
    box.innerHTML = '<div class="pempty">加载失败:' + (e.message || "未知错误") + "</div>";
    return;
  }
  if (!list.length) { box.innerHTML = '<div class="pempty">没有活跃会话</div>'; return; }
  const roleTxt = { admin: "管理员", user: "普通用户", viewer: "只读用户" };
  const isMobile = /mobile|android|iphone|ipad|harmonyos/i.test(navigator.userAgent);
  box.innerHTML = "";
  for (const s of list) {
    const row = document.createElement("div");
    row.className = "session-row" + (s.current ? " current" : "");
    const ic = document.createElement("span");
    ic.className = "sess-ic";
    ic.innerHTML = svgIcon(isMobile ? "smartphone" : "monitor");
    const main = document.createElement("div");
    main.className = "sess-main";
    const name = document.createElement("b");
    name.textContent = s.username || "—";
    const role = document.createElement("span");
    role.className = "sess-role";
    role.textContent = roleTxt[s.role] || s.role || "";
    const sub = document.createElement("div");
    sub.className = "sess-sub";
    sub.textContent = (s.ip || "—") + " · 最近活动 " + fmtRel(s.lastSeen);
    main.append(name, document.createTextNode(" "), role, sub);
    row.append(ic, main);
    if (s.current) {
      const tag = document.createElement("span");
      tag.className = "sess-tag";
      tag.textContent = "当前";
      row.appendChild(tag);
    } else {
      const b = document.createElement("button");
      b.className = "btn ghost sm";
      b.textContent = "踢出";
      b.onclick = async () => {
        b.disabled = true;
        try {
          await api("/api/auth/revoke", { method: "POST", body: { id: s.id } });
          toast("已踢出该会话");
          await loadSessions();
        } catch (e) { toast("失败: " + e.message); b.disabled = false; }
      };
      row.appendChild(b);
    }
    box.appendChild(row);
  }
}

/* ---------- 用户管理 ---------- */
async function openUsersDialog() {
  await loadUsers();
  $("#dlg-users").showModal();
}
async function loadUsers() {
  let list = [];
  try { list = await api("/api/users"); } catch (e) { /* ignore */ }
  state.users = list || [];
  const box = $("#users-list");
  if (!box) return;
  box.innerHTML = "";
  if (!state.users.length) { box.innerHTML = '<div class="pempty">还没有用户(当前为免登录访问)</div>'; return; }
  const roleTxt = { admin: "管理员", user: "普通用户", viewer: "只读用户" };
  for (const u of state.users) {
    const row = document.createElement("div"); row.className = "userrow";
    const nm = document.createElement("span"); nm.className = "uname"; nm.textContent = u.username;
    const rl = document.createElement("span"); rl.className = "urole" + (u.role === "admin" ? " admin" : ""); rl.textContent = roleTxt[u.role] || u.role;
    row.appendChild(nm); row.appendChild(rl);
    const sel = document.createElement("select");
    for (const [v, l] of Object.entries(roleTxt)) {
      const o = document.createElement("option"); o.value = v; o.textContent = l;
      if (u.role === v) o.selected = true;
      sel.appendChild(o);
    }
    sel.onchange = async () => {
      try { await api("/api/users/update", { method: "POST", body: { id: u.id, role: sel.value } }); toast("角色已更新"); }
      catch (e) { toast("失败: " + e.message); }
    };
    row.appendChild(sel);
    const pwBtn = document.createElement("button"); pwBtn.className = "btn small"; pwBtn.textContent = "改密码";
    pwBtn.onclick = async () => {
      const np = await askVaultPw({ title: "为 " + u.username + " 设置新密码", hint: "至少 4 位。", withConfirm: true });
      if (!np) return;
      try { await api("/api/users/update", { method: "POST", body: { id: u.id, password: np.password } }); toast("密码已更新"); }
      catch (e) { toast("失败: " + e.message); }
    };
    row.appendChild(pwBtn);
    const delBtn = document.createElement("button"); delBtn.className = "btn small del"; delBtn.textContent = "删除";
    delBtn.onclick = async () => {
      const ok = await askConfirm("删除用户", `删除用户「${u.username}」?该用户的登录会立即失效。`, "删除");
      if (!ok) return;
      try { await api("/api/users?id=" + enc(u.id), { method: "DELETE" }); toast("已删除"); loadUsers(); }
      catch (e) { toast("失败: " + e.message); }
    };
    row.appendChild(delBtn);
    box.appendChild(row);
  }
}
$("#btn-add-user").onclick = async () => {
  const u = $("#in-new-user").value.trim();
  const p = $("#in-new-pw").value;
  const role = $("#sel-new-role").value;
  if (!u) { toast("请输入用户名"); return; }
  if (p.length < 4) { toast("密码至少 4 位"); return; }
  try {
    await api("/api/users", { method: "POST", body: { username: u, password: p, role } });
    toast("用户已创建");
    $("#in-new-user").value = ""; $("#in-new-pw").value = "";
    loadUsers();
  } catch (e) { toast("失败: " + e.message); }
};
$("#btn-users-close").onclick = () => $("#dlg-users").close();

/* ---------- 操作日志 ---------- */
async function loadAudit() {
  let list = [];
  try { list = await api("/api/audit?limit=300"); } catch (e) { list = []; }
  state.audit = list || [];
  const box = $("#audit-list");
  box.innerHTML = "";
  if (!state.audit.length) { box.innerHTML = '<div class="pempty">暂无记录</div>'; return; }
  for (const a of state.audit) {
    const row = document.createElement("div"); row.className = "auditrow";
    const tm = document.createElement("span"); tm.className = "atime"; tm.textContent = a.time ? new Date(a.time * 1000).toLocaleString() : "";
    const us = document.createElement("span"); us.className = "auser"; us.textContent = a.user || "(未登录)";
    const ac = document.createElement("span"); ac.className = "aact"; ac.textContent = (a.action || a.path || "") + (a.target ? " · " + a.target : "");
    const st = document.createElement("span"); st.className = "astatus" + (a.status >= 400 ? " err" : ""); st.textContent = a.status || "";
    row.appendChild(tm); row.appendChild(us); row.appendChild(ac); row.appendChild(st);
    box.appendChild(row);
  }
}
$("#btn-audit-refresh").onclick = loadAudit;

/* ---------- 分享链接 ---------- */
async function loadShares() {
  let list = [];
  try { list = await api("/api/shares"); } catch (e) { list = []; }
  state.shares = list || [];
  const box = $("#share-list");
  box.innerHTML = "";
  if (!state.shares.length) { box.innerHTML = '<div class="pempty">还没有分享链接</div>'; return; }
  for (const s of state.shares) {
    const row = document.createElement("div"); row.className = "sharerow";
    const ic = document.createElement("div"); ic.className = "sicon"; ic.innerHTML = svgIcon(s.isDir ? "folder" : "file");
    const meta = document.createElement("div"); meta.className = "smeta";
    const nm = document.createElement("div"); nm.className = "sname"; nm.textContent = s.name;
    const sub = document.createElement("div"); sub.className = "ssub";
    const expTxt = s.expiresAt ? new Date(s.expiresAt * 1000).toLocaleString() : "永久有效";
    sub.textContent = `${s.vpath || ""} · ${s.hasPassword ? "有密码" : "无密码"} · ${s.allowDownload ? "可下载" : "仅查看"} · ${expTxt}`;
    meta.appendChild(nm); meta.appendChild(sub);
    row.appendChild(ic); row.appendChild(meta);
    if (s.expired) { const ex = document.createElement("span"); ex.className = "sexpired"; ex.textContent = "已过期"; row.appendChild(ex); }
    const copy = document.createElement("button"); copy.className = "btn small"; copy.textContent = "复制链接";
    copy.onclick = () => { copyText(location.origin + s.url); };
    row.appendChild(copy);
    const del = document.createElement("button"); del.className = "btn small del"; del.textContent = "删除";
    del.onclick = async () => {
      const ok = await askConfirm("删除分享", `删除「${s.name}」的分享链接?链接立即失效。`, "删除");
      if (!ok) return;
      try { await api("/api/shares?id=" + enc(s.id), { method: "DELETE" }); toast("已删除"); loadShares(); }
      catch (e) { toast("失败: " + e.message); }
    };
    row.appendChild(del);
    box.appendChild(row);
  }
}
function copyText(t) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(t).then(() => toast("已复制")).catch(() => fallbackCopy(t));
  } else fallbackCopy(t);
}
function fallbackCopy(t) {
  const ta = document.createElement("textarea"); ta.value = t; ta.style.position = "fixed"; ta.style.opacity = "0";
  document.body.appendChild(ta); ta.select();
  try { document.execCommand("copy"); toast("已复制"); } catch (e) { toast("复制失败,请手动复制"); }
  ta.remove();
}
$("#btn-share-refresh").onclick = loadShares;

function openShareDialog(vp, name) {
  $("#share-hint").textContent = "分享「" + (name || vp) + "」,拿到链接的人无需登录即可访问。";
  $("#share-exp").value = "24";
  $("#share-pw").value = "";
  $("#share-dl").checked = true;
  state.shareTarget = vp;
  $("#dlg-share").showModal();
}
$("#btn-share-cancel").onclick = () => $("#dlg-share").close();
$("#btn-share-ok").onclick = async () => {
  const vp = state.shareTarget;
  if (!vp) { $("#dlg-share").close(); return; }
  const hours = parseInt($("#share-exp").value, 10) || 0;
  const pw = $("#share-pw").value.trim();
  const dl = $("#share-dl").checked;
  try {
    const r = await api("/api/shares", { method: "POST", body: { vpath: vp, expiresHours: hours, password: pw, allowDownload: dl } });
    $("#dlg-share").close();
    const full = location.origin + (r.url || ("/s/" + r.id));
    $("#share-url").value = full;
    $("#dlg-share-done").showModal();
  } catch (e) { toast("失败: " + e.message); }
};
$("#btn-share-copy").onclick = () => copyText($("#share-url").value);
$("#btn-share-done-ok").onclick = () => $("#dlg-share-done").close();

/* ---------- 远程访问(Tailscale / 托盘) ---------- */
function showRemote() { state.view = "remote"; showView("view-remote"); renderSidebar(); loadRemote(); }
$("#btn-remote").onclick = showRemote;
$("#btn-remote-refresh").onclick = () => loadRemote(true);

function setRow(container, key, valueNode) {
  const rows = container.querySelectorAll(".set-row");
  let row = null;
  for (const r of rows) {
    const k = r.querySelector(".k");
    if (k && k.textContent === key) { row = r; break; }
  }
  if (!row) {
    row = document.createElement("div"); row.className = "set-row";
    const kk = document.createElement("span"); kk.className = "k"; kk.textContent = key;
    const vv = document.createElement("span"); vv.className = "v";
    row.appendChild(kk); row.appendChild(vv);
    container.appendChild(row);
  }
  const vv = row.querySelector(".v");
  vv.innerHTML = "";
  if (typeof valueNode === "string") vv.textContent = valueNode; else vv.appendChild(valueNode);
  return row;
}

function linkBtn(text, url) {
  const b = document.createElement("button");
  b.className = "btn small";
  b.textContent = text;
  b.onclick = () => copyText(url);
  return b;
}

async function loadRemote(force) {
  const st = $("#remote-status"), tsBox = $("#remote-ts"), lanBox = $("#remote-lan"), trayBox = $("#remote-tray");
  st.innerHTML = "<h4>连接状态</h4>";
  setRow(st, "正在检测…", "…");
  let info;
  try { info = await api("/api/remote" + (force ? "?refresh=1" : "")); }
  catch (e) {
    st.innerHTML = "<h4>连接状态</h4>";
    setRow(st, "检测失败", e.message);
    return;
  }

  // 状态总览
  st.innerHTML = "<h4>连接状态</h4>";
  const ts = info.tailscale || {};
  setRow(st, "Tailscale", ts.ready ? "已连接" : (ts.installed ? "已安装,未连接" : "未安装"));
  setRow(st, "托盘常驻", info.tray ? "运行中(点右下角托盘图标)" : "未启用(可从「启动 linNas.bat」或托盘菜单开启)");
  setRow(st, "开机自启", info.autostart ? "已开启(开机自动启动)" : "未开启");

  // Tailscale 区块
  tsBox.innerHTML = "<h4>Tailscale(外网访问)</h4>";
  if (!ts.installed) {
    setRow(tsBox, "状态", "本机还没有安装 Tailscale");
    setRow(tsBox, "怎么用", "安装 Tailscale 并用同一账号登录后,回到这里点「刷新」,就能拿到你的专属外网地址。");
    const a = document.createElement("a");
    a.className = "btn small"; a.textContent = "前往 tailscale.com/download";
    a.href = "https://tailscale.com/download"; a.target = "_blank"; a.rel = "noreferrer";
    setRow(tsBox, "下载地址", a);
  } else {
    setRow(tsBox, "状态", ts.state + (ts.version ? `(v${ts.version})` : ""));
    if (ts.host) setRow(tsBox, "设备名", ts.host);
    if (ts.tailnetName) setRow(tsBox, "网络", ts.tailnetName);
    if (ts.ips && ts.ips.length) setRow(tsBox, "Tailscale IP", ts.ips.join(", "));
    if (ts.dnsName) {
      const wrap = document.createElement("div");
      wrap.appendChild(linkBtn("复制地址", ts.dnsUrl));
      const a = document.createElement("a");
      a.className = "btn small"; a.textContent = "打开"; a.href = ts.dnsUrl; a.target = "_blank"; a.rel = "noreferrer";
      wrap.appendChild(a);
      setRow(tsBox, "MagicDNS 地址", wrap);
    }
    if (ts.url) {
      const wrap = document.createElement("div");
      wrap.appendChild(linkBtn("复制地址", ts.url));
      const a = document.createElement("a");
      a.className = "btn small"; a.textContent = "打开"; a.href = ts.url; a.target = "_blank"; a.rel = "noreferrer";
      wrap.appendChild(a);
      setRow(tsBox, "外网访问", wrap);
    }
    if (ts.hint) setRow(tsBox, "提示", ts.hint);
    if (ts.error) setRow(tsBox, "错误", ts.error);

    // tailscale serve(HTTPS 发布)
    if (ts.ready) {
      const wrap = document.createElement("div");
      const btn = document.createElement("button");
      btn.className = "btn small" + (ts.serveOn ? " primary" : "");
      btn.textContent = ts.serveOn ? "关闭 HTTPS 发布" : "开启 HTTPS 发布";
      btn.onclick = async () => {
        try {
          const r = await api("/api/remote/serve", { method: "POST", body: { enable: !ts.serveOn } });
          toast(ts.serveOn ? "已关闭 HTTPS 发布" : "已开启:" + (r.url || ""));
          loadRemote(true);
        } catch (e) { toast("失败: " + e.message); }
      };
      wrap.appendChild(btn);
      if (ts.serveOn && ts.serveUrl) {
        const a = document.createElement("a");
        a.className = "btn small"; a.textContent = "打开"; a.href = ts.serveUrl; a.target = "_blank"; a.rel = "noreferrer";
        wrap.appendChild(a);
        wrap.appendChild(linkBtn("复制", ts.serveUrl));
      }
      setRow(tsBox, "HTTPS 发布", wrap);
    }
  }

  // 局域网地址
  lanBox.innerHTML = "<h4>局域网地址</h4>";
  const lanUrls = info.lanUrls || [];
  if (!lanUrls.length) setRow(lanBox, "地址", "未获取到局域网地址");
  for (const u of lanUrls) {
    const wrap = document.createElement("div");
    wrap.appendChild(linkBtn("复制", u));
    const a = document.createElement("a");
    a.className = "btn small"; a.textContent = "打开"; a.href = u; a.target = "_blank"; a.rel = "noreferrer";
    wrap.appendChild(a);
    setRow(lanBox, u.replace("http://", "").replace("/ui/", ""), wrap);
  }

  // 本机常驻
  trayBox.innerHTML = "<h4>本机常驻</h4>";
  setRow(trayBox, "托盘模式", info.tray ? "运行中" : "未运行(用带 -tray 参数启动即可后台常驻)");
  const adm = isAdmin();
  if (adm) {
    let autoOn = info.autostart;
    const autoBtn = document.createElement("button");
    autoBtn.className = "btn small" + (autoOn ? "" : " primary");
    autoBtn.textContent = autoOn ? "关闭开机自启" : "开启开机自启";
    autoBtn.onclick = async () => {
      try {
        const r = await api("/api/autostart", { method: "POST", body: { enable: !autoOn } });
        autoOn = !!r.enabled;
        toast(autoOn ? "已开启开机自启" : "已关闭开机自启");
        loadRemote();
      } catch (e) { toast("失败: " + e.message); }
    };
    setRow(trayBox, "开机自启", autoBtn);
    setRow(trayBox, "说明", "开机自启会在登录 Windows 后以托盘方式启动 linNas,不弹黑窗口。");
  } else {
    setRow(trayBox, "开机自启", "仅管理员可设置");
  }
}

/* ---------- 视图切换 ---------- */
function showAudit() { state.view = "audit"; showView("view-audit"); renderSidebar(); loadAudit(); }
function showShares() { state.view = "shares"; showView("view-shares"); renderSidebar(); loadShares(); }
$("#btn-audit").onclick = showAudit;
$("#btn-shares").onclick = showShares;

(async function boot() {
  applyIcons();
  applyTheme();
  $("#btn-settings").innerHTML = svgIcon("settings");
  $("#btn-viewmode").innerHTML = svgIcon(state.mode === "grid" ? "list" : "grid");
  $("#btn-viewmode").title = state.mode === "grid" ? "切换为列表" : "切换为网格";
  sortDirIcon();
  const ok = await checkHealth();
  if (ok) {
    // v1.4.0:先探知是否多用户模式,再加载数据
    try {
      const st = await api("/api/auth/status");
      state.auth.multiUser = !!st.multiUser;
      state.auth.userCount = st.userCount || 0;
      state.auth.idleLockMinutes = st.idleLockMinutes || 30;
      if (st.multiUser) {
        try {
          const me = await api("/api/auth/me");
          if (me && me.username) {
            state.auth.username = me.username; state.auth.role = me.role; state.auth.session = true;
          }
        } catch (e) { /* 需要登录 */ }
      }
    } catch (e) { /* ignore */ }
    try { await loadLibs(); await loadDisks(); } catch (e) { /* ignore */ }
    await loadVaultStatus();
  }
  renderHome();
  renderUserMenu();
  updateRoleUI();
  // 托盘菜单「远程访问…」会打开 /ui/#remote;按锚点直达对应页面。
  if (location.hash === "#remote") showRemote();
  else if (!state.auth.multiUser || state.auth.session) showOverview();
  setInterval(checkHealth, 15000);
})();