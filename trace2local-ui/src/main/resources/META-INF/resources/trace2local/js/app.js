/* Trace2Local — RESONANCE (UI v4): boot, roteamento entre visões, seleção
   (execução → passo), destaque cruzado, faixa da execução e barra de status. */
import { state, $, el, clear, on, emit, json, API, ms, shortId, toast, copy, STATUS, STATUS_CHIP, TRIGGER, CHAPTER_COLOR, buildTree, selectedExec } from "./core.js";
import { loadMeta, loadEndpoints, loadRecent, loadExecution, loadLogs, loadAssist, loadInsights, loadTopology, loadIntelligence, loadHealth, connectStream } from "./data.js";
import { initRail, switchRail } from "./rail.js";
import { initTree, render as renderTree, centerOn, ensureVisible, refit as refitTree } from "./tree.js";
import { initAnatomy, render as renderAnatomy, selectComponent } from "./anatomy.js";
import { initTimeline, render as renderTimeline, stopPlayback } from "./timeline.js";
import { initInvestigate, render as renderInvestigate } from "./investigate.js";
import { initInspector, close as closeInspector, isOpen as inspectorOpen } from "./inspector.js";
import { initLegacy, renderStory, renderDashboard, renderCompare, renderInfra } from "./legacy.js";
import { initPalette, openPalette, closePalette, isPaletteOpen, defaultItems } from "./palette.js";
import { initMocks, render as renderMocks, load as loadMocks, enabled as mocksEnabled, focusSuggestion } from "./mocks.js";

const VIEWS = ["anatomy", "tree", "timeline", "investigate", "story", "dashboard", "compare", "infra", "mocks"];
const RENDER = {
  anatomy: () => { loadTopology(); renderAnatomy(); },
  tree: renderTree, timeline: renderTimeline, investigate: renderInvestigate,
  story: renderStory, dashboard: renderDashboard, compare: renderCompare, infra: renderInfra,
  mocks: () => renderMocks({ refresh: true }),
};

/* ------------------------------------------------------------- navegação */
function goto(view) {
  if (!VIEWS.includes(view)) return;
  if (state.view === "timeline" && view !== "timeline") stopPlayback();
  state.view = view;
  document.querySelectorAll("#views button").forEach((b) => {
    const active = b.dataset.view === view;
    b.classList.toggle("active", active);
    b.setAttribute("aria-current", active ? "page" : "false");
    // telas estreitas: a barra de visões rola — a visão ativa nunca fica fora da vista
    if (active && b.parentElement.scrollWidth > b.parentElement.clientWidth) {
      const bar = b.parentElement, br = bar.getBoundingClientRect(), r = b.getBoundingClientRect();
      if (r.left < br.left || r.right > br.right) bar.scrollLeft += r.left - br.left - 16;
    }
  });
  VIEWS.forEach((v) => $("view-" + v).classList.toggle("hidden", v !== view));
  // o inspector é do PASSO: só acompanha visões centradas em passos
  if (["tree", "timeline", "story", "investigate"].includes(view)) {
    if (state.selectedNodeId && !inspectorOpen()) emit("node", state.selectedNodeId);
  } else if (inspectorOpen()) {
    closeInspector();
  }
  syncUrl();
  try { RENDER[view](); } catch (e) { console.error("[t2l] render", view, e); }
}

async function selectExecution(id, opts = {}) {
  if (!id) return;
  const changed = state.selectedId !== id;
  state.selectedId = id;
  if (changed) {
    state.selectedNodeId = opts.nodeId || null;
    state.highlight = null;
    state.playhead = null;
    refitTree();
  }
  $("btn-export").disabled = false;
  syncUrl();
  emit("selection");
  renderStrip();
  const exec = await loadExecution(id, !!opts.force);
  if (!exec) { toast("Execução " + shortId(id) + " não está mais no acervo", "error"); return; }
  if (opts.nodeId) selectNode(opts.nodeId);
  emit("selection");
  renderStrip();
  if (exec.completed) {
    loadAssist(id);
    loadLogs(id);
  }
  if (state.view === "anatomy" && opts.live) goto("tree");
}

function selectNode(nodeId) {
  state.selectedNodeId = nodeId;
  state.pulseNodeId = nodeId;
  setTimeout(() => { if (state.pulseNodeId === nodeId) state.pulseNodeId = null; }, 2100);
  emit("node", nodeId);
  // a gaveta acabou de abrir e cobre a direita: garante que o passo continua à vista
  if (state.view === "tree") requestAnimationFrame(() => ensureVisible(nodeId));
}

