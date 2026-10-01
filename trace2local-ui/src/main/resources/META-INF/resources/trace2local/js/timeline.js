/* LINHA DO TEMPO — a execução contada em capítulos, com os logs no estilo
   CloudWatch (log group/stream, START/END/REPORT da plataforma, RequestId) presos
   ao span que os produziu. Um cursor de reprodução "narra" o que acontecia em
   cada instante; filas aparecem como espera hachurada entre produtor e consumidor. */
import { state, $, el, svg, clear, on, emit, ms, clock, truncate, kindOf, icon, ICONS, tip, buildTree, selectedExec, isAsyncConsumer, CHAPTER_COLOR, copy } from "./core.js";
import { loadLogs, loadAssist } from "./data.js";

const LABEL_W = 250, ROW_H = 26, AXIS_H = 26, CH_H = 24, PAD_R = 70;
const LOG_CLASS_CHIP = { "erro-de-negocio": "warn", "erro-tecnico": "err", "evento-de-negocio": "ok", diagnostico: "", plataforma: "info" };
const reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let host, chartHost, tableHost, captionTxt, timeTxt, playBtn, groupsHost, countTxt, honestHost;
let playing = false, speed = 1, raf = 0, lastTs = 0;
let follow = true;
let filters = { q: "", levels: new Set(), sources: new Set(), classes: new Set(), group: null };
let model = null;   // {tree, total, rowsY: Map(nodeId -> y), scale, logs, byReq}
let onSelectNodeCb = () => {};

