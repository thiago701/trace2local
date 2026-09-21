/* TraceVanta UI — vanilla JS, zero dependência externa (ADR-005).
   Um único EventSource multiplexa todos os tipos de evento (ADR-004). */
"use strict";

const BASE = location.pathname.endsWith("/") ? location.pathname.slice(0, -1) : location.pathname;
const API = BASE + "/api";

/* ------------------------------------------------------------- estado */
const state = {
  meta: null,
  endpoints: [],
  activeEndpointId: null,
  executions: new Map(),   // executionId -> {summary, nodes: Map, completed, warnings}
  selectedExecutionId: null,
  selectedNodeId: null,
  lastEventId: null,
  activeTab: "canvas",
  pendingDeepLink: null,
  compare: { a: null, b: null },
  notesOn: false,
  story: null,
  pulseNodeId: null,
  view: { x: 60, y: 40, scale: 1 },
  dragging: false,
  dragStart: null,
};
window.__tvState = state; // auxílio de debug/evidência (removível)

const $ = (id) => document.getElementById(id);
const kindTag = { HTTP_SERVER: "HTTP", HTTP_CLIENT: "HTTP", BUSINESS: "◆", DYNAMODB: "DDB", SQS: "SQS", SNS: "SNS", SQL: "SQL", LAMBDA: "λ", UNKNOWN: "?" };
const kindColor = { HTTP_SERVER: "#7cc4f2", HTTP_CLIENT: "#7cc4f2", BUSINESS: "#cf9ef2", DYNAMODB: "#8ee6a8", SQS: "#f2d98e", SNS: "#f2a24e", SQL: "#66d9d0", LAMBDA: "#f2a0c0", UNKNOWN: "#7f8ea0" };
const kindName = { HTTP_SERVER: "HTTP server", HTTP_CLIENT: "HTTP client", BUSINESS: "Business", DYNAMODB: "DynamoDB", SQS: "SQS", SNS: "SNS", SQL: "SQL", LAMBDA: "Lambda", UNKNOWN: "?" };
const triggerLabel = { UI_DISPATCH: "UI", EXTERNAL: "EXTERNAL", LAMBDA_EVENT: "LAMBDA", TEST: "TEST" };
const statusLabel = { COMPLETED: "OK", FAILED: "FALHOU", PARTIAL: "PARCIAL", ORPHANED: "ÓRFÃO", RUNNING: "EM CURSO" };

/** Tempo relativo humanizado ("há 2 min") — sem dependência externa. */
function fmtRelativeTime(iso) {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return "agora";
  if (s < 3600) return "há " + Math.round(s / 60) + " min";
  if (s < 86400) return "há " + Math.round(s / 3600) + " h";
  return "há " + Math.round(s / 86400) + " d";
}

/** Toast não bloqueante (substitui alert()) — auto-dismiss. */
function toast(message, type) {
  const box = document.createElement("div");
  box.className = "toast" + (type ? " " + type : "");
  box.textContent = message;
  $("toasts").appendChild(box);
  setTimeout(() => {
    box.classList.add("out");
    setTimeout(() => box.remove(), 350);
  }, 4200);
}

/* ------------------------------------------------------------- API */
async function api(path, opts) {
  const res = await fetch(API + path, opts);
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(res.status + " " + body.slice(0, 300));
  }
  return res;
}

async function loadMeta() {
  try {
    state.meta = await (await api("/meta")).json();
    $("env-app").textContent = "app: " + (state.meta.app || "?");
    $("env-mode").textContent = "mode: " + (state.meta.mode || "?");
    document.title = "TraceVanta — " + (state.meta.app || "");
  } catch (e) {
    $("env-app").textContent = "app: offline";
  }
}

async function loadEndpoints() {
  try {
    const list = await (await api("/endpoints")).json();
    state.endpoints = list || [];
    renderEndpoints();
  } catch (e) { /* sem catálogo: UI vira somente-observação (SPEC §4.8) */ }
}

async function loadRecent() {
  try {
    const list = await (await api("/executions?limit=50")).json();
    for (const summary of list) {
      const existing = state.executions.get(summary.executionId);
      if (!existing) {
        state.executions.set(summary.executionId, { summary, nodes: new Map(), completed: true, warnings: [] });
      } else {
        existing.summary = summary;
        existing.completed = true;
      }
    }
    renderRecent();
    // deep link: ?execution=<id> abre direto na execução (compartilhável)
    if (state.pendingDeepLink && state.executions.has(state.pendingDeepLink)) {
      const id = state.pendingDeepLink;
      state.pendingDeepLink = null;
      selectExecution(id);
    }
  } catch (e) { /* ignore */ }
}

let inFlightExecution = null;

function executeRequest(ep) {
  const bodyText = $("ep-body-" + cssEscape(ep.endpointId)).value;
  let body = null;
  if (bodyText && bodyText.trim()) {
    try { body = JSON.parse(bodyText); } catch (e) {
      toast("JSON inválido no corpo: " + e.message, "error");
      return;
    }
  }
  // SPEC §4.9: um disparo por vez por sessão de UI — o segundo clique troca a
  // visualização para a nova execução; a anterior continua no servidor.
  inFlightExecution = null;
  const btn = $("btn-exec-" + cssEscape(ep.endpointId));
  document.querySelectorAll("[id^=btn-exec-]").forEach((b) => { b.disabled = true; });
  btn.textContent = "DISPATCHING…";
  api("/execute", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ endpointId: ep.endpointId, body: body, headers: {}, pathVariables: {} }),
  })
    .then(async (res) => {
      const result = await res.json();
      inFlightExecution = result.executionId;
      selectExecution(result.executionId);
    })
    .catch((e) => toast("Falha no disparo: " + e.message, "error"))
    .finally(() => {
      btn.textContent = "▶ EXECUTE REQUEST";
      document.querySelectorAll("[id^=btn-exec-]").forEach((b) => { b.disabled = false; });
    });
}

/* ------------------------------------------------------------- render: endpoints */
function cssEscape(id) {
  return String(id).replace(/[^a-zA-Z0-9-]/g, "_");
}

