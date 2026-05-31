/* global fetch API, marked */
/** Shared by app.js, releases.js, provisioning.js (must be `function`, not script-scoped const). */
function $(id) {
  return document.getElementById(id);
}

const SETTINGS_KEY = "db-agent-ui-settings";
const CONTEXT_USAGE_CACHE_KEY = "db-agent-context-usage-cache";
const SIDEBAR_RECENT_LIMIT = 6;

const state = {
  activeId: null,
  openMenuId: null,
  inFlight: false,
  abortController: null,
  loadingTimer: null,
  loadingStartedAt: null,
  lastContextUsage: null,
  contextUsageBySessionId: {},
  contextPanelOpen: false,
  chatSidebarExpanded: false,
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

function loadContextUsageCache() {
  try {
    const raw = localStorage.getItem(CONTEXT_USAGE_CACHE_KEY);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }
    return parsed;
  } catch {
    return {};
  }
}

function saveContextUsageCache(cache) {
  try {
    localStorage.setItem(CONTEXT_USAGE_CACHE_KEY, JSON.stringify(cache || {}));
  } catch {
    // ignore storage failures (private mode / quota)
  }
}

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

function categoryColor(category) {
  const map = {
    system_prompt: "#6366f1",
    tools: "#a855f7",
    memory_user: "#0ea5e9",
    memory_assistant: "#22c55e",
    memory_tool: "#eab308",
    current_user: "#f97316",
  };
  return map[category] || "#94a3b8";
}

function clearContextUsage() {
  state.lastContextUsage = null;
  state.contextPanelOpen = false;
  const btn = $("contextUsageBtn");
  if (btn) {
    btn.hidden = false;
    btn.setAttribute("aria-expanded", "false");
    btn.title = "Estimated input context (send a message to populate)";
  }
  const ring = $("contextUsageRing");
  if (ring) {
    ring.style.background = "conic-gradient(var(--border-subtle) 0 360deg)";
  }
  const pct = $("contextUsagePct");
  if (pct) {
    pct.textContent = "--";
  }
  const panel = $("contextUsagePanel");
  if (panel) {
    panel.hidden = true;
  }
}

function setContextUsage(u) {
  if (!u || typeof u.usedPercent !== "number") {
    clearContextUsage();
    return;
  }
  state.lastContextUsage = u;
  const btn = $("contextUsageBtn");
  btn.hidden = false;
  btn.title = "Estimated input context (last message sent)";
  const pctVal = Math.min(100, Math.round(u.usedPercent * 10) / 10);
  $("contextUsagePct").textContent = `${pctVal}%`;
  const deg = Math.min(360, u.usedPercent * 3.6);
  $("contextUsageRing").style.background =
    `conic-gradient(#5f5f5f 0 ${deg}deg, #d9d9d9 ${deg}deg 360deg)`;

  const sum = $("contextUsageSummary");
  sum.textContent = `~${u.totalEstimatedInputTokens.toLocaleString()} / ${u.contextWindowTokens.toLocaleString()} input tokens (${pctVal}% of configured window) · ${u.estimationMethod}`;

  const bar = $("contextUsageBar");
  bar.innerHTML = "";
  const rows = u.breakdown || [];
  for (const r of rows) {
    const seg = document.createElement("div");
    seg.className = "context-usage-seg";
    seg.title = `${r.label}: ~${r.estimatedTokens}`;
    seg.style.background = categoryColor(r.category);
    seg.style.flexGrow = Math.max(1, r.estimatedTokens || 0);
    bar.appendChild(seg);
  }

  const tbody = $("contextUsageTbody");
  tbody.innerHTML = "";
  for (const r of rows) {
    const tr = document.createElement("tr");
    const c1 = document.createElement("td");
    c1.innerHTML = `<span style="color:${categoryColor(r.category)}">●</span> ${escapeHtml(r.label)}`;
    const c2 = document.createElement("td");
    c2.textContent = r.estimatedTokens != null ? String(r.estimatedTokens) : "—";
    const c3 = document.createElement("td");
    c3.textContent = r.charCount != null ? String(r.charCount) : "—";
    tr.appendChild(c1);
    tr.appendChild(c2);
    tr.appendChild(c3);
    tbody.appendChild(tr);
  }
}

