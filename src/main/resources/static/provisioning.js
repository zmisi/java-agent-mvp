/* global $, api, escapeHtml, showToast, state, setComposerState, selectVisibleSidebarItems, appendSidebarMoreToggle */

function provEl(id) {
  return document.getElementById(id);
}

const provisioningState = {
  activeId: null,
  retryId: null,
  pollTimer: null,
  busy: false,
  preflight: null,
  preflightBusy: false,
  preflightElapsedTimer: null,
  preflightStartedAt: null,
  sidebarExpanded: false,
};

function provStatusLabel(status) {
  const map = {
    PENDING: "Pending",
    RUNNING: "Running",
    SUCCEEDED: "Succeeded",
    FAILED: "Failed",
    SKIPPED: "Skipped",
    CANCELLED: "Cancelled",
  };
  return map[status] || status;
}

function updateProvisioningSidebarActive() {
  document.querySelectorAll(".sidebar-list-row[data-provisioning-id]").forEach((el) => {
    const active =
      typeof releaseState !== "undefined" &&
      releaseState.view === "db-provisioning" &&
      el.dataset.provisioningId === provisioningState.activeId;
    el.classList.toggle("active", active);
  });
}

function renderProvisioningSidebar(items, selectId) {
  if (!items || items.length === 0) {
    return `<div class="sidebar-list-empty">No provisioning yet</div>`;
  }
  return items
    .map((p) => {
      const active =
        typeof releaseState !== "undefined" &&
        releaseState.view === "db-provisioning" &&
        p.id === selectId
          ? " active"
          : "";
      const label = p.title || p.id;
      return `<div class="sidebar-list-row${active}" data-provisioning-id="${escapeHtml(p.id)}">
        <button type="button" class="sidebar-list-item" data-provisioning-id="${escapeHtml(p.id)}" title="${escapeHtml(provStatusLabel(p.status))}">
          <span class="sidebar-list-dot" aria-hidden="true"></span>
          <span class="sidebar-list-text">${escapeHtml(label)}</span>
        </button>
      </div>`;
    })
    .join("");
}

const PROV_STEP_LABELS = {
  VALIDATE_INPUT: "Validate input",
  SSH_CONNECT: "SSH connect",
  DETECT_OS: "Detect OS",
  CHECK_PG_VERSION: "Check PG version",
  INSTALL_PG18: "Install PostgreSQL",
  TUNE_MEMORY: "Tune memory",
  CHECK_DISK: "Check disk",
  CREATE_DATABASE: "Create database",
  INSTALL_EXTENSIONS: "Install extensions",
  VERIFY_CONNECTION: "Verify connection",
  COMPLETE: "Complete",
};

function provStepLabel(stepName) {
  return PROV_STEP_LABELS[stepName] || stepName;
}

function buildProvWorkflowSteps(detail) {
  const steps = detail.steps || [];
  return steps.map((s) => {
    let stateClass = "is-pending";
    if (s.status === "FAILED") stateClass = "is-failed";
    else if (s.status === "SUCCEEDED" || s.status === "SKIPPED") stateClass = "is-done";
    else if (s.status === "RUNNING") stateClass = "is-running";
    return {
      label: provStepLabel(s.stepName),
      stepName: s.stepName,
      status: s.status,
      stateClass,
      log: s.logText,
    };
  });
}

function workflowStepDotHtml() {
  return `<span class="release-workflow-dot" aria-hidden="true"></span>`;
}

