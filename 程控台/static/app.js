const TOKEN = window.DESK_TOKEN;
const SECTIONS = {
  overview: ["运行总览", "集中登记、检索并启动本机程序"],
  favorite: ["常用", "优先放在手边的项目"],
  recent: ["最近", "按最近一次启动排列"],
  office: ["办公", "文档、表格与沟通"],
  dev: ["研发", "开发、设计与工程"],
  tools: ["工具", "系统与效率工具"],
  media: ["媒体", "影音与互动程序"],
  other: ["其他", "尚未归类的项目"],
};
const DEFAULT_CATEGORIES = [
  { id: "office", name: "办公" },
  { id: "dev", name: "研发" },
  { id: "tools", name: "工具" },
  { id: "media", name: "媒体" },
  { id: "other", name: "其他" },
];
const CAT = {
  office: "#7ea0c4",
  dev: "#6eaea6",
  tools: "#c6a15b",
  media: "#b3a6cc",
  other: "#c8c2b6",
};
const TONES = [
  ["#e5f1ee", "#0f5f5c"],
  ["#f4efe4", "#8a6a32"],
  ["#e7eef6", "#2c5278"],
  ["#f6ecea", "#7a403a"],
  ["#eeeaf4", "#53406e"],
  ["#e8f0e6", "#2d5a3c"],
  ["#f1eee8", "#5c5648"],
];

const q = document.getElementById("q");
const nav = document.getElementById("nav");
const kicker = document.getElementById("kicker");
const title = document.getElementById("title");
const subtitle = document.getElementById("subtitle");
const countPill = document.getElementById("count-pill");
const sortBox = document.getElementById("sort");
const viewBox = document.getElementById("view");
const stats = document.getElementById("stats");
const recent = document.getElementById("recent");
const main = document.getElementById("main");
const scroll = document.getElementById("scroll");
const statusText = document.getElementById("status-text");
const clock = document.getElementById("clock");
const live = document.getElementById("live");
const version = document.getElementById("version");
const modalRoot = document.getElementById("modal-root");
const menu = document.getElementById("menu");
const toastEl = document.getElementById("toast");

let state = { apps: [], history: [], categories: [], stats: {}, version: "" };
let section = localStorage.getItem("desk-section") || "overview";
let view = localStorage.getItem("desk-view") || "grid";
let sort = localStorage.getItem("desk-sort") || "name";
let query = "";
let toastTimer = 0;
const launchedAt = new Map();

const params = new URLSearchParams(location.search);
if (params.get("section") && Object.prototype.hasOwnProperty.call(SECTIONS, params.get("section"))) section = params.get("section");
if (["grid", "list"].includes(params.get("view"))) view = params.get("view");
if (["name", "recent", "count"].includes(params.get("sort"))) sort = params.get("sort");
if (!["overview", "favorite", "recent", "office", "dev", "tools", "media", "other"].includes(section)) section = "overview";
if (!["grid", "list"].includes(view)) view = "grid";
if (!["name", "recent", "count"].includes(sort)) sort = "name";
sortBox.value = sort;
viewBox.querySelectorAll("button").forEach((button) => button.classList.toggle("on", button.dataset.view === view));

function h(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), value);
    else if (key === "checked" || key === "disabled" || key === "hidden") node[key] = Boolean(value);
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  const list = Array.isArray(children) ? children : [children];
  for (const child of list) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function markNode() {
  const wrap = document.createElement("div");
  wrap.innerHTML = '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="4" y="4" width="56" height="56" rx="14" fill="#24343E"/><rect x="14" y="14" width="15" height="15" rx="3.5" fill="#E4EEEA"/><rect x="35" y="14" width="15" height="15" rx="3.5" fill="#E4EEEA"/><rect x="14" y="35" width="15" height="15" rx="3.5" fill="#E4EEEA"/><rect x="35" y="35" width="15" height="15" rx="3.5" fill="#C6A15B"/></svg>';
  return wrap.firstChild;
}

function monogram(name) {
  const text = String(name || "").replace(/\s+/g, "");
  if (!text) return "·";
  if (/^[A-Za-z0-9]/.test(text)) return text.slice(0, 2).toUpperCase();
  return text.slice(0, 1);
}

function tone(name) {
  let hash = 0;
  for (const char of String(name || "")) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return TONES[hash % TONES.length];
}