function toggleContextUsagePanel() {
  const panel = $("contextUsagePanel");
  const btn = $("contextUsageBtn");
  if (!panel || btn.hidden || !state.lastContextUsage) {
    return;
  }
  state.contextPanelOpen = !state.contextPanelOpen;
  panel.hidden = !state.contextPanelOpen;
  btn.setAttribute("aria-expanded", state.contextPanelOpen ? "true" : "false");
}

function closeContextUsagePanel() {
  state.contextPanelOpen = false;
  const panel = $("contextUsagePanel");
  const btn = $("contextUsageBtn");
  if (panel) {
    panel.hidden = true;
  }
  if (btn) {
    btn.setAttribute("aria-expanded", "false");
  }
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
  const isEmptySession = hasSession && isCurrentSessionEmpty();
  $("send").hidden = false;
  $("stop").hidden = true;
  $("send").disabled = !hasSession || !hasText;
  $("input").disabled = false;
  document.querySelector(".composer-inner")?.classList.remove("is-busy");
  $("input").placeholder = hasSession
    ? "Ask about the database or docs… (⌘+Enter to send)"
    : "Select a chat on the left, or click New Chat";
  setCompactionButtonsState(hasSession && !isEmptySession, false);
  if (!hasSession) {
    setSessionEmptyState(false);
  } else {
    const box = $("messages");
    setComposerCentered(box?.dataset?.sessionEmpty === "true");
  }
}

function setComposerCentered(centered) {
  const chatView = $("chatView");
  if (!chatView) {
    return;
  }
  chatView.classList.toggle("composer-centered", !!centered);
}

function isCurrentSessionEmpty() {
  return $("messages")?.dataset?.sessionEmpty === "true";
}

function setSessionEmptyState(isEmpty) {
  const box = $("messages");
  if (!box) {
    return;
  }
  box.dataset.sessionEmpty = isEmpty ? "true" : "false";
  setComposerCentered(isEmpty);
  setCompactionButtonsState(!!state.activeId && !isEmpty, state.inFlight);
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
  setCompactionButtonsState(!!state.activeId && !isCurrentSessionEmpty(), busy);
}

function setCompactionButtonsState(hasSession, busy) {
  const reviewBtn = $("compactReviewBtn");
  const executeBtn = $("compactExecuteBtn");
  if (!reviewBtn || !executeBtn) {
    return;
  }
  reviewBtn.hidden = !hasSession;
  executeBtn.hidden = !hasSession;
  reviewBtn.disabled = !hasSession || busy;
  executeBtn.disabled = !hasSession || busy;
}

