/* global fetch API, marked */
/** Shared DOM helpers (must be `function`, not script-scoped const). */
function $(id) {
  return document.getElementById(id);
}

const SETTINGS_KEY = "db-agent-ui-settings";
const CONTEXT_USAGE_CACHE_KEY = "db-agent-context-usage-cache";
const AUTH_TOKEN_KEY = "db-agent-auth-token";
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

const WORKFLOW_LOADING_HINTS = [
  "正在生成志愿报告…",
  "正在查询分数与政策数据…",
  "正在调用模型撰写报告，请稍候…",
  "报告生成中 — 可点击 Stop 取消",
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

function getAuthToken() {
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY) || "";
  } catch {
    return "";
  }
}

function setAuthToken(token) {
  try {
    localStorage.setItem(AUTH_TOKEN_KEY, token || "");
  } catch {
    // ignore storage failures
  }
}

function clearAuthToken() {
  try {
    localStorage.removeItem(AUTH_TOKEN_KEY);
  } catch {
    // ignore storage failures
  }
}

function showAuthOverlay(message) {
  const overlay = $("authOverlay");
  const errorEl = $("authOverlayError");
  if (overlay) {
    overlay.removeAttribute("hidden");
  }
  if (errorEl) {
    if (message) {
      errorEl.removeAttribute("hidden");
      errorEl.textContent = message;
    } else {
      errorEl.hidden = true;
      errorEl.textContent = "";
    }
  }
}

function hideAuthOverlay() {
  const overlay = $("authOverlay");
  if (overlay) {
    overlay.hidden = true;
  }
}

let authBootstrapComplete = false;

async function submitWebLogin() {
  const input = $("authSecretInput");
  const secret = input ? input.value : "";
  if (!secret) {
    showAuthOverlay("请输入登录密钥。");
    return;
  }
  try {
    const result = await api("/api/auth/web/login", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ secret }),
    });
    if (!result || !result.token) {
      throw new Error("登录响应未包含 token");
    }
    setAuthToken(result.token);
    hideAuthOverlay();
    if (input) {
      input.value = "";
    }
    await refreshSessions(null);
  } catch (e) {
    showAuthOverlay(String(e && e.message ? e.message : e));
  }
}

async function ensureAuthenticated() {
  const token = getAuthToken();
  if (token) {
    try {
      await api("/api/auth/me", { suppressAuthRedirect: true });
      hideAuthOverlay();
      return true;
    } catch {
      clearAuthToken();
    }
  }
  showAuthOverlay();
  return false;
}

function handleUnauthorized() {
  if (!authBootstrapComplete) {
    return;
  }
  clearAuthToken();
  showAuthOverlay("会话已过期，请重新登录。");
}

