/* global fetch API, marked */
const $ = (id) => document.getElementById(id);

const SETTINGS_KEY = "db-agent-ui-settings";

const state = {
  activeId: null,
  openMenuId: null,
};

const defaultSettings = () => ({
  theme: "system",
  fontSize: 14,
});

function loadSettings() {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (!raw) {
      return defaultSettings();
    }
    return { ...defaultSettings(), ...JSON.parse(raw) };
  } catch {
    return defaultSettings();
  }
}

function saveSettings(settings) {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
}

function applySettings(settings) {
  document.documentElement.dataset.theme = settings.theme;
  document.documentElement.style.setProperty("--font-size-base", `${settings.fontSize}px`);
  const themeSelect = $("themeSelect");
  const fontSizeSelect = $("fontSizeSelect");
  if (themeSelect) {
    themeSelect.value = settings.theme;
  }
  if (fontSizeSelect) {
    fontSizeSelect.value = String(settings.fontSize);
  }
}

function showToast(text) {
  let el = document.querySelector(".toast");
  if (!el) {
    el = document.createElement("div");
    el.className = "toast";
    document.body.appendChild(el);
  }
  el.textContent = text;
  el.classList.add("show");
  window.clearTimeout(showToast._t);
  showToast._t = window.setTimeout(() => el.classList.remove("show"), 4500);
}

async function api(path, options = {}) {
  const { headers: hdr, ...rest } = options;
  const headers = new Headers(hdr || {});
  const method = (rest.method || "GET").toUpperCase();
  const body = rest.body;
  if (body != null && method !== "GET" && method !== "HEAD") {
    if (!headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
  }
  const res = await fetch(path, {
    ...rest,
    headers,
  });
  if (!res.ok) {
    const errBody = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText}${errBody ? `\n${errBody}` : ""}`);
  }
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) {
    return await res.json();
  }
  return null;
}

function escapeHtml(s) {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderMarkdown(text) {
  if (!window.marked) {
    return escapeHtml(text);
  }
  return window.marked.parse(text, { breaks: true, mangle: false, headerIds: false });
}

function formatSessionTime(iso) {
  if (!iso) {
    return "";
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return iso.replace("T", " ").replaceAll("Z", "");
  }
  const now = new Date();
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) {
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}

function setComposerState(hasSession) {
  const hasText = $("input").value.trim().length > 0;
  $("send").disabled = !hasSession || !hasText;
  $("input").disabled = false;
  $("input").placeholder = hasSession
    ? "询问数据库…（Enter 发送，Shift+Enter 换行）"
    : "请先选择左侧会话，或点击「新对话」";
}

function closeSessionMenu() {
  const menu = $("sessionMenu");
  menu.hidden = true;
  menu.innerHTML = "";
  state.openMenuId = null;
  document.querySelectorAll(".session-row.menu-open").forEach((el) => {
    el.classList.remove("menu-open");
  });
}

function openSessionMenu(sessionId, anchorEl) {
  closeSessionMenu();
  state.openMenuId = sessionId;

  const row = anchorEl.closest(".session-row");
  if (row) {
    row.classList.add("menu-open");
  }

  const menu = $("sessionMenu");
  menu.innerHTML = `
    <button type="button" class="dropdown-item" data-action="rename">重命名</button>
    <div class="dropdown-divider" role="separator"></div>
    <button type="button" class="dropdown-item danger" data-action="delete">删除会话</button>
  `;

  const rect = anchorEl.getBoundingClientRect();
  menu.style.top = `${rect.bottom + 4}px`;
  menu.style.left = `${Math.max(8, rect.right - 168)}px`;
  menu.hidden = false;

  menu.querySelector('[data-action="rename"]').addEventListener("click", async (ev) => {
    ev.stopPropagation();
    closeSessionMenu();
    await renameSession(sessionId);
  });

  menu.querySelector('[data-action="delete"]').addEventListener("click", async (ev) => {
    ev.stopPropagation();
    closeSessionMenu();
    await deleteSession(sessionId);
  });
}

async function refreshSessions(selectId) {
  const sessions = await api("/api/conversations");
  const list = $("sessionList");
  list.innerHTML = "";

  if (sessions.length === 0) {
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.style.padding = "24px 12px";
    empty.textContent = "暂无会话，点击「新对话」开始";
    list.appendChild(empty);
    return;
  }

  for (const s of sessions) {
    const row = document.createElement("div");
    row.className = "session-row" + (s.id === selectId ? " active" : "");
    row.dataset.id = s.id;

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "session-item";
    btn.innerHTML = `<div class="session-title"></div>`;
    btn.querySelector(".session-title").textContent = s.title || s.id;
    btn.title = formatSessionTime(s.updatedAt || s.createdAt);
    btn.addEventListener("click", () => selectSession(s.id));

    const menuBtn = document.createElement("button");
    menuBtn.type = "button";
    menuBtn.className = "session-menu-btn";
    menuBtn.setAttribute("aria-label", "会话菜单");
    menuBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><circle cx="3" cy="7" r="1.2" fill="currentColor"/><circle cx="7" cy="7" r="1.2" fill="currentColor"/><circle cx="11" cy="7" r="1.2" fill="currentColor"/></svg>`;
    menuBtn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      if (state.openMenuId === s.id && !$("sessionMenu").hidden) {
        closeSessionMenu();
      } else {
        openSessionMenu(s.id, menuBtn);
      }
    });

    row.appendChild(btn);
    row.appendChild(menuBtn);
    list.appendChild(row);
  }
}

