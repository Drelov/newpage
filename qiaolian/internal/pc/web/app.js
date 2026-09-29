const token = document.querySelector('meta[name="csrf"]').content;
const $ = (id) => document.getElementById(id);

let session = null;
let dir = "";
let drag = null;

function headers(json) {
  const h = { "X-Qiaolian-Token": token };
  if (json) h["Content-Type"] = "application/json";
  return h;
}

async function api(url, opt) {
  const res = await fetch(url, opt);
  const text = await res.text();
  let body = {};
  if (text) {
    try { body = JSON.parse(text); } catch { body = { error: text }; }
  }
  if (!res.ok) throw new Error(body.error || "请求失败");
  return body;
}

function osLabel(os) {
  if (os === "android") return "安卓";
  if (os === "harmony") return "鸿蒙";
  if (os === "fake") return "模拟";
  return os || "手机";
}

function fmtSize(n) {
  if (!n) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v.toFixed(1)) + " " + u[i];
}

async function refreshState() {
  try {
    const state = await api("/api/state", { headers: headers(false) });
    $("pc-name").textContent = state.pcName || "这台电脑";
    renderDevices(state.devices || []);
    if (state.session) {
      const changed = !session || session.id !== state.session.id;
      session = state.session;
      showSession(changed);
      if (changed) loadFiles();
      if (state.session.screenError) $("screen-error").textContent = state.session.screenError;
    } else if (session) {
      session = null;
      showWelcome();
    }
  } catch (err) {
    $("connect-error").textContent = err.message;
  }
}

function renderDevices(list) {
  const ul = $("devices");
  ul.innerHTML = "";
  $("devices-empty").hidden = list.length > 0;
  for (const d of list) {
    const li = document.createElement("li");
    li.innerHTML = `<strong>${escapeHtml(d.name || "手机")}<span class="badge">${osLabel(d.os)}</span></strong><span>${escapeHtml(d.ip)}:${d.port}</span>`;
    li.onclick = () => {
      $("ip").value = d.ip;
      $("port").value = d.port;
      $("code").focus();
    };
    ul.appendChild(li);
  }
}

function showSession(resetTabs) {
  $("welcome").hidden = true;
  $("tabs").hidden = false;
  $("disconnect").hidden = false;
  if (resetTabs) {
    $("panel-files").hidden = false;
    $("panel-screen").hidden = true;
    $("panel-clip").hidden = true;
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === "files"));
  }
  $("session-os").textContent = osLabel(session.os) + (session.osVersion ? " " + session.osVersion : "");
  $("session-name").textContent = session.name || "手机";
  const hint = $("screen-hint");
  if (session.os === "harmony") {
    hint.textContent = "鸿蒙 7 普通应用不能截取其他应用，也不能向系统注入触控。这里投的是桥联自己的界面，远程操作也作用在桥联里。传文件不受这个限制。";
  } else if (session.os === "android") {
    hint.textContent = "请先在手机上点「允许投屏」，并打开「远程控制」。点画面是点击，拖动是滑动。";
  } else {
    hint.textContent = "点画面是点击，拖动是滑动。";
  }
}

function showWelcome() {
  $("welcome").hidden = false;
  $("tabs").hidden = true;
  $("disconnect").hidden = true;
  $("session-os").textContent = "未连接";
  $("session-name").textContent = "选择一台手机";
  $("panel-files").hidden = true;
  $("panel-screen").hidden = true;
  $("panel-clip").hidden = true;
  $("mirror").removeAttribute("src");
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

$("connect-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  $("connect-error").textContent = "";
  $("connect-btn").disabled = true;
  try {
    const body = await api("/api/connect", {
      method: "POST",
      headers: headers(true),
      body: JSON.stringify({
        ip: $("ip").value.trim(),
        port: Number($("port").value || 37622),
        code: $("code").value.trim(),
      }),
    });
    session = body.session;
    dir = "";
    showSession(true);
    loadFiles();
  } catch (err) {
    $("connect-error").textContent = err.message;
  } finally {
    $("connect-btn").disabled = false;
  }
});

