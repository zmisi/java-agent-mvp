/* global $, api, escapeHtml, showToast, state, abortInFlight, refreshSessions, setComposerState */

const releaseState = {
  activeReleaseId: null,
  view: "chat",
  busy: false,
  uploadedDesignDocPath: null,
};

function statusLabel(status) {
  const map = {
    DRAFT: "Draft",
    IN_REVIEW: "In review",
    PENDING_REVIEW: "Pending review",
    APPROVED: "Approved",
    REJECTED: "Rejected",
    DEPLOYED: "Deployed",
    DEPLOYED_TEST: "Deployed (test)",
    FAILED: "Failed",
    SUCCESS: "Success",
    RUNNING: "Running",
  };
  return map[status] || status;
}

function statusClass(status) {
  return (status || "").toLowerCase();
}

function updateSidebarActiveStates() {
  document.querySelectorAll(".sidebar-list-row[data-id]").forEach((el) => {
    const active = releaseState.view === "chat" && el.dataset.id === state.activeId;
    el.classList.toggle("active", active);
  });
  document.querySelectorAll(".sidebar-list-row[data-release-id]").forEach((el) => {
    const active = releaseState.view === "releases" && el.dataset.releaseId === releaseState.activeReleaseId;
    el.classList.toggle("active", active);
  });
}

function showView(view) {
  releaseState.view = view;
  const appRoot = $("appRoot");
  if (appRoot) {
    appRoot.dataset.workspace = view;
  }

  $("chatView").hidden = view !== "chat";
  $("releasesView").hidden = view !== "releases";

  updateSidebarActiveStates();

  if (view === "chat") {
    setComposerState(!!state.activeId);
  }
}

function renderReleaseSidebarItems(releases, selectId) {
  if (releases.length === 0) {
    return `<div class="sidebar-list-empty">No releases yet</div>`;
  }
  return releases
    .map((r) => {
      const active = releaseState.view === "releases" && r.id === selectId ? " active" : "";
      const label = r.title || r.id;
      return `<div class="sidebar-list-row${active}" data-release-id="${escapeHtml(r.id)}">
        <button type="button" class="sidebar-list-item" data-release-id="${escapeHtml(r.id)}" title="${escapeHtml(statusLabel(r.status))}">
          <span class="sidebar-list-dot" aria-hidden="true"></span>
          <span class="sidebar-list-text">${escapeHtml(label)}</span>
        </button>
      </div>`;
    })
    .join("");
}

function scriptEditable(status) {
  return status === "DRAFT" || status === "REJECTED";
}

function buildWorkflowSteps(detail) {
  const script = detail.scripts && detail.scripts.length > 0 ? detail.scripts[0] : null;
  const scriptStatus = script ? script.status : null;
  const releaseStatus = detail.status;

  const submitted =
    scriptStatus === "PENDING_REVIEW" ||
    scriptStatus === "APPROVED" ||
    scriptStatus === "DEPLOYED" ||
    scriptStatus === "REJECTED" ||
    releaseStatus === "IN_REVIEW";
  const approved =
    scriptStatus === "APPROVED" ||
    scriptStatus === "DEPLOYED" ||
    releaseStatus === "APPROVED" ||
    releaseStatus === "DEPLOYED_TEST";
  const testDeployed = scriptStatus === "DEPLOYED" || releaseStatus === "DEPLOYED_TEST";
  const deployFailed = releaseStatus === "FAILED";

  return [
    { label: "Create release", done: true },
    { label: "Draft SQL", done: !!(script && script.sqlContent) },
    { label: "Submit for review", done: submitted },
    { label: "Approved", done: approved },
    { label: "Deploy to test DB", done: testDeployed, failed: deployFailed && !testDeployed },
  ];
}

function renderWorkflowPipeline(detail) {
  const steps = buildWorkflowSteps(detail);
  const items = steps
    .map((step) => {
      let stateClass = "is-pending";
      if (step.failed) {
        stateClass = "is-failed";
      } else if (step.done) {
        stateClass = "is-done";
      }
      return `<li class="release-workflow-step ${stateClass}">
        <span class="release-workflow-dot" aria-hidden="true"></span>
        <span class="release-workflow-label">${escapeHtml(step.label)}</span>
      </li>`;
    })
    .join("");
  return `<ol class="release-workflow" aria-label="Release workflow">${items}</ol>`;
}

function updateReleaseHeader(detail) {
  const subtitle = $("releaseDetailSubtitle");
  if (!subtitle) {
    return;
  }
  if (!detail) {
    subtitle.textContent = "Select a release from the sidebar";
    return;
  }
  subtitle.textContent = `${detail.id} · ${statusLabel(detail.status)} · ${detail.designDocPath}`;
}