function renderProvisioningDetail(detail) {
  const subtitle = $("provisioningDetailSubtitle");
  if (subtitle) {
    const pg = detail.pgMajorVersion ? `PG ${detail.pgMajorVersion}` : "";
    const os = detail.osVersionLabel || detail.osFamily || "";
    const extra = [pg, os].filter(Boolean).join(" · ");
    subtitle.textContent = `${detail.host} · ${detail.databaseName}${extra ? ` · ${extra}` : ""} · ${provStatusLabel(detail.status)}`;
  }
  const workflow = buildProvWorkflowSteps(detail);
  const runningStep = workflow.find((s) => s.status === "RUNNING");
  const workflowHtml = workflow
    .map((step) => {
      const runningHint =
        step.status === "RUNNING" ? ' <span class="prov-step-running-hint">(in progress…)</span>' : "";
      return `<li class="release-workflow-step ${step.stateClass}" data-step="${escapeHtml(step.stepName)}">
          ${workflowStepDotHtml()}
          <span class="release-workflow-label">${escapeHtml(step.label)}${runningHint}</span>
        </li>`;
    })
    .join("");

  const logs = workflow
    .filter((s) => s.log)
    .map(
      (s) =>
        `<details class="prov-step-log"><summary>${escapeHtml(s.label)}</summary><pre>${escapeHtml(s.log)}</pre></details>`,
    )
    .join("");

  let hint = "";
  if (detail.connectionHint) {
    hint = `<p class="setting-hint"><strong>Connection:</strong> <code>${escapeHtml(detail.connectionHint)}</code></p>`;
  }
  if (detail.errorSummary) {
    hint += `<p class="setting-hint" style="color:var(--danger)">${escapeHtml(detail.errorSummary)}</p>`;
  }

  const canRetry = detail.status === "FAILED" || detail.status === "CANCELLED";
  const retryBtn = canRetry
    ? `<div class="release-detail-actions">
        <button type="button" class="btn btn-new btn-sm" id="provRetryBtn">Retry provisioning</button>
      </div>`
    : "";

  const runningBanner = runningStep
    ? `<p class="prov-running-banner" role="status">
        <span class="prov-running-banner-spinner" aria-hidden="true"></span>
        Running: <strong>${escapeHtml(runningStep.label)}</strong>
      </p>`
    : "";

  $("provisioningPanel").innerHTML = `
    <div class="release-detail">
      ${runningBanner}
      <ul class="release-workflow">${workflowHtml}</ul>
      ${hint}
      ${retryBtn}
      <div class="prov-logs">${logs || "<p class=\"setting-hint\">No step logs yet.</p>"}</div>
    </div>`;

  $("provRetryBtn")?.addEventListener("click", () => openProvisioningModalForRetry(detail));
}

async function refreshProvisioningWorkspace(selectId) {
  const list = await api("/api/db-provisioning");
  const sidebar = $("dbProvisioningSidebarList");
  if (sidebar) {
    const activeId = selectId ?? provisioningState.activeId;
    const visibleItems =
      typeof selectVisibleSidebarItems === "function"
        ? selectVisibleSidebarItems(
            list,
            provisioningState.sidebarExpanded,
            activeId,
            (item) => item.id,
          )
        : list;
    sidebar.innerHTML = renderProvisioningSidebar(visibleItems, activeId);
    sidebar.querySelectorAll("[data-provisioning-id]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const id = btn.dataset.provisioningId;
        provisioningState.activeId = id;
        if (typeof showView === "function") showView("db-provisioning");
        loadProvisioningDetail(id);
      });
    });
    appendSidebarMoreToggle(
      sidebar,
      provisioningState.sidebarExpanded,
      list.length,
      visibleItems.length,
      async () => {
        provisioningState.sidebarExpanded = !provisioningState.sidebarExpanded;
        await refreshProvisioningWorkspace(activeId);
      },
    );
  }
  updateProvisioningSidebarActive();
  if (selectId) {
    await loadProvisioningDetail(selectId);
  }
}

async function loadProvisioningDetail(id) {
  provisioningState.activeId = id;
  const detail = await api(`/api/db-provisioning/${encodeURIComponent(id)}`);
  renderProvisioningDetail(detail);
  updateProvisioningSidebarActive();
  stopProvisioningPoll();
  if (detail.status === "RUNNING") {
    provisioningState.pollTimer = window.setInterval(() => {
      api(`/api/db-provisioning/${encodeURIComponent(id)}`)
        .then((d) => {
          renderProvisioningDetail(d);
          if (d.status !== "RUNNING") {
            stopProvisioningPoll();
            refreshProvisioningWorkspace(id);
          }
        })
        .catch(() => stopProvisioningPoll());
    }, 1000);
  }
}

