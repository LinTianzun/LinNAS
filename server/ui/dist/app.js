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
  devices: [],
  trash: [],
  // v1.4.0 D 安全
  auth: { multiUser: false, userCount: 0, idleLockMinutes: 30, username: "", role: "", session: false, locked: false },
  users: [],
  audit: [],
  shares: [],
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
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
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
async function checkHealth() {
  const conn = $("#conn");
  try {
    const h = await api("/api/health");
    state.version = h.version;
    conn.className = "conn ok";
    $("#conn-text").textContent = "在线";
    conn.title = `linNas v${h.version}`;
    $("#ver-text").textContent = `v${h.version}`;
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
  $("#btn-media").classList.toggle("active", state.view === "media");
  $("#btn-music").classList.toggle("active", state.view === "music");
  $("#btn-search").classList.toggle("active", state.view === "search");
  $("#btn-devices").classList.toggle("active", state.view === "devices");
  $("#btn-trash").classList.toggle("active", state.view === "trash");
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
const VIEWS = ["view-home", "view-browse", "view-vault", "view-media", "view-music", "view-search", "view-devices", "view-trash", "view-audit", "view-shares", "view-remote", "view-pair"];
function showView(target) {
  VIEWS.forEach((v) => { const el = document.getElementById(v); if (el) el.hidden = v !== target; });
  if (typeof updateMiniPlayer === "function") updateMiniPlayer();
}
function showHome() { state.view = "home"; showView("view-home"); renderSidebar(); renderHome(); }
function showBrowse() { state.view = "browse"; showView("view-browse"); renderSidebar(); }
function showVault() { state.view = "vault"; showView("view-vault"); renderSidebar(); }
function showMedia() { state.view = "media"; showView("view-media"); renderSidebar(); }
function showMusic() { state.view = "music"; showView("view-music"); renderSidebar(); }
function showSearch() { state.view = "search"; showView("view-search"); renderSidebar(); setTimeout(() => $("#in-global-search").focus(), 60); }
function showDevices() { state.view = "devices"; showView("view-devices"); renderSidebar(); }
function showTrash() { state.view = "trash"; showView("view-trash"); renderSidebar(); }
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
  if (kind === "video" || kind === "audio") {
    const playable = (k) => k === "video" || k === "audio";
    let items = [];
    if (state.view === "media") {
      items = state.mediaItems.filter((x) => playable(x.kind)).map((x) => ({ kind: x.kind, vp: x.path, name: x.name }));
    } else if (state.view === "search") {
      items = state.searchResults.filter((x) => playable(x.kind)).map((x) => ({ kind: x.kind, vp: x.path, name: x.name }));
    } else {
      const base = state.curPath.replace(/\/$/, "");
      items = state.entries.filter((e) => !e.isDir).map((e) => ({ kind: kindOf(e.name), vp: base + "/" + e.name, name: e.name })).filter((x) => playable(x.kind));
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
  modal.hidden = false;
  renderPlaylist(); applyPlaylistVisibility();
  if (kind === "video") { loadSubtitles(v); }
  else { $("#player-sub").hidden = true; state.subTracks = []; state.subIdx = -1; }
  if (kind === "video" || kind === "audio") {
    const el = curPlay.el;
    el.onloadedmetadata = () => {
      const saved = parseFloat(localStorage.getItem("linnas_pos:" + curPlay.key) || "0");
      if (saved > 3 && isFinite(el.duration) && saved < el.duration - 3) el.currentTime = saved;
      el.play().catch(() => {});
    };
    el.ontimeupdate = () => {
      if (isFinite(el.currentTime) && el.currentTime > 0) { try { localStorage.setItem("linnas_pos:" + curPlay.key, String(el.currentTime)); } catch (e) {} }
    };
    el.onended = () => {
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
  if (v.hidden) return;
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
$("#btn-brand").onclick = showHome;
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
  $("#pair-qr-cap").textContent = "扫码连接 · " + ip + ":" + port;
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
  group("关于", [
    row("版本", "v" + s.version),
    row("监听端口", String(s.port)),
    row("数据目录", s.dataDir),
    row("Go 运行时", s.goos + "/" + s.goarch),
    row("ffmpeg", s.ffmpeg ? "已检测到(缩略图/转码可用)" : "未安装(仅图标)"),
    row("存储库", String(s.libCount) + " 个"),
  ]);

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
const musicState = { list: [], index: -1, playing: false, miniClosed: false, shuffle: false, repeat: "off", filter: "", saveAt: 0, pendingPos: 0 };

function fmtClock(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  return Math.floor(sec / 60) + ":" + String(sec % 60).padStart(2, "0");
}
function musicAudio() { return $("#music-audio"); }

/* 唱片旋转交给 CSS 动画(停播就移除 class,避免每帧改 DOM) */
function startRotation() { const disc = $("#music-disc"); if (disc) disc.classList.add("spinning"); }
function stopRotation() { const disc = $("#music-disc"); if (disc) disc.classList.remove("spinning"); }

function setMusicPlaying(playing) {
  musicState.playing = playing;
  if (playing) musicState.miniClosed = false;
  const btn = $("#music-play");
  if (btn) btn.innerHTML = svgIcon(playing ? "pause" : "play");
  if (playing) startRotation(); else stopRotation();
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
  const items = q ? musicState.list.filter((it) => it.name.toLowerCase().includes(q)) : musicState.list;
  $("#music-count").textContent = musicState.list.length
    ? (q ? items.length + " / " + musicState.list.length + " 首" : musicState.list.length + " 首") : "";
  ul.innerHTML = "";
  if (!items.length) {
    ul.innerHTML = '<li class="music-empty">' + (musicState.list.length ? "没有匹配的歌名" : "暂无音乐文件(索引建好后才有)") + "</li>";
    return;
  }
  items.forEach((it) => {
    const i = musicState.list.indexOf(it);
    const li = document.createElement("li");
    if (i === musicState.index) li.className = "playing";
    li.innerHTML = '<span class="ml-idx"></span><span class="ml-main"><span class="ml-name"></span><span class="ml-sub"></span></span>';
    li.querySelector(".ml-idx").textContent = (i === musicState.index && musicState.playing) ? "♪" : String(i + 1);
    li.querySelector(".ml-name").textContent = it.name.replace(/\.[^.]+$/, "");
    li.querySelector(".ml-sub").textContent = fmtSize(it.size) + (it.modTime ? " · " + fmtTime(it.modTime) : "");
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
  let vol = 1;
  try { const v = parseFloat(localStorage.getItem("linnas_music_vol") || ""); if (isFinite(v)) vol = Math.min(1, Math.max(0, v)); } catch (e) {}
  a.volume = vol;
  const mv = $("#music-vol"); if (mv) mv.value = String(vol);
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
  if (musicState.shuffle && n > 1) {
    let k = musicState.index;
    for (let t = 0; t < 8; t++) { const r = Math.floor(Math.random() * n); if (r !== musicState.index) { k = r; break; } }
    if (k === musicState.index) k = (musicState.index + 1) % n;
    playMusic(k);
    return;
  }
  playMusic((musicState.index + delta + n) % n);
}

/* 随机 / 循环 / 音量 / 筛选 状态与按钮同步 */
function syncMusicToggles() {
  const sh = $("#music-shuffle");
  if (sh) { sh.classList.toggle("on", musicState.shuffle); sh.setAttribute("aria-pressed", musicState.shuffle ? "true" : "false"); sh.title = "随机播放:" + (musicState.shuffle ? "开" : "关"); }
  const rp = $("#music-repeat");
  if (rp) {
    const on = musicState.repeat !== "off";
    rp.classList.toggle("on", on);
    rp.setAttribute("aria-pressed", on ? "true" : "false");
    rp.title = "循环:" + (musicState.repeat === "one" ? "单曲循环" : musicState.repeat === "all" ? "列表循环" : "关");
    rp.textContent = musicState.repeat === "one" ? "⥂" : "⥁";
  }
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

$("#btn-music").onclick = () => { showMusic(); loadMusic(); };
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
    if (musicState.repeat === "one") { try { a.currentTime = 0; } catch (e) {} a.play().catch(() => {}); return; }
    const n = musicState.list.length;
    if (musicState.shuffle && n > 1) { musicStep(1); return; }
    if (musicState.index < n - 1) { musicStep(1); return; }
    if (musicState.repeat === "all" && n > 0) { playMusic(0); return; }
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

/* 随机 / 循环 / 音量 / 筛选 / 迷你播放器拖动进度 / 音乐页快捷键 */
(function bindMusicExtras() {
  const sh = $("#music-shuffle"), rp = $("#music-repeat"), vol = $("#music-vol"), filter = $("#music-filter");
  if (sh) sh.onclick = () => { musicState.shuffle = !musicState.shuffle; syncMusicToggles(); };
  if (rp) rp.onclick = () => {
    musicState.repeat = musicState.repeat === "off" ? "all" : musicState.repeat === "all" ? "one" : "off";
    syncMusicToggles();
  };
  if (vol) vol.oninput = () => {
    const a = musicAudio(); const v = parseFloat(vol.value);
    if (a) a.volume = isFinite(v) ? Math.min(1, Math.max(0, v)) : 1;
    try { localStorage.setItem("linnas_music_vol", String(isFinite(v) ? v : 1)); } catch (e) {}
  };
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
$("#btn-media-more").onclick = () => loadMedia(false);

async function loadMedia(reset) {
  const grid = $("#media-grid"), sub = $("#media-sub");
  if (reset) { state.mediaOffset = 0; state.mediaItems = []; grid.innerHTML = '<div class="pempty" style="grid-column:1/-1">加载中…</div>'; }
  try {
    const items = await api(`/api/media?type=${enc(state.mediaType)}&sort=${enc(state.mediaSort)}&limit=200&offset=${state.mediaOffset}`);
    if (reset) state.mediaItems = [];
    state.mediaItems = state.mediaItems.concat(items || []);
    state.mediaOffset = state.mediaItems.length;
    renderMediaGrid();
    const label = { video: "视频", image: "图片", audio: "音乐" }[state.mediaType] || "媒体";
    sub.textContent = `${label} · 已加载 ${state.mediaItems.length} 项`;
    $("#btn-media-more").hidden = (items || []).length < 200;
  } catch (e) { grid.innerHTML = `<div class="pempty" style="grid-column:1/-1">加载失败: ${e.message}</div>`; }
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
$("#btn-search").onclick = () => { showSearch(); if (state.searchResults.length) renderSearchResults(); };
$("#in-global-search").oninput = (e) => {
  const q = e.target.value.trim();
  clearTimeout(searchTimer);
  if (!q) { state.searchResults = []; $("#search-sub").textContent = ""; $("#search-list").innerHTML = ""; return; }
  searchTimer = setTimeout(() => doGlobalSearch(q), 250);
};
async function doGlobalSearch(q) {
  const list = $("#search-list");
  list.className = "file-list";
  list.innerHTML = '<div class="pempty">搜索中…</div>';
  try {
    const items = await api("/api/search?q=" + enc(q) + "&limit=200");
    state.searchResults = items || [];
    $("#search-sub").textContent = `找到 ${state.searchResults.length} 个文件`;
    renderSearchResults();
  } catch (e) { list.innerHTML = `<div class="pempty">搜索失败: ${e.message}</div>`; }
}
function renderSearchResults() {
  const list = $("#search-list"); list.className = "file-list"; list.innerHTML = "";
  if (!state.searchResults.length) { list.innerHTML = '<div class="pempty">没有找到匹配文件</div>'; return; }
  for (const it of state.searchResults) {
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
    list.appendChild(row);
  }
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
$("#btn-trash").onclick = async () => { showTrash(); await loadTrash(); };
$("#btn-trash-refresh").onclick = () => loadTrash();
$("#btn-trash-empty").onclick = async () => {
  const ok = await askConfirm("清空回收站", "将彻底删除回收站中的所有文件,不可恢复。");
  if (!ok) return;
  try { const r = await api("/api/trash/empty", { method: "POST", body: {} }); await loadTrash(); toast(`已清理 ${r.count} 项`); }
  catch (e) { toast("失败: " + e.message); }
};
async function loadTrash() {
  const wrap = $("#trash-list");
  wrap.innerHTML = '<div class="pempty">加载中…</div>';
  let items = [];
  try { items = await api("/api/trash"); } catch (e) { wrap.innerHTML = `<div class="pempty">加载失败: ${e.message}</div>`; return; }
  state.trash = items || [];
  wrap.innerHTML = "";
  if (!state.trash.length) { wrap.innerHTML = '<div class="pempty">回收站是空的</div>'; return; }
  for (const it of state.trash) {
    const kind = it.isDir ? "dir" : kindOf(it.name);
    const row = document.createElement("div"); row.className = "vrow";
    const icon = document.createElement("div"); icon.className = "fi t-" + kind;
    icon.innerHTML = svgIcon(kind === "dir" ? "folder" : "file");
    const meta = document.createElement("div"); meta.className = "vm";
    meta.innerHTML = `<div class="vname"></div><div class="vsub"></div>`;
    meta.querySelector(".vname").textContent = it.name;
    meta.querySelector(".vsub").textContent = (it.isDir ? "文件夹" : fmtSize(it.size)) + " · 原位置 " + (it.origPath || "") + " · " + fmtTime(it.trashedAt);
    const acts = document.createElement("div"); acts.className = "facts";
    const ab = (ic, title, fn, extra) => { const b = document.createElement("button"); b.className = "btn icon-btn " + (extra || ""); b.title = title; b.innerHTML = svgIcon(ic); b.onclick = fn; acts.appendChild(b); };
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
    row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);
    wrap.appendChild(row);
  }
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
  renderHome();
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
  setInterval(checkHealth, 15000);
})();