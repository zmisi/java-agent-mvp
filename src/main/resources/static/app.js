/* global fetch API, marked */
/** Shared by app.js, releases.js, provisioning.js (must be `function`, not script-scoped const). */
function $(id) {
  return document.getElementById(id);
}

const SETTINGS_KEY = "db-agent-ui-settings";

const state = {
  activeId: null,
  openMenuId: null,
  inFlight: false,
  abortController: null,
  loadingTimer: null,
  loadingStartedAt: null,
};

const LOADING_HINTS = [
  "Understanding your question…",
  "Calling the model and database tools…",
  "Query may run multiple SQL rounds, please wait…",
  "Still working — click Stop to cancel",
];

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
  const { headers: hdr, signal, ...rest } = options;
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
    signal,
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
  if (state.inFlight) {
    return;
  }
  const hasText = $("input").value.trim().length > 0;
  $("send").hidden = false;
  $("stop").hidden = true;
  $("send").disabled = !hasSession || !hasText;
  $("input").disabled = false;
  document.querySelector(".composer-inner")?.classList.remove("is-busy");
  $("input").placeholder = hasSession
    ? "Ask about the database… (⌘+Enter to send)"
    : "Select a chat on the left, or click New Chat";
}

function setComposerBusy(busy) {
  const inner = document.querySelector(".composer-inner");
  $("send").hidden = busy;
  $("stop").hidden = !busy;
  $("input").disabled = busy;
  if (busy) {
    inner?.classList.add("is-busy");
    $("input").placeholder = "Query in progress…";
  } else {
    inner?.classList.remove("is-busy");
    setComposerState(!!state.activeId);
  }
}

function clearMessagesEmpty() {
  $("messages").querySelectorAll(".messages-empty").forEach((el) => el.remove());
}

function appendUserBubble(text) {
  clearMessagesEmpty();
  const div = document.createElement("div");
  div.className = "msg user";
  div.innerHTML = `<div class="body">${escapeHtml(text)}</div>`;
  $("messages").appendChild(div);
  scrollMessagesToBottom();
}

function scrollMessagesToBottom() {
  const box = $("messages");
  box.scrollTop = box.scrollHeight;
}

function formatElapsed(ms) {
  const sec = Math.floor(ms / 1000);
  if (sec < 60) {
    return `${sec}s`;
  }
  const min = Math.floor(sec / 60);
  const rem = sec % 60;
  return `${min}m ${rem}s`;
}

function showLoadingBubble() {
  removeLoadingBubble();
  clearMessagesEmpty();

  const div = document.createElement("div");
  div.className = "msg assistant msg-loading";
  div.id = "chatLoading";
  div.innerHTML = `
    <div class="loading-card">
      <div class="loading-row">
        <span class="loading-dots" aria-hidden="true"><span></span><span></span><span></span></span>
        <span class="loading-text" id="loadingStatus">${LOADING_HINTS[0]}</span>
      </div>
      <span class="loading-elapsed" id="loadingElapsed">Elapsed 0s</span>
      <span class="loading-hint">The model may query the database multiple times</span>
    </div>
  `;
  $("messages").appendChild(div);
  scrollMessagesToBottom();

  state.loadingStartedAt = Date.now();
  let hintIndex = 0;
  const statusEl = () => document.getElementById("loadingStatus");
  const elapsedEl = () => document.getElementById("loadingElapsed");

  state.loadingTimer = window.setInterval(() => {
    const elapsed = Date.now() - state.loadingStartedAt;
    const el = elapsedEl();
    if (el) {
      el.textContent = `Elapsed ${formatElapsed(elapsed)}`;
    }
    hintIndex = Math.min(LOADING_HINTS.length - 1, Math.floor(elapsed / 4000));
    const st = statusEl();
    if (st) {
      st.textContent = LOADING_HINTS[hintIndex];
    }
    scrollMessagesToBottom();
  }, 500);

  return div;
}

function removeLoadingBubble() {
  if (state.loadingTimer != null) {
    window.clearInterval(state.loadingTimer);
    state.loadingTimer = null;
  }
  state.loadingStartedAt = null;
  document.getElementById("chatLoading")?.remove();
}

function abortInFlight() {
  if (state.abortController) {
    state.abortController.abort();
  }
}

function isAbortError(err) {
  return err && (err.name === "AbortError" || err.message === "The user aborted a request.");
}

function closeSessionMenu() {
  const menu = $("sessionMenu");
  menu.hidden = true;
  menu.innerHTML = "";
  state.openMenuId = null;
  document.querySelectorAll(".sidebar-list-row.menu-open").forEach((el) => {
    el.classList.remove("menu-open");
  });
}