export function initTimeline(onSelectNode) {
  onSelectNodeCb = onSelectNode;
  host = $("view-timeline");
  const root = el("div", { class: "tl" });

  // topo: player + legenda narrada
  playBtn = el("button", { type: "button", class: "icon primary", title: "Reproduzir / pausar (espaço)", "aria-label": "Reproduzir ou pausar" }, icon(ICONS.play));
  playBtn.addEventListener("click", togglePlay);
  const restart = el("button", { type: "button", class: "icon", title: "Voltar ao início (Home)", "aria-label": "Voltar ao início" }, icon(ICONS.back));
  restart.addEventListener("click", () => seek(0));
  const spd = el("select", { "aria-label": "Velocidade da reprodução", title: "Velocidade" },
    ...[0.25, 0.5, 1, 2, 4].map((v) => el("option", { value: String(v), text: v + "×" })));
  spd.value = "1";
  spd.addEventListener("change", () => { speed = Number(spd.value); });
  timeTxt = el("span", { class: "mono muted", text: "—" });
  captionTxt = el("span", { class: "txt", text: "Selecione uma execução para narrar." });
  const followBtn = el("button", { type: "button", class: "small active", title: "Rolar a tabela de logs junto com o cursor" }, "seguir logs");
  followBtn.addEventListener("click", () => { follow = !follow; followBtn.classList.toggle("active", follow); });
  root.appendChild(el("div", { class: "tl-top" },
    el("div", { class: "tl-player" }, playBtn, restart, spd, timeTxt),
    el("div", { class: "tl-caption", "aria-live": "polite" }, el("span", { class: "now", text: "AGORA" }), captionTxt),
    followBtn));

  chartHost = el("div", { class: "tl-chart", tabindex: "0", "aria-label": "Gráfico da linha do tempo — clique para posicionar o cursor; setas avançam" });
  root.appendChild(chartHost);

  // logs (CloudWatch)
  const search = el("input", { type: "search", placeholder: "Filtrar logs (texto, RequestId, logger)…", "aria-label": "Filtrar logs" });
  search.addEventListener("input", () => { filters.q = search.value.trim().toLowerCase(); renderTable(); });
  const lvl = chipGroup(["ERROR", "WARN", "INFO", "DEBUG"], filters.levels, "nível");
  const src = chipGroup(["APP", "PLATFORM", "CLOUDWATCH"], filters.sources, "origem", { APP: "aplicação", PLATFORM: "plataforma (sintética)", CLOUDWATCH: "CloudWatch real" });
  const cls = chipGroup(Object.keys(LOG_CLASS_CHIP), filters.classes, "classe",
    { "erro-de-negocio": "erro de negócio", "erro-tecnico": "erro técnico", "evento-de-negocio": "evento de negócio", diagnostico: "diagnóstico", plataforma: "plataforma" });
  groupsHost = el("div", { class: "lg-groups", "aria-label": "Log groups" });
  countTxt = el("span", { class: "muted" });
  const cp = el("button", { type: "button", class: "small ghost", title: "Copiar as linhas visíveis (formato CloudWatch)" }, icon(ICONS.copy), " copiar");
  cp.addEventListener("click", () => copy(visibleLines().map((l) => (l.timestamp || "") + "\t" + (l.requestId || "-") + "\t" + (l.level || "") + "\t" + l.message).join("\n"), "Linhas copiadas"));
  tableHost = el("div", { class: "tl-table" });
  honestHost = el("div", { class: "view-pad hidden" });
  root.appendChild(el("div", { class: "tl-logs" },
    el("div", { class: "tl-logs-head" }, el("strong", { text: "LOGS" }), search, lvl, src, cls, el("span", { class: "grow" }), countTxt, cp),
    el("div", { class: "tl-logs-head" }, el("span", { class: "muted", text: "log group / stream" }), groupsHost),
    honestHost,
    tableHost));
  host.appendChild(root);

  chartHost.addEventListener("click", (e) => {
    const row = e.target.closest(".tl-row");
    if (row) { onSelectNodeCb(row.dataset.nodeId); }
    const chap = e.target.closest(".tl-chap");
    if (chap) { seek(Number(chap.dataset.from)); highlightChapter(chap.dataset.nodes); return; }
    const s = chartHost.querySelector("svg");
    if (!s || !model) return;
    const r = s.getBoundingClientRect();
    const x = e.clientX - r.left - LABEL_W;
    if (x >= 0) seek(Math.max(0, Math.min(model.total, x / model.scale)));
  });
  chartHost.addEventListener("mousemove", (e) => {
    const lg = e.target.closest(".tl-log");
    if (lg && model) {
      const l = model.logs[Number(lg.dataset.idx)];
      if (l) { tip(e, "<b>" + esc(l.level || "") + "</b> +" + ms(l.offsetMs) + "<br>" + esc(truncate(l.message, 220))); return; }
    }
    const row = e.target.closest(".tl-row");
    if (row && model) {
      const n = model.tree.byId.get(row.dataset.nodeId);
      if (n) {
        tip(e, "<b>" + esc(n.label) + "</b><br>" + esc(kindOf(n.kind).name) + " · +" + ms(n.startMs) + " → +" + ms(n.endMs)
          + " (" + ms(n.durMs) + ")" + (n.waitMs ? "<br><span class='tip-muted'>esperou " + ms(n.waitMs) + " na fila</span>" : ""));
        return;
      }
    }
    tip(e, null);
  });
  chartHost.addEventListener("mouseleave", (e) => tip(e, null));
  chartHost.addEventListener("keydown", (e) => {
    if (!model) return;
    const step = Math.max(1, model.total / 50);
    if (e.key === "ArrowRight") { e.preventDefault(); seek(Math.min(model.total, (state.playhead || 0) + step)); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); seek(Math.max(0, (state.playhead || 0) - step)); }
    else if (e.key === "Home") seek(0);
    else if (e.key === "End") seek(model.total);
    else if (e.key === " ") { e.preventDefault(); togglePlay(); }
  });

  on("selection", () => { stop(); state.playhead = null; if (state.view === "timeline") render(); });
  on("execution", (id) => { if (id === state.selectedId && state.view === "timeline") render(); });
  on("logs", (id) => { if (id === state.selectedId && state.view === "timeline") render(); });
  on("assist", (id) => { if (id === state.selectedId && state.view === "timeline") render(); });
  on("node", () => { if (state.view === "timeline") { paintSelection(); renderTable(); } });
  on("completed", async (id) => {
    if (id === state.selectedId && state.view === "timeline") { await loadLogs(id, true); render(); }
  });
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function chipGroup(values, set, label, names) {
  const g = el("div", { class: "seg", role: "group", "aria-label": "Filtro por " + label });
  for (const v of values) {
    const b = el("button", { type: "button", class: "small", "aria-pressed": "false", title: "Filtrar por " + label + ": " + ((names && names[v]) || v) }, (names && names[v]) || v);
    b.addEventListener("click", () => {
      if (set.has(v)) set.delete(v); else set.add(v);
      b.classList.toggle("active", set.has(v));
      b.setAttribute("aria-pressed", String(set.has(v)));
      renderTable();
    });
    g.appendChild(b);
  }
  return g;
}