async function selectSession(id) {
  closeSessionMenu();
  state.activeId = id;
  const row = document.querySelector(`.session-row[data-id="${id}"]`);
  const titleEl = row ? row.querySelector(".session-title") : null;
  const title = titleEl ? titleEl.textContent : id;
  $("sessionTitle").textContent = title;
  setComposerState(true);

  document.querySelectorAll(".session-row").forEach((el) => {
    el.classList.toggle("active", el.dataset.id === id);
  });

  const msgs = await api(`/api/conversations/${encodeURIComponent(id)}/messages`);
  const box = $("messages");
  box.innerHTML = "";

  if (msgs.length === 0) {
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "向数据库提问，例如：最近 5 条 opschange 记录";
    box.appendChild(empty);
    return;
  }

  for (const m of msgs) {
    const div = document.createElement("div");
    const role = (m.role || "unknown").toLowerCase();
    div.className = `msg ${role}`;
    const body = role === "assistant" ? renderMarkdown(m.text || "") : escapeHtml(m.text || "");
    const showRole = role === "tool" || role === "system";
    div.innerHTML = showRole
      ? `<div class="role">${escapeHtml(role)}</div><div class="body">${body}</div>`
      : `<div class="body">${body}</div>`;
    box.appendChild(div);
  }

  box.scrollTop = box.scrollHeight;
}

async function createSession() {
  const created = await api("/api/conversations", { method: "POST" });
  await refreshSessions(created.id);
  await selectSession(created.id);
  $("input").focus();
}

async function renameSession(id) {
  const row = document.querySelector(`.session-row[data-id="${id}"]`);
  const current = row ? row.querySelector(".session-title").textContent : "";
  const next = window.prompt("重命名会话", current);
  if (next == null) {
    return;
  }
  const title = next.trim();
  if (!title) {
    showToast("标题不能为空");
    return;
  }
  await api(`/api/conversations/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
  await refreshSessions(state.activeId === id ? id : state.activeId);
  if (state.activeId === id) {
    $("sessionTitle").textContent = title;
  }
}

async function deleteSession(id) {
  if (!window.confirm("确定删除该会话？消息记录将一并删除。")) {
    return;
  }
  await api(`/api/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
  if (state.activeId === id) {
    state.activeId = null;
    $("messages").innerHTML = "";
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "选择或创建一个会话";
    $("messages").appendChild(empty);
    $("sessionTitle").textContent = "选择或创建一个会话";
    setComposerState(false);
  }
  await refreshSessions(state.activeId);
}

async function sendMessage() {
  const text = $("input").value.trim();
  if (!text) {
    return;
  }
  if (!state.activeId) {
    showToast("请先选择或创建一个会话");
    return;
  }

  setComposerState(true);
  $("send").disabled = true;

  try {
    await api(`/api/conversations/${encodeURIComponent(state.activeId)}/chat`, {
      method: "POST",
      body: JSON.stringify({ message: text }),
    });
    $("input").value = "";
    autoResizeInput();
    await refreshSessions(state.activeId);
    await selectSession(state.activeId);
  } catch (e) {
    showToast(String(e && e.message ? e.message : e));
  } finally {
    setComposerState(!!state.activeId);
    $("input").focus();
  }
}

function autoResizeInput() {
  const ta = $("input");
  ta.style.height = "auto";
  ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`;
}

function openSettings() {
  $("settingsBackdrop").hidden = false;
  $("settingsPanel").hidden = false;
}

function closeSettings() {
  $("settingsBackdrop").hidden = true;
  $("settingsPanel").hidden = true;
}

function wireSettings() {
  const settings = loadSettings();
  applySettings(settings);

  $("openSettings").addEventListener("click", openSettings);
  $("closeSettings").addEventListener("click", closeSettings);
  $("settingsBackdrop").addEventListener("click", closeSettings);

  $("themeSelect").addEventListener("change", (ev) => {
    const s = loadSettings();
    s.theme = ev.target.value;
    saveSettings(s);
    applySettings(s);
  });

  $("fontSizeSelect").addEventListener("change", (ev) => {
    const s = loadSettings();
    s.fontSize = Number.parseInt(ev.target.value, 10) || 14;
    saveSettings(s);
    applySettings(s);
  });

  const mq = window.matchMedia("(prefers-color-scheme: dark)");
  mq.addEventListener("change", () => {
    if (loadSettings().theme === "system") {
      applySettings(loadSettings());
    }
  });
}

function wire() {
  $("newChat").addEventListener("click", async () => {
    try {
      await createSession();
    } catch (e) {
      showToast(String(e && e.message ? e.message : e));
    }
  });

  $("send").addEventListener("click", sendMessage);

  const input = $("input");
  input.addEventListener("input", () => {
    autoResizeInput();
    setComposerState(!!state.activeId);
  });

  input.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && !ev.shiftKey) {
      ev.preventDefault();
      sendMessage();
    }
  });

  document.addEventListener("click", (ev) => {
    const menu = $("sessionMenu");
    if (!menu.hidden && !ev.target.closest(".session-menu-btn") && !ev.target.closest("#sessionMenu")) {
      closeSessionMenu();
    }
  });

  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") {
      closeSessionMenu();
      if (!$("settingsPanel").hidden) {
        closeSettings();
      }
    }
  });

  wireSettings();
}

window.addEventListener("DOMContentLoaded", async () => {
  document.documentElement.dataset.theme = loadSettings().theme;
  applySettings(loadSettings());
  wire();
  setComposerState(false);
  autoResizeInput();

  const box = $("messages");
  const empty = document.createElement("div");
  empty.className = "messages-empty";
  empty.textContent = "选择或创建一个会话";
  box.appendChild(empty);

  try {
    await refreshSessions(null);
  } catch (e) {
    showToast(String(e && e.message ? e.message : e));
  }
});