function stopProvisioningPoll() {
  if (provisioningState.pollTimer) {
    window.clearInterval(provisioningState.pollTimer);
    provisioningState.pollTimer = null;
  }
}

function toggleAuthFields() {
  const auth = $("provAuthTypeSelect").value;
  $("provPasswordGroup").hidden = auth !== "PASSWORD";
  $("provKeyGroup").hidden = auth !== "PRIVATE_KEY";
  $("provPassphraseGroup").hidden = auth !== "PRIVATE_KEY";
}

function setProvisioningModalMode(mode) {
  const title = $("provisioningModalTitle");
  const confirm = $("confirmProvisioningModal");
  if (mode === "retry") {
    if (title) title.textContent = "Retry DB Provisioning";
    if (confirm) confirm.textContent = "Retry";
  } else {
    if (title) title.textContent = "New DB Provisioning";
    if (confirm) confirm.textContent = "Create & run";
  }
}

function fillProvisioningFormFromDetail(detail) {
  $("provTitleInput").value = detail.title || "";
  $("provHostInput").value = detail.host || "";
  $("provSshPortInput").value = detail.sshPort ?? 22;
  $("provSshUserInput").value = detail.sshUser || "";
  $("provAuthTypeSelect").value = detail.authType || "PASSWORD";
  $("provPasswordInput").value = "";
  $("provKeyInput").value = "";
  $("provPassphraseInput").value = "";
  $("provMemoryInput").value = detail.memoryMb ?? 2048;
  $("provDiskInput").value = detail.diskGb ?? 20;
  $("provDbNameInput").value = detail.databaseName || "";
  $("provSchemaInput").value = detail.schemaName || "";
  $("provOwnerInput").value = detail.dbOwnerUser || "";
  $("provOwnerPasswordInput").value = "";
  const exts = detail.extensions || [];
  $("provExtPgStat").checked = exts.includes("pg_stat_statements");
  $("provExtAutoExplain").checked = exts.includes("auto_explain");
  $("provExtPgProfile").checked = exts.includes("pg_profile");
  toggleAuthFields();
}

const PROV_CONFIG_FIELD_IDS = [
  "provTitleInput",
  "provHostInput",
  "provSshPortInput",
  "provSshUserInput",
  "provMemoryInput",
  "provDiskInput",
  "provDbNameInput",
  "provSchemaInput",
  "provOwnerInput",
  "provExtPgStat",
  "provExtAutoExplain",
  "provExtPgProfile",
];

function setProvisioningConfigFieldsDisabled(disabled) {
  PROV_CONFIG_FIELD_IDS.forEach((id) => {
    const el = $(id);
    if (el) el.disabled = disabled;
  });
}

function formatPreflightTime() {
  const t = new Date();
  return t.toLocaleTimeString(undefined, { hour12: false });
}

function showPreflightLogPanel() {
  const wrap = $("provPreflightLogWrap");
  if (wrap) wrap.hidden = false;
}

function appendPreflightLog(line) {
  showPreflightLogPanel();
  const pre = $("provPreflightLog");
  if (!pre) return;
  const text = `[${formatPreflightTime()}] ${line}`;
  pre.textContent = pre.textContent ? `${pre.textContent}\n${text}` : text;
  pre.scrollTop = pre.scrollHeight;
}

function clearPreflightLog() {
  const pre = $("provPreflightLog");
  if (pre) pre.textContent = "";
}

function renderPreflightActivityLog(activityLog) {
  if (!activityLog || activityLog.length === 0) return;
  appendPreflightLog("--- Server / target steps ---");
  for (const entry of activityLog) {
    const phase = entry.phase ? `[${entry.phase}] ` : "";
    const status = entry.status ? ` (${entry.status})` : "";
    appendPreflightLog(`${phase}${entry.message || ""}${status}`);
  }
}

function stopPreflightElapsedTimer() {
  if (provisioningState.preflightElapsedTimer) {
    window.clearInterval(provisioningState.preflightElapsedTimer);
    provisioningState.preflightElapsedTimer = null;
  }
  provisioningState.preflightStartedAt = null;
}