/* ------------------------------------------------------------- modelo */
function buildModel(exec) {
  const tree = buildTree(exec);
  const logsJson = state.logs.get(state.selectedId);
  const logs = (logsJson && logsJson.lines) || [];
  const lastLog = logs.reduce((m, l) => Math.max(m, l.offsetMs || 0), 0);
  const total = Math.max(1, ...tree.flat.map((n) => n.endMs), lastLog);
  for (const n of tree.flat) {
    n.waitMs = isAsyncConsumer(n) ? Math.max(0, n.startMs - n.parent.endMs) : 0;
  }
  // RequestId → nó Lambda (linhas START/END/REPORT da plataforma não têm span)
  const byReq = new Map();
  for (const n of tree.flat) {
    const rid = n.attributes && (n.attributes["faas.invocation_id"] || n.attributes["faas.execution"]);
    if (rid) byReq.set(rid, n.nodeId);
  }
  for (const l of logs) {
    l.nodeId = (l.spanId && tree.byId.has(l.spanId)) ? l.spanId : (l.requestId && byReq.get(l.requestId)) || null;
  }
  return { tree, total, logs, byReq, logsJson };
}

/* ------------------------------------------------------------- render */
export async function render() {
  const exec = selectedExec();
  clear(chartHost);
  if (!exec || !exec.nodes.size) {
    model = null;
    chartHost.appendChild(el("div", { class: "empty", text: "Selecione uma execução no painel lateral — a linha do tempo conta a execução em capítulos e prende cada linha de log ao passo que a produziu." }));
    clear(tableHost);
    clear(groupsHost);
    captionTxt.textContent = "Selecione uma execução para narrar.";
    timeTxt.textContent = "—";
    return;
  }
  if (!state.logs.has(state.selectedId)) loadLogs(state.selectedId);
  if (!state.assist.has(state.selectedId) && exec.completed) loadAssist(state.selectedId);
  model = buildModel(exec);
  renderChart();
  renderGroups();
  renderTable();
  paintPlayhead();
}