function categories() {
  return state.categories && state.categories.length ? state.categories : DEFAULT_CATEGORIES;
}

function categoryName(id) {
  const found = categories().find((item) => item.id === id);
  return found ? found.name : "其他";
}

function pad(number) {
  return String(number).padStart(2, "0");
}

function formatWhen(iso) {
  if (!iso) return "尚未启动";
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return iso;
  const diff = Date.now() - time;
  if (diff >= 0 && diff < 60000) return "刚刚";
  if (diff >= 0 && diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`;
  const date = new Date(time);
  const hm = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  const today = new Date();
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
  if (time >= start) return `今天 ${hm}`;
  if (time >= start - 86400000) return `昨天 ${hm}`;
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hm}`;
}

function tick() {
  const now = new Date();
  clock.textContent = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}  ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

function setStatus(text) {
  statusText.textContent = text;
}

function toast(text) {
  toastEl.textContent = text;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.hidden = true; }, 2600);
}

function setLive(online) {
  live.classList.toggle("off", !online);
  live.querySelector("span").textContent = online ? "在线" : "离线";
}

async function api(path, options = {}) {
  const headers = { "X-Desk-Token": TOKEN, ...(options.headers || {}) };
  if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(path, { ...options, headers, cache: "no-store" });
  } catch {
    throw new Error("与程控台断开连接");
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!response.ok) throw new Error((data && data.error) || "请求失败");
  return data;
}

async function load(resetScroll = false) {
  state = await api("/api/state");
  render();
  if (resetScroll) scroll.scrollTop = 0;
}

function visibleApps() {
  const needle = query.trim().toLowerCase();
  let apps = state.apps.slice();
  if (section === "favorite") apps = apps.filter((app) => app.favorite);
  else if (section === "recent") apps = apps.filter((app) => app.lastLaunch);
  else if (section !== "overview") apps = apps.filter((app) => app.category === section);
  if (needle) {
    apps = apps.filter((app) => [app.name, app.note, app.target, app.args, categoryName(app.category)].join("\n").toLowerCase().includes(needle));
  }
  const mode = section === "recent" && !needle ? "recent" : sort;
  const byName = (a, b) => a.name.localeCompare(b.name, "zh");
  const byTime = (app) => app.lastLaunch ? Date.parse(app.lastLaunch) || 0 : 0;
  if (mode === "recent") apps.sort((a, b) => byTime(b) - byTime(a) || byName(a, b));
  else if (mode === "count") apps.sort((a, b) => (b.launchCount || 0) - (a.launchCount || 0) || byName(a, b));
  else apps.sort(byName);
  return apps;
}

function render() {
  const apps = visibleApps();
  renderNav();
  renderTitle(apps.length);
  renderStats();
  renderRecent();
  renderMain(apps);
}

function renderNav() {
  const counts = { overview: state.apps.length, favorite: 0, recent: 0, office: 0, dev: 0, tools: 0, media: 0, other: 0 };
  for (const app of state.apps) {
    if (app.favorite) counts.favorite += 1;
    if (app.lastLaunch) counts.recent += 1;
    if (Object.prototype.hasOwnProperty.call(counts, app.category)) counts[app.category] += 1;
  }
  for (const [id, count] of Object.entries(counts)) {
    const node = document.getElementById(`count-${id}`);
    if (node) node.textContent = String(count);
  }
  nav.querySelectorAll(".nav-btn").forEach((button) => button.classList.toggle("active", button.dataset.section === section));
}

function renderTitle(count) {
  if (query.trim()) {
    kicker.textContent = "检索";
    title.textContent = "检索结果";
    subtitle.textContent = `“${query.trim()}” · 当前范围内`;
  } else {
    const copy = SECTIONS[section] || SECTIONS.overview;
    kicker.textContent = "应用调度";
    title.textContent = copy[0];
    subtitle.textContent = copy[1];
  }
  countPill.textContent = String(count);
  if (state.version) version.textContent = state.version;
}