function renderReleaseDetail(detail) {
  const script = detail.scripts && detail.scripts.length > 0 ? detail.scripts[0] : null;
  if (!script) {
    return `<div class="release-workspace-empty">This release has no SQL scripts.</div>`;
  }

  const editable = scriptEditable(script.status);
  const reviewBlock = script.reviewComment
    ? `<div class="review-comment">Review: ${escapeHtml(script.reviewComment)}</div>`
    : "";

  const deployLogs = (detail.deployments || []).length
    ? `<p class="release-section-label">Deployment log</p>${(detail.deployments || [])
        .map(
          (d) => `<div class="deployment-log"><strong>${escapeHtml(d.environment)} · ${escapeHtml(statusLabel(d.status))}</strong>\n${escapeHtml(d.log || "")}</div>`
        )
        .join("")}`
    : "";

  const actionButtons = [];
  if (editable) {
    actionButtons.push(`<button type="button" class="btn btn-new btn-sm" data-action="save-sql">Save SQL</button>`);
    actionButtons.push(`<button type="button" class="btn btn-ghost btn-sm" data-action="submit-review">Submit for review</button>`);
  }
  if (script.status === "PENDING_REVIEW") {
    actionButtons.push(`<button type="button" class="btn btn-new btn-sm" data-action="approve">Approve</button>`);
    actionButtons.push(`<button type="button" class="btn btn-ghost btn-sm" data-action="reject">Reject</button>`);
  }
  if (script.status === "APPROVED") {
    actionButtons.push(`<button type="button" class="btn btn-new btn-sm" data-action="deploy-test">Deploy to test DB</button>`);
  }

  return `
    <div class="release-detail" data-release-id="${escapeHtml(detail.id)}" data-script-id="${script.id}">
      <h3>${escapeHtml(detail.title)}</h3>
      ${renderWorkflowPipeline(detail)}
      <div class="release-meta">
        ${escapeHtml(detail.id)} · Design doc: ${escapeHtml(detail.designDocPath)}
        · Script: ${escapeHtml(script.fileName)}
        · <span class="status-badge ${statusClass(script.status)}">${escapeHtml(statusLabel(script.status))}</span>
      </div>
      ${reviewBlock}
      <p class="release-section-label">SQL script</p>
      <textarea class="sql-editor" id="releaseSqlEditor" ${editable ? "" : "readonly"}>${escapeHtml(script.sqlContent || "")}</textarea>
      <div class="release-actions">${actionButtons.join("")}</div>
      ${deployLogs}
    </div>`;
}

async function refreshReleaseSidebar(releases, selectId) {
  const list = $("releaseSidebarList");
  if (!list) {
    return;
  }
  list.innerHTML = renderReleaseSidebarItems(releases, selectId);
  list.querySelectorAll(".sidebar-list-item[data-release-id]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (state.inFlight) {
        abortInFlight();
      }
      releaseState.activeReleaseId = btn.dataset.releaseId;
      showView("releases");
      await refreshReleaseDetailPanel(releaseState.activeReleaseId);
      updateSidebarActiveStates();
    });
  });
}

async function refreshReleaseDetailPanel(releaseId) {
  const panel = $("releasesPanel");
  if (!panel) {
    return;
  }
  if (!releaseId) {
    updateReleaseHeader(null);
    panel.innerHTML = `<div class="release-workspace-empty">Select a release under <strong>DB Releases</strong>, or click <strong>New DB Release</strong>.</div>`;
    return;
  }

  panel.innerHTML = `<div class="release-workspace-empty">Loading…</div>`;
  try {
    const detail = await api(`/api/releases/${encodeURIComponent(releaseId)}`);
    updateReleaseHeader(detail);
    panel.innerHTML = renderReleaseDetail(detail);
    wireReleaseDetailActions(panel);
  } catch (e) {
    updateReleaseHeader(null);
    panel.innerHTML = `<div class="release-workspace-empty">${escapeHtml(String(e.message || e))}</div>`;
  }
}

async function refreshReleasesWorkspace(selectId) {
  try {
    const releases = await api("/api/releases");
    const activeId = selectId || releaseState.activeReleaseId || null;
    releaseState.activeReleaseId = activeId;
    await refreshReleaseSidebar(releases, activeId);
    if (releaseState.view === "releases") {
      await refreshReleaseDetailPanel(activeId);
    }
    updateSidebarActiveStates();
  } catch (e) {
    $("releaseSidebarList").innerHTML = `<div class="sidebar-list-empty">${escapeHtml(String(e.message || e))}</div>`;
    if (releaseState.view === "releases") {
      $("releasesPanel").innerHTML = `<div class="release-workspace-empty">${escapeHtml(String(e.message || e))}</div>`;
    }
  }
}