function clearCompactionResultPanel() {
  const panel = $("compactionResultPanel");
  if (!panel) {
    return;
  }
  panel.hidden = true;
  panel.innerHTML = "";
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

function renderCompactionResultPanel(mode, result) {
  const panel = $("compactionResultPanel");
  if (!panel) {
    return;
  }
  const title = mode === "execute" ? "Compaction executed" : "Compaction review";
  const summary = (result && result.summary)
    ? formatCompactionSummaryForDisplay(result.summary)
    : "(no summary available)";
  const stats = result
    ? `Messages ${result.beforeMessageCount} → ${result.afterMessageCount} · Tokens ${result.beforeEstimatedTokens} → ${result.afterEstimatedTokens}`
    : "No compaction metrics returned";
  panel.innerHTML = `
    <div class="compaction-result-head">
      <div class="compaction-result-title">${title}</div>
      <button type="button" class="compaction-result-clear" id="compactionResultClearBtn">Clear</button>
    </div>
    <div class="compaction-result-stats">${escapeHtml(stats)}</div>
    <pre class="compaction-result-body">${escapeHtml(summary)}</pre>
  `;
  panel.hidden = false;
  $("compactionResultClearBtn")?.addEventListener("click", () => {
    clearCompactionResultPanel();
  });
}

function formatCompactionSummaryForDisplay(rawSummary) {
  const raw = String(rawSummary || "").replace(/\r\n/g, "\n").trim();
  if (!raw || raw === "(no summary available)") {
    return "(no summary available)";
  }

  const compactBrief = extractCompactionField(raw, "Compact brief");
  const userGoalsRaw = extractCompactionField(raw, "User goals");
  const knownFindingsRaw = extractCompactionField(raw, "Known findings");
  const nextStepRaw = extractCompactionField(raw, "Next step");

  if (!compactBrief && !userGoalsRaw && !knownFindingsRaw && !nextStepRaw) {
    return raw;
  }

  const userGoals = splitCompactionItems(userGoalsRaw);
  const knownFindings = splitCompactionItems(knownFindingsRaw);
  const lines = ["Compact brief:"];
  lines.push("User goals:");
  if (userGoals.length === 0) {
    lines.push("1. (none)");
  } else {
    userGoals.forEach((item, idx) => lines.push(`${idx + 1}. ${item}`));
  }
  lines.push("");
  lines.push("Known findings:");
  if (knownFindings.length === 0) {
    lines.push("1. (none)");
  } else {
    knownFindings.forEach((item, idx) => lines.push(`${idx + 1}. ${item}`));
  }
  lines.push("");
  lines.push(`Next step: ${nextStepRaw || "(none)"}`);
  return lines.join("\n");
}

function extractCompactionField(text, field) {
  const escaped = field.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`(?:^|\\n)-\\s*${escaped}:\\s*([\\s\\S]*?)(?=\\n-\\s*[A-Za-z ]+:|$)`, "i");
  const match = text.match(pattern);
  if (!match) {
    return "";
  }
  return match[1].replace(/\n+/g, " ").replace(/\s{2,}/g, " ").trim();
}

function splitCompactionItems(fieldValue) {
  if (!fieldValue) {
    return [];
  }
  return fieldValue
    .split("|")
    .map((item) => item.trim())
    .filter(Boolean);
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
    <button type="button" class="dropdown-item" data-action="archive">Archive chat</button>
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

  menu.querySelector('[data-action="archive"]').addEventListener("click", async (ev) => {
    ev.stopPropagation();
    closeSessionMenu();
    await archiveSession(sessionId);
  });

  menu.querySelector('[data-action="delete"]').addEventListener("click", async (ev) => {
    ev.stopPropagation();
    closeSessionMenu();
    await deleteSession(sessionId);
  });
}

function selectVisibleSidebarItems(items, expanded, activeId, getId) {
  if (!Array.isArray(items) || items.length <= SIDEBAR_RECENT_LIMIT || expanded) {
    return items || [];
  }
  const visible = items.slice(0, SIDEBAR_RECENT_LIMIT);
  if (!activeId) {
    return visible;
  }
  const activeIndex = items.findIndex((item) => getId(item) === activeId);
  if (activeIndex < 0 || activeIndex < SIDEBAR_RECENT_LIMIT) {
    return visible;
  }
  return [...items.slice(0, SIDEBAR_RECENT_LIMIT - 1), items[activeIndex]];
}