function renderStats() {
  const show = section === "overview" && !query.trim() && state.apps.length > 0;
  stats.hidden = !show;
  stats.replaceChildren();
  if (!show) return;
  const cells = [
    ["已登记", String(state.stats.total || 0), false],
    ["今日启动", String(state.stats.today || 0), false],
    ["常用", String(state.stats.favorites || 0), false],
    ["最近一项", state.stats.last || "—", true],
  ];
  for (const [label, value, textual] of cells) {
    stats.append(h("div", { class: "stat" }, [
      h("div", { class: "label" }, label),
      h("div", { class: textual ? "value text" : "value" }, value),
    ]));
  }
}

function renderRecent() {
  recent.replaceChildren();
  const show = section === "overview" && !query.trim();
  if (!show) { recent.hidden = true; return; }
  const chips = h("div", { class: "chips" });
  const seen = new Set();
  for (const item of state.history || []) {
    if (!item || seen.has(item.appId) || seen.size >= 6) continue;
    const app = state.apps.find((entry) => entry.id === item.appId);
    if (!app) continue;
    seen.add(item.appId);
    const [bg, fg] = tone(app.name);
    chips.append(h("button", { class: "chip", type: "button", "data-action": "launch", "data-id": app.id }, [
      h("span", { class: "mini", style: `background:${bg};color:${fg}` }, monogram(app.name)),
      app.name,
    ]));
  }
  recent.hidden = seen.size === 0;
  if (!seen.size) return;
  recent.append(h("div", { class: "recent-label" }, "最近运行"), chips);
}

function renderMain(apps) {
  main.replaceChildren();
  if (!state.apps.length) {
    main.append(emptyState(
      "台账为空",
      "登记本机程序，或把桌面快捷方式导入进来。之后桌面可以保持干净，需要时从程控台启动。",
      true,
    ));
    return;
  }
  if (!apps.length) {
    main.append(emptyState(query.trim() ? "没有匹配的应用" : "这个分类还没有应用", query.trim() ? "换一个关键词，或清除检索。" : "可以从其他分类改过来，也可以直接登记。", false));
    return;
  }
  if (view === "list") main.append(renderList(apps));
  else main.append(renderGrid(apps));
}

function emptyState(heading, text, actions) {
  const box = h("div", { class: actions ? "empty" : "empty compact" }, [markNode(), h("h2", {}, heading), h("p", {}, text)]);
  if (actions) {
    box.append(h("div", { class: "row" }, [
      h("button", { class: "btn primary", type: "button", "data-action": "import" }, "导入桌面快捷方式"),
      h("button", { class: "btn line", type: "button", "data-action": "add" }, "登记应用"),
    ]));
  }
  return box;
}

function renderGrid(apps) {
  const grid = h("div", { class: "grid" });
  for (const app of apps) grid.append(card(app));
  return grid;
}

function card(app) {
  const [bg, fg] = tone(app.name);
  const node = h("article", { class: "card", "data-id": app.id, "data-launch": "1", tabindex: "0" });
  node.style.setProperty("--cat", CAT[app.category] || CAT.other);
  const ident = h("div", { class: "ident" }, [
    h("div", { class: "name-line" }, [h("strong", {}, app.name), app.favorite ? h("span", { class: "tag" }, "常用") : null]),
    h("div", { class: "meta" }, `${categoryName(app.category)} · ${formatWhen(app.lastLaunch)}`),
    app.note ? h("div", { class: "note" }, app.note) : null,
  ]);
  node.append(
    h("div", { class: "card-top" }, [
      h("div", { class: "mark", style: `background:${bg};color:${fg}` }, monogram(app.name)),
      ident,
      h("button", { class: "more", type: "button", "data-action": "menu", "aria-label": "更多" }, "⋯"),
    ]),
    h("div", { class: "path", title: app.target }, app.target),
    h("div", { class: "card-foot" }, [
      h("span", { class: "count" }, app.launchCount ? `启动 ${app.launchCount} 次` : "尚未启动"),
      app.exists === false ? h("span", { class: "badge-miss" }, "路径缺失") : null,
      h("button", { class: "launch", type: "button", "data-action": "launch" }, "启动"),
    ]),
  );
  return node;
}