function renderChart() {
  const { tree, total } = model;
  const assist = state.assist.get(state.selectedId);
  const chapters = (assist && assist.chapters) || [];
  const width = Math.max(720, chartHost.clientWidth - 32);
  const plotW = width - LABEL_W - PAD_R;
  model.scale = plotW / total;
  const rows = tree.flat;
  const top = AXIS_H + (chapters.length ? CH_H + 8 : 0);
  const unattached = model.logs.filter((l) => !l.nodeId).length;
  const height = top + rows.length * ROW_H + (unattached ? ROW_H : 0) + 10;
  const s = svg("svg", { width, height, role: "img", "aria-label": "Linha do tempo com " + rows.length + " passos" });
  s.appendChild(svg("defs", null,
    svg("pattern", { id: "hatch", width: 6, height: 6, patternUnits: "userSpaceOnUse", patternTransform: "rotate(45)" },
      svg("rect", { width: 6, height: 6, fill: "rgba(245,176,65,0.08)" }),
      svg("line", { x1: 0, y1: 0, x2: 0, y2: 6, stroke: "rgba(245,176,65,0.55)", "stroke-width": 2 }))));
  const X = (t) => LABEL_W + t * model.scale;

  // eixo + grade
  const nTicks = Math.max(2, Math.min(10, Math.floor(plotW / 90)));
  for (let i = 0; i <= nTicks; i++) {
    const t = (total * i) / nTicks;
    s.appendChild(svg("line", { class: "tl-grid", x1: X(t), x2: X(t), y1: AXIS_H - 4, y2: height }));
    s.appendChild(svg("text", { class: "tl-tick", x: X(t) + 3, y: 14, text: "+" + ms(t) }));
  }
  s.appendChild(svg("line", { class: "tl-axis", x1: LABEL_W, x2: LABEL_W + plotW, y1: AXIS_H - 4, y2: AXIS_H - 4 }));

  // capítulos (narrativa)
  if (chapters.length) {
    s.appendChild(svg("text", { class: "tl-tick", x: 8, y: AXIS_H + 16, text: "CAPÍTULOS" }));
    for (const c of chapters) {
      const x1 = X(c.fromMs), w = Math.max(c.kind === "desfecho" ? 6 : 4, X(c.toMs) - x1);
      const g = svg("g", { class: "tl-chap", "data-from": c.fromMs, "data-nodes": (c.nodeIds || []).join(","), role: "button", tabindex: "-1" });
      g.appendChild(svg("rect", { class: "tl-ch", x: x1, y: AXIS_H + 2, width: w, height: CH_H - 4, rx: 5, fill: CHAPTER_COLOR[c.kind] || "#919bad" }));
      if (w > 60) g.appendChild(svg("text", { class: "tl-ch-txt", x: x1 + 6, y: AXIS_H + 16, text: truncate(c.index + ". " + c.title, Math.floor(w / 6.4)) }));
      g.appendChild(svg("title", { text: c.index + ". " + c.title + " — " + (c.summary || "") }));
      s.appendChild(g);
    }
  }

  // esperas em fila também são "capítulos": é onde o tempo do fluxo assíncrono costuma ir
  if (chapters.length) {
    for (const n of tree.flat) {
      if (!n.waitMs || n.waitMs < total * 0.04) continue;
      const x1 = X(n.parent.endMs), w = Math.max(4, X(n.startMs) - x1);
      const g = svg("g", { class: "tl-chap", "data-from": n.parent.endMs, "data-nodes": n.parent.nodeId + "," + n.nodeId, role: "button", tabindex: "-1" });
      g.appendChild(svg("rect", { class: "tl-wait", x: x1, y: AXIS_H + 2, width: w, height: CH_H - 4 }));
      if (w > 90) g.appendChild(svg("text", { class: "tl-wait-txt", x: x1 + 6, y: AXIS_H + 16, text: truncate("⧗ espera na fila " + ms(n.waitMs) + " até " + n.label, Math.floor(w / 6.2)) }));
      g.appendChild(svg("title", { text: "Espera na fila: a mensagem ficou " + ms(n.waitMs) + " entre a publicação e o início de " + n.label }));
      s.appendChild(g);
    }
  }

  // linhas (passos)
  model.rowsY = new Map();
  rows.forEach((n, i) => {
    const y = top + i * ROW_H;
    model.rowsY.set(n.nodeId, y);
    const m = kindOf(n.kind);
    const g = svg("g", { class: "tl-row", "data-node-id": n.nodeId, "data-start": n.startMs });
    g.appendChild(svg("rect", { class: "tl-row-bg", x: 0, y, width, height: ROW_H }));
    g.appendChild(svg("text", { class: "tl-row-lbl" + (n.status === "ERROR" ? " err" : ""), x: 8 + Math.min(n.depth, 8) * 12, y: y + 17,
      text: truncate((n.status === "ERROR" ? "✕ " : "") + (n.label || "?"), 34 - Math.min(n.depth, 8) * 2) }));
    if (n.waitMs > 0) {
      const wx = X(n.parent.endMs);
      g.appendChild(svg("rect", { class: "tl-wait", x: wx, y: y + 7, width: Math.max(2, X(n.startMs) - wx), height: ROW_H - 14 }));
      if (X(n.startMs) - wx > 70) g.appendChild(svg("text", { class: "tl-wait-txt", x: wx + 4, y: y + 4, text: "fila " + ms(n.waitMs) }));
    }
    const bw = Math.max(2, n.durMs * model.scale);
    g.appendChild(svg("rect", { class: "tl-bar" + (n.status === "ERROR" ? " err" : ""), x: X(n.startMs), y: y + 6, width: bw, height: ROW_H - 12, fill: m.color }));
    if (n.selfTime > 0 && n.children.length) {
      // trecho próprio x tempo nos filhos: a borda clara mostra quanto o passo "trabalhou sozinho"
      g.appendChild(svg("rect", { x: X(n.startMs), y: y + ROW_H - 8, width: Math.max(1, Math.min(bw, n.selfTime * model.scale)), height: 2, fill: "#ffffff", opacity: 0.55 }));
    }
    g.appendChild(svg("text", { class: "tl-dur", x: X(n.startMs) + bw + 5, y: y + 17, text: ms(n.durMs) }));
    s.appendChild(g);
  });
  // logs como marcadores
  const lostY = top + rows.length * ROW_H;
  if (unattached) s.appendChild(svg("text", { class: "tl-row-lbl", x: 8, y: lostY + 17, text: "logs sem span (" + unattached + ")" }));
  model.logs.forEach((l, idx) => {
    const y = l.nodeId ? model.rowsY.get(l.nodeId) : lostY;
    const lvl = l.platform ? "PLATFORM" : (l.level || "INFO").toUpperCase();
    s.appendChild(svg("rect", { class: "tl-log " + lvl, "data-idx": idx, x: X(Math.max(0, Math.min(total, l.offsetMs || 0))) - 2, y: y + 3, width: 4, height: ROW_H - 6, rx: 1.5 }));
  });
  // cursor
  const ph = svg("g", { id: "tl-ph" },
    svg("line", { class: "tl-playhead", x1: 0, x2: 0, y1: AXIS_H - 6, y2: height }),
    svg("path", { class: "tl-playhead-cap", d: "M-6,0 L6,0 L0,8 Z", transform: `translate(0,${AXIS_H - 12})` }));
  s.appendChild(ph);
  chartHost.appendChild(s);
  paintSelection();
}