$("disconnect").onclick = async () => {
  await api("/api/disconnect", { method: "POST", headers: headers(true) });
  session = null;
  showWelcome();
};

document.querySelectorAll(".tabs button").forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const tab = btn.dataset.tab;
    $("panel-files").hidden = tab !== "files";
    $("panel-screen").hidden = tab !== "screen";
    $("panel-clip").hidden = tab !== "clip";
    if (tab === "screen") $("stage").focus();
  };
});

async function loadFiles() {
  if (!session) return;
  $("files-error").textContent = "";
  $("crumb").textContent = "/" + dir;
  try {
    const res = await api("/api/files?path=" + encodeURIComponent(dir), { headers: headers(false) });
    const box = $("files");
    box.innerHTML = "";
    if (!res.entries || res.entries.length === 0) {
      box.innerHTML = "<p class='muted'>这个文件夹是空的</p>";
      return;
    }
    for (const ent of res.entries) {
      const row = document.createElement("div");
      row.className = "file";
      const child = dir ? dir + "/" + ent.name : ent.name;
      row.innerHTML = `<button class="link" type="button">${ent.dir ? "📁 " : "📄 "}${escapeHtml(ent.name)}</button><span class="meta">${ent.dir ? "文件夹" : fmtSize(ent.size)}</span>`;
      const actions = document.createElement("span");
      if (!ent.dir) {
        const a = document.createElement("a");
        a.textContent = "下载";
        a.href = "/api/download?path=" + encodeURIComponent(child) + "&t=" + encodeURIComponent(token);
        actions.appendChild(a);
      }
      const del = document.createElement("button");
      del.type = "button";
      del.textContent = "删除";
      del.onclick = async () => {
        if (!confirm("删除 " + ent.name + " ？")) return;
        try {
          await api("/api/delete", { method: "POST", headers: headers(true), body: JSON.stringify({ path: child }) });
          loadFiles();
        } catch (err) {
          $("files-error").textContent = err.message;
        }
      };
      actions.appendChild(del);
      row.appendChild(actions);
      row.querySelector(".link").onclick = () => {
        if (ent.dir) {
          dir = child;
          loadFiles();
        }
      };
      box.appendChild(row);
    }
  } catch (err) {
    $("files-error").textContent = err.message;
  }
}