/** Abre a execução no passo (evidência de insight, uso de infra, lista de componente). */
async function openTrace(executionId, nodeId) {
  if (!executionId) return;
  await selectExecution(executionId, { nodeId });
  goto("tree");
  if (nodeId) setTimeout(() => centerOn(nodeId), 60);
}

function openInsight(i) {
  state.focusInsight = i.fingerprint;
  switchRail("ins");
  const exec = (i.executionIds || []).find((id) => state.executions.has(id));
  if (exec && exec !== state.selectedId) selectExecution(exec);
  goto("investigate");
}

function openComponent(componentId) {
  goto("anatomy");
  setTimeout(() => selectComponent(componentId), 50);
}

async function openComponentOf(nodeId) {
  const a = await loadAssist(state.selectedId);
  const comp = a && a.components && a.components[nodeId];
  if (comp) openComponent(comp); else goto("anatomy");
}

/** Da anatomia/painel para a execução, destacando os passos do componente. */
async function examine(executionId, componentId) {
  await selectExecution(executionId);
  if (componentId) {
    const a = await loadAssist(executionId);
    const ids = a && a.components ? Object.entries(a.components).filter(([, c]) => c === componentId).map(([n]) => n) : [];
    if (ids.length) {
      state.highlight = { nodeIds: new Set(ids), reason: "componente" };
      emit("highlight");
    }
  }
  goto("tree");
}

/** Do passo (inspetor) para a sugestão de mock do parceiro. */
function openMocks(suggestionId) {
  if (suggestionId) focusSuggestion(suggestionId);
  goto("mocks");
}

/** Disparo pelo contrato (Station): o id da execução nasce na Lambda — segue pelo traceId. */
function awaitTrace(traceId) {
  state.awaitTrace = { traceId, until: Date.now() + 120000 };
  matchAwaited();
}
function matchAwaited() {
  const w = state.awaitTrace;
  if (!w) return;
  if (Date.now() > w.until) { state.awaitTrace = null; return; }
  for (const [id, e] of state.executions) {
    if (e.summary && e.summary.traceId === w.traceId) {
      state.awaitTrace = null;
      selectExecution(id, { live: true });
      return;
    }
  }
}

function openHistory(insight) {
  state.focusFlowExec = (insight.executionIds || [])[0] || null;
  goto("dashboard");
}

/* ------------------------------------------------------------- URL (deep link) */
function syncUrl() {
  const p = new URLSearchParams(location.search);
  if (state.selectedId) p.set("execution", state.selectedId); else p.delete("execution");
  p.set("view", state.view);
  p.delete("token");
  history.replaceState(null, "", location.pathname + "?" + p.toString());
}

/* ------------------------------------------------------------- faixa da execução selecionada */
function renderStrip() {
  const strip = $("exec-strip");
  const exec = selectedExec();
  if (!exec || !exec.summary) { strip.classList.add("hidden"); return; }
  strip.classList.remove("hidden");
  clear(strip);
  const s = exec.summary;
  const a = state.assist.get(state.selectedId);
  strip.appendChild(el("span", { class: "chip " + (STATUS_CHIP[s.status] || ""), text: STATUS[s.status] || s.status || "em curso" }));
  strip.appendChild(el("span", { class: "what", title: s.rootLabel || s.executionId, text: s.rootLabel || shortId(s.executionId) }));
  strip.appendChild(el("span", { class: "metric" }, "gatilho ", el("strong", { text: TRIGGER[s.trigger] || s.trigger || "externo" })));
  strip.appendChild(el("span", { class: "metric" }, "duração ", el("strong", { text: ms(s.duration) })));
  strip.appendChild(el("span", { class: "metric" }, "passos ", el("strong", { text: String(exec.nodes.size || s.nodeCount || 0) })));
  const chapters = (a && a.chapters) || [];
  if (chapters.length) {
    const total = Math.max(1, ...chapters.map((c) => c.toMs));
    const bar = el("div", { class: "chapters-mini", title: chapters.map((c) => c.index + ". " + c.title).join("  ·  "), role: "img", "aria-label": "Capítulos da execução" });
    for (const c of chapters) {
      if (c.kind === "desfecho") continue;
      const i = el("i");
      i.style.width = Math.max(2, ((c.toMs - c.fromMs) / total) * 100) + "%";
      i.style.background = CHAPTER_COLOR[c.kind] || "#919bad";
      bar.appendChild(i);
    }
    strip.appendChild(bar);
  }
  if (a && a.executive && a.executive.headline) {
    const h = el("span", { class: "metric grow", title: a.executive.summary || "", text: a.executive.headline });
    h.addEventListener("click", () => goto("investigate"));
    strip.appendChild(h);
  } else {
    strip.appendChild(el("span", { class: "grow" }));
  }
  if (state.highlight) {
    const clr = el("button", { type: "button", class: "small ghost", title: "Limpar destaque (Esc)" }, "destaque: " + (state.highlight.reason || "") + " ✕");
    clr.addEventListener("click", clearHighlight);
    strip.appendChild(clr);
  }
  const link = el("button", { type: "button", class: "small ghost", title: "Copiar link desta execução" }, "link");
  link.addEventListener("click", copyLink);
  strip.appendChild(link);
}