function renderEndpoints() {
  const container = $("endpoints");
  container.innerHTML = "";
  if (!state.endpoints.length) {
    container.innerHTML = '<p class="sidebar-note">Nenhum endpoint descoberto — a UI opera em modo somente-observação.</p>';
    return;
  }
  for (const ep of state.endpoints) {
    const item = document.createElement("div");
    item.className = "endpoint";
    item.setAttribute("role", "listitem");
    item.innerHTML =
      '<div class="ep-head" data-id="' + esc(ep.endpointId) + '">' +
        '<span class="method ' + esc(ep.method) + '">' + esc(ep.method) + "</span>" +
        '<span class="ep-path">' + esc(ep.path) + "</span>" +
        '<span class="ep-arrow">▾</span>' +
      "</div>" +
      '<div class="ep-body hidden">' +
        (ep.requestSchema ? "" : '<div class="ep-schema-warning">Schema não inferido — corpo vazio editável (nunca um schema inventado).</div>') +
        '<label for="ep-body-' + cssEscape(ep.endpointId) + '">Request body (JSON)</label>' +
        '<textarea id="ep-body-' + cssEscape(ep.endpointId) + '" spellcheck="false">' +
          esc(ep.sampleBody || "{}") +
        "</textarea>" +
        '<div class="ep-actions">' +
          '<button type="button" class="primary" id="btn-exec-' + cssEscape(ep.endpointId) + '">▶ EXECUTE REQUEST</button>' +
        "</div>" +
      "</div>";
    container.appendChild(item);

    const head = item.querySelector(".ep-head");
    head.addEventListener("click", () => {
      const body = item.querySelector(".ep-body");
      body.classList.toggle("hidden");
      const wasActive = state.activeEndpointId === ep.endpointId;
      state.activeEndpointId = wasActive ? null : ep.endpointId;
      document.querySelectorAll(".endpoint").forEach((e) => e.classList.remove("active"));
      if (state.activeEndpointId) { item.classList.add("active"); }
    });
    item.querySelector(".primary").addEventListener("click", () => executeRequest(ep));
  }
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ------------------------------------------------------------- render: recent */
function maxDurationMs() {
  let m = 0;
  for (const exec of state.executions.values()) {
    const d = exec.summary && exec.summary.duration;
    if (typeof d === "number" && d > m) m = d;
  }
  return m;
}

function renderRecent() {
  const container = $("recent");
  container.innerHTML = "";
  const filter = (state.recentFilter || "").trim().toLowerCase();
  const all = [...state.executions.values()].sort((a, b) => {
    const at = (x) => (x.summary && x.summary.startedAt) || "";
    return at(b).localeCompare(at(a));
  });
  const maxDur = Math.max(1, maxDurationMs());
  let shown = 0;
  for (const exec of all) {
    const s = exec.summary;
    if (!s) continue;
    if (filter) {
      const hay = [s.rootLabel, s.executionId, s.status, s.trigger, triggerLabel[s.trigger]]
        .join(" ").toLowerCase();
      if (!hay.includes(filter)) continue;
    }
    shown++;
    const item = document.createElement("div");
    item.className = "exec-card"
      + (state.selectedExecutionId === s.executionId ? " selected" : "")
      + (s.status === "FAILED" ? " failed" : "");
    item.setAttribute("role", "listitem");
    item.innerHTML =
      '<div class="row1">' +
        '<span class="st ' + esc(s.status) + '" title="' + esc(s.status) + '"></span>' +
        '<span class="root-label">' + esc(s.rootLabel || s.executionId) + "</span>" +
        '<span class="status-badge ' + esc(s.status) + '">' + esc(statusLabel[s.status] || s.status) + "</span>" +
      "</div>" +
      '<div class="row2">' +
        '<span class="chip ' + esc(s.trigger || "EXTERNAL") + '">' + esc(triggerLabel[s.trigger] || s.trigger || "EXTERNAL") + "</span>" +
        '<span class="exec-id" title="' + esc(s.executionId) + '">' + esc(s.executionId) + "</span>" +
        '<span class="rel">' + esc(fmtDuration(s.duration)) + " · " + (s.nodeCount || 0) + " nós · " + esc(fmtRelativeTime(s.startedAt)) + "</span>" +
      "</div>" +
      '<div class="dur-bar"><i></i></div>';
    const durMs = typeof s.duration === "number" ? s.duration : 0;
    const pct = durMs > 0 ? Math.min(100, Math.max(2, (durMs / maxDur) * 100)) : 0;
    item.querySelector(".dur-bar i").style.width = pct + "%";
    item.addEventListener("click", () => selectExecution(s.executionId));
    container.appendChild(item);
  }
  const cnt = $("recent-count");
  if (all.length > 0) {
    cnt.classList.remove("hidden");
    cnt.textContent = String(all.length);
  } else {
    cnt.classList.add("hidden");
  }
  if (shown === 0) {
    container.innerHTML = '<div class="empty-note">'
      + (filter ? "Nenhuma execução casa com o filtro." : "Nenhuma execução ainda — dispare a aplicação ou use um endpoint.")
      + "</div>";
  }
}

function fmtDuration(d) {
  if (d === null || d === undefined) return "—";
  // servidor serializa Duration como milissegundos (número)
  const ms = typeof d === "number" ? d : (Number(d.seconds || 0) * 1000 + Number(d.nano || 0) / 1e6);
  return ms < 1000 ? Math.round(ms) + "ms" : (ms / 1000).toFixed(2) + "s";
}

/* ------------------------------------------------------------- execução */
async function selectExecution(executionId) {
  state.selectedExecutionId = executionId;
  state.selectedNodeId = null;
  state.story = null; // narrativa é por execução — invalida o cache
  state.fittedFor = null; // novo contexto: re-encaixar a árvore na primeira renderização
  // deep link compartilhável: ?execution=<id> reflete a seleção sem recarregar
  try {
    history.replaceState(null, "", "?execution=" + encodeURIComponent(executionId));
  } catch (e) { /* ignore */ }
  renderRecent();
  const exec = state.executions.get(executionId);
  if (!exec || !exec.completed) {
    // ainda viva ou desconhecida — o SSE alimenta; mostra o que temos
    renderCanvas();
    return;
  }
  $("canvas-loading").classList.remove("hidden");
  try {
    const full = await (await api("/executions/" + encodeURIComponent(executionId))).json();
    ingestExecution(full, true);
    renderCanvas();
    $("btn-export").disabled = false;
  } catch (e) { /* execução expirou do acervo em memória */ }
  finally {
    $("canvas-loading").classList.add("hidden");
  }
}

/* ------------------------------------------------------------- ingest */
function ingestExecution(execution, fromSnapshot) {
  const existing = state.executions.get(execution.executionId) || { nodes: new Map(), warnings: [] };
  existing.summary = {
    executionId: execution.executionId, traceId: execution.traceId, status: execution.status,
    trigger: execution.trigger, startedAt: execution.startedAt, duration: execution.duration,
    rootLabel: execution.roots && execution.roots[0] ? execution.roots[0].label : null,
    nodeCount: execution.metrics ? execution.metrics.nodeCount : 0,
  };
  existing.warnings = execution.warnings || [];
  existing.completed = execution.status !== "RUNNING";
  existing.nodes = new Map();
  const collect = (nodes) => {
    for (const n of nodes) {
      existing.nodes.set(n.nodeId, n);
      if (n.children) collect(n.children);
    }
  };
  collect(execution.roots || []);
  state.executions.set(execution.executionId, existing);
  if (!state.selectedExecutionId && state.executions.size === 1) {
    state.selectedExecutionId = execution.executionId;
  }
  if (execution.executionId === state.selectedExecutionId) {
    $("btn-export").disabled = !existing.completed;
  }
  // a lista de execuções recentes SEMPRE reflete o acervo (independente da seleção)
  renderRecent();
  if (state.activeTab === "dashboard") {
    renderDashboard();
  }
  if (fromSnapshot || state.selectedExecutionId === execution.executionId) {
    renderCanvas();
  }
  renderWarnings();
}

function upsertNode(event) {
  const exec = state.executions.get(event.executionId) || { nodes: new Map(), warnings: [], completed: false };
  exec.nodes.set(event.node.nodeId, event.node);
  state.executions.set(event.executionId, exec);
  if (!state.selectedExecutionId) { state.selectedExecutionId = event.executionId; }
  if (state.selectedExecutionId === event.executionId) { renderCanvas(); }
}

function applyMutation(event) {
  const exec = state.executions.get(event.executionId);
  const node = exec && exec.nodes.get(event.nodeId);
  if (node) {
    node.mutation = event.mutation;
    if (state.selectedExecutionId === event.executionId) { renderCanvas(); }
    if (state.selectedNodeId === event.nodeId) { renderInspector(); }
  }
}

function renderWarnings() {
  const all = [];
  for (const exec of state.executions.values()) {
    for (const w of exec.warnings || []) { all.push(w); }
  }
  const section = $("warnings-section");
  if (!all.length) { section.classList.add("hidden"); return; }
  section.classList.remove("hidden");
  $("warnings").innerHTML = all.map((w) =>
    '<div class="warning-item" role="listitem">⚠ ' + esc(w.message) + "</div>").join("");
}

/* ------------------------------------------------------------- tabs: dashboard e comparação */
function switchTab(tab) {
  state.activeTab = tab;
  $("canvas").classList.toggle("hidden", tab !== "canvas");
  $("dashboard-view").classList.toggle("hidden", tab !== "dashboard");
  $("compare-view").classList.toggle("hidden", tab !== "compare");
  $("story-view").classList.toggle("hidden", tab !== "story");
  ["canvas", "dashboard", "compare", "story"].forEach((t) =>
    $("tab-" + t).classList.toggle("active", t === tab));
  if (tab === "dashboard") renderDashboard();
  if (tab === "compare") renderCompare();
  if (tab === "story") renderStory();
}

function executionList() {
  return [...state.executions.values()]
    .map((e) => e.summary)
    .filter(Boolean)
    .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || ""));
}

