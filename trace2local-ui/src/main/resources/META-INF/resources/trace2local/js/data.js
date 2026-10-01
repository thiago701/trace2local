/* Dados: carga inicial, SSE (um stream por aba — ADR-004) e caches por execução. */
import { state, api, json, emit, $, toast, API } from "./core.js";

export async function loadMeta() {
  try {
    state.meta = await json("/meta");
    $("env-app").textContent = "app " + (state.meta.app || "?");
    $("env-mode").textContent = "modo " + (state.meta.mode || "?");
    document.title = "Trace2Local — Resonance" + (state.meta.app && state.meta.app !== "?" ? " · " + state.meta.app : "");
  } catch (e) {
    state.metaError = e.message;
    $("env-app").textContent = String(e.message).startsWith("401") ? "acesso protegido" : "app offline";
  }
}

export async function loadEndpoints() {
  try {
    state.endpoints = (await json("/endpoints")) || [];
  } catch (e) {
    state.endpoints = [];
  }
  emit("endpoints");
}

export async function loadRecent() {
  try {
    const list = await json("/executions?limit=100");
    for (const summary of list) {
      const existing = state.executions.get(summary.executionId);
      if (!existing) {
        state.executions.set(summary.executionId, { summary, nodes: new Map(), completed: true, warnings: [] });
      } else {
        existing.summary = Object.assign({}, existing.summary, summary);
        existing.completed = summary.status !== "RUNNING";
      }
    }
    emit("executions");
  } catch (e) { /* acervo vazio ou offline */ }
}

/** Execução completa (árvore). */
export async function loadExecution(id, force) {
  const exec = state.executions.get(id);
  if (exec && exec.full && !force && exec.completed) return exec;
  // em curso: o acervo só guarda execuções concluídas — os nós chegam pelo stream (SSE)
  if (exec && !exec.completed && !force) return exec;
  try {
    const full = await json("/executions/" + encodeURIComponent(id));
    ingestExecution(full, true);
    return state.executions.get(id);
  } catch (e) {
    return exec || null;
  }
}

export async function loadLogs(id, force) {
  if (state.logs.has(id) && !force) return state.logs.get(id);
  try {
    const logs = await json("/executions/" + encodeURIComponent(id) + "/logs");
    state.logs.set(id, logs);
    emit("logs", id);
    return logs;
  } catch (e) {
    return null;
  }
}

export async function loadAssist(id, force) {
  if (state.assist.has(id) && !force) return state.assist.get(id);
  try {
    const a = await json("/executions/" + encodeURIComponent(id) + "/insights");
    state.assist.set(id, a);
    emit("assist", id);
    return a;
  } catch (e) {
    return null;
  }
}

export async function loadStory(id) {
  if (state.story.has(id)) return state.story.get(id);
  try {
    const s = await json("/executions/" + encodeURIComponent(id) + "/story");
    state.story.set(id, s);
    return s;
  } catch (e) {
    return null;
  }
}

export async function loadInsights() {
  try {
    const r = await json("/insights?limit=24");
    state.insights = r.insights || [];
    state.insightsSuppressed = r.suppressed || 0;
    emit("insights");
  } catch (e) { /* motor indisponível */ }
}

export async function loadTopology() {
  try {
    state.topology = await json("/topology");
    emit("topology");
  } catch (e) { /* ignore */ }
  return state.topology;
}

export async function loadIntelligence() {
  try {
    state.intelligence = await json("/intelligence");
    emit("intelligence");
  } catch (e) { /* ignore */ }
  return state.intelligence;
}

export async function loadHealth() {
  try {
    return await json("/health");
  } catch (e) {
    return null;
  }
}

export function ingestExecution(execution, fromSnapshot) {
  const existing = state.executions.get(execution.executionId) || { nodes: new Map(), warnings: [] };
  existing.summary = {
    executionId: execution.executionId, traceId: execution.traceId, status: execution.status,
    trigger: execution.trigger, startedAt: execution.startedAt, duration: execution.duration,
    rootLabel: execution.roots && execution.roots[0] ? execution.roots[0].label : (existing.summary || {}).rootLabel,
    nodeCount: execution.metrics ? execution.metrics.nodeCount : 0,
  };
  existing.metrics = execution.metrics;
  existing.warnings = execution.warnings || [];
  existing.completed = execution.status !== "RUNNING";
  existing.full = true;
  existing.nodes = new Map();
  const collect = (nodes) => {
    for (const n of nodes) {
      const copy = Object.assign({}, n);
      delete copy.children;
      existing.nodes.set(n.nodeId, copy);
      if (n.children) collect(n.children);
    }
  };
  collect(execution.roots || []);
  state.executions.set(execution.executionId, existing);
  emit("executions");
  emit("execution", execution.executionId);
}