function wireReleaseDetailActions(panel) {
  const detail = panel.querySelector(".release-detail[data-script-id]");
  if (!detail) {
    return;
  }
  const releaseId = detail.dataset.releaseId;
  const scriptId = detail.dataset.scriptId;

  detail.querySelector('[data-action="save-sql"]')?.addEventListener("click", async () => {
    if (releaseState.busy) return;
    releaseState.busy = true;
    try {
      const sqlContent = $("releaseSqlEditor").value;
      await api(`/api/releases/${encodeURIComponent(releaseId)}/scripts/${scriptId}`, {
        method: "PUT",
        body: JSON.stringify({ sqlContent }),
      });
      showToast("SQL saved");
      await refreshReleasesWorkspace(releaseId);
    } catch (e) {
      showToast(String(e.message || e));
    } finally {
      releaseState.busy = false;
    }
  });

  detail.querySelector('[data-action="submit-review"]')?.addEventListener("click", async () => {
    if (releaseState.busy) return;
    releaseState.busy = true;
    try {
      await api(`/api/releases/${encodeURIComponent(releaseId)}/scripts/${scriptId}/submit-review`, {
        method: "POST",
      });
      showToast("Submitted for review");
      await refreshReleasesWorkspace(releaseId);
    } catch (e) {
      showToast(String(e.message || e));
    } finally {
      releaseState.busy = false;
    }
  });

  detail.querySelector('[data-action="approve"]')?.addEventListener("click", async () => {
    if (releaseState.busy) return;
    const comment = window.prompt("Review comment (optional)", "") ?? "";
    releaseState.busy = true;
    try {
      await api(`/api/releases/${encodeURIComponent(releaseId)}/scripts/${scriptId}/review`, {
        method: "POST",
        body: JSON.stringify({ action: "approve", comment: comment.trim() || null }),
      });
      showToast("Approved");
      await refreshReleasesWorkspace(releaseId);
    } catch (e) {
      showToast(String(e.message || e));
    } finally {
      releaseState.busy = false;
    }
  });

  detail.querySelector('[data-action="reject"]')?.addEventListener("click", async () => {
    if (releaseState.busy) return;
    const comment = window.prompt("Rejection reason", "");
    if (comment == null) return;
    releaseState.busy = true;
    try {
      await api(`/api/releases/${encodeURIComponent(releaseId)}/scripts/${scriptId}/review`, {
        method: "POST",
        body: JSON.stringify({ action: "reject", comment: comment.trim() || "Changes required" }),
      });
      showToast("Rejected");
      await refreshReleasesWorkspace(releaseId);
    } catch (e) {
      showToast(String(e.message || e));
    } finally {
      releaseState.busy = false;
    }
  });

  detail.querySelector('[data-action="deploy-test"]')?.addEventListener("click", async () => {
    if (releaseState.busy) return;
    if (!window.confirm("Deploy approved SQL to the test database (emp_test)?")) return;
    releaseState.busy = true;
    try {
      await api(`/api/releases/${encodeURIComponent(releaseId)}/deploy-test`, { method: "POST" });
      showToast("Deployed to test DB");
      await refreshReleasesWorkspace(releaseId);
    } catch (e) {
      showToast(String(e.message || e));
      await refreshReleasesWorkspace(releaseId);
    } finally {
      releaseState.busy = false;
    }
  });
}