function renderDashboard() {
  const container = $("dashboard-view");
  const all = executionList();
  if (!all.length) {
    container.innerHTML = '<h2>DASHBOARD</h2><div class="empty-note dash">' +
      "Nenhuma execução ainda — dispare a aplicação e os agregados aparecem aqui.</div>";
    return;
  }
  const finished = all.filter((s) => s.status !== "RUNNING");
  const failed = finished.filter((s) => s.status === "FAILED");
  const partial = finished.filter((s) => s.status === "PARTIAL" || s.status === "ORPHANED");
  const total = finished.length || 1;
  const avgMs = finished.reduce((acc, s) => acc + (typeof s.duration === "number" ? s.duration : 0), 0) / total;
  const nodes = finished.reduce((acc, s) => acc + (s.nodeCount || 0), 0);
  const warnings = state.executions.size > 0
    ? [...state.executions.values()].reduce((acc, e) => acc + (e.warnings || []).length, 0)
    : 0;
  const slowest = [...finished].sort((a, b) => (b.duration || 0) - (a.duration || 0)).slice(0, 5);
  const byTrigger = {};
  for (const s of finished) {
    const t = s.trigger || "EXTERNAL";
    byTrigger[t] = (byTrigger[t] || 0) + 1;
  }

  container.innerHTML =
    '<h2>DASHBOARD <span class="pill">visão geral do acervo</span></h2>' +
    '<div class="stat-grid">' +
      statCard("EXECUÇÕES", String(finished.length), "sub", "concluídas no acervo", "ok") +
      statCard("FALHAS", String(failed.length), "sub", pct(failed.length, total) + " · " + partial.length + " parcial(is)", failed.length ? "err" : "ok") +
      statCard("DURAÇÃO MÉDIA", fmtDuration(avgMs), "sub", "por execução concluída", "") +
      statCard("NÓS OBSERVADOS", String(nodes), "sub", "total no acervo", "") +
      statCard("AVISOS", String(warnings), "sub", "honestidade ativa (I1–I3)", warnings ? "err" : "") +
    "</div>" +
    '<div class="dash-section"><h3>MAIS LENTAS (TOP 5)</h3>' +
      slowest.map((s) => dashRow(s, fmtDuration(s.duration))).join("") +
    "</div>" +
    '<div class="dash-section"><h3>FALHAS RECENTES</h3>' +
      (failed.length
        ? failed.slice(0, 8).map((s) => dashRow(s, statusLabel[s.status] || s.status, "bad")).join("")
        : '<div class="empty-note dash">Nenhuma falha registrada 🎉</div>') +
    "</div>" +
    '<div class="dash-section"><h3>POR TRIGGER</h3><div class="stat-grid">' +
      Object.keys(byTrigger).map((t) =>
        statCard(triggerLabel[t] || t, String(byTrigger[t]), "sub", "execuções", "")).join("") +
    "</div></div>";

  container.querySelectorAll(".dash-row").forEach((row) => {
    row.addEventListener("click", () => {
      switchTab("canvas");
      selectExecution(row.dataset.executionId);
    });
  });
}

function statCard(label, value, subClass, sub, tone) {
  return '<div class="stat-card"><div class="label">' + esc(label) + '</div>' +
    '<div class="value ' + tone + '">' + esc(value) + "</div>" +
    (sub ? '<div class="' + subClass + '">' + esc(sub) + "</div>" : "") + "</div>";
}

function dashRow(s, value, valueTone) {
  return '<div class="dash-row" data-execution-id="' + esc(s.executionId) + '">' +
    '<span class="st ' + esc(s.status) + '"></span>' +
    '<span class="root-label">' + esc(s.rootLabel || s.executionId) + "</span>" +
    '<span class="chip ' + esc(s.trigger || "EXTERNAL") + '">' + esc(triggerLabel[s.trigger] || "EXTERNAL") + "</span>" +
    '<span class="val' + (valueTone ? " " + valueTone : "") + '">' + esc(value) + "</span>" +
  "</div>";
}

function pct(part, whole) {
  return Math.round((part / whole) * 100) + "%";
}

/* ------------------------------------------------------------- comparar (A/B diff) */
function renderCompare() {
  const container = $("compare-view");
  const all = executionList();
  if (all.length < 2) {
    container.innerHTML = '<h2>COMPARAR</h2><div class="empty-note dash">' +
      "São necessárias pelo menos 2 execuções concluídas para comparar.</div>";
    return;
  }
  const options = all.map((s) =>
    '<option value="' + esc(s.executionId) + '">' +
    esc(shortId(s.executionId)) + " · " + esc(s.rootLabel || "?") + " · " +
    esc(statusLabel[s.status] || s.status) + " · " + esc(fmtDuration(s.duration)) + "</option>").join("");
  state.compare.a = state.compare.a && state.executions.has(state.compare.a) ? state.compare.a : all[1].executionId;
  state.compare.b = state.compare.b && state.executions.has(state.compare.b) ? state.compare.b : all[0].executionId;

  container.innerHTML =
    '<h2>COMPARAR EXECUÇÕES <span class="pill">diff de spans, durações e mutações</span></h2>' +
    '<div class="compare-bar">' +
      '<select id="cmp-a" aria-label="Execução A">' + options + "</select>" +
      '<span class="vs">VS</span>' +
      '<select id="cmp-b" aria-label="Execução B">' + options + "</select>" +
      '<button type="button" class="primary" id="btn-compare">COMPARAR</button>' +
    "</div>" +
    '<div id="compare-result"></div>';

  $("cmp-a").value = state.compare.a;
  $("cmp-b").value = state.compare.b;
  $("cmp-a").addEventListener("change", (e) => { state.compare.a = e.target.value; });
  $("cmp-b").addEventListener("change", (e) => { state.compare.b = e.target.value; });
  $("btn-compare").addEventListener("click", () => runCompare(state.compare.a, state.compare.b));
  runCompare(state.compare.a, state.compare.b);
}