function paintSelection() {
  chartHost.querySelectorAll(".tl-row").forEach((g) => g.classList.toggle("sel", g.dataset.nodeId === state.selectedNodeId));
}

function highlightChapter(nodesCsv) {
  const ids = new Set(String(nodesCsv || "").split(",").filter(Boolean));
  state.highlight = ids.size ? { nodeIds: ids, reason: "capítulo" } : null;
  emit("highlight");
}

function renderGroups() {
  clear(groupsHost);
  const j = model.logsJson;
  const groups = (j && j.groups) || [];
  if (!groups.length) {
    groupsHost.appendChild(el("span", { class: "muted", text: "—" }));
    return;
  }
  const all = el("button", { type: "button", class: "small" + (!filters.group ? " active" : "") }, "todos");
  all.addEventListener("click", () => { filters.group = null; renderGroups(); renderTable(); });
  groupsHost.appendChild(all);
  for (const g of groups) {
    const b = el("button", { type: "button", class: "small" + (filters.group === g.logGroup ? " active" : ""), title: (g.logStreams || []).join("\n") },
      g.logGroup, el("span", { class: "muted", text: " · " + (g.logStreams || []).length + " stream(s)" }));
    b.addEventListener("click", () => { filters.group = g.logGroup; renderGroups(); renderTable(); });
    groupsHost.appendChild(b);
  }
  if (j.requestIds && j.requestIds.length) {
    groupsHost.appendChild(el("span", { class: "chip", title: "RequestIds Lambda correlacionados a esta execução" }, "RequestId " + j.requestIds.map((r) => r.slice(0, 8)).join(", ")));
  }
}

function classOf(l) {
  const a = state.assist.get(state.selectedId);
  return a && a.logs ? a.logs[String(l.index)] : null;
}

function visibleLines() {
  if (!model) return [];
  return model.logs.filter((l) => {
    const lvl = (l.level || "INFO").toUpperCase();
    if (filters.levels.size && !filters.levels.has(lvl)) return false;
    if (filters.sources.size && !filters.sources.has(l.source || "APP")) return false;
    if (filters.group && l.logGroup !== filters.group) return false;
    if (filters.classes.size) {
      const c = classOf(l);
      if (!c || !filters.classes.has(c.choice)) return false;
    }
    if (filters.q) {
      const hay = [l.message, l.logger, l.requestId, l.logGroup, l.logStream].join(" ").toLowerCase();
      if (!hay.includes(filters.q)) return false;
    }
    return true;
  });
}