function upsertNode(evt) {
  const exec = state.executions.get(evt.executionId) || { nodes: new Map(), warnings: [], completed: false };
  const copy = Object.assign({}, evt.node);
  delete copy.children;
  exec.nodes.set(evt.node.nodeId, copy);
  state.executions.set(evt.executionId, exec);
  emit("execution", evt.executionId);
}

function applyMutation(evt) {
  const exec = state.executions.get(evt.executionId);
  const node = exec && exec.nodes.get(evt.nodeId);
  if (node) {
    node.mutation = evt.mutation;
    emit("execution", evt.executionId);
  }
}

let es = null;
export function connectStream() {
  es = new EventSource(API + "/stream");
  const led = $("conn-led");
  const txt = $("conn-text");
  es.onopen = () => { led.className = "led on"; txt.textContent = "ao vivo"; };
  es.onerror = () => { led.className = "led off"; txt.textContent = "reconectando…"; };
  es.addEventListener("execution.started", (e) => {
    const d = JSON.parse(e.data);
    const exec = state.executions.get(d.executionId) || { nodes: new Map(), warnings: [], completed: false };
    exec.summary = Object.assign({}, exec.summary, { executionId: d.executionId, traceId: d.traceId, trigger: d.trigger, startedAt: d.at, status: "RUNNING" });
    exec.completed = false;
    state.executions.set(d.executionId, exec);
    emit("executions");
    emit("live", d.executionId);
  });
  es.addEventListener("node.upserted", (e) => upsertNode(JSON.parse(e.data)));
  es.addEventListener("node.mutation", (e) => applyMutation(JSON.parse(e.data)));
  es.addEventListener("execution.completed", (e) => {
    const d = JSON.parse(e.data);
    ingestExecution(d.execution, false);
    // caches dependentes da execução ficam velhos
    state.logs.delete(d.executionId);
    state.assist.delete(d.executionId);
    state.story.delete(d.executionId);
    emit("completed", d.executionId);
  });
  es.addEventListener("execution.merged", (e) => {
    // continuação tardia: o consumidor assíncrono foi fundido na árvore do produtor
    const d = JSON.parse(e.data);
    state.executions.delete(d.executionId);
    [state.logs, state.assist, state.story].forEach((m) => { m.delete(d.executionId); m.delete(d.intoExecutionId); });
    emit("executions");
    emit("merged", d);
  });
  es.addEventListener("execution.renamed", (e) => {
    // id provisório → id declarado pela raiz (filhos chegaram antes): mesma execução, nova chave
    const d = JSON.parse(e.data);
    const exec = state.executions.get(d.executionId);
    state.executions.delete(d.executionId);
    if (exec && !state.executions.has(d.toExecutionId)) {
      exec.summary = Object.assign({}, exec.summary, { executionId: d.toExecutionId });
      state.executions.set(d.toExecutionId, exec);
    }
    [state.logs, state.assist, state.story].forEach((m) => m.delete(d.executionId));
    emit("renamed", d);
    emit("executions");
  });
  es.addEventListener("execution.snapshot", (e) => ingestExecution(JSON.parse(e.data).execution, true));
  es.addEventListener("insights.updated", (e) => {
    const d = JSON.parse(e.data);
    (d.executionIds || []).forEach((id) => state.assist.delete(id));
    emit("insights.updated", d);
  });
  es.addEventListener("system.warning", (e) => {
    const d = JSON.parse(e.data);
    const b = $("sb-dropped");
    b.classList.remove("hidden");
    b.textContent = "⚠ " + (d.count || 0) + " evento(s) descartado(s)";
    toast(d.message || "Eventos descartados na borda do buffer", "error");
  });
}

export async function clearAll() {
  await api("/executions", { method: "DELETE" });
  state.executions.clear();
  state.logs.clear();
  state.assist.clear();
  state.story.clear();
  state.selectedId = null;
  state.selectedNodeId = null;
  emit("executions");
  emit("selection");
}