function startPreflightElapsedTimer() {
  stopPreflightElapsedTimer();
  provisioningState.preflightStartedAt = Date.now();
  provisioningState.preflightElapsedTimer = window.setInterval(() => {
    const sec = Math.floor((Date.now() - provisioningState.preflightStartedAt) / 1000);
    setPreflightStatus(`Checking target… (${sec}s)`, "loading");
  }, 1000);
}

function setPreflightLoading(loading) {
  const btn = $("provCheckTargetBtn");
  const label = $("provCheckTargetBtnLabel");
  const wrap = $("provPreflightLogWrap");
  if (btn) {
    btn.disabled = loading;
    btn.classList.toggle("is-loading", loading);
    btn.setAttribute("aria-busy", loading ? "true" : "false");
  }
  if (label) label.textContent = loading ? "Checking…" : "Check target";
  if (wrap) wrap.classList.toggle("is-active", loading);
  provisioningState.preflightBusy = loading;
}

function validatePreflightInputs() {
  const host = $("provHostInput").value.trim();
  const sshUser = $("provSshUserInput").value.trim();
  const authType = $("provAuthTypeSelect").value;
  if (!host || !sshUser) {
    showToast("Host and SSH user are required to check target");
    return false;
  }
  if (authType === "PASSWORD" && !$("provPasswordInput").value) {
    showToast("SSH password is required to check target");
    return false;
  }
  if (authType === "PRIVATE_KEY" && !$("provKeyInput").value.trim()) {
    showToast("Private key is required to check target");
    return false;
  }
  return true;
}

/** Let the browser paint button loading state before starting async work. */
function afterNextPaint(fn) {
  return new Promise((resolve, reject) => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        Promise.resolve(fn()).then(resolve, reject);
      });
    });
  });
}

function resetProvisioningPreflight() {
  provisioningState.preflight = null;
  stopPreflightElapsedTimer();
  setPreflightLoading(false);
  const status = $("provPreflightStatus");
  if (status) {
    status.hidden = true;
    status.textContent = "";
    status.classList.remove("is-error", "is-ok", "is-loading");
  }
  const logWrap = $("provPreflightLogWrap");
  if (logWrap) {
    logWrap.hidden = true;
    logWrap.classList.remove("is-active");
  }
  clearPreflightLog();
  const select = $("provPgVersionSelect");
  const hint = $("provPgVersionHint");
  if (select) {
    select.disabled = true;
    select.innerHTML = '<option value="">Check target first…</option>';
  }
  if (hint) {
    hint.hidden = false;
    hint.textContent =
      "Run Check target above to load installable versions for this OS.";
  }
}

function pgVersionOptionLabel(v, installedMajor) {
  const base = v.label || `PostgreSQL ${v.major}`;
  if (installedMajor === v.major) {
    return `${base} (already on host)`;
  }
  if (installedMajor && installedMajor > v.major) {
    return `${base} (host has PG ${installedMajor})`;
  }
  return base;
}

function renderPreflightVersions(preflight) {
  const select = $("provPgVersionSelect");
  const hint = $("provPgVersionHint");
  if (!select) return;
  const versions = preflight.installableVersions || [];
  if (versions.length === 0) {
    select.disabled = true;
    select.innerHTML = '<option value="">No installable versions for this OS</option>';
    if (hint) {
      hint.hidden = false;
      hint.textContent = preflight.error || "This OS does not support automated PostgreSQL installation.";
    }
    return;
  }
  const installed = preflight.installedPgMajor;
  select.innerHTML = versions
    .map((v) => {
      const label = pgVersionOptionLabel(v, installed);
      return `<option value="${v.major}">${escapeHtml(label)}</option>`;
    })
    .join("");
  select.disabled = false;
  const preferred =
    versions.find((v) => v.major === 18)?.major ??
    versions[0]?.major;
  if (preferred) {
    select.value = String(preferred);
  }
  if (hint) {
    hint.hidden = false;
    const pkg = versions.find((v) => v.major === Number(select.value, 10))?.packageHint;
    hint.textContent = pkg
      ? `Package on target: ${pkg}. Change version if your app requires a specific major release.`
      : "Choose the PostgreSQL major version to install on the target host.";
  }
}