function renderTable() {
  clear(tableHost);
  clear(honestHost).classList.add("hidden");
  if (!model) return;
  const j = model.logsJson;
  if (!j) {
    tableHost.appendChild(el("div", { class: "empty", text: "Carregando logs…" }));
    return;
  }
  const lines = visibleLines();
  countTxt.textContent = lines.length + " de " + model.logs.length + " linha(s)"
    + (j.bySource ? " · " + Object.entries(j.bySource).map(([k, v]) => k.toLowerCase() + " " + v).join(" · ") : "");
  if (!model.logs.length) {
    honestHost.classList.remove("hidden");
    honestHost.appendChild(el("div", { class: "honest" },
      "Nenhuma linha de log correlacionada a esta execução. Em modo Companion (Lambda) o Trace2Local captura o stdout/stderr da função "
      + "e sintetiza START/END/REPORT; com a Station, defina TRACE2LOCAL_CLOUDWATCH_ENDPOINT (ex.: LocalStack) para ler o CloudWatch Logs real. "
      + "Logs de aplicação precisam do traceId/spanId no MDC para serem presos ao passo certo."));
    return;
  }
  const table = el("table", null,
    el("thead", null, el("tr", null,
      el("th", { text: "HORA" }), el("th", { text: "+T" }), el("th", { text: "NÍVEL" }), el("th", { text: "CLASSE" }),
      el("th", { text: "ORIGEM" }), el("th", { text: "MENSAGEM" }))));
  const body = el("tbody");
  for (const l of lines) {
    const lvl = l.platform ? "PLATFORM" : (l.level || "INFO").toUpperCase();
    const c = classOf(l);
    const tr = el("tr", { class: lvl + (l.platform ? " platform" : "") + (l.nodeId && l.nodeId === state.selectedNodeId ? " sel" : ""),
      "data-offset": l.offsetMs || 0, "data-node-id": l.nodeId || "" },
    el("td", { class: "ts", title: l.aligned ? "carimbo observado: " + clock(l.timestamp) : null,
      text: l.aligned && model.tree.t0 ? clock(new Date(model.tree.t0 + (l.offsetMs || 0)).toISOString()) + "≈" : clock(l.timestamp) }),
    el("td", { class: "ts", title: l.aligned ? "Alinhado à borda do span da invocação (carimbo observado: +" + ms(l.observedOffsetMs) + " — o LocalStack carimba na ingestão)" : null,
      text: "+" + ms(l.offsetMs) + (l.aligned ? " ≈" : "") }),
    el("td", null, el("span", { class: "lvl " + lvl, text: l.platform ? "PLATAF." : lvl })),
    el("td", null, c ? el("span", { class: "chip " + (LOG_CLASS_CHIP[c.choice] || ""), title: (c.engine || "") + " · " + Math.round((c.confidence || 0) * 100) + "% — " + (c.rationale || ""), text: c.label }) : null),
    el("td", null,
      el("div", { class: "src", text: l.source === "CLOUDWATCH" ? "CloudWatch" : l.source === "PLATFORM" ? "plataforma (sintética)" : "aplicação" }),
      l.logStream ? el("div", { class: "src", title: (l.logGroup || "") + " / " + l.logStream, text: truncate(l.logStream, 26) }) : null),
    el("td", { class: "msg", text: l.message }));
    tr.addEventListener("click", () => {
      seek(Math.max(0, l.offsetMs || 0));
      if (l.nodeId) onSelectNodeCb(l.nodeId);
    });
    body.appendChild(tr);
  }
  table.appendChild(body);
  tableHost.appendChild(table);
  if (j.dropped) tableHost.appendChild(el("div", { class: "honest", text: j.dropped + " linha(s) descartada(s) pelo limite do LogStore — a lista pode estar incompleta." }));
  paintPlayhead();
}

/* ------------------------------------------------------------- reprodução */
function togglePlay() {
  if (!model) return;
  if (playing) { stop(); return; }
  if (state.playhead == null || state.playhead >= model.total) state.playhead = 0;
  playing = true;
  playBtn.replaceChildren(icon(ICONS.pause));
  lastTs = performance.now();
  // a execução inteira é narrada em ~8 s (1×), qualquer que seja sua duração real
  const msPerReal = model.total / 8000;
  const frame = (now) => {
    if (!playing || !model) return;
    const dt = now - lastTs;
    lastTs = now;
    state.playhead = Math.min(model.total, state.playhead + dt * msPerReal * speed);
    paintPlayhead();
    emit("playhead", state.playhead);
    if (state.playhead >= model.total) { stop(); return; }
    raf = requestAnimationFrame(frame);
  };
  if (reduceMotion) {
    // sem animação contínua: avança por capítulos
    stepByChapters();
    return;
  }
  raf = requestAnimationFrame(frame);
}

function stepByChapters() {
  const a = state.assist.get(state.selectedId);
  const marks = ((a && a.chapters) || []).map((c) => c.fromMs).concat([model.total]);
  let i = 0;
  const next = () => {
    if (!playing || i >= marks.length) { stop(); return; }
    seek(marks[i++]);
    setTimeout(next, 1400 / speed);
  };
  next();
}

function stop() {
  playing = false;
  cancelAnimationFrame(raf);
  if (playBtn) playBtn.replaceChildren(icon(ICONS.play));
}