async function runCompare(idA, idB) {
  const result = $("compare-result");
  if (!idA || !idB || idA === idB) {
    result.innerHTML = '<div class="empty-note dash">Escolha duas execuções diferentes.</div>';
    return;
  }
  result.innerHTML = '<div class="empty-note dash">carregando…</div>';
  const execA = await loadFull(idA);
  const execB = await loadFull(idB);
  if (!execA || !execB) {
    result.innerHTML = '<div class="empty-note dash">Execução não encontrada no acervo.</div>';
    return;
  }

  // spans por rótulo (recorrência conta: "label ×n")
  const labelCount = (nodes) => {
    const m = new Map();
    const walk = (list) => {
      for (const n of list) {
        const key = n.label || "?";
        m.set(key, (m.get(key) || 0) + 1);
        walk(n.children || []);
      }
    };
    walk(nodes);
    return m;
  };
  const spanA = labelCount(execA.roots || []);
  const spanB = labelCount(execB.roots || []);
  const durOf = (nodes) => {
    const m = new Map();
    const walk = (list) => {
      for (const n of list) {
        const ms = typeof n.totalTime === "number" ? n.totalTime : 0;
        m.set(n.label || "?", ms);
        walk(n.children || []);
      }
    };
    walk(nodes);
    return m;
  };
  const durA = durOf(execA.roots || []);
  const durB = durOf(execB.roots || []);
  const mutKeys = (nodes) => {
    const out = [];
    const walk = (list) => {
      for (const n of list) {
        if (n.mutation && n.mutation.key) out.push(n.mutation.key);
        walk(n.children || []);
      }
    };
    walk(nodes);
    return out;
  };
  const keysA = mutKeys(execA.roots || []);
  const keysB = mutKeys(execB.roots || []);

  const labels = new Set([...spanA.keys(), ...spanB.keys()]);
  const rows = [];
  for (const label of [...labels].sort()) {
    const ca = spanA.get(label) || 0;
    const cb = spanB.get(label) || 0;
    const da = durA.get(label) || 0;
    const db = durB.get(label) || 0;
    const delta = db - da;
    let tag = "same";
    let deltaHtml = "—";
    if (ca && !cb) {
      tag = "gone";
      deltaHtml = '<span class="tag gone">REMOVIDO</span>';
    } else if (!ca && cb) {
      tag = "added";
      deltaHtml = '<span class="tag new">NOVO</span>';
    } else if (delta !== 0) {
      const cls = delta > 0 ? "delta-bad" : "delta-good";
      deltaHtml = '<span class="' + cls + '">' + (delta > 0 ? "+" : "") + fmtDuration(Math.abs(delta)) + "</span>";
    } else {
      deltaHtml = '<span class="tag same">=</span>';
    }
    rows.push('<tr class="' + tag + '"><td>' + esc(label) + "</td>" +
      '<td class="mono">' + esc(ca ? fmtDuration(da) + " ×" + ca : "—") + "</td>" +
      '<td class="mono">' + esc(cb ? fmtDuration(db) + " ×" + cb : "—") + "</td>" +
      "<td>" + deltaHtml + "</td></tr>");
  }

  const durDelta = (execB.duration || 0) - (execA.duration || 0);
  const addedMutations = keysB.filter((k) => !keysA.includes(k));
  const removedMutations = keysA.filter((k) => !keysB.includes(k));

  result.innerHTML =
    '<div class="compare-summary">' +
      '<div class="cs-item">duração A <strong>' + esc(fmtDuration(execA.duration)) + "</strong></div>" +
      '<div class="cs-item">duração B <strong>' + esc(fmtDuration(execB.duration)) + "</strong></div>" +
      '<div class="cs-item">Δ <strong class="' + (durDelta > 0 ? "delta-bad" : "delta-good") + '">' +
        (durDelta > 0 ? "+" : "") + esc(fmtDuration(Math.abs(durDelta))) + "</strong>" +
        " (" + (durDelta > 0 ? "+" : "") + pct(Math.abs(durDelta), Math.max(1, execA.duration || 0)) + ")</div>" +
      '<div class="cs-item">nós A <strong>' + execA.metrics.nodeCount + "</strong> → B <strong>" + execB.metrics.nodeCount + "</strong></div>" +
      (addedMutations.length ? '<div class="cs-item">mutações novas em B: <strong>' + addedMutations.join(", ") + "</strong></div>" : "") +
      (removedMutations.length ? '<div class="cs-item">mutações só em A: <strong>' + removedMutations.join(", ") + "</strong></div>" : "") +
    "</div>" +
    '<div class="dash-section"><h3>SPANS — ' + labels.size + " rótulo(s) distintos</h3>" +
    '<table class="diff-table"><thead><tr><th>span</th><th>execução A</th><th>execução B</th><th>delta</th></tr></thead>' +
    "<tbody>" + rows.join("") + "</tbody></table></div>";
}

async function loadFull(executionId) {
  const exec = state.executions.get(executionId);
  if (exec && exec.completed && exec.nodes.size) {
    return { roots: [...exec.nodes.values()], duration: exec.summary.duration,
             metrics: { nodeCount: exec.summary.nodeCount } };
  }
  try {
    const full = await (await api("/executions/" + encodeURIComponent(executionId))).json();
    return full;
  } catch (e) {
    return null;
  }
}

function shortId(id) {
  return String(id).length > 10 ? String(id).slice(0, 10) + "…" : id;
}

/* ------------------------------------------------------------- storytelling (negócio) */
async function loadStory(executionId) {
  if (state.story && state.story.executionId === executionId) {
    return state.story;
  }
  try {
    state.story = await (await api("/executions/" + encodeURIComponent(executionId) + "/story")).json();
  } catch (e) {
    state.story = null;
  }
  return state.story;
}

async function renderStory() {
  const container = $("story-view");
  const id = state.selectedExecutionId;
  if (!id) {
    container.innerHTML = '<h2>STORY <span class="pill">narrativa de negócio</span></h2>' +
      '<div class="empty-note dash">Selecione uma execução para ver a narrativa.</div>';
    return;
  }
  container.innerHTML = '<h2>STORY <span class="pill">narrativa de negócio</span></h2>' +
    '<div class="empty-note dash">montando a narrativa…</div>';
  const story = await loadStory(id);
  if (!story || !story.steps) {
    container.innerHTML = '<h2>STORY <span class="pill">narrativa de negócio</span></h2>' +
      '<div class="empty-note dash">Narrativa indisponível para esta execução.</div>';
    return;
  }
  container.innerHTML =
    '<h2>STORY <span class="pill">' + esc(fmtDuration(story.durationMs)) + " · " + esc(story.status) + "</span></h2>" +
    '<div class="story-actions">' +
      '<button type="button" class="small" id="btn-copy-story">COPIAR COMO MARKDOWN</button>' +
    "</div>" +
    '<div class="story-header"><div class="title">' + esc(story.title || "Jornada") + "</div>" +
    '<div class="intro">' + esc(story.intro || "") + "</div></div>" +
    story.steps.map(stepHtml).join("") +
    '<div class="story-conclusion">' + esc(story.conclusion || "") + "</div>";
  $("btn-copy-story").addEventListener("click", () => {
    navigator.clipboard.writeText(storyToMarkdown(story))
      .then(() => toast("Narrativa copiada como Markdown — pronta para slides/PR", "ok"))
      .catch(() => toast("Não foi possível copiar", "error"));
  });

  // LINKING & BRUSHING: clicar num passo leva ao nó correspondente no canvas
  container.querySelectorAll(".story-step").forEach((step) => {
    const focus = () => {
      const nodeId = step.dataset.nodeId;
      if (!nodeId) return;
      state.selectedNodeId = nodeId;
      state.pulseNodeId = nodeId;
      switchTab("canvas");
      renderCanvas();
      setTimeout(() => {
        state.pulseNodeId = null;
        if (state.activeTab === "canvas") renderCanvas();
      }, 1900);
    };
    step.addEventListener("click", focus);
    step.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        focus();
      }
    });
  });
  // passo ativo reflete o nó selecionado (brushing reverso)
  container.querySelectorAll(".story-step").forEach((step) => {
    step.classList.toggle("active", step.dataset.nodeId === state.selectedNodeId);
  });
}

function stepHtml(s) {
  return '<div class="story-step' + (s.error ? " error" : "") + '" data-node-id="' + esc(s.nodeId || "") + '" role="button" tabindex="0" title="Ver este passo no canvas">' +
    '<span class="n">' + s.order + "</span>" +
    '<span class="swatch sw-' + esc(s.kind || "UNKNOWN") + '"></span>' +
    '<span class="icon">' + esc(s.icon || "·") + "</span>" +
    '<div class="body"><div class="text">' + esc(s.text) + "</div>" +
    '<div class="meta">' +
      "<span>" + esc(s.kind) + "</span>" +
      "<span>" + esc(fmtDuration(s.durationMs)) + "</span>" +
      (s.mutationSummary ? '<span class="mutation">Δ ' + esc(s.mutationSummary) + "</span>" : "") +
      (s.error ? '<span class="error-tag">⚠ FALHOU</span>' : "") +
    "</div></div></div>";
}