function clearHighlight() {
  state.highlight = null;
  emit("highlight");
  renderStrip();
}

function copyLink() {
  if (!state.selectedId) { toast("Nenhuma execução selecionada", "error"); return; }
  copy(location.origin + location.pathname + "?execution=" + encodeURIComponent(state.selectedId) + "&view=" + state.view, "Link copiado");
}

function exportSelected() {
  if (!state.selectedId) { toast("Nenhuma execução selecionada", "error"); return; }
  const a = el("a", { href: API + "/executions/" + encodeURIComponent(state.selectedId) + "/export", download: "" });
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function toggleRail() {
  const app = $("app");
  if (window.matchMedia("(max-width: 860px)").matches) app.classList.toggle("rail-open");
  else app.classList.toggle("rail-collapsed");
  setTimeout(() => { if (state.view === "tree") renderTree(); if (state.view === "anatomy") renderAnatomy(); }, 220);
}

function help() {
  toast("Atalhos: 1–9 visões · Ctrl+K buscar · [ painel · ←→↑↓ navegar na árvore · espaço recolher/reproduzir · F enquadrar · Esc fechar/limpar", "ok");
}

/* ------------------------------------------------------------- barra de status */
async function pollStatus() {
  const h = await loadHealth();
  if (h) {
    $("sb-buffer").textContent = "buffer " + (h.bufferUsage ?? 0) + " · " + (h.liveExecutions ?? 0) + " ao vivo · " + (h.connectedClients ?? 0) + " cliente(s)";
    $("sb-logs").textContent = "logs " + (h.logLines ?? 0);
    if (h.dropped) {
      const b = $("sb-dropped");
      b.classList.remove("hidden");
      b.textContent = "⚠ " + h.dropped + " evento(s) descartado(s)";
    }
  }
  const st = await loadIntelligence();
  if (st) {
    const e = st.engine || {}, p = st.pipeline || {};
    $("sb-pipeline").textContent = "análise " + (p.analyzed || 0) + " · fila " + (p.queueDepth || 0) + "/" + (p.queueCapacity || 0) + (p.dropped ? " · descartes " + p.dropped : "");
    const jevOn = e.effectiveMode && e.effectiveMode !== "deterministic" && e.effectiveMode !== "off";
    $("sb-jev").textContent = jevOn ? "Jev " + e.effectiveMode + " · circuito " + e.circuit + " · " + (e.liveCalls || 0) + " chamadas" : "Jev desligado · regras locais";
    const chip = $("engine-chip");
    chip.textContent = jevOn ? "motor: Jev + regras" : "motor: regras locais";
    chip.classList.toggle("scan", !!jevOn);
    chip.title = "Micro-decisões: " + (jevOn ? "Jev (" + e.effectiveMode + ", egress " + e.egress + ") com fallback determinístico" : "motor determinístico local (" + (e.deterministicVersion || "") + ") — nenhum dado sai da máquina");
  }
}

/* ------------------------------------------------------------- teclado */
function onKey(e) {
  const typing = e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA" || e.target.tagName === "SELECT");
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    if (isPaletteOpen()) closePalette(); else openPalette();
    return;
  }
  if (e.key === "Escape") {
    if (isPaletteOpen()) { closePalette(); return; }
    if (inspectorOpen()) { state.selectedNodeId = null; closeInspector(); emit("node"); return; }
    if (state.highlight) { clearHighlight(); return; }
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (/^[1-9]$/.test(e.key)) { goto(VIEWS[Number(e.key) - 1]); return; }
  if (e.key === "[") { toggleRail(); return; }
  if (e.key === "?") { help(); return; }
}

/* ------------------------------------------------------------- boot */
async function boot() {
  initRail((id, opts) => selectExecution(id, opts), openInsight, awaitTrace);
  initTree(selectNode);
  initAnatomy({ openInsight, examine });
  initTimeline(selectNode);
  const nav = { selectNode, openTrace, openComponent, openHistory, openInsight, goto, examine, openComponentOf, openMocks };
  initInvestigate(nav);
  initInspector(nav);
  initLegacy(nav);
  initMocks(nav);
  initPalette(() => defaultItems({ goto, examine, openComponent, openInsight, toggleRail, exportSelected, copyLink, help, openMocks, mocks: mocksEnabled() }));

  document.querySelectorAll("#views button").forEach((b) => b.addEventListener("click", () => goto(b.dataset.view)));
  $("btn-palette").addEventListener("click", openPalette);
  $("btn-rail").addEventListener("click", toggleRail);
  $("btn-export").addEventListener("click", exportSelected);
  $("btn-help").addEventListener("click", help);
  const engineChip = $("engine-chip");
  engineChip.addEventListener("click", () => goto("dashboard"));
  engineChip.addEventListener("keydown", (e) => { if (e.key === "Enter") goto("dashboard"); });
  document.addEventListener("keydown", onKey);

  // reações globais
  on("assist", (id) => { if (id === state.selectedId) renderStrip(); });
  on("executions", () => { if (state.selectedId) renderStrip(); matchAwaited(); });
  on("highlight", renderStrip);
  on("live", (id) => {
    if (state.awaitTrace) { matchAwaited(); return; }
    // nada selecionado (ou acompanhando ao vivo): a nova execução vira o foco
    if (!state.selectedId || state.followLive) selectExecution(id, { live: true });
  });
  on("renamed", (d) => {
    if (state.selectedId !== d.executionId) return;
    state.selectedId = d.toExecutionId;
    syncUrl();
    emit("selection");
    renderStrip();
  });
  on("merged", (d) => {
    if (state.selectedId === d.executionId || state.selectedId === d.intoExecutionId) {
      selectExecution(d.intoExecutionId, { force: true });
      toast("Consumidor assíncrono chegou depois — fundido na mesma árvore (continuação tardia)", "ok");
    }
  });
  let topoTimer = null, insTimer = null;
  on("completed", (id) => {
    if (id === state.selectedId) {
      loadAssist(id, true);
      loadLogs(id, true);
      renderStrip();
    }
    clearTimeout(topoTimer);
    topoTimer = setTimeout(() => loadTopology(), 900);
  });
  on("insights.updated", (d) => {
    clearTimeout(insTimer);
    insTimer = setTimeout(() => {
      loadInsights();
      if (state.selectedId && (d.executionIds || []).includes(state.selectedId)) loadAssist(state.selectedId, true);
      if ((d.insights || []).some((i) => i.severity === "HIGH" || i.severity === "CRITICAL")) {
        const top = d.insights.find((i) => i.severity === "HIGH" || i.severity === "CRITICAL");
        toast("Ponto de atenção: " + top.title, "error");
      }
    }, 500);
  });

  await loadMeta();
  if (state.metaError && String(state.metaError).startsWith("401")) {
    // perfil corporate: token de UI exigido e ausente (o cookie de sessão nasce do ?token=)
    toast("Acesso protegido: abra a URL com ?token=… exibida no log de boot do Trace2Local", "error");
    return;
  }
  pollStatus(); // barra de status e chip do motor desde o primeiro instante
  await Promise.all([loadEndpoints(), loadRecent(), loadInsights(), loadIntelligence(), loadMocks()]);
  await loadTopology();
  connectStream();

  const p = new URLSearchParams(location.search);
  const initialView = VIEWS.includes(p.get("view")) ? p.get("view") : null;
  const execParam = p.get("execution");
  if (execParam) {
    await selectExecution(execParam);
    goto(initialView || "tree");
  } else {
    const hasExec = state.executions.size > 0;
    goto(initialView || "anatomy");
    if (hasExec && !initialView) {
      const latest = [...state.executions.values()].map((e) => e.summary).filter(Boolean)
        .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || ""))[0];
      if (latest) selectExecution(latest.executionId);
    }
  }
  setInterval(pollStatus, 5000);
  window.__t2lReady = true;
}

boot().catch((e) => {
  console.error("[t2l] boot", e);
  toast("Falha ao iniciar a UI: " + e.message, "error");
});

export { json, buildTree };