function setPreflightStatus(message, ok) {
  const el = $("provPreflightStatus");
  if (!el) return;
  el.hidden = !message;
  el.textContent = message || "";
  el.classList.remove("is-error", "is-ok", "is-loading");
  if (ok === false) el.classList.add("is-error");
  else if (ok === true) el.classList.add("is-ok");
  else if (ok === "loading") el.classList.add("is-loading");
}

function parseApiErrorMessage(err) {
  const raw = String(err?.message || err || "Request failed");
  try {
    const jsonStart = raw.indexOf("{");
    if (jsonStart >= 0) {
      const body = JSON.parse(raw.slice(jsonStart));
      if (body.message) return body.message;
      if (body.error) return `${body.error}: ${body.message || ""}`.trim();
    }
  } catch {
    /* use raw */
  }
  return raw;
}

async function onCheckTargetClick() {
  if (provisioningState.retryId) return;
  if (provisioningState.preflightBusy) return;
  if (!validatePreflightInputs()) return;

  setPreflightLoading(true);
  await afterNextPaint(() => checkProvisioningTarget());
}

async function checkProvisioningTarget() {
  const host = $("provHostInput").value.trim();
  const sshUser = $("provSshUserInput").value.trim();
  const sshPort = Number.parseInt($("provSshPortInput").value, 10) || 22;
  const authType = $("provAuthTypeSelect").value;

  provisioningState.preflight = null;
  clearPreflightLog();
  showPreflightLogPanel();
  appendPreflightLog("Check target — starting preflight…");
  const select = $("provPgVersionSelect");
  if (select) {
    select.disabled = true;
    select.innerHTML = '<option value="">Check target first…</option>';
  }

  setPreflightStatus("Checking target… (0s)", "loading");
  startPreflightElapsedTimer();

  appendPreflightLog(`Target: ${host}:${sshPort} user=${sshUser} auth=${authType}`);
  appendPreflightLog("Sending preflight request to server…");
  appendPreflightLog("Server will start provisioning MCP on first run (may take 30–90s).");

  try {
    const result = await api("/api/db-provisioning/preflight", {
      method: "POST",
      body: JSON.stringify({
        host,
        sshPort,
        sshUser,
        authType,
        sshPassword: authType === "PASSWORD" ? $("provPasswordInput").value : null,
        privateKeyPem: authType === "PRIVATE_KEY" ? $("provKeyInput").value : null,
        privateKeyPassphrase: authType === "PRIVATE_KEY" ? $("provPassphraseInput").value || null : null,
      }),
    });
    provisioningState.preflight = result;
    renderPreflightActivityLog(result.activityLog);
    if (result.logExcerpt) {
      appendPreflightLog("--- Remote script output ---");
      result.logExcerpt.split("\n").forEach((line) => {
        if (line.trim()) appendPreflightLog(line);
      });
    }

    if (!result.ok) {
      appendPreflightLog(`FAILED: ${result.error || "Target not supported"}`);
      setPreflightStatus(result.error || "Target not supported for provisioning", false);
      showToast(result.error || "Target check failed");
      return;
    }
    const osLine = result.osVersionLabel || result.osFamily || "unknown";
    let msg = `OS: ${osLine}`;
    if (result.installedPgMajor) {
      msg += ` · existing PostgreSQL ${result.installedPgMajor}`;
    }
    appendPreflightLog(`SUCCESS: ${msg}`);
    setPreflightStatus(msg, true);
    renderPreflightVersions(result);
    showToast("Target check OK — select PostgreSQL version");
  } catch (e) {
    const msg = parseApiErrorMessage(e);
    appendPreflightLog(`ERROR: ${msg}`);
    setPreflightStatus(msg, false);
    provisioningState.preflight = null;
    showToast(msg);
  } finally {
    stopPreflightElapsedTimer();
    setPreflightLoading(false);
  }
}

function selectedPgMajor() {
  const select = $("provPgVersionSelect");
  if (!select || select.disabled || !select.value) {
    return null;
  }
  return Number.parseInt(select.value, 10);
}