function storyToMarkdown(story) {
  const lines = [];
  lines.push("# " + (story.title || "Jornada TraceVanta"));
  lines.push("");
  lines.push(story.intro || "");
  lines.push("");
  for (const s of story.steps || []) {
    lines.push(s.order + ". **" + (s.text || "") + "** — " + s.kind + " · " + fmtDuration(s.durationMs)
      + (s.mutationSummary ? " · Δ " + s.mutationSummary : "") + (s.error ? " · ⚠ FALHOU" : ""));
  }
  lines.push("");
  lines.push("> " + (story.conclusion || ""));
  return lines.join("\n");
}

function wrapText(text, width) {
  const words = String(text).split(/\s+/);
  const lines = [];
  let cur = "";
  for (const w of words) {
    const candidate = (cur + " " + w).trim();
    if (candidate.length > width) {
      if (cur) lines.push(cur);
      cur = w;
    } else {
      cur = candidate;
    }
  }
  if (cur) lines.push(cur);
  return lines;
}
function treeRoots(exec) {
  const nodes = exec ? exec.nodes : new Map();
  const byParent = new Map();
  for (const n of nodes.values()) {
    if (!byParent.has(n.parentId)) byParent.set(n.parentId, []);
    byParent.get(n.parentId).push(n);
  }
  return (byParent.get(null) || []).sort((a, b) => (a.startedAt || "").localeCompare(b.startedAt || ""));
}

/**
 * Reconstrói a árvore por parentId — os nós do estado são snapshots congelados
 * em momentos diferentes (children incompletos), então o campo `children` dos
 * snapshots NÃO é confiável. Órfão de pai desconhecido vira raiz (honestidade I1).
 * Implementação imperativa direta: devolve o array de nós ordenado (DFS).
 */
function rebuildTree(exec) {
  const nodes = [...exec.nodes.values()];
  const byParent = new Map();
  for (const n of nodes) {
    const pid = n.parentId == null ? null : String(n.parentId);
    if (!byParent.has(pid)) byParent.set(pid, []);
    byParent.get(pid).push(n);
  }
  const sortByStart = (a, b) => (a.startedAt || "").localeCompare(b.startedAt || "");
  const rootList = (byParent.get(null) || []).sort(sortByStart);

  const ordered = [];        // DFS: pai antes dos filhos
  const seen = new Set();
  const stack = [...rootList].reverse();
  while (stack.length > 0) {
    const node = stack.pop();
    if (seen.has(node.nodeId)) continue;
    seen.add(node.nodeId);
    ordered.push(node);
    const kids = (byParent.get(String(node.nodeId)) || []).sort(sortByStart);
    node.children = kids;    // reconstruído a partir do parentId
    for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
  }
  const orphans = nodes.filter((n) => !seen.has(n.nodeId));
  for (const o of orphans) { o.parentId = null; o.children = []; ordered.push(o); }
  return ordered;
}

function layout(roots) {
  // tidy tree simplificado: folhas recebem x sequencial; pais centralizam filhos
  const leafCount = { n: 0 };
  const W = 220, H = 64, GX = 30, GY = 34;
  const all = [];
  // processa em ordem reversa (DFS pós-ordem): folhas primeiro, pais por último
  for (let i = roots.length - 1; i >= 0; i--) {
    const node = roots[i];
    const kids = node.children || [];
    let x;
    if (kids.length === 0) {
      x = leafCount.n * (W + GX);
      leafCount.n += 1;
    } else {
      const xs = kids.map((k) => k.__tvX).filter((v) => v !== undefined);
      x = xs.length > 0 ? (xs[0] + xs[xs.length - 1]) / 2 : leafCount.n * (W + GX);
    }
    node.__tvX = x;
    all.push({ node, x, y: (node.__tvDepth || 0) * (H + GY), depth: node.__tvDepth || 0 });
  }
  // profundidades: percorre a árvore do topo
  const assignDepth = (node, depth) => {
    node.__tvDepth = depth;
    for (const kid of node.children || []) assignDepth(kid, depth + 1);
  };
  const tops = roots.filter((n) => n.parentId == null || n.parentId === null);
  for (const t of tops) assignDepth(t, 0);
  const out = [];
  for (const item of all) {
    out.push({ node: item.node, x: item.x, y: item.node.__tvDepth * (H + GY), depth: item.node.__tvDepth });
  }
  for (const n of roots) { delete n.__tvX; delete n.__tvDepth; }
  return { all: out, W, H, leafCount: leafCount.n };
}

function renderSummary(exec) {
  const el = $("exec-summary");
  if (!exec || !exec.summary) {
    el.classList.add("hidden");
    el.innerHTML = "";
    return;
  }
  const s = exec.summary;
  const warnings = (exec.warnings || []).length;
  el.classList.remove("hidden");
  el.innerHTML =
    '<span class="status-badge ' + esc(s.status || "RUNNING") + '">' + esc(statusLabel[s.status] || s.status || "RUNNING") + "</span>" +
    '<span class="chip ' + esc(s.trigger || "EXTERNAL") + '">' + esc(triggerLabel[s.trigger] || s.trigger || "EXTERNAL") + "</span>" +
    '<span class="sum-item">duração <strong>' + esc(fmtDuration(s.duration)) + "</strong></span>" +
    '<span class="sum-item">nós <strong>' + esc(String(s.nodeCount || 0)) + "</strong></span>" +
    (warnings > 0 ? '<span class="sum-item warn-count" title="' + warnings + ' aviso(s)">⚠ ' + warnings + " aviso(s)</span>" : "");
}