async function api(path, options = {}) {
  const { headers: hdr, signal, skipAuth, suppressAuthRedirect, ...rest } = options;
  const headers = new Headers(hdr || {});
  const method = (rest.method || "GET").toUpperCase();
  const body = rest.body;
  if (body != null && method !== "GET" && method !== "HEAD") {
    if (!headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
  }
  if (!skipAuth) {
    const token = getAuthToken();
    if (token && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }
  const res = await fetch(path, {
    ...rest,
    headers,
    signal,
  });
  if (!res.ok) {
    if (res.status === 401 && !skipAuth && !suppressAuthRedirect) {
      handleUnauthorized();
    }
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
  refreshWorkflowReportButtonState(false);
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
  refreshWorkflowReportButtonState(state.inFlight);
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
  refreshWorkflowReportButtonState(busy);
}

function getLastUserMessageText() {
  const userMessages = $("messages")?.querySelectorAll(".msg.user .body");
  if (!userMessages || userMessages.length === 0) {
    return "";
  }
  return (userMessages[userMessages.length - 1].textContent || "").trim();
}

function canRunWorkflowReport(hasSession) {
  if (!hasSession) {
    return false;
  }
  if ($("input").value.trim().length > 0) {
    return true;
  }
  return getLastUserMessageText().length > 0;
}

function resolveWorkflowReportMessage() {
  const inputText = $("input").value.trim();
  if (inputText) {
    return { text: inputText, fromInput: true };
  }
  const lastUser = getLastUserMessageText();
  if (lastUser) {
    return { text: lastUser, fromInput: false };
  }
  return null;
}

function refreshWorkflowReportButtonState(busy) {
  const btn = $("workflowReportBtn");
  if (!btn) {
    return;
  }
  btn.disabled = !canRunWorkflowReport(!!state.activeId) || busy;
}

function setWorkflowReportButtonState(_hasSession, _hasText, busy) {
  refreshWorkflowReportButtonState(busy);
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

function showLoadingBubble(hints) {
  removeLoadingBubble();
  clearMessagesEmpty();

  const loadingHints = hints && hints.length > 0 ? hints : LOADING_HINTS;
  const loadingHint = loadingHints === WORKFLOW_LOADING_HINTS
    ? "Workflow may query score data and call the model"
    : "The model may query the database multiple times";

  const div = document.createElement("div");
  div.className = "msg assistant msg-loading";
  div.id = "chatLoading";
  div.innerHTML = `
    <div class="loading-card">
      <div class="loading-row">
        <span class="loading-dots" aria-hidden="true"><span></span><span></span><span></span></span>
        <span class="loading-text" id="loadingStatus">${loadingHints[0]}</span>
      </div>
      <span class="loading-elapsed" id="loadingElapsed">Elapsed 0s</span>
      <span class="loading-hint">${loadingHint}</span>
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
    hintIndex = Math.min(loadingHints.length - 1, Math.floor(elapsed / 4000));
    const st = statusEl();
    if (st) {
      st.textContent = loadingHints[hintIndex];
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

  const activeId = selectId ?? state.activeId;
  const visibleSessions = selectVisibleSidebarItems(
    sessions,
    state.chatSidebarExpanded,
    activeId,
    (session) => session.id,
  );
  for (const s of visibleSessions) {
    const row = document.createElement("div");
    const isActive = s.id === activeId;
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
    empty.textContent = "输入问题开始对话，或在下方点击「志愿报告」生成分析报告，例如：安徽物理类620分合工大计算机和软件工程政策";
    box.appendChild(empty);
    refreshWorkflowReportButtonState(state.inFlight);
    return;
  }

  setSessionEmptyState(false);

  for (const m of msgs) {
    const div = document.createElement("div");
    const role = (m.role || "unknown").toLowerCase();
    div.className = `msg ${role}`;
    div.innerHTML = buildMessageHtml(m.role, m.text, m.tables);
    box.appendChild(div);
  }

  box.scrollTop = box.scrollHeight;
  refreshWorkflowReportButtonState(state.inFlight);
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

const INDEX_COLUMN_KEY = "_index";
const MAX_TABLE_BODY_HEIGHT_PX = 280;

const COLUMN_LAYOUT = {
  [INDEX_COLUMN_KEY]: { minWidthPx: 36, align: "center", wrap: false },
  university_name: { minWidthPx: 84, align: "left", wrap: false },
  major_name: { minWidthPx: 120, align: "left", wrap: true },
  plan_count: { minWidthPx: 48, align: "left", wrap: false },
  campus: { minWidthPx: 64, align: "left", wrap: false },
  min_score: { minWidthPx: 48, align: "left", wrap: false },
  min_rank: { minWidthPx: 56, align: "left", wrap: false },
  max_score: { minWidthPx: 48, align: "left", wrap: false },
  year: { minWidthPx: 40, align: "left", wrap: false },
  subject_group: { minWidthPx: 48, align: "left", wrap: false },
  year_label: { minWidthPx: 88, align: "left", wrap: false },
  rank_range: { minWidthPx: 80, align: "left", wrap: false },
  segment_count: { minWidthPx: 72, align: "left", wrap: false },
  source_label: { minWidthPx: 88, align: "left", wrap: false },
  admission_type: { minWidthPx: 52, align: "left", wrap: false },
};

const RANK_STRONG_KEYS = new Set(["year_label", "rank_range", "segment_count"]);

const DEFAULT_COLUMN_LAYOUT = { minWidthPx: 60, align: "left", wrap: false };

function layoutForColumn(key) {
  return COLUMN_LAYOUT[key] || DEFAULT_COLUMN_LAYOUT;
}

function buildDisplayColumns(columns) {
  const indexColumn = {
    key: INDEX_COLUMN_KEY,
    label: "序号",
    ...layoutForColumn(INDEX_COLUMN_KEY),
  };
  const dataColumns = (columns || []).map((col) => ({
    key: col.key,
    label: col.label,
    ...layoutForColumn(col.key),
  }));
  return [indexColumn, ...dataColumns];
}

function majorColumnsFromTable(columns) {
  return buildDisplayColumns((columns || []).filter((col) => col.key !== "university_name"));
}

function tableBodyScrollHeightPx(rowCount) {
  return Math.min(Math.max(rowCount * 36, 72), MAX_TABLE_BODY_HEIGHT_PX);
}

function renderTableCell(col, value) {
  const wrapCls = col.wrap ? " wrap" : " nowrap";
  return `<td style="min-width:${col.minWidthPx}px" class="align-${col.align}${wrapCls}">${value}</td>`;
}

function renderTableGrid(displayColumns, rows) {
  const tableMinWidth = displayColumns.reduce((sum, col) => sum + col.minWidthPx, 0);
  const bodyHeight = tableBodyScrollHeightPx(rows.length);
  const scrollMaxHeight = bodyHeight + 44;
  const head = displayColumns
    .map((col) => `<th style="min-width:${col.minWidthPx}px" class="align-${col.align}">${escapeHtml(col.label)}</th>`)
    .join("");
  const body = rows
    .map((row, rowIndex) => {
      const cells = displayColumns.map((col) => {
        const value = col.key === INDEX_COLUMN_KEY
          ? String(rowIndex + 1)
          : formatTableCell(row[col.key]);
        return renderTableCell(col, value);
      }).join("");
      return `<tr>${cells}</tr>`;
    })
    .join("");
  return `<div class="chat-table-scroll" style="max-height:${scrollMaxHeight}px">
    <table class="chat-table-grid" style="min-width:${tableMinWidth}px">
      <thead><tr>${head}</tr></thead>
      <tbody>${body}</tbody>
    </table>
  </div>`;
}

function formatTableCell(value) {
  if (value == null || value === "") {
    return "-";
  }
  return escapeHtml(String(value));
}

function isRankTable(table) {
  return (table.columns || []).some(
    (col) => col.key === "year_label" || col.key === "rank_range",
  );
}

function isPublicSourceUrl(url) {
  const text = String(url || "").trim();
  return text.startsWith("http://") || text.startsWith("https://");
}

function formatRankSourceCell(row) {
  const url = String(row.source_url || "").trim();
  if (isPublicSourceUrl(url)) {
    return `<span class="rank-source"><span class="rank-source-icon" aria-hidden="true">✅</span>官方已公布 <a class="rank-source-link" href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">来源</a></span>`;
  }
  return `<span class="rank-source"><span class="rank-source-icon" aria-hidden="true">✅</span>${formatTableCell(row.source_label || "官方已公布")}</span>`;
}

function rankTableProvinceLabel(table) {
  const explicit = String(table.province || table.title || "").trim();
  if (explicit) {
    const legacy = explicit.match(/^(.+?)\s*·/);
    return legacy ? legacy[1].trim() : explicit;
  }
  const rows = table.rows || [];
  for (const row of rows) {
    const fromRow = String(row.province || "").trim();
    if (fromRow) {
      return fromRow;
    }
  }
  return "";
}

function renderRankTable(table) {
  const columns = (table.columns || []).map((col) => ({
    key: col.key,
    label: col.label,
    strong: RANK_STRONG_KEYS.has(col.key),
    ...layoutForColumn(col.key),
  }));
  const rows = table.rows || [];
  const tableMinWidth = columns.reduce((sum, col) => sum + col.minWidthPx, 0);
  const head = columns
    .map((col) => `<th scope="col">${escapeHtml(col.label)}</th>`)
    .join("");
  const body = rows
    .map((row) => {
      const cells = columns
        .map((col) => {
          if (col.key === "source_label") {
            return `<td>${formatRankSourceCell(row)}</td>`;
          }
          const value = formatTableCell(row[col.key]);
          const cls = col.strong ? ' class="rank-cell-strong"' : "";
          return `<td${cls}>${value}</td>`;
        })
        .join("");
      return `<tr>${cells}</tr>`;
    })
    .join("");
  const provinceLabel = rankTableProvinceLabel(table);
  const headerHtml = provinceLabel
    ? `<div class="rank-table-header" data-province="${escapeHtml(provinceLabel)}">${escapeHtml(provinceLabel)}</div>`
    : "";
  return `<section class="rank-table-block">${headerHtml}<div class="rank-result-wrap"><table class="rank-result-table" style="min-width:${tableMinWidth}px"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div></section>`;
}

function tierKeyFromTitle(title) {
  if (!title) {
    return "default";
  }
  if (title.startsWith("冲")) {
    return "chong";
  }
  if (title.startsWith("稳")) {
    return "wen";
  }
  if (title.startsWith("保")) {
    return "bao";
  }
  return "default";
}

function hasDisplayText(value) {
  const text = String(value || "").trim();
  return text.length > 0 && text !== "-";
}

function renderSchoolTags(tags) {
  if (!tags || tags.length === 0) {
    return "";
  }
  return `<div class="chat-table-school-tags">${tags
    .map((tag) => `<span class="chat-table-school-tag">${escapeHtml(tag)}</span>`)
    .join("")}</div>`;
}

function renderSchoolLogo(logoUrl, universityName) {
  const initial = escapeHtml((universityName || "校").slice(0, 1));
  if (!hasDisplayText(logoUrl)) {
    return `<div class="chat-table-school-logo chat-table-school-logo-fallback" aria-hidden="true">${initial}</div>`;
  }
  const fallbackChar = JSON.stringify((universityName || "校").slice(0, 1));
  return `<img class="chat-table-school-logo" src="${escapeHtml(logoUrl)}" alt="" loading="lazy" decoding="async" onerror='this.replaceWith(Object.assign(document.createElement("div"),{className:"chat-table-school-logo chat-table-school-logo-fallback",ariaHidden:"true",textContent:${fallbackChar}}))'/>`;
}

function renderSchoolLocation(group) {
  const province = hasDisplayText(group.province) ? group.province : "";
  const department = hasDisplayText(group.department) ? group.department : "";
  if (!province && !department) {
    return "";
  }
  const parts = [];
  if (province) {
    parts.push(`<span class="chat-table-school-province"><span class="chat-table-school-pin" aria-hidden="true"></span>${escapeHtml(province)}</span>`);
  }
  if (province && department) {
    parts.push('<span class="chat-table-school-divider" aria-hidden="true"></span>');
  }
  if (department) {
    parts.push(`<span class="chat-table-school-department">${escapeHtml(department)}</span>`);
  }
  return `<div class="chat-table-school-location">${parts.join("")}</div>`;
}

function renderSchoolGroup(group, columns) {
  const majors = group.majors || [];
  const majorCount = group.majorCount || majors.length;
  const minScore = group.minScore || "-";
  const headerMeta = minScore === "-"
    ? `${majorCount}个专业`
    : `${majorCount}个专业 · ${minScore}起`;
  const displayColumns = majorColumnsFromTable(columns);
  const tagsHtml = renderSchoolTags(group.tags || []);
  const locationHtml = renderSchoolLocation(group);
  const titleExtra = (locationHtml || tagsHtml)
    ? `<div class="chat-table-school-title-extra">${locationHtml}${tagsHtml}</div>`
    : "";
  return `<details class="chat-table-school">
    <summary class="chat-table-school-header">
      <div class="chat-table-school-card">
        <div class="chat-table-school-logo-wrap">
          ${renderSchoolLogo(group.logoUrl, group.universityName)}
        </div>
        <div class="chat-table-school-main">
          <div class="chat-table-school-title-row">
            <span class="chat-table-school-name">${escapeHtml(group.universityName || "未知院校")}</span>
            ${titleExtra}
          </div>
          <span class="chat-table-school-meta">${escapeHtml(headerMeta)}</span>
        </div>
      </div>
      <span class="chat-table-school-chevron" aria-hidden="true"></span>
    </summary>
    <div class="major-table-wrap">
      ${renderTableGrid(displayColumns, majors)}
    </div>
  </details>`;
}

function renderFlatChatTable(table) {
  const displayColumns = buildDisplayColumns(table.columns || []);
  const rows = table.rows || [];
  if (displayColumns.length <= 1 || rows.length === 0) {
    return "";
  }
  return renderTableGrid(displayColumns, rows);
}

function renderChatTable(table) {
  if (isRankTable(table)) {
    return renderRankTable(table);
  }
  const tier = tierKeyFromTitle(table.title);
  const groups = table.groups || [];
  const rows = table.rows || [];
  const isEmpty = groups.length === 0 && rows.length === 0;
  let body = "";
  if (isEmpty) {
    body = `<div class="chat-table-empty">暂无匹配专业</div>`;
  } else if (groups.length > 0) {
    body = groups.map((group) => renderSchoolGroup(group, table.columns || [])).join("");
  } else {
    body = renderFlatChatTable(table);
  }
  return `<section class="chat-table-tier chat-table-tier-${tier}">
    ${table.title ? `<h4 class="chat-table-tier-title">${escapeHtml(table.title)}</h4>` : ""}
    <div class="chat-table-tier-body">${body}</div>
  </section>`;
}

function formatChatTables(tables) {
  if (!tables || tables.length === 0) {
    return "";
  }
  const rankMode = tables.some(isRankTable);
  const className = rankMode ? "chat-tables chat-tables-rank" : "chat-tables";
  return `<div class="${className}">${tables.map(renderChatTable).join("")}</div>`;
}

function buildMessageHtml(role, text, tables) {
  const normalizedRole = (role || "unknown").toLowerCase();
  const body = normalizedRole === "assistant" ? renderMarkdown(text || "") : escapeHtml(text || "");
  const showRole = normalizedRole === "tool" || normalizedRole === "system";
  const tablesHtml = normalizedRole === "assistant" ? formatChatTables(tables) : "";
  const roleHtml = showRole ? `<div class="role">${escapeHtml(normalizedRole)}</div>` : "";
  return `${roleHtml}<div class="body">${body}${tablesHtml}</div>`;
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

async function sendWorkflowReport() {
  if (state.inFlight) {
    return;
  }
  if (!state.activeId) {
    showToast("Select or create a chat first");
    return;
  }

  const resolved = resolveWorkflowReportMessage();
  if (!resolved) {
    showToast("Enter a question or open a chat with a prior user message");
    return;
  }

  const { text, fromInput } = resolved;
  setSessionEmptyState(false);
  const conversationId = state.activeId;

  if (fromInput) {
    appendUserBubble(text);
    $("input").value = "";
    autoResizeInput();
  }

  state.inFlight = true;
  state.abortController = new AbortController();
  setComposerBusy(true);
  showLoadingBubble(WORKFLOW_LOADING_HINTS);

  try {
    const accepted = await api("/api/workflows/report", {
      method: "POST",
      body: JSON.stringify({ message: text, conversationId }),
      signal: state.abortController.signal,
    });
    removeLoadingBubble();

    let reply = accepted;
    if (accepted && accepted.runId && accepted.status !== "SUCCEEDED" && accepted.status !== "FAILED") {
      showLoadingBubble(WORKFLOW_LOADING_HINTS);
      const terminal = await pollWorkflowRun(accepted.runId, state.abortController.signal);
      removeLoadingBubble();
      if (terminal.status !== "SUCCEEDED") {
        const errMsg = terminal.errorMessage || "Workflow report failed";
        showToast(errMsg);
        await selectSession(conversationId);
        return;
      }
      reply = await api(`/api/workflows/${accepted.runId}/report`, {
        signal: state.abortController.signal,
      });
    }

    if (!reply || reply.status !== "SUCCEEDED") {
      const errMsg = (reply && reply.errorMessage) || "Workflow report failed";
      showToast(errMsg);
      await selectSession(conversationId);
      return;
    }

    await refreshSessions(conversationId);
    await selectSession(conversationId);
    if (reply.sources && reply.sources.length > 0) {
      appendSourcesToLastAssistant(reply.sources);
    }
  } catch (e) {
    removeLoadingBubble();
    if (isAbortError(e)) {
      showToast("Report generation stopped");
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

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function pollWorkflowRun(runId, signal, intervalMs = 1500, maxAttempts = 120) {
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const run = await api(`/api/workflows/${runId}`, { signal });
    if (run && (run.status === "SUCCEEDED" || run.status === "FAILED")) {
      return run;
    }
    await delay(intervalMs);
  }
  throw new Error("Workflow report timed out");
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
    try {
      await createSession();
    } catch (e) {
      showToast(String(e && e.message ? e.message : e));
    }
  });

  $("send").addEventListener("click", sendMessage);
  $("workflowReportBtn")?.addEventListener("click", sendWorkflowReport);
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
}

let resolveAuthReady;
window.authReady = new Promise((resolve) => {
  resolveAuthReady = resolve;
});

window.addEventListener("DOMContentLoaded", async () => {
  document.documentElement.dataset.theme = loadSettings().theme;
  applySettings(loadSettings());
  state.contextUsageBySessionId = loadContextUsageCache();

  let authed = false;
  try {
    authed = await ensureAuthenticated();
    resolveAuthReady(authed);
  } catch (e) {
    resolveAuthReady(false);
    showToast(String(e && e.message ? e.message : e));
  } finally {
    authBootstrapComplete = true;
  }

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

  const authForm = $("authLoginForm");
  if (authForm) {
    authForm.addEventListener("submit", (event) => {
      event.preventDefault();
      submitWebLogin().catch((e) => showAuthOverlay(String(e && e.message ? e.message : e)));
    });
  }

  if (authed) {
    try {
      await refreshSessions(null);
    } catch (e) {
      showToast(String(e && e.message ? e.message : e));
    }
  }
});