function appendSidebarMoreToggle(list, expanded, totalCount, visibleCount, onToggle) {
  if (!list || totalCount <= SIDEBAR_RECENT_LIMIT) {
    return;
  }
  const moreWrap = document.createElement("div");
  moreWrap.className = "sidebar-list-more";
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "sidebar-list-more-btn";
  const hiddenCount = Math.max(0, totalCount - visibleCount);
  btn.textContent = expanded
    ? "See less"
    : `See more${hiddenCount > 0 ? ` (${hiddenCount})` : ""}`;
  btn.addEventListener("click", onToggle);
  moreWrap.appendChild(btn);
  list.appendChild(moreWrap);
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
  const visibleSessions = selectVisibleSidebarItems(
    sessions,
    state.chatSidebarExpanded,
    activeId,
    (session) => session.id,
  );
  for (const s of visibleSessions) {
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

  appendSidebarMoreToggle(
    list,
    state.chatSidebarExpanded,
    sessions.length,
    visibleSessions.length,
    async () => {
      state.chatSidebarExpanded = !state.chatSidebarExpanded;
      await refreshSessions(state.activeId);
    },
  );
}

async function selectSession(id) {
  if (typeof showView === "function") {
    showView("chat");
  }
  if (state.inFlight && state.activeId !== id) {
    abortInFlight();
  }
  closeSessionMenu();
  const previousActive = state.activeId;
  state.activeId = id;
  if (previousActive !== id) {
    const cachedUsage = state.contextUsageBySessionId[id];
    if (cachedUsage) {
      setContextUsage(cachedUsage);
    } else {
      clearContextUsage();
    }
  }
  setCompactionButtonsState(true, state.inFlight);
  clearCompactionResultPanel();
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
    setSessionEmptyState(true);
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "Ask about the database or docs, e.g. RAG 和微调有什么区别？";
    box.appendChild(empty);
    return;
  }

  setSessionEmptyState(false);

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
  delete state.contextUsageBySessionId[id];
  saveContextUsageCache(state.contextUsageBySessionId);
  if (state.activeId === id) {
    state.activeId = null;
    setComposerCentered(false);
    setCompactionButtonsState(false, state.inFlight);
    clearCompactionResultPanel();
    clearContextUsage();
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

async function archiveSession(id) {
  await api(`/api/conversations/${encodeURIComponent(id)}/archive`, { method: "POST" });
  delete state.contextUsageBySessionId[id];
  saveContextUsageCache(state.contextUsageBySessionId);
  if (state.activeId === id) {
    state.activeId = null;
    setComposerCentered(false);
    setCompactionButtonsState(false, state.inFlight);
    clearCompactionResultPanel();
    clearContextUsage();
    $("messages").innerHTML = "";
    const empty = document.createElement("div");
    empty.className = "messages-empty";
    empty.textContent = "Select or create a chat";
    $("messages").appendChild(empty);
    $("sessionTitle").textContent = "Select or create a chat";
    setComposerState(false);
  }
  await refreshSessions(state.activeId);
  showToast("Chat archived");
}

function stopMessage() {
  if (!state.inFlight) {
    return;
  }
  abortInFlight();
}

function resolveRagSourceHref(rawSource) {
  if (!rawSource || !String(rawSource).trim()) {
    return "";
  }
  const source = String(rawSource).trim();
  if (/^https?:\/\//i.test(source)) {
    return source;
  }
  const normalized = source.replaceAll("\\", "/").toLowerCase();
  if (normalized.includes("rag-docs/")) {
    return `/api/rag-docs/open?source=${encodeURIComponent(source)}`;
  }
  return "";
}

function formatRagSources(sources) {
  if (!sources || sources.length === 0) {
    return "";
  }
  const sourceCount = sources.length;
  const items = sources
    .map((source) => {
      const school = source.school ? `<span class="rag-source-school">${escapeHtml(source.school)}</span>` : "";
      const title = source.title || source.source || "unknown";
      const sourceRef = source.source || "";
      const href = resolveRagSourceHref(sourceRef);
      const snippet = source.snippet || "";
      const titleHtml = href
        ? `<a class="rag-source-link rag-source-title-link" href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(title)}</a>`
        : `<strong>${escapeHtml(title)}</strong>`;
      const sourceRefHtml = sourceRef
        ? (href
          ? `<a class="rag-source-link rag-source-ref-link" href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(sourceRef)}</a>`
          : `<span class="rag-source-ref-text">${escapeHtml(sourceRef)}</span>`)
        : "";
      return `<li>${school}${titleHtml}${sourceRefHtml}<span>${escapeHtml(snippet)}</span></li>`;
    })
    .join("");
  return `<div class="rag-sources"><details class="rag-sources-details"><summary class="rag-sources-summary">Sources <span class="rag-sources-count">${sourceCount}</span></summary><ol>${items}</ol></details></div>`;
}

function appendSourcesToLastAssistant(sources) {
  const assistants = $("messages").querySelectorAll(".msg.assistant");
  const last = assistants[assistants.length - 1];
  if (!last || last.classList.contains("msg-loading")) {
    return;
  }
  const body = last.querySelector(".body");
  if (!body || body.querySelector(".rag-sources")) {
    return;
  }
  body.insertAdjacentHTML("beforeend", formatRagSources(sources));
  scrollMessagesToBottom();
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

  setSessionEmptyState(false);
  const conversationId = state.activeId;
  appendUserBubble(text);
  $("input").value = "";
  autoResizeInput();

  state.inFlight = true;
  state.abortController = new AbortController();
  setComposerBusy(true);
  showLoadingBubble();

  try {
    const reply = await api(`/api/conversations/${encodeURIComponent(conversationId)}/chat`, {
      method: "POST",
      body: JSON.stringify({ message: text }),
      signal: state.abortController.signal,
    });
    removeLoadingBubble();
    await refreshSessions(conversationId);
    await selectSession(conversationId);
    if (reply && reply.contextUsage) {
      state.contextUsageBySessionId[conversationId] = reply.contextUsage;
      saveContextUsageCache(state.contextUsageBySessionId);
      setContextUsage(reply.contextUsage);
    }
    if (reply && reply.sources && reply.sources.length > 0) {
      appendSourcesToLastAssistant(reply.sources);
    }
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

async function runCompaction(mode) {
  if (state.inFlight) {
    return;
  }
  if (!state.activeId) {
    showToast("Select or create a chat first");
    return;
  }
  const conversationId = state.activeId;
  const endpoint = mode === "execute" ? "compact-execute" : "compact-review";
  state.inFlight = true;
  setComposerBusy(true);
  showLoadingBubble();
  try {
    const result = await api(`/api/conversations/${encodeURIComponent(conversationId)}/${endpoint}`, {
      method: "POST",
    });
    removeLoadingBubble();
    renderCompactionResultPanel(mode, result);
    if (mode === "execute") {
      await refreshSessions(conversationId);
      await selectSession(conversationId);
      renderCompactionResultPanel(mode, result);
      showToast("Compaction executed");
    } else {
      showToast("Compaction review ready");
    }
  } catch (e) {
    removeLoadingBubble();
    showToast(String(e && e.message ? e.message : e));
  } finally {
    state.inFlight = false;
    setComposerBusy(false);
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
  $("compactReviewBtn")?.addEventListener("click", () => runCompaction("review"));
  $("compactExecuteBtn")?.addEventListener("click", () => runCompaction("execute"));

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
      closeContextUsagePanel();
      if (typeof closeReleaseModal === "function" && !$("releaseModal").hidden) {
        closeReleaseModal();
      }
      if (typeof closeProvisioningModal === "function" && !$("provisioningModal").hidden) {
        closeProvisioningModal();
      }
    }
  });

  wireSettings();
  const ctxBtn = $("contextUsageBtn");
  if (ctxBtn) {
    ctxBtn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      toggleContextUsagePanel();
    });
  }
  $("contextUsageClose")?.addEventListener("click", () => closeContextUsagePanel());
  document.addEventListener("click", (ev) => {
    const panel = $("contextUsagePanel");
    const btn = $("contextUsageBtn");
    if (!state.contextPanelOpen || !panel || panel.hidden) {
      return;
    }
    if (ev.target.closest("#contextUsagePanel") || ev.target.closest("#contextUsageBtn")) {
      return;
    }
    closeContextUsagePanel();
  });

  if (typeof wireReleases === "function") {
    wireReleases();
  }
}

window.addEventListener("DOMContentLoaded", async () => {
  document.documentElement.dataset.theme = loadSettings().theme;
  applySettings(loadSettings());
  state.contextUsageBySessionId = loadContextUsageCache();
  wire();
  clearContextUsage();
  setComposerState(false);
  autoResizeInput();

  const box = $("messages");
  box.dataset.sessionEmpty = "false";
  const empty = document.createElement("div");
  empty.className = "messages-empty";
  empty.textContent = "Select or create a chat";
  box.appendChild(empty);
  setSessionEmptyState(false);

  try {
    await refreshSessions(null);
  } catch (e) {
    showToast(String(e && e.message ? e.message : e));
  }
});