function renderCanvas() {
  const svg = $("tree");
  svg.innerHTML = "";
  const exec = state.selectedExecutionId ? state.executions.get(state.selectedExecutionId) : null;
  if (!exec || !exec.nodes.size) {
    $("canvas-empty").classList.remove("hidden");
    $("canvas-title").innerHTML = "EXECUTION CANVAS";
    renderSummary(null);
    return;
  }
  $("canvas-empty").classList.add("hidden");
  $("canvas-title").innerHTML =
    'EXECUTION CANVAS: Trace <span class="trace">#' + esc(state.selectedExecutionId) + "</span>";
  renderSummary(exec);

  // gradiente compartilhado dos cards (CSP: referência local ao próprio SVG)
  const defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
  defs.innerHTML =
    '<linearGradient id="tv-node-grad" x1="0" y1="0" x2="0" y2="1">' +
    '<stop offset="0" stop-color="#1d2734"/><stop offset="1" stop-color="#151e29"/>' +
    "</linearGradient>";
  svg.appendChild(defs);

  const ordered = rebuildTree(exec);
  const { all, W, H } = layout(ordered);
  const byId = new Map(all.map((p) => [p.node.nodeId, p]));
  // waterfall: proporção relativa à duração total da execução (min 1ms)
  let maxDur = 0;
  for (const p of all) {
    const t = p.node.totalTime;
    if (typeof t === "number" && t > maxDur) maxDur = t;
  }
  maxDur = Math.max(1, maxDur);

  // arestas
  for (const p of all) {
    const kids = p.node.children || [];
    for (const kid of kids) {
      const c = byId.get(kid.nodeId);
      if (!c) continue;
      const edge = document.createElementNS("http://www.w3.org/2000/svg", "path");
      const x1 = p.x + W / 2, y1 = p.y + H, x2 = c.x + W / 2, y2 = c.y;
      const mx = (y1 + y2) / 2;
      edge.setAttribute("d", "M " + x1 + " " + y1 + " C " + x1 + " " + mx + " " + x2 + " " + mx + " " + x2 + " " + y2);
      edge.setAttribute("class", "tv-edge" +
        (kid.status === "ORPHANED" ? " orphaned" : "") +
        (kid.status === "ERROR" ? " error" : ""));
      svg.appendChild(edge);
    }
  }

  // nós — card v2: kind chip + nome + rótulo + métricas + waterfall bar
  for (const p of all) {
    const n = p.node;
    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    g.setAttribute("class", "tv-node" + (state.selectedNodeId === n.nodeId ? " selected" : ""));
    g.setAttribute("transform", "translate(" + p.x + "," + p.y + ")");
    g.setAttribute("tabindex", "-1");
    g.dataset.nodeId = n.nodeId;

    const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    rect.setAttribute("class", "box " + n.status.toLowerCase()
        + (state.pulseNodeId === n.nodeId ? " pulse" : ""));
    rect.setAttribute("width", W); rect.setAttribute("height", H);
    g.appendChild(rect);

    const tag = document.createElementNS("http://www.w3.org/2000/svg", "text");
    tag.setAttribute("class", "kind-tag");
    tag.setAttribute("x", 12); tag.setAttribute("y", 18);
    tag.setAttribute("fill", kindColor[n.kind] || kindColor.UNKNOWN);
    tag.textContent = kindTag[n.kind] || "?";
    g.appendChild(tag);

    const kind = document.createElementNS("http://www.w3.org/2000/svg", "text");
    kind.setAttribute("class", "kind-name");
    kind.setAttribute("x", 34); kind.setAttribute("y", 18);
    kind.textContent = kindName[n.kind] || "?";
    g.appendChild(kind);

    const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
    label.setAttribute("class", "label");
    label.setAttribute("x", 12); label.setAttribute("y", 35);
    label.textContent = truncate(n.label || "?", 26);
    g.appendChild(label);

    const totalMs = typeof n.totalTime === "number" ? n.totalTime : 0;
    const selfMs = typeof n.selfTime === "number" ? n.selfTime : 0;
    const meta = document.createElementNS("http://www.w3.org/2000/svg", "text");
    meta.setAttribute("class", "meta");
    meta.setAttribute("x", 12); meta.setAttribute("y", 48);
    meta.textContent = (n.status === "PENDING" ? "… " : fmtDuration(totalMs) + " · self " + fmtDuration(selfMs)) +
      (n.mutation ? " · Δ" : "");
    g.appendChild(meta);

    if (n.mutation) {
      const mutDot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      mutDot.setAttribute("class", "mut-dot");
      mutDot.setAttribute("cx", W - 12); mutDot.setAttribute("cy", 15); mutDot.setAttribute("r", 3.5);
      g.appendChild(mutDot);
    }

    // waterfall: barra total + self proporcional ao nó mais longo da execução
    const barW = W - 24, barX = 12, barY = 53;
    const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    bg.setAttribute("class", "timebar-bg");
    bg.setAttribute("x", barX); bg.setAttribute("y", barY); bg.setAttribute("width", barW); bg.setAttribute("height", 5);
    g.appendChild(bg);
    const totalW = Math.max(0, Math.min(barW, (totalMs / maxDur) * barW));
    if (totalW > 0.5) {
      const tb = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      tb.setAttribute("class", "timebar-total");
      tb.setAttribute("x", barX); tb.setAttribute("y", barY); tb.setAttribute("width", totalW); tb.setAttribute("height", 5);
      g.appendChild(tb);
    }
    const selfW = Math.min(totalW, (selfMs / maxDur) * barW);
    if (selfW > 0.5) {
      const sb = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      sb.setAttribute("class", "timebar-self");
      sb.setAttribute("x", barX); sb.setAttribute("y", barY); sb.setAttribute("width", selfW); sb.setAttribute("height", 5);
      g.appendChild(sb);
    }

    g.addEventListener("click", () => selectNode(n.nodeId));
    svg.appendChild(g);

    // storytelling: anotação de negócio AO LADO do nó (toggle NOTAS),
    // ENCADEADA ao passo da narrativa: número do passo + conector tracejado
    if (state.notesOn && state.story && state.story.steps) {
      const step = state.story.steps.find((s) => s.nodeId === n.nodeId);
      if (step && step.text) {
        const lines = wrapText(step.text, 40);
        const bw = 252, lh = 14, bh = lines.length * lh + 10;
        // conector: da borda do cartão ao balão de nota
        const link = document.createElementNS("http://www.w3.org/2000/svg", "path");
        link.setAttribute("class", "note-link");
        link.setAttribute("d", "M " + (W + 2) + " " + (H / 2)
            + " C " + (W + 7) + " " + (H / 2) + ", " + (W + 7) + " " + 14 + ", " + (W + 11) + " " + 14);
        g.appendChild(link);
        const bubble = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        bubble.setAttribute("class", "note-bubble");
        bubble.setAttribute("x", W + 12); bubble.setAttribute("y", 4);
        bubble.setAttribute("width", bw); bubble.setAttribute("height", bh);
        g.appendChild(bubble);
        // número do passo = MESMO número da aba STORY (encadeamento visual)
        const numC = document.createElementNS("http://www.w3.org/2000/svg", "circle");
        numC.setAttribute("class", "note-num");
        numC.setAttribute("cx", W + 24); numC.setAttribute("cy", 13); numC.setAttribute("r", 8);
        g.appendChild(numC);
        const numT = document.createElementNS("http://www.w3.org/2000/svg", "text");
        numT.setAttribute("class", "note-num-text");
        numT.setAttribute("x", W + 24); numT.setAttribute("y", 16.5);
        numT.textContent = String(step.order);
        g.appendChild(numT);
        lines.forEach((line, i) => {
          const noteText = document.createElementNS("http://www.w3.org/2000/svg", "text");
          noteText.setAttribute("class", "note-line");
          noteText.setAttribute("x", W + 38); noteText.setAttribute("y", 18 + i * lh);
          noteText.textContent = line;
          g.appendChild(noteText);
        });
      }
    }
  }

  // dimensões do viewBox (considera as notas ao lado quando ligadas)
  let maxX = 0, maxY = 0;
  for (const p of all) {
    maxX = Math.max(maxX, p.x + W + (state.notesOn ? 280 : 0));
    maxY = Math.max(maxY, p.y + H);
  }
  svg.setAttribute("viewBox", "0 0 " + Math.max(400, maxX + 60) + " " + Math.max(300, maxY + 60));

  // auto-fit: primeira renderização de cada execução encaixa a árvore na viewport
  if (state.fittedFor !== state.selectedExecutionId) {
    fitTree(all, W, H);
    state.fittedFor = state.selectedExecutionId;
  }
  applyView(svg);

  if (state.selectedNodeId) {
    renderInspector();
  }
}

function fitTree(positions, W, H) {
  if (!positions.length) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of positions) {
    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
    maxX = Math.max(maxX, p.x + W); maxY = Math.max(maxY, p.y + H);
  }
  const canvas = $("canvas");
  const cw = canvas.clientWidth || 900, ch = canvas.clientHeight || 600;
  const tw = Math.max(1, maxX - minX), th = Math.max(1, maxY - minY);
  const scale = Math.min(1, (cw / tw) * 0.92, (ch / th) * 0.92);
  state.view = {
    x: cw / 2 - ((minX + maxX) / 2) * scale,
    y: ch / 2 - ((minY + maxY) / 2) * scale,
    scale,
  };
}

function applyView(svg) {
  svg.style.transform = "translate(" + state.view.x + "px," + state.view.y + "px) scale(" + state.view.scale + ")";
  svg.style.transformOrigin = "0 0";
}

function truncate(s, n) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

/* ------------------------------------------------------------- inspector */
function selectNode(nodeId) {
  state.selectedNodeId = nodeId;
  renderCanvas();
  // brushing reverso: destaca o passo correspondente na narrativa (se renderizada)
  document.querySelectorAll("#story-view .story-step").forEach((step) => {
    step.classList.toggle("active", step.dataset.nodeId === nodeId);
  });
}