function renderList(apps) {
  const list = h("div", { class: "list" });
  list.append(h("div", { class: "cols head" }, ["名称", "分类", "最近启动", "次数", "路径", "操作"].map((text) => h("span", {}, text))));
  for (const app of apps) {
    const [bg, fg] = tone(app.name);
    const row = h("div", { class: "cols rowline", "data-id": app.id, "data-launch": "1", tabindex: "0" });
    row.append(
      h("div", { class: "namecell" }, [
        h("div", { class: "mark", style: `background:${bg};color:${fg}` }, monogram(app.name)),
        h("strong", {}, app.name),
        app.favorite ? h("span", { class: "tag" }, "常用") : null,
      ]),
      categoryName(app.category),
      formatWhen(app.lastLaunch),
      String(app.launchCount || 0),
      h("div", { class: "grow", title: app.target }, app.target),
      h("div", { class: "actions" }, [
        h("button", { class: "launch", type: "button", "data-action": "launch" }, "启动"),
        h("button", { class: "more", type: "button", "data-action": "menu", "aria-label": "更多" }, "⋯"),
      ]),
    );
    list.append(row);
  }
  return list;
}

async function launch(id) {
  const now = Date.now();
  if (now - (launchedAt.get(id) || 0) < 700) return;
  launchedAt.set(id, now);
  const app = state.apps.find((item) => item.id === id);
  if (!app) return;
  setStatus(`正在启动 ${app.name}`);
  try {
    await api(`/api/apps/${id}/launch`, { method: "POST" });
    setStatus(`已启动  ${app.name}`);
    toast(`已启动「${app.name}」`);
    await load(false);
  } catch (error) {
    setStatus(error.message);
    toast(error.message);
  }
}

async function reveal(id) {
  const app = state.apps.find((item) => item.id === id);
  setStatus(app ? `正在打开 ${app.name} 的所在位置` : "正在打开所在位置");
  try {
    await api(`/api/apps/${id}/reveal`, { method: "POST" });
    setStatus("已打开所在位置");
  } catch (error) {
    setStatus(error.message);
    toast(error.message);
  }
}

async function toggleFavorite(app) {
  try {
    await api(`/api/apps/${app.id}`, { method: "PATCH", body: JSON.stringify({ favorite: !app.favorite }) });
    await load(false);
  } catch (error) {
    toast(error.message);
  }
}

async function copyPath(app) {
  try {
    await navigator.clipboard.writeText(app.target);
    toast("已复制路径");
  } catch {
    toast("复制失败");
  }
}

function closeMenu() { menu.hidden = true; menu.replaceChildren(); }

function openMenuAt(x, y, entries) {
  menu.replaceChildren();
  for (const [label, action, danger] of entries) {
    menu.append(h("button", {
      type: "button",
      class: danger ? "danger" : "",
      onclick: () => { closeMenu(); action(); },
    }, label));
  }
  menu.hidden = false;
  menu.style.left = "0px";
  menu.style.top = "0px";
  const rect = menu.getBoundingClientRect();
  menu.style.left = `${Math.max(8, Math.min(x, window.innerWidth - rect.width - 8))}px`;
  menu.style.top = `${Math.max(8, Math.min(y, window.innerHeight - rect.height - 8))}px`;
}

function openAppMenu(id, x, y) {
  const app = state.apps.find((item) => item.id === id);
  if (!app) return;
  openMenuAt(x, y, [
    ["启动", () => launch(id)],
    ["打开所在位置", () => reveal(id)],
    ["编辑", () => openEditor(app)],
    [app.favorite ? "取消常用" : "设为常用", () => toggleFavorite(app)],
    ["复制路径", () => copyPath(app)],
    ["移除", () => confirmRemove(app), true],
  ]);
}

function closeModal() { modalRoot.replaceChildren(); }

function openModal(build, wide) {
  closeMenu();
  modalRoot.replaceChildren();
  const back = h("div", { class: "modal-back" });
  const modal = h("div", { class: wide ? "modal wide" : "modal" });
  back.addEventListener("mousedown", (event) => { if (event.target === back) closeModal(); });
  back.append(modal);
  modalRoot.append(back);
  build(modal);
  const field = modal.querySelector("input, select, textarea, button");
  if (field) field.focus();
}

function field(label, control) {
  return h("label", { class: "field" }, [h("span", {}, label), control]);
}