$("refresh").onclick = loadFiles;
$("up").onclick = () => {
  if (!dir) return;
  const i = dir.lastIndexOf("/");
  dir = i < 0 ? "" : dir.slice(0, i);
  loadFiles();
};
$("mkdir").onclick = async () => {
  const name = prompt("文件夹名称");
  if (!name) return;
  const child = dir ? dir + "/" + name : name;
  try {
    await api("/api/mkdir", { method: "POST", headers: headers(true), body: JSON.stringify({ path: child }) });
    loadFiles();
  } catch (err) {
    $("files-error").textContent = err.message;
  }
};
$("upload").onchange = async () => {
  const file = $("upload").files[0];
  if (!file) return;
  const data = new FormData();
  data.append("file", file);
  $("files-error").textContent = "正在上传…";
  try {
    const res = await fetch("/api/upload?path=" + encodeURIComponent(dir), {
      method: "POST",
      headers: { "X-Qiaolian-Token": token },
      body: data,
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || "上传失败");
    $("files-error").textContent = "";
    $("upload").value = "";
    loadFiles();
  } catch (err) {
    $("files-error").textContent = err.message;
  }
};

$("screen-start").onclick = async () => {
  $("screen-error").textContent = "";
  try {
    await api("/api/screen/start", {
      method: "POST",
      headers: headers(true),
      body: JSON.stringify({ maxWidth: 720, quality: 70, fps: 8 }),
    });
    $("mirror").src = "/api/screen/mjpeg?t=" + encodeURIComponent(token) + "&ts=" + Date.now();
  } catch (err) {
    $("screen-error").textContent = err.message;
  }
};
$("screen-stop").onclick = async () => {
  try {
    await api("/api/screen/stop", { method: "POST", headers: headers(true), body: "{}" });
  } catch (err) {
    $("screen-error").textContent = err.message;
  }
  $("mirror").removeAttribute("src");
};

document.querySelectorAll("[data-key]").forEach((btn) => {
  btn.onclick = () => sendInput({ action: "key", key: btn.dataset.key });
});

$("text-form").onsubmit = async (ev) => {
  ev.preventDefault();
  const text = $("remote-text").value;
  if (!text) return;
  await sendInput({ action: "text", text });
  $("remote-text").value = "";
};

async function sendInput(body) {
  $("screen-error").textContent = "";
  try {
    await api("/api/input", { method: "POST", headers: headers(true), body: JSON.stringify(body) });
  } catch (err) {
    $("screen-error").textContent = err.message;
  }
}

function contentRect(img) {
  const r = img.getBoundingClientRect();
  const nw = img.naturalWidth || 1;
  const nh = img.naturalHeight || 1;
  const scale = Math.min(r.width / nw, r.height / nh);
  const w = nw * scale;
  const h = nh * scale;
  return { left: r.left + (r.width - w) / 2, top: r.top + (r.height - h) / 2, width: w, height: h };
}

const stage = $("stage");
stage.addEventListener("dragstart", (ev) => ev.preventDefault());
stage.addEventListener("pointerdown", (ev) => {
  ev.preventDefault();
  const box = contentRect($("mirror"));
  drag = { x: ev.clientX, y: ev.clientY, box };
  stage.setPointerCapture(ev.pointerId);
});
stage.addEventListener("pointerup", (ev) => {
  if (!drag) return;
  const box = drag.box;
  const x = (drag.x - box.left) / box.width;
  const y = (drag.y - box.top) / box.height;
  const x2 = (ev.clientX - box.left) / box.width;
  const y2 = (ev.clientY - box.top) / box.height;
  const moved = Math.hypot(ev.clientX - drag.x, ev.clientY - drag.y);
  drag = null;
  if (x < 0 || y < 0 || x > 1 || y > 1) return;
  if (moved < 8) sendInput({ action: "tap", x, y });
  else sendInput({ action: "swipe", x, y, x2: clamp(x2), y2: clamp(y2), duration: 280 });
});
stage.addEventListener("wheel", (ev) => {
  ev.preventDefault();
  const box = contentRect($("mirror"));
  const x = clamp((ev.clientX - box.left) / box.width);
  const y = clamp((ev.clientY - box.top) / box.height);
  sendInput({ action: "scroll", x, y, dy: ev.deltaY > 0 ? 0.18 : -0.18 });
}, { passive: false });
stage.addEventListener("keydown", (ev) => {
  if (ev.target !== stage) return;
  if (ev.key === "Escape") sendInput({ action: "key", key: "back" });
  if (ev.key === "Backspace") sendInput({ action: "key", key: "del" });
  if (ev.key === "Enter") sendInput({ action: "key", key: "enter" });
});

function clamp(v) { return Math.max(0, Math.min(1, v)); }

$("clip-get").onclick = async () => {
  $("clip-error").textContent = "";
  try {
    const body = await api("/api/clipboard", { headers: headers(false) });
    $("clip").value = body.text || "";
  } catch (err) {
    $("clip-error").textContent = err.message;
  }
};
$("clip-set").onclick = async () => {
  $("clip-error").textContent = "";
  try {
    await api("/api/clipboard", {
      method: "POST",
      headers: headers(true),
      body: JSON.stringify({ text: $("clip").value }),
    });
  } catch (err) {
    $("clip-error").textContent = err.message;
  }
};

refreshState();
setInterval(refreshState, 2000);