function openProvisioningModal() {
  provisioningState.retryId = null;
  resetProvisioningPreflight();
  setProvisioningModalMode("create");
  setProvisioningConfigFieldsDisabled(false);
  const preflightSection = document.querySelector(".prov-preflight-section");
  if (preflightSection) preflightSection.hidden = false;
  const pgGroup = $("provPgVersionGroup");
  if (pgGroup) pgGroup.hidden = false;
  $("provisioningModalBackdrop").hidden = false;
  $("provisioningModal").hidden = false;
  $("provHostInput").focus();
}

function openProvisioningModalForRetry(detail) {
  provisioningState.retryId = detail.id;
  resetProvisioningPreflight();
  setProvisioningModalMode("retry");
  fillProvisioningFormFromDetail(detail);
  setProvisioningConfigFieldsDisabled(true);
  const preflightSection = document.querySelector(".prov-preflight-section");
  if (preflightSection) preflightSection.hidden = true;
  const pgGroup = $("provPgVersionGroup");
  if (pgGroup) pgGroup.hidden = true;
  $("provisioningModalBackdrop").hidden = false;
  $("provisioningModal").hidden = false;
  $("provPasswordInput").focus();
  showToast("Re-enter SSH credentials to retry");
}

function closeProvisioningModal() {
  provisioningState.retryId = null;
  setProvisioningConfigFieldsDisabled(false);
  $("provisioningModalBackdrop").hidden = true;
  $("provisioningModal").hidden = true;
}

function collectExtensions() {
  const exts = [];
  if ($("provExtPgStat").checked) exts.push("pg_stat_statements");
  if ($("provExtAutoExplain").checked) exts.push("auto_explain");
  if ($("provExtPgProfile").checked) exts.push("pg_profile");
  return exts;
}

async function confirmProvisioning() {
  const authType = $("provAuthTypeSelect").value;
  if (provisioningState.retryId) {
    await confirmProvisioningRetry(authType);
    return;
  }

  const host = $("provHostInput").value.trim();
  const sshUser = $("provSshUserInput").value.trim();
  const databaseName = $("provDbNameInput").value.trim();
  const schemaName = $("provSchemaInput").value.trim();
  if (!host || !sshUser || !databaseName || !schemaName) {
    showToast("Host, SSH user, database and schema are required");
    return;
  }
  const pf = provisioningState.preflight;
  if (!pf || !pf.ok) {
    showToast("Run Check target and confirm OS support before creating");
    return;
  }
  const pgMajorVersion = selectedPgMajor();
  if (!pgMajorVersion) {
    showToast("Select a PostgreSQL version to install");
    return;
  }
  const body = {
    title: $("provTitleInput").value.trim() || null,
    host,
    sshPort: Number.parseInt($("provSshPortInput").value, 10) || 22,
    sshUser,
    authType,
    sshPassword: authType === "PASSWORD" ? $("provPasswordInput").value : null,
    privateKeyPem: authType === "PRIVATE_KEY" ? $("provKeyInput").value : null,
    privateKeyPassphrase: authType === "PRIVATE_KEY" ? $("provPassphraseInput").value || null : null,
    memoryMb: Number.parseInt($("provMemoryInput").value, 10) || 512,
    diskGb: Number.parseInt($("provDiskInput").value, 10) || 10,
    databaseName,
    schemaName,
    dbOwnerUser: $("provOwnerInput").value.trim() || null,
    dbOwnerPassword: $("provOwnerPasswordInput").value || null,
    pgMajorVersion,
    osFamily: pf.osFamily,
    osVersionLabel: pf.osVersionLabel || null,
    extensions: collectExtensions(),
  };
  provisioningState.busy = true;
  closeProvisioningModal();
  showToast("Starting DB provisioning…");
  try {
    const created = await api("/api/db-provisioning", {
      method: "POST",
      body: JSON.stringify(body),
    });
    provisioningState.activeId = created.id;
    if (typeof showView === "function") showView("db-provisioning");
    await refreshProvisioningWorkspace(created.id);
    showToast(`Provisioning ${created.id} started`);
  } catch (e) {
    showToast(String(e.message || e));
  } finally {
    provisioningState.busy = false;
  }
}