function openEditor(app) {
  let shortcut = app ? app.sourceShortcut : null;
  openModal((modal) => {
    const name = h("input", { class: "input", value: app ? app.name : "", maxlength: "48", autocomplete: "off" });
    const target = h("input", { class: "input", value: app ? app.target : "", maxlength: "520", spellcheck: "false", autocomplete: "off" });
    const args = h("input", { class: "input", value: app ? app.args : "", maxlength: "300", spellcheck: "false", placeholder: "可选" });
    const workdir = h("input", { class: "input", value: app ? app.workdir : "", maxlength: "520", spellcheck: "false" });
    const note = h("textarea", { class: "input", maxlength: "200" }, app ? app.note : "");
    const category = h("select", { class: "input" });
    for (const item of categories()) {
      const option = h("option", { value: item.id }, item.name);
      if ((app ? app.category : "other") === item.id) option.selected = true;
      category.append(option);
    }
    const favorite = h("input", { type: "checkbox", checked: !!(app && app.favorite) });
    const error = h("div", { class: "form-error" });
    const browseTarget = h("button", { class: "btn line", type: "button" }, "浏览");
    const browseDir = h("button", { class: "btn line", type: "button" }, "浏览");
    browseTarget.addEventListener("click", () => browseInto(target, "file", (result) => {
      if (result.args && !args.value) args.value = result.args;
      if (result.workdir && !workdir.value) workdir.value = result.workdir;
      if (result.name && !name.value) name.value = result.name;
      if (result.note && !note.value) note.value = result.note;
      if (String(result.path || "").toLowerCase().endsWith(".lnk")) shortcut = result.path;
    }));
    browseDir.addEventListener("click", () => browseInto(workdir, "folder"));
    const save = h("button", { class: "btn primary", type: "button", id: "editor-save" }, app ? "保存" : "登记");
    save.addEventListener("click", async () => {
      if (!name.value.trim() || !target.value.trim()) {
        error.textContent = "名称和程序路径都要填写";
        return;
      }
      const fields = {
        name: name.value, target: target.value, args: args.value, workdir: workdir.value,
        note: note.value, category: category.value, favorite: favorite.checked, sourceShortcut: shortcut,
      };
      save.disabled = true;
      try {
        const saved = app
          ? await api(`/api/apps/${app.id}`, { method: "PATCH", body: JSON.stringify(fields) })
          : await api("/api/apps", { method: "POST", body: JSON.stringify(fields) });
        closeModal();
        if (saved.category) {
          section = saved.category;
          localStorage.setItem("desk-section", section);
        }
        toast(app ? "已更新" : "已登记");
        setStatus(app ? `已更新 ${saved.name}` : `已登记 ${saved.name}`);
        await load(true);
      } catch (err) {
        error.textContent = err.message;
        save.disabled = false;
      }
    });
    modal.append(
      h("h2", {}, app ? "编辑应用" : "登记应用"),
      h("p", { class: "hint" }, "填写程序路径。Windows 上可以浏览 exe 或快捷方式，快捷方式会自动带出目标和参数。"),
      h("div", { class: "fields" }, [
        field("名称", name),
        field("程序路径", h("div", { class: "path-row" }, [target, browseTarget])),
        field("启动参数", args),
        field("工作目录", h("div", { class: "path-row" }, [workdir, browseDir])),
        field("分类", category),
        field("备注", note),
        h("label", { class: "checkline" }, [favorite, "加入常用"]),
      ]),
      error,
      h("div", { class: "modal-actions" }, [
        h("button", { class: "btn line", type: "button", onclick: closeModal }, "取消"),
        save,
      ]),
    );
  });
}

async function browseInto(input, kind, onResult) {
  setStatus("正在打开系统对话框");
  try {
    const result = await api("/api/browse", { method: "POST", body: JSON.stringify({ kind }) });
    if (result.unavailable) {
      setStatus(result.message || "请直接粘贴路径");
      toast(result.message || "请直接粘贴路径");
      input.focus();
      return;
    }
    if (result.path) {
      input.value = result.target || result.path;
      if (onResult) onResult(result);
      setStatus("已选择路径");
    } else setStatus("就绪");
  } catch (error) {
    setStatus(error.message);
    toast(error.message);
  }
}