function openSessionMenu(sessionId, anchorEl) {
  closeSessionMenu();
  state.openMenuId = sessionId;

  const row = anchorEl.closest(".sidebar-list-row");
  if (row) {
    row.classList.add("menu-open");
  }

  const menu = $("sessionMenu");
  menu.innerHTML = `
    <button type="button" class="dropdown-item" data-action="rename">Rename</button>
    <div class="dropdown-divider" role="separator"></div>
    <button type="button" class="dropdown-item danger" data-action="delete">Delete chat</button>
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
    empty.className = "sidebar-list-empty";
    empty.textContent = "No chats yet";
    list.appendChild(empty);
    return;
  }

  const chatViewActive = typeof releaseState === "undefined" || releaseState.view === "chat";
  const activeId = selectId ?? state.activeId;
  for (const s of sessions) {
    const row = document.createElement("div");
    const isActive = chatViewActive && s.id === activeId;
    row.className = "sidebar-list-row" + (isActive ? " active" : "");
    row.dataset.id = s.id;

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "sidebar-list-item";
    btn.innerHTML = `<span class="sidebar-list-dot" aria-hidden="true"></span><span class="sidebar-list-text"></span>`;
    btn.querySelector(".sidebar-list-text").textContent = s.title || s.id;
    btn.title = formatSessionTime(s.updatedAt || s.createdAt);
    btn.addEventListener("click", () => selectSession(s.id));

    const menuBtn = document.createElement("button");
    menuBtn.type = "button";
    menuBtn.className = "sidebar-list-menu-btn";
    menuBtn.setAttribute("aria-label", "Chat menu");
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
  if (typeof showView === "function") {
    showView("chat");
  }
  if (state.inFlight && state.activeId !== id) {
    abortInFlight();
  }
  closeSessionMenu();
  state.activeId = id;
  const row = document.querySelector(`.sidebar-list-row[data-id="${id}"]`);
  const titleEl = row ? row.querySelector(".sidebar-list-text") : null;
  const title = titleEl ? titleEl.textContent : id;
  $("sessionTitle").textContent = title;
  setComposerState(true);

  if (typeof updateSidebarActiveStates === "function") {
    updateSidebarActiveStates();
  } else {
    document.querySelectorAll(".sidebar-list-row[data-id]").forEach((el) => {
      el.classList.toggle("active", el.dataset.id === id);
    });
  }

  const msgs = await api(`/api/conversations/${encodeURIComponent(id)}/messages`);
  const box = $("messages");
  box.innerHTML = "";

  if (msgs.length === 0) {
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "Ask about the database, e.g. How many employees per department?";
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
  const row = document.querySelector(`.sidebar-list-row[data-id="${id}"]`);
  const current = row ? row.querySelector(".sidebar-list-text").textContent : "";
  const next = window.prompt("Rename chat", current);
  if (next == null) {
    return;
  }
  const title = next.trim();
  if (!title) {
    showToast("Title cannot be empty");
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
  if (!window.confirm("Delete this chat and all messages?")) {
    return;
  }
  await api(`/api/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
  if (state.activeId === id) {
    state.activeId = null;
    $("messages").innerHTML = "";
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "Select or create a chat";
    $("messages").appendChild(empty);
    $("sessionTitle").textContent = "Select or create a chat";
    setComposerState(false);
  }
  await refreshSessions(state.activeId);
}

function stopMessage() {
  if (!state.inFlight) {
    return;
  }
  abortInFlight();
}

async function sendMessage() {
  if (state.inFlight) {
    return;
  }
  const text = $("input").value.trim();
  if (!text) {
    return;
  }
  if (!state.activeId) {
    showToast("Select or create a chat first");
    return;
  }

  const conversationId = state.activeId;
  appendUserBubble(text);
  $("input").value = "";
  autoResizeInput();

  state.inFlight = true;
  state.abortController = new AbortController();
  setComposerBusy(true);
  showLoadingBubble();

  try {
    await api(`/api/conversations/${encodeURIComponent(conversationId)}/chat`, {
      method: "POST",
      body: JSON.stringify({ message: text }),
      signal: state.abortController.signal,
    });
    removeLoadingBubble();
    await refreshSessions(conversationId);
    await selectSession(conversationId);
  } catch (e) {
    removeLoadingBubble();
    if (isAbortError(e)) {
      showToast("Query stopped");
    } else {
      showToast(String(e && e.message ? e.message : e));
      await selectSession(conversationId);
    }
  } finally {
    state.inFlight = false;
    state.abortController = null;
    setComposerBusy(false);
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
    if (typeof showView === "function") {
      showView("chat");
    }
    try {
      await createSession();
    } catch (e) {
      showToast(String(e && e.message ? e.message : e));
    }
  });

  $("send").addEventListener("click", sendMessage);
  $("stop").addEventListener("click", stopMessage);

  const input = $("input");
  input.addEventListener("input", () => {
    autoResizeInput();
    setComposerState(!!state.activeId);
  });

  input.addEventListener("keydown", (ev) => {
    const sendShortcut = ev.key === "Enter" && (ev.metaKey || ev.ctrlKey);
    if (!sendShortcut) {
      return;
    }
    ev.preventDefault();
    if (state.inFlight) {
      stopMessage();
    } else {
      sendMessage();
    }
  });

  document.addEventListener("click", (ev) => {
    const menu = $("sessionMenu");
    if (!menu.hidden && !ev.target.closest(".sidebar-list-menu-btn") && !ev.target.closest("#sessionMenu")) {
      closeSessionMenu();
    }
  });

  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") {
      if (state.inFlight) {
        stopMessage();
        return;
      }
      closeSessionMenu();
      if (!$("settingsPanel").hidden) {
        closeSettings();
      }
      if (typeof closeReleaseModal === "function" && !$("releaseModal").hidden) {
        closeReleaseModal();
      }
      if (typeof closeProvisioningModal === "function" && !$("provisioningModal").hidden) {
        closeProvisioningModal();
      }
    }
  });

  wireSettings();
  if (typeof wireReleases === "function") {
    wireReleases();
  }
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
  empty.textContent = "Select or create a chat";
  box.appendChild(empty);

  try {
    await refreshSessions(null);
  } catch (e) {
    showToast(String(e && e.message ? e.message : e));
  }
});