async function confirmProvisioningRetry(authType) {
  const id = provisioningState.retryId;
  if (authType === "PASSWORD" && !$("provPasswordInput").value) {
    showToast("SSH password is required to retry");
    return;
  }
  if (authType === "PRIVATE_KEY" && !$("provKeyInput").value.trim()) {
    showToast("Private key is required to retry");
    return;
  }
  const body = {
    authType,
    sshPassword: authType === "PASSWORD" ? $("provPasswordInput").value : null,
    privateKeyPem: authType === "PRIVATE_KEY" ? $("provKeyInput").value : null,
    privateKeyPassphrase: authType === "PRIVATE_KEY" ? $("provPassphraseInput").value || null : null,
    dbOwnerPassword: $("provOwnerPasswordInput").value || null,
  };
  provisioningState.busy = true;
  closeProvisioningModal();
  showToast("Retrying provisioning…");
  try {
    const updated = await api(`/api/db-provisioning/${encodeURIComponent(id)}/retry`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    provisioningState.activeId = id;
    if (typeof showView === "function") showView("db-provisioning");
    await loadProvisioningDetail(id);
    await refreshProvisioningWorkspace(id);
    showToast(`Retry started for ${id}`);
  } catch (e) {
    showToast(String(e.message || e));
  } finally {
    provisioningState.busy = false;
  }
}

function wireProvisioning() {
  if (typeof api !== "function") {
    console.error("provisioning.js: api() missing — load app.js before provisioning.js");
    return;
  }

  const modal = provEl("provisioningModal");
  provEl("provAuthTypeSelect")?.addEventListener("change", toggleAuthFields);
  ["provHostInput", "provSshPortInput", "provSshUserInput", "provPasswordInput", "provKeyInput"].forEach((id) => {
    provEl(id)?.addEventListener("input", () => {
      if (!provisioningState.retryId) resetProvisioningPreflight();
    });
  });

  const onCheckClick = (e) => {
    e.preventDefault();
    e.stopPropagation();
    onCheckTargetClick().catch((err) => {
      setPreflightLoading(false);
      stopPreflightElapsedTimer();
      const msg = String(err?.message || err);
      console.error("Check target failed:", err);
      showToast(msg);
    });
  };
  const checkBtn = provEl("provCheckTargetBtn");
  if (checkBtn) {
    checkBtn.addEventListener("click", onCheckClick);
  } else {
    console.error("provisioning.js: #provCheckTargetBtn not found in DOM");
  }
  $("provPgVersionSelect")?.addEventListener("change", () => {
    const pf = provisioningState.preflight;
    const hint = $("provPgVersionHint");
    if (!hint || !pf?.installableVersions) return;
    const major = selectedPgMajor();
    const v = pf.installableVersions.find((x) => x.major === major);
    if (v?.packageHint) {
      hint.textContent = `Package on target: ${v.packageHint}`;
    }
  });
  $("closeProvisioningModal")?.addEventListener("click", closeProvisioningModal);
  $("cancelProvisioningModal")?.addEventListener("click", closeProvisioningModal);
  $("provisioningModalBackdrop")?.addEventListener("click", closeProvisioningModal);
  $("confirmProvisioningModal")?.addEventListener("click", () => {
    confirmProvisioning().catch((e) => showToast(String(e.message || e)));
  });
  $("createDbProvisioningBtn")?.addEventListener("click", () => {
    if (typeof showView === "function") showView("db-provisioning");
    openProvisioningModal();
  });
}

window.addEventListener("DOMContentLoaded", async () => {
  try {
    wireProvisioning();
  } catch (err) {
    console.error("wireProvisioning failed:", err);
    showToast?.(`Provisioning UI failed to initialize: ${err.message}`);
  }
  const authed = await (window.authReady || Promise.resolve(false));
  if (!authed) {
    return;
  }
  refreshProvisioningWorkspace(null).catch((err) => console.error("refreshProvisioningWorkspace:", err));
});