function renderInspector() {
  const exec = state.executions.get(state.selectedExecutionId);
  const node = exec && exec.nodes.get(state.selectedNodeId);
  const panel = $("inspector");
  if (!node) { panel.classList.add("hidden"); return; }
  panel.classList.remove("hidden");
  $("inspector-title").textContent = node.label + " (" + node.nodeId + ")";

  const attrsHtml = attributesSection(node.attributes || {});
  const errorHtml = node.error
    ? '<section class="insp-section"><h3 class="clickable"><span class="chev">▼</span> ERRO</h3><div class="sec-body">' +
      '<div class="error-block kv">' +
        '<div class="k">type</div><div class="v">' + esc(node.error.type || "?") + "</div>" +
        '<div class="k">message</div><div class="v">' + esc(node.error.message || "?") + "</div></div>" +
      '<button type="button" class="copy-btn small" data-copy="' + esc(node.error.message || "") + '">COPIAR MENSAGEM</button>' +
      (node.error.stack ? '<pre class="stack">' + esc(node.error.stack) + "</pre>" : "") + "</div></section>"
    : "";
  const mutationHtml = node.mutation ? mutationSection(node.mutation) : "";
  const payloadHtml = (node.payload && (node.payload.request || node.payload.response))
    ? '<section class="insp-section"><h3 class="clickable"><span class="chev">▼</span> PAYLOAD</h3><div class="sec-body">' +
      (node.payload.request
        ? "<h3>request</h3><pre class=\"json\">" + esc(pretty(node.payload.request)) + "</pre>" +
          '<button type="button" class="copy-btn small" data-copy="' + esc(pretty(node.payload.request)) + '">COPIAR REQUEST</button>'
        : "") +
      (node.payload.response
        ? "<h3>response</h3><pre class=\"json\">" + esc(pretty(node.payload.response)) + "</pre>" +
          '<button type="button" class="copy-btn small" data-copy="' + esc(pretty(node.payload.response)) + '">COPIAR RESPONSE</button>'
        : "") +
      "</div></section>"
    : "";

  $("inspector-body").innerHTML =
    '<section class="insp-section"><h3>VISÃO GERAL</h3><div class="sec-body kv">' +
      '<div class="k">operation</div><div class="v">' + esc(node.label || "?") + "</div>" +
      '<div class="k">status</div><div class="v"><span class="badge ' + esc(node.status) + '">' + esc(node.status) + "</span></div>" +
      '<div class="k">latency</div><div class="v">' + fmtDuration(node.totalTime) + " (self " + fmtDuration(node.selfTime) + ")</div>" +
      '<div class="k">kind</div><div class="v">' + esc(kindName[node.kind] || node.kind || "?") + "</div>" +
    "</div></section>" +
    attrsHtml + mutationHtml + payloadHtml + errorHtml;

  wireInspectorInteractions();
}

function attributesSection(attrs) {
  const entries = Object.entries(attrs || {});
  const collapsed = entries.length > 6;
  const rows = entries.map(([k, v]) =>
    '<div class="kv-pair"><div class="k">' + esc(k) + '</div><div class="v">' + esc(v) + "</div></div>").join("");
  return '<section class="insp-section' + (collapsed ? " collapsed" : "") + '">' +
    '<h3 class="clickable"><span class="chev">▼</span> ATRIBUTOS <span class="count-badge">' + entries.length + "</span></h3>" +
    '<div class="sec-body">' +
      '<input class="kv-filter" placeholder="Filtrar atributos…" aria-label="Filtrar atributos" autocomplete="off">' +
      '<div class="kv">' + (rows || '<div class="kv-pair"><div class="v">—</div></div>') + "</div>" +
      (collapsed ? '<button type="button" class="sec-more">mostrar todos (' + entries.length + ")</button>" : "") +
    "</div></section>";
}

function wireInspectorInteractions() {
  const body = $("inspector-body");
  // seções colapsáveis
  body.querySelectorAll("h3.clickable").forEach((h) => {
    h.addEventListener("click", () => h.parentElement.classList.toggle("collapsed"));
  });
  body.querySelectorAll(".sec-more").forEach((b) => {
    b.addEventListener("click", () => b.closest(".insp-section").classList.remove("collapsed"));
  });
  // filtro de atributos
  const filterInput = body.querySelector(".kv-filter");
  if (filterInput) {
    filterInput.addEventListener("input", (e) => {
      const q = e.target.value.trim().toLowerCase();
      body.querySelectorAll(".kv-pair").forEach((row) => {
        row.classList.toggle("hidden", q !== "" && !row.textContent.toLowerCase().includes(q));
      });
    });
  }
  // copiar
  body.querySelectorAll(".copy-btn").forEach((b) => {
    b.addEventListener("click", () => {
      const text = b.getAttribute("data-copy") || "";
      navigator.clipboard.writeText(text)
        .then(() => toast("Copiado para a área de transferência", "ok"))
        .catch(() => toast("Não foi possível copiar", "error"));
    });
  });
}

function mutationSection(m) {
  const deltas = (m.deltas || []).map((d) =>
    '<div class="k delta-rem">− ' + esc(d.path) + "</div><div class=\"v\">" + esc(jsonOf(d.before)) + "</div>" +
    '<div class="k delta-add">+ ' + esc(d.path) + "</div><div class=\"v\">" + esc(jsonOf(d.after)) + "</div>").join("");
  return '<section class="insp-section"><h3 class="clickable"><span class="chev">▼</span> DATA MUTATION — ' + esc(m.target || "?") +
    ' <span class="badge ' + esc(m.fidelity) + '">' + esc(m.fidelity) + "</span></h3>" +
    '<div class="sec-body">' +
    '<div class="kv"><div class="k">kind</div><div class="v">' + esc(m.kind || "?") + "</div>" +
    '<div class="k">key</div><div class="v">' + esc(m.key || "—") + "</div></div>" +
    (deltas ? '<h3>field deltas</h3><div class="kv">' + deltas + "</div>" : "") +
    '<h3>before</h3><pre class="json">' + esc(pretty(jsonOf(m.before))) + "</pre>" +
    '<button type="button" class="copy-btn small" data-copy="' + esc(pretty(jsonOf(m.before))) + '">COPIAR BEFORE</button>' +
    '<h3>after</h3><pre class="json">' + esc(pretty(jsonOf(m.after))) + "</pre>" +
    '<button type="button" class="copy-btn small" data-copy="' + esc(pretty(jsonOf(m.after))) + '">COPIAR AFTER</button>' +
    (m.fidelity === "INFERRED"
      ? '<div class="mutation-note">Delta INFERIDO por análise do comando — não observado no banco (invariante I3).</div>'
      : "") +
    (m.fidelity === "UNAVAILABLE"
      ? '<div class="mutation-note">Delta indisponível para esta operação — declarado, não presumido.</div>'
      : "") +
    "</div></section>";
}

function jsonOf(v) {
  if (v === null || v === undefined) return "null";
  if (typeof v === "string") { try { return JSON.stringify(JSON.parse(v), null, 2); } catch (e) { return v; } }
  return JSON.stringify(v, null, 2);
}

function pretty(s) {
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
}

/* ------------------------------------------------------------- SSE (um stream por aba — ADR-004) */
function connectStream() {
  const es = new EventSource(API + "/stream");
  es.onopen = () => { $("conn-dot").classList.add("on"); $("conn-dot").classList.remove("off"); $("conn-text").textContent = "CONNECTED"; };
  es.onerror = () => { $("conn-dot").classList.add("off"); $("conn-dot").classList.remove("on"); $("conn-text").textContent = "RECONNECTING…"; };

  es.addEventListener("execution.started", (e) => {
    const d = JSON.parse(e.data);
    const exec = state.executions.get(d.executionId) || { nodes: new Map(), warnings: [], completed: false };
    exec.summary = Object.assign({}, exec.summary, { executionId: d.executionId, traceId: d.traceId, trigger: d.trigger, startedAt: d.at, status: "RUNNING" });
    state.executions.set(d.executionId, exec);
    if (!state.selectedExecutionId) state.selectedExecutionId = d.executionId;
    renderRecent();
  });
  es.addEventListener("node.upserted", (e) => upsertNode(JSON.parse(e.data)));
  es.addEventListener("node.mutation", (e) => applyMutation(JSON.parse(e.data)));
  es.addEventListener("execution.completed", (e) => {
    const execution = JSON.parse(e.data).execution;
    ingestExecution(execution, false);
  });
  es.addEventListener("execution.snapshot", (e) => ingestExecution(JSON.parse(e.data), true));
  es.addEventListener("system.warning", (e) => {
    const d = JSON.parse(e.data);
    $("dropped-badge").classList.remove("hidden");
    $("dropped-badge").textContent = (d.count || 0) + " evento(s) descartado(s)";
    const section = $("warnings-section");
    section.classList.remove("hidden");
    $("warnings").insertAdjacentHTML("afterbegin",
      '<div class="warning-item" role="listitem">⚠ ' + esc(d.message) + "</div>");
  });
}