async function openImport() {
  setStatus("正在扫描桌面快捷方式");
  let scan;
  try { scan = await api("/api/scan-desktop", { method: "POST" }); }
  catch (error) { setStatus(error.message); toast(error.message); return; }
  setStatus("就绪");
  openModal((modal) => {
    modal.append(h("h2", {}, "导入桌面快捷方式"));
    modal.append(h("p", { class: "hint" }, scan.message || "勾选要收进台账的快捷方式。导入之后，桌面上的图标就可以收走，程序本身不会被删除。"));
    if (!scan.items || !scan.items.length) {
      modal.append(h("div", { class: "modal-actions" }, [
        h("button", { class: "btn line", type: "button", onclick: closeModal }, "关闭"),
        h("button", { class: "btn primary", type: "button", onclick: () => { closeModal(); openEditor(); } }, "改为手动登记"),
      ]));
      return;
    }
    const picks = [];
    const list = h("div", { class: "pick-list" });
    for (const item of scan.items) {
      const box = h("input", { type: "checkbox", class: "pick-box", checked: true });
      picks.push({ box, item });
      list.append(h("label", { class: "pick" }, [
        box,
        h("div", {}, [h("b", {}, item.name), h("small", {}, item.target)]),
      ]));
    }
    const all = h("input", { type: "checkbox", checked: true });
    all.addEventListener("change", () => picks.forEach((entry) => { entry.box.checked = all.checked; }));
    const category = h("select", { class: "input" });
    for (const item of categories()) {
      const option = h("option", { value: item.id }, item.name);
      if (item.id === "other") option.selected = true;
      category.append(option);
    }
    const recycle = h("input", { type: "checkbox" });
    const go = h("button", { class: "btn primary", type: "button" }, "导入所选");
    go.addEventListener("click", async () => {
      const items = picks.filter((entry) => entry.box.checked).map((entry) => ({ ...entry.item, category: category.value }));
      if (!items.length) { toast("请先勾选要导入的快捷方式"); return; }
      go.disabled = true;
      try {
        const result = await api("/api/import", { method: "POST", body: JSON.stringify({ items, category: category.value, recycle: recycle.checked }) });
        closeModal();
        let message = `已导入 ${result.added} 项`;
        if (result.skipped && result.skipped.length) message += `，跳过 ${result.skipped.length} 项`;
        if (result.recycled && result.recycled.length) message += `，${result.recycled.length} 个快捷方式已移入回收站`;
        if (result.refused && result.refused.length) message += `，${result.refused.length} 个未能移入回收站`;
        toast(message);
        setStatus(message);
        section = category.value;
        localStorage.setItem("desk-section", section);
        await load(true);
      } catch (error) {
        go.disabled = false;
        toast(error.message);
      }
    });
    modal.append(
      h("div", { class: "pick-tools" }, [h("label", { class: "checkline" }, [all, `全选 ${scan.items.length} 项`])]),
      list,
      h("div", { class: "import-bar" }, [
        h("div", { class: "import-options" }, [
          field("导入到分类", category),
          h("label", { class: "checkline" }, [recycle, "导入后把这些快捷方式移入回收站"]),
        ]),
        h("div", { class: "modal-actions" }, [
          h("button", { class: "btn line", type: "button", onclick: closeModal }, "取消"),
          go,
        ]),
      ]),
    );
  }, true);
}

function confirmRemove(app) {
  openModal((modal) => {
    const go = h("button", { class: "btn danger", type: "button" }, "移除");
    go.addEventListener("click", async () => {
      go.disabled = true;
      try {
        await api(`/api/apps/${app.id}`, { method: "DELETE" });
        closeModal();
        toast("已移除");
        setStatus(`已移除 ${app.name}`);
        await load(false);
      } catch (error) {
        go.disabled = false;
        toast(error.message);
      }
    });
    modal.append(
      h("h2", {}, "移除应用"),
      h("p", { class: "hint" }, `只从程控台台账中移除「${app.name}」，不会删除程序本身。`),
      h("div", { class: "modal-actions" }, [
        h("button", { class: "btn line", type: "button", onclick: closeModal }, "取消"),
        go,
      ]),
    );
  });
}

async function exportLedger() {
  try {
    const data = await api("/api/ledger");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    link.download = "程控台台账.json";
    link.click();
    URL.revokeObjectURL(link.href);
    toast("台账已导出");
  } catch (error) { toast(error.message); }
}