async function uploadDesignDoc(file) {
  if (!file) {
    throw new Error("No file selected");
  }
  const form = new FormData();
  form.append("file", file);
  const res = await fetch("/api/design-docs/upload", {
    method: "POST",
    body: form,
  });
  if (!res.ok) {
    const errBody = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText}${errBody ? `\n${errBody}` : ""}`);
  }
  return await res.json();
}

function defaultReleaseTitle() {
  const now = new Date();
  const yyyy = now.getFullYear();
  const mm = String(now.getMonth() + 1).padStart(2, "0");
  const dd = String(now.getDate()).padStart(2, "0");
  const day = `${yyyy}${mm}${dd}`;
  const suffix =
    typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID().replace(/-/g, "").substring(0, 6)
      : Math.random().toString(36).substring(2, 8);
  return `REL-${day}-${suffix}`;
}

async function fetchSuggestedReleaseTitle() {
  try {
    const res = await api("/api/releases/suggested-id");
    return res.suggestedId || defaultReleaseTitle();
  } catch {
    return defaultReleaseTitle();
  }
}

function updateReleaseModalCreateState() {
  const btn = $("confirmReleaseModal");
  const title = $("releaseTitleInput")?.value.trim() || "";
  if (btn) {
    btn.disabled = !releaseState.uploadedDesignDocPath || !title || releaseState.busy;
  }
}

function resetReleaseModalForm() {
  releaseState.uploadedDesignDocPath = null;
  $("releaseTitleInput").value = "";
  setDesignDocUploadStatus("");
  const text = $("designDocDropzone")?.querySelector(".upload-dropzone-text");
  if (text) {
    text.textContent = "Drop a .md file here, or click to browse";
  }
  updateReleaseModalCreateState();
}

function setDesignDocUploadStatus(message, isError) {
  const el = $("designDocUploadStatus");
  if (!el) return;
  if (!message) {
    el.hidden = true;
    el.textContent = "";
    return;
  }
  el.hidden = false;
  el.textContent = message;
  el.style.color = isError ? "var(--danger)" : "var(--text-muted)";
}

function closeReleaseModal() {
  $("releaseModalBackdrop").hidden = true;
  $("releaseModal").hidden = true;
  resetReleaseModalForm();
}

async function openReleaseModal() {
  resetReleaseModalForm();
  $("releaseModalBackdrop").hidden = false;
  $("releaseModal").hidden = false;
  $("releaseTitleInput").value = await fetchSuggestedReleaseTitle();
  updateReleaseModalCreateState();
  $("releaseTitleInput").focus();
}

async function handleDesignDocUpload(file) {
  if (!file) return null;
  if (!file.name.toLowerCase().endsWith(".md")) {
    setDesignDocUploadStatus("Only .md files are supported", true);
    return null;
  }
  setDesignDocUploadStatus("Uploading…", false);
  try {
    const uploaded = await uploadDesignDoc(file);
    releaseState.uploadedDesignDocPath = uploaded.relativePath;
    setDesignDocUploadStatus(`Uploaded as ${uploaded.relativePath}`, false);
    const text = $("designDocDropzone")?.querySelector(".upload-dropzone-text");
    if (text) {
      text.textContent = uploaded.fileName || uploaded.relativePath;
    }
    updateReleaseModalCreateState();
    return uploaded.relativePath;
  } catch (e) {
    releaseState.uploadedDesignDocPath = null;
    setDesignDocUploadStatus(String(e.message || e), true);
    updateReleaseModalCreateState();
    return null;
  }
}

function wireDesignDocUpload() {
  const dropzone = $("designDocDropzone");
  const modalInput = $("releaseDesignDocUpload");
  dropzone.addEventListener("click", () => {
    modalInput.value = "";
    modalInput.click();
  });
  dropzone.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" || ev.key === " ") {
      ev.preventDefault();
      modalInput.click();
    }
  });
  modalInput.addEventListener("change", async () => {
    const file = modalInput.files && modalInput.files[0];
    await handleDesignDocUpload(file);
  });

  dropzone.addEventListener("dragover", (ev) => {
    ev.preventDefault();
    dropzone.classList.add("is-dragover");
  });
  dropzone.addEventListener("dragleave", () => {
    dropzone.classList.remove("is-dragover");
  });
  dropzone.addEventListener("drop", async (ev) => {
    ev.preventDefault();
    dropzone.classList.remove("is-dragover");
    const file = ev.dataTransfer?.files?.[0];
    await handleDesignDocUpload(file);
  });

  $("closeReleaseModal").addEventListener("click", closeReleaseModal);
  $("cancelReleaseModal").addEventListener("click", closeReleaseModal);
  $("releaseModalBackdrop").addEventListener("click", closeReleaseModal);
  $("confirmReleaseModal").addEventListener("click", () => {
    confirmCreateRelease().catch((e) => showToast(String(e.message || e)));
  });
  $("releaseTitleInput").addEventListener("input", updateReleaseModalCreateState);
}

async function confirmCreateRelease() {
  const designDocPath = releaseState.uploadedDesignDocPath;
  if (!designDocPath) {
    showToast("Upload a design document first");
    return;
  }
  const title = $("releaseTitleInput").value.trim();
  if (!title) {
    showToast("Release title is required");
    return;
  }

  releaseState.busy = true;
  updateReleaseModalCreateState();
  closeReleaseModal();
  showToast("Generating draft SQL from design doc…");
  try {
    const created = await api("/api/releases", {
      method: "POST",
      body: JSON.stringify({ designDocPath, title }),
    });
    releaseState.activeReleaseId = created.id;
    showView("releases");
    showToast(`Created release ${created.id}`);
    await refreshReleasesWorkspace(created.id);
  } catch (e) {
    showToast(String(e.message || e));
  } finally {
    releaseState.busy = false;
  }
}

async function openCreateReleaseDialog() {
  openReleaseModal();
}

function wireReleases() {
  wireDesignDocUpload();

  $("createReleaseBtn").addEventListener("click", async () => {
    if (state.inFlight) {
      abortInFlight();
    }
    showView("releases");
    openCreateReleaseDialog();
  });
}

window.addEventListener("DOMContentLoaded", () => {
  refreshReleasesWorkspace(null).catch(() => {
    /* sidebar list loads on startup; ignore if API unavailable */
  });
});