/* ------------------------------------------------------------- interação do canvas */
function wireCanvas() {
  const canvas = $("canvas");
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
    state.view.scale = Math.min(3, Math.max(0.2, state.view.scale * factor));
    renderCanvas();
  }, { passive: false });
  canvas.addEventListener("mousedown", (e) => {
    state.dragging = true; state.dragStart = { x: e.clientX - state.view.x, y: e.clientY - state.view.y };
    canvas.classList.add("dragging");
  });
  window.addEventListener("mouseup", () => { state.dragging = false; canvas.classList.remove("dragging"); });
  window.addEventListener("mousemove", (e) => {
    if (!state.dragging) return;
    state.view.x = e.clientX - state.dragStart.x;
    state.view.y = e.clientY - state.dragStart.y;
    renderCanvas();
  });
  canvas.addEventListener("keydown", (e) => {
    const exec = state.executions.get(state.selectedExecutionId);
    if (!exec) return;
    const nodes = [...exec.nodes.values()];
    const idx = nodes.findIndex((n) => n.nodeId === state.selectedNodeId);
    if (e.key === "ArrowRight" || e.key === "ArrowDown") {
      e.preventDefault();
      selectNode(nodes[(idx + 1 + nodes.length) % nodes.length].nodeId);
    } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
      e.preventDefault();
      selectNode(nodes[(idx - 1 + nodes.length) % nodes.length].nodeId);
    } else if (e.key === "Escape") {
      state.selectedNodeId = null;
      renderCanvas();
      $("inspector").classList.add("hidden");
    } else if (e.key === "f" || e.key === "F") {
      state.view = { x: 60, y: 40, scale: 1 };
      renderCanvas();
    }
  });
  $("zoom-in").addEventListener("click", () => { state.view.scale = Math.min(3, state.view.scale * 1.15); renderCanvas(); });
  $("zoom-out").addEventListener("click", () => { state.view.scale = Math.max(0.2, state.view.scale / 1.15); renderCanvas(); });
  $("zoom-fit").addEventListener("click", () => { state.view = { x: 60, y: 40, scale: 1 }; renderCanvas(); });
  $("inspector-close").addEventListener("click", () => {
    state.selectedNodeId = null;
    renderCanvas();
    $("inspector").classList.add("hidden");
  });
  $("btn-export").addEventListener("click", () => {
    if (!state.selectedExecutionId) return;
    window.location.href = API + "/executions/" + encodeURIComponent(state.selectedExecutionId) + "/export";
  });

  // busca/filtro de execuções recentes
  $("recent-filter").addEventListener("input", (e) => {
    state.recentFilter = e.target.value;
    renderRecent();
  });

  // limpar histórico (destrutivo: dois cliques, segundo dentro de 3 s)
  let clearArmed = false;
  let clearTimer = null;
  const resetClearBtn = () => {
    clearArmed = false;
    clearTimeout(clearTimer);
    $("btn-clear").classList.remove("danger");
    $("btn-clear").textContent = "LIMPAR";
  };
  $("btn-clear").addEventListener("click", async () => {
    if (!clearArmed) {
      clearArmed = true;
      $("btn-clear").classList.add("danger");
      $("btn-clear").textContent = "CONFIRMAR?";
      clearTimer = setTimeout(resetClearBtn, 3000);
      return;
    }
    resetClearBtn();
    try {
      await api("/executions", { method: "DELETE" });
      state.executions.clear();
      state.selectedExecutionId = null;
      state.selectedNodeId = null;
      state.story = null;
      state.pendingDeepLink = null;
      $("btn-export").disabled = true;
      try {
        history.replaceState(null, "", location.pathname);
      } catch (e) { /* ignore */ }
      renderRecent();
      renderWarnings();
      renderCanvas();
      renderSummary(null);
      if (state.activeTab === "dashboard") renderDashboard();
      if (state.activeTab === "compare") renderCompare();
      if (state.activeTab === "story") renderStory();
      toast("Histórico de execuções limpo", "ok");
    } catch (e) {
      toast("Falha ao limpar: " + e.message, "error");
    }
  });

  // abas de visão (canvas / dashboard / comparar / story)
  $("tab-canvas").addEventListener("click", () => switchTab("canvas"));
  $("tab-dashboard").addEventListener("click", () => switchTab("dashboard"));
  $("tab-compare").addEventListener("click", () => switchTab("compare"));
  $("tab-story").addEventListener("click", () => switchTab("story"));

  // notas de storytelling ao lado dos nós (descoberta de negócio)
  $("btn-notes").addEventListener("click", async () => {
    state.notesOn = !state.notesOn;
    $("btn-notes").classList.toggle("active", state.notesOn);
    if (state.notesOn && state.selectedExecutionId) {
      await loadStory(state.selectedExecutionId);
    }
    renderCanvas();
  });

  // legenda + atalhos (popovers)
  $("btn-legend").addEventListener("click", (e) => {
    e.stopPropagation();
    togglePop("legend-pop", legendHtml());
  });
  $("btn-shortcuts").addEventListener("click", (e) => {
    e.stopPropagation();
    togglePop("shortcuts-pop", shortcutsHtml());
  });
  document.addEventListener("click", (e) => {
    if (!e.target.closest(".pop") && e.target.id !== "btn-legend" && e.target.id !== "btn-shortcuts") {
      $("legend-pop").classList.add("hidden");
      $("shortcuts-pop").classList.add("hidden");
    }
  });
}

function togglePop(id, html) {
  const el = $(id);
  if (el.classList.contains("hidden")) {
    el.innerHTML = html;
    el.classList.remove("hidden");
  } else {
    el.classList.add("hidden");
  }
}

function legendHtml() {
  return "<h3>TIPOS DE NÓ</h3><div class=\"legend-grid\">" +
    Object.keys(kindName).map((k) =>
      '<span class="swatch sw-' + esc(k) + '"></span><span class="lg-kind">' +
      esc(kindTag[k]) + " — " + esc(kindName[k]) + "</span>").join("") +
    "</div>";
}

function shortcutsHtml() {
  const rows = [
    ["pan", "arrastar"],
    ["zoom", "scroll / ＋ －"],
    ["ajustar à tela", "F"],
    ["navegar nós", "← ↑ ↓ →"],
    ["fechar inspector", "Esc"],
    ["exportar", "EXPORTAR .tvtrace"],
  ];
  return "<h3>ATALHOS</h3>" + rows.map(([k, v]) =>
    '<div class="kbd-row"><span>' + esc(k) + "</span><span><kbd>" + esc(v) + "</kbd></span></div>").join("");
}

/* ------------------------------------------------------------- boot */
// deep link: ?execution=<id> seleciona a execução compartilhada ao abrir
const bootParams = new URLSearchParams(location.search);
state.pendingDeepLink = bootParams.get("execution");
loadMeta().then(loadEndpoints).then(loadRecent);
connectStream();
wireCanvas();
renderEndpoints();
renderRecent();
