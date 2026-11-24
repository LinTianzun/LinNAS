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
  searchResults: [],
  devices: [],
  trash: [],
};

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const enc = (s) => encodeURIComponent(s);

/* ============================================================ 图标 */
const ICONS = {
  plus: { d: '<path d="M12 5v14M5 12h14"/>' },
  "chevron-left": { d: '<path d="M15 18l-6-6 6-6"/>' },
  search: { d: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>' },
  x: { d: '<path d="M18 6L6 18M6 6l12 12"/>' },
  upload: { d: '<path d="M12 15V4M6 9l6-6 6 6"/><path d="M4 20h16"/>' },
  download: { d: '<path d="M12 4v11M6 11l6 6 6-6"/><path d="M4 20h16"/>' },
  play: { d: '<path d="M8 5v14l11-7z"/>', fill: true },
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
  logout: { d: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5M21 12H9"/>' },
  eye: { d: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>' },
  film: { d: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5"/>' },
  monitor: { d: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>' },
  smartphone: { d: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M12 18h.01"/>' },
  tablet: { d: '<rect x="4" y="2" width="16" height="20" rx="2"/><path d="M12 18h.01"/>' },
  tv: { d: '<rect x="2" y="7" width="20" height="13" rx="2"/><path d="M8 2l4 5 4-5"/>' },
  pip: { d: '<rect x="2" y="4" width="20" height="16" rx="2"/><rect x="12" y="12" width="8" height="6" rx="1"/>' },
  maximize: { d: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/>' },
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
function askVaultPw({ title, hint, withConfirm }) {
  return new Promise((resolve) => {
    vaultPwResolver = resolve; vaultPwWithConfirm = !!withConfirm;
    $("#vaultpw-title").textContent = title;
    $("#vaultpw-hint").textContent = hint || "";
    $("#in-vault-pw").value = ""; $("#in-vault-pw2").value = "";
    $("#vaultpw-pw2-wrap").hidden = !withConfirm;
    $("#in-vault-pw").type = "password"; $("#in-vault-pw2").type = "password";
    $("#dlg-vaultpw").showModal();
    setTimeout(() => $("#in-vault-pw").focus(), 30);
  });
}
function settleVaultPw(v) { if (vaultPwResolver) { const r = vaultPwResolver; vaultPwResolver = null; r(v); } }
$("#btn-vaultpw-ok").onclick = () => {
  const pw = $("#in-vault-pw").value;
  if (vaultPwWithConfirm) {
    const pw2 = $("#in-vault-pw2").value;
    if (pw.length < 4) { toast("密码至少 4 位"); return; }
    if (pw !== pw2) { toast("两次输入的密码不一致"); return; }
  } else if (!pw) { toast("请输入密码"); return; }
  settleVaultPw({ password: pw });
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
    wrap.appendChild(btn);
  }
  $("#btn-vault").classList.toggle("active", state.view === "vault");
  $("#btn-media").classList.toggle("active", state.view === "media");
  $("#btn-search").classList.toggle("active", state.view === "search");
  $("#btn-devices").classList.toggle("active", state.view === "devices");
  $("#btn-trash").classList.toggle("active", state.view === "trash");
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
    card.querySelector(".lc-edit").onclick = () => openLibDialog(lib);
    card.querySelector(".lc-del").onclick = async () => {
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
const VIEWS = ["view-home", "view-browse", "view-vault", "view-media", "view-search", "view-devices", "view-trash"];
function showView(target) {
  VIEWS.forEach((v) => { const el = document.getElementById(v); if (el) el.hidden = v !== target; });
}
function showHome() { state.view = "home"; showView("view-home"); renderSidebar(); renderHome(); }
function showBrowse() { state.view = "browse"; showView("view-browse"); renderSidebar(); }
function showVault() { state.view = "vault"; showView("view-vault"); renderSidebar(); }
function showMedia() { state.view = "media"; showView("view-media"); renderSidebar(); }
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
$("#btn-add-lib").onclick = () => openLibDialog(null);
$("#btn-add-lib2").onclick = () => openLibDialog(null);
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
    a.addEventListener("drop", (ev) => { if (!isDraggingItems()) return; ev.preventDefault(); a.classList.remove("drop-target"); const vps = getDragVPaths(ev); if (vps.length) doMove(vps, target()); });
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
  const arr = list.slice();
  arr.sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    if (state.sort === "size") return (b.size || 0) - (a.size || 0);
    if (state.sort === "time") return (b.modTime || 0) - (a.modTime || 0);
    return a.name.localeCompare(b.name, "zh-Hans-CN", { numeric: true, sensitivity: "base" });
  });
  return arr;
}

function thumbUrl(vp, w) { return apiUrl("/api/thumb?w=" + (w || 320) + "&vpath=" + enc(vp)); }

function renderEntries() {
  const list = $("#file-list");
  list.className = "file-list" + (state.mode === "grid" ? " grid" : "") + (state.selectMode ? " selmode" : "");
  list.innerHTML = "";
  if (!state.entries.length) { list.appendChild(emptyState("folder", "这个文件夹是空的", "把文件拖进窗口,或点右上角「上传」添加文件。", false)); return; }
  const rows = sortedEntries();
  if (!rows.length) { list.appendChild(emptyState("search", "没有匹配的文件", `当前目录没有包含「${state.filter.trim()}」的文件或文件夹。`, false)); return; }

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
      addBtn("lock", "设为私密(存入保险箱)", () => setPrivate(vp));
    }
    addBtn("edit", "改名", () => renameEntry(vp, e.name));
    addBtn("trash", "删除", () => deleteEntry(vp, e), "del");

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
    row.addEventListener("dragend", () => { dragVPaths = []; row.classList.remove("dragging"); $$(".drop-target").forEach((x) => x.classList.remove("drop-target")); });
    if (e.isDir) {
      row.addEventListener("dragover", (ev) => {
        if (!isDraggingItems()) return;
        ev.preventDefault();
        if (ev.dataTransfer) ev.dataTransfer.dropEffect = "move";
        row.classList.add("drop-target");
      });
      row.addEventListener("dragleave", (ev) => { if (!row.contains(ev.relatedTarget)) row.classList.remove("drop-target"); });
      row.addEventListener("drop", (ev) => {
        if (!isDraggingItems()) return;
        ev.preventDefault(); ev.stopPropagation(); row.classList.remove("drop-target");
        const vps = getDragVPaths(ev).filter((p) => p !== vp && !p.startsWith(vp + "/"));
        if (vps.length) doMove(vps, vp);
      });
    }
    list.appendChild(row);
  }
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
}
function updatePasteBar() {
  const c = state.clipboard;
  $("#pastebar").hidden = !c || !c.items.length || state.view !== "browse";
  if (c && c.items.length) {
    $("#paste-count").textContent = String(c.items.length);
    $("#paste-mode").textContent = c.mode === "copy" ? "(复制)" : "(剪切)";
  }
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
  const ok = await askConfirm("删除", `确定删除选中的 ${vps.length} 项?(文件夹将递归删除,不可恢复)`);
  if (!ok) return;
  let errs = 0;
  for (const vp of vps) {
    try { await api("/api/delete", { method: "POST", body: { vpath: vp, recursive: true } }); }
    catch (e) { errs++; }
  }
  toast(errs ? `完成,${errs} 项失败` : "已删除");
  clearSelection(); loadDir();
};

$("#btn-sel-move").onclick = () => {
  const vps = Array.from(state.selected);
  if (!vps.length) return;
  openFolderPicker(`移动 ${vps.length} 项到…`, async (dest) => {
    try {
      const r = await api("/api/move", { method: "POST", body: { vpaths: vps, dest } });
      toast(r.errors && r.errors.length ? `移动 ${r.count} 项,${r.errors.length} 项失败` : `已移动 ${r.count} 项`);
    } catch (e) { toast("失败: " + e.message); }
    clearSelection(); loadDir();
  }, state.curPath);
};

$("#btn-sel-copy").onclick = () => { setClipboard("copy"); };
$("#btn-sel-cut").onclick = () => { setClipboard("cut"); };
function setClipboard(mode) {
  const items = Array.from(state.selected).map((vp) => ({ vpath: vp, name: vp.split("/").pop(), isDir: false }));
  state.clipboard = { mode, items };
  clearSelection(); updatePasteBar();
  toast(`已${mode === "copy" ? "复制" : "剪切"} ${items.length} 项,到目标文件夹点「粘贴」`);
}
$("#btn-paste").onclick = async () => {
  const c = state.clipboard;
  if (!c || !c.items.length) return;
  const endpoint = c.mode === "copy" ? "/api/copy" : "/api/move";
  try {
    const r = await api(endpoint, { method: "POST", body: { vpaths: c.items.map((i) => i.vpath), dest: state.curPath } });
    toast(r.errors && r.errors.length ? `粘贴 ${r.count} 项,${r.errors.length} 项失败` : `已粘贴 ${r.count} 项`);
  } catch (e) { toast("失败: " + e.message); }
  state.clipboard = null; updatePasteBar(); loadDir();
};
$("#btn-paste-cancel").onclick = () => { state.clipboard = null; updatePasteBar(); };

/* 拖拽辅助 */
let dragVPaths = [];
function isDraggingItems() { return dragVPaths.length > 0; }
function getDragVPaths(ev) {
  if (dragVPaths.length) return dragVPaths.slice();
  try { return JSON.parse(ev.dataTransfer.getData("application/x-linnas-drag") || "[]"); } catch (e) { return []; }
}
async function doMove(vps, dest) {
  try {
    const r = await api("/api/move", { method: "POST", body: { vpaths: vps, dest } });
    toast(r.errors && r.errors.length ? `移动 ${r.count} 项,${r.errors.length} 项失败` : `已移动到「${dest.split("/").pop()}」`);
  } catch (e) { toast("失败: " + e.message); }
  loadDir();
}

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
let curPlay = { key: "", kind: "", el: null };

function openPreview(kind, vp, name) {
  // 视频走 /api/stream:原生格式转发 raw(支持 Range 拖动),AVI/RMVB 等自动转码
  if (kind === "video") openPreviewSrc(kind, apiUrl("/api/stream?vpath=" + enc(vp)), name, "vp:" + vp);
  else openPreviewSrc(kind, apiUrl("/api/raw?vpath=" + enc(vp)), name, "vp:" + vp);
}

function openPreviewSrc(kind, src, name, key) {
  const modal = $("#player-modal");
  const v = $("#player-video"), a = $("#player-audio"), img = $("#player-img");
  v.pause(); a.pause();
  v.removeAttribute("src"); a.removeAttribute("src"); img.removeAttribute("src");
  v.hidden = true; a.hidden = true; img.hidden = true;
  curPlay = { key: key || src, kind, el: null };
  if (kind === "video") { v.src = src; v.hidden = false; curPlay.el = v; }
  else if (kind === "audio") { a.src = src; a.hidden = false; curPlay.el = a; }
  else { img.src = src; img.hidden = false; }
  $("#player-title").textContent = name;
  $("#player-speed").textContent = "1×";
  if (kind === "video") v.playbackRate = 1;
  modal.hidden = false;
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
  }
}

function savePos() {
  const el = curPlay.el;
  if (el && el.currentTime > 0) { try { localStorage.setItem("linnas_pos:" + curPlay.key, String(el.currentTime)); } catch (e) {} }
}

function closePreview() {
  savePos();
  const v = $("#player-video"), a = $("#player-audio"), img = $("#player-img");
  v.pause(); a.pause();
  v.onloadedmetadata = null; v.ontimeupdate = null; a.onloadedmetadata = null; a.ontimeupdate = null;
  v.removeAttribute("src"); a.removeAttribute("src"); img.removeAttribute("src");
  if (v.load) v.load(); if (a.load) a.load();
  $("#player-modal").hidden = true;
  curPlay = { key: "", kind: "", el: null };
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

function joinRel(dir, name) {
  dir = (dir || "").replace(/^\/+|\/+$/g, "");
  return dir ? dir + "/" + name : name;
}

function vaultOpen(rel) {
  state.vault.path = rel || "";
  loadVaultList().then(renderVault);
}

function renderVault() {
  const body = $("#vault-body");
  const acts = $("#vault-actions");
  body.innerHTML = ""; acts.innerHTML = "";
  const v = state.vault;
  if (!v.exists) {
    body.innerHTML = `<div class="vault-hero"><div class="vlock">${svgIcon("lock")}</div><div><b>尚未创建保险箱</b><br><span>设置一个密码即可创建;之后存放在这里的文件不会出现在普通浏览中。</span></div></div>`;
    const b = document.createElement("button"); b.className = "btn primary"; b.innerHTML = svgIcon("lock") + " 设置密码"; b.onclick = vaultSetup;
    body.querySelector(".vault-hero").appendChild(b);
    return;
  }
  if (!v.unlocked) {
    body.innerHTML = `<div class="vault-hero"><div class="vlock">${svgIcon("lock")}</div><div><b>保险箱已锁定</b><br><span>输入密码解锁后可查看其中的文件。</span></div></div>`;
    const b = document.createElement("button"); b.className = "btn primary"; b.innerHTML = svgIcon("unlock") + " 解锁"; b.onclick = vaultUnlock;
    body.querySelector(".vault-hero").appendChild(b);
    return;
  }
  // 已解锁:操作按钮
  const up = document.createElement("button"); up.className = "btn primary"; up.innerHTML = svgIcon("upload") + " 上传"; up.onclick = () => $("#vault-file-input").click();
  const mk = document.createElement("button"); mk.className = "btn"; mk.innerHTML = svgIcon("folder-plus") + " 新建文件夹"; mk.onclick = vaultMkdir;
  const imp = document.createElement("button"); imp.className = "btn"; imp.innerHTML = svgIcon("folder") + " 导入文件"; imp.onclick = vaultImportFiles;
  const lock = document.createElement("button"); lock.className = "btn"; lock.innerHTML = svgIcon("logout") + " 锁定"; lock.onclick = vaultLock;
  acts.appendChild(up); acts.appendChild(mk); acts.appendChild(imp); acts.appendChild(lock);

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

  if (!v.entries.length) {
    body.insertAdjacentHTML("beforeend", `<div class="vault-hero"><div class="vlock">${svgIcon("folder")}</div><div><b>这个文件夹是空的</b><br><span>点「上传」放文件,或「新建文件夹」「导入文件」。</span></div></div>`);
    return;
  }
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
    toast("保险箱已创建");
    await vaultUnlock(r.password);
  } catch (e) { toast("失败: " + e.message); }
}
async function vaultUnlock(preset) {
  let pw = preset;
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

function renderMediaGrid() {
  const grid = $("#media-grid");
  grid.innerHTML = "";
  if (!state.mediaItems.length) { grid.innerHTML = '<div class="pempty" style="grid-column:1/-1">暂无媒体文件(索引建好后才有)</div>'; return; }
  for (const it of state.mediaItems) {
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
    ab("play", "播放", () => openPreview(kind, it.path, it.name));
    ab("download", "下载", () => { location.href = apiUrl("/api/raw?dl=1&vpath=" + enc(it.path)); });
    ab("folder", "打开所在文件夹", () => { openPath(it.path.replace(/\/[^/]*$/, "") || "/"); });
    row.appendChild(icon); row.appendChild(meta); row.appendChild(acts);
    row.onclick = () => openPreview(kind, it.path, it.name);
    grid.appendChild(row);
  }
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
(async function boot() {
  applyIcons();
  applyTheme();
  $("#btn-settings").innerHTML = svgIcon("settings");
  $("#btn-viewmode").innerHTML = svgIcon(state.mode === "grid" ? "list" : "grid");
  $("#btn-viewmode").title = state.mode === "grid" ? "切换为列表" : "切换为网格";
  const ok = await checkHealth();
  if (ok) {
    try { await loadLibs(); await loadDisks(); } catch (e) { /* ignore */ }
    await loadVaultStatus();
  }
  renderHome();
  setInterval(checkHealth, 15000);
})();