function importLedger() {
  const input = h("input", { type: "file", accept: "application/json,.json" });
  input.addEventListener("change", async () => {
    const file = input.files && input.files[0];
    if (!file) return;
    try {
      const payload = JSON.parse(await file.text());
      const result = await api("/api/ledger/import", { method: "POST", body: JSON.stringify(payload) });
      toast(`已合并台账：新增 ${result.added}，更新 ${result.updated}`);
      await load(false);
    } catch (error) { toast(error.message || "台账文件无法解析"); }
  });
  input.click();
}

function handleAction(name, id, node) {
  if (name === "add") return openEditor();
  if (name === "import") return openImport();
  if (name === "launch" && id) return launch(id);
  if (name === "menu" && id) {
    const rect = node.getBoundingClientRect();
    return openAppMenu(id, rect.right - 180, rect.bottom + 4);
  }
  return undefined;
}

nav.addEventListener("click", (event) => {
  const button = event.target.closest(".nav-btn");
  if (!button) return;
  section = button.dataset.section;
  localStorage.setItem("desk-section", section);
  render();
  scroll.scrollTop = 0;
});
q.addEventListener("input", () => { query = q.value; render(); });
sortBox.addEventListener("change", () => {
  sort = sortBox.value;
  localStorage.setItem("desk-sort", sort);
  render();
});
viewBox.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  view = button.dataset.view;
  localStorage.setItem("desk-view", view);
  viewBox.querySelectorAll("button").forEach((item) => item.classList.toggle("on", item === button));
  render();
});
document.getElementById("btn-add").addEventListener("click", () => openEditor());
document.getElementById("btn-import").addEventListener("click", () => openImport());
document.getElementById("btn-ledger").addEventListener("click", (event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  openMenuAt(rect.left, rect.bottom + 6, [["导出台账", exportLedger], ["导入台账", importLedger]]);
});
document.getElementById("btn-quit").addEventListener("click", async () => {
  setStatus("正在退出");
  try { await api("/api/shutdown", { method: "POST" }); } catch { /* 服务即将停止 */ }
  window.close();
});
scroll.addEventListener("click", (event) => {
  const action = event.target.closest("[data-action]");
  if (action) {
    const id = action.dataset.id || (action.closest("[data-id]") && action.closest("[data-id]").dataset.id);
    handleAction(action.dataset.action, id, action);
    return;
  }
  const cardNode = event.target.closest("[data-launch]");
  if (cardNode) launch(cardNode.dataset.id);
});
scroll.addEventListener("contextmenu", (event) => {
  const cardNode = event.target.closest("[data-id]");
  if (!cardNode) return;
  event.preventDefault();
  openAppMenu(cardNode.dataset.id, event.clientX, event.clientY);
});
scroll.addEventListener("keydown", (event) => {
  if (event.key !== "Enter") return;
  const cardNode = event.target.closest("[data-launch]");
  if (cardNode && event.target === cardNode) launch(cardNode.dataset.id);
});
scroll.addEventListener("scroll", closeMenu);
document.addEventListener("mousedown", (event) => {
  if (!menu.hidden && !menu.contains(event.target)) closeMenu();
});
document.addEventListener("keydown", (event) => {
  const key = event.key.toLowerCase();
  if ((event.ctrlKey || event.metaKey) && key === "k") {
    event.preventDefault();
    closeModal();
    q.focus();
    q.select();
    return;
  }
  if ((event.ctrlKey || event.metaKey) && key === "n" && !modalRoot.childElementCount) {
    event.preventDefault();
    openEditor();
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
    const save = document.getElementById("editor-save");
    if (save) { event.preventDefault(); save.click(); }
    return;
  }
  if (event.key === "Escape") {
    if (!menu.hidden) { closeMenu(); return; }
    if (modalRoot.childElementCount) { closeModal(); return; }
    if (document.activeElement === q && query) { q.value = ""; query = ""; render(); }
  }
});
window.addEventListener("pagehide", () => {
  navigator.sendBeacon(`/api/bye?t=${encodeURIComponent(TOKEN)}`);
});

tick();
setInterval(tick, 1000);
setInterval(() => { api("/api/ping", { method: "POST" }).then(() => setLive(true)).catch(() => setLive(false)); }, 2000);
load().then(() => {
  if (location.hash === "#new") openEditor();
}).catch((error) => {
  setStatus(error.message);
  main.replaceChildren(emptyState("没能读到台账", error.message, false));
});