export function seek(t) {
  state.playhead = t;
  paintPlayhead();
  emit("playhead", t);
}

function paintPlayhead() {
  if (!model) return;
  const t = state.playhead;
  const ph = chartHost.querySelector("#tl-ph");
  const active = t != null;
  if (ph) {
    ph.classList.toggle("hidden", !active);
    if (active) ph.setAttribute("transform", `translate(${LABEL_W + t * model.scale},0)`);
  }
  chartHost.querySelectorAll(".tl-row").forEach((g) => {
    const future = active && Number(g.dataset.start) > t;
    g.querySelectorAll(".tl-bar").forEach((b) => b.classList.toggle("future", future));
  });
  let lastVisible = null;
  tableHost.querySelectorAll("tbody tr").forEach((tr) => {
    const future = active && Number(tr.dataset.offset) > t;
    tr.classList.toggle("future", future);
    if (!future) lastVisible = tr;
  });
  if (active && playing && follow && lastVisible) lastVisible.scrollIntoView({ block: "nearest" });
  timeTxt.textContent = active ? "+" + ms(t) + " / " + ms(model.total) : ms(model.total);
  captionTxt.textContent = narrate(t);
}

/** Legenda "AGORA": capítulo + passo mais profundo ativo + última linha de log. */
function narrate(t) {
  if (!model) return "";
  if (t == null) {
    const root = model.tree.roots[0];
    return "Execução " + (root ? "“" + root.label + "”" : "") + " — " + model.tree.flat.length + " passo(s) em " + ms(model.total)
      + ". Aperte ▶ para narrar, clique no gráfico para posicionar o cursor.";
  }
  const a = state.assist.get(state.selectedId);
  const chap = ((a && a.chapters) || []).filter((c) => c.fromMs <= t).pop();
  let deepest = null;
  for (const n of model.tree.flat) {
    if (n.startMs <= t && t <= Math.max(n.endMs, n.startMs + 0.5) && (!deepest || n.depth >= deepest.depth)) deepest = n;
  }
  const waiting = model.tree.flat.find((n) => n.waitMs > 0 && t > n.parent.endMs && t < n.startMs);
  const lastLog = model.logs.filter((l) => (l.offsetMs || 0) <= t && !l.platform).pop();
  const lastPlatform = model.logs.filter((l) => (l.offsetMs || 0) <= t && l.platform).pop();
  const parts = [];
  if (chap) parts.push("Cap. " + chap.index + " · " + chap.title);
  if (waiting) parts.push("mensagem aguardando na fila há " + ms(t - waiting.parent.endMs) + " até " + waiting.label);
  else if (deepest) parts.push(kindOf(deepest.kind).name + ": " + deepest.label + (deepest.mutation && deepest.mutation.kind !== "READ_ONLY" ? " (" + String(deepest.mutation.kind).toLowerCase() + " de dados)" : "") + (deepest.status === "ERROR" ? " — falhou" : ""));
  if (lastPlatform && (!lastLog || (lastPlatform.offsetMs || 0) > (lastLog.offsetMs || 0))) parts.push(platformSays(lastPlatform.message));
  else if (lastLog) parts.push("log: " + truncate(lastLog.message, 90));
  return parts.join(" — ") || "+" + ms(t);
}

/** START/END/REPORT do CloudWatch em português de gente. */
function platformSays(msg) {
  const m = String(msg || "");
  if (m.startsWith("INIT_START")) return "plataforma: cold start — a JVM da função está subindo";
  if (m.startsWith("START")) return "plataforma: invocação iniciada (" + (m.match(/RequestId:\s*([\w-]{8})/) || [])[1] + "…)";
  if (m.startsWith("END")) return "plataforma: invocação terminou";
  if (m.startsWith("REPORT")) {
    const d = (m.match(/Duration:\s*([\d.]+)\s*ms/) || [])[1];
    const mem = (m.match(/Max Memory Used:\s*(\d+)\s*MB/) || [])[1];
    const init = (m.match(/Init Duration:\s*([\d.]+)\s*ms/) || [])[1];
    return "plataforma: relatório — " + (d ? d.replace(".", ",") + " ms" : "?") + (mem ? " · " + mem + " MB de memória" : "") + (init ? " · cold start " + init.replace(".", ",") + " ms" : "");
  }
  return "plataforma: " + truncate(m, 80);
}

export function stopPlayback() { stop(); }
