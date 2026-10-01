/* Visões complementares: NARRATIVA (negócio), PAINEL (acervo + inteligência +
   histórico/baseline por fluxo), COMPARAR (A/B) e INFRA (configuração/IaC ×
   uso real). Tudo via DOM (textContent) — nunca HTML montado com dados. */
import { state, $, el, svg, clear, on, json, ms, pct, truncate, kindOf, icon, ICONS, copy, toast, STATUS, STATUS_CHIP, TRIGGER, shortId, relTime, CATEGORY } from "./core.js";
import { loadStory, loadExecution, loadIntelligence } from "./data.js";
import { engineName } from "./investigate.js";

let cb = {};
export function initLegacy(callbacks) {
  cb = callbacks || {};
  on("selection", () => { if (state.view === "story") renderStory(); });
  on("executions", () => { if (state.view === "dashboard") throttleDash(); });
  on("intelligence", () => { if (state.view === "dashboard") throttleDash(); });
}
let dashTimer = null;
function throttleDash() {
  clearTimeout(dashTimer);
  dashTimer = setTimeout(renderDashboard, 600);
}

function head(title, sub, ...tools) {
  return el("div", { class: "row" }, el("h2", { class: "grow", text: title }), sub ? el("span", { class: "muted", text: sub }) : null, ...tools);
}

/* ============================================================= NARRATIVA */
export async function renderStory() {
  const host = clear($("view-story"));
  const pad = el("div", { class: "view-pad" });
  host.appendChild(pad);
  if (!state.selectedId) {
    pad.append(head("Narrativa", "a execução contada em linguagem de negócio"), el("div", { class: "empty", text: "Selecione uma execução para ler a narrativa." }));
    return;
  }
  pad.appendChild(el("div", { class: "empty", text: "Montando a narrativa…" }));
  const story = await loadStory(state.selectedId);
  clear(pad);
  if (!story || !story.steps) {
    pad.append(head("Narrativa"), el("div", { class: "honest", text: "Narrativa indisponível para esta execução." }));
    return;
  }
  const md = el("button", { type: "button", class: "small" }, icon(ICONS.copy), " copiar (Markdown)");
  md.addEventListener("click", () => copy(storyMarkdown(story), "Narrativa copiada"));
  pad.appendChild(head(story.title || "Narrativa", ms(story.durationMs) + " · " + (STATUS[story.status] || story.status || ""), md));
  if (story.intro) pad.appendChild(el("p", { class: "summary", text: story.intro }));
  const list = el("div", { class: "panel" });
  for (const s of story.steps) {
    const m = kindOf(s.kind);
    const row = el("div", { class: "story-step" + (s.error ? " error" : "") + (state.selectedNodeId === s.nodeId ? " active" : ""), tabindex: "0", role: "button" },
      el("div", { class: "n", text: String(s.order) }),
      el("div", null,
        el("div", { text: s.text }),
        el("div", { class: "meta" },
          el("span", { class: "chip " + m.cls }, m.tag),
          el("span", { text: ms(s.durationMs) }),
          s.mutationSummary ? el("span", { class: "chip data", text: s.mutationSummary }) : null,
          s.error ? el("span", { class: "chip err", text: "falhou" }) : null)));
    row.addEventListener("click", () => cb.selectNode && cb.selectNode(s.nodeId));
    row.addEventListener("keydown", (e) => { if (e.key === "Enter") cb.selectNode && cb.selectNode(s.nodeId); });
    list.appendChild(row);
  }
  pad.appendChild(list);
  if (story.conclusion) pad.appendChild(el("div", { class: "note-box", text: story.conclusion }));
}

function storyMarkdown(story) {
  return ["# " + (story.title || "Narrativa"), "", story.intro || "", "",
    ...story.steps.map((s) => s.order + ". " + s.text + (s.mutationSummary ? " _(" + s.mutationSummary + ")_" : "") + (s.error ? " **— falhou**" : "")),
    "", story.conclusion || ""].join("\n");
}

/* ============================================================= PAINEL */
export async function renderDashboard() {
  const host = clear($("view-dashboard"));
  const pad = el("div", { class: "view-pad" });
  host.appendChild(pad);
  const all = [...state.executions.values()].map((e) => e.summary).filter(Boolean)
    .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || ""));
  const finished = all.filter((s) => s.status !== "RUNNING");
  const failed = finished.filter((s) => s.status === "FAILED");
  const partial = finished.filter((s) => s.status === "PARTIAL" || s.status === "ORPHANED");
  const avg = finished.length ? finished.reduce((a, s) => a + (typeof s.duration === "number" ? s.duration : 0), 0) / finished.length : 0;
  const warnings = [...state.executions.values()].reduce((a, e) => a + (e.warnings || []).length, 0);
  const urgent = (state.insights || []).filter((i) => i.severity === "HIGH" || i.severity === "CRITICAL").length;

  pad.appendChild(head("Painel", "acervo vivo · inteligência · baseline"));
  const cards = el("div", { class: "cards" });
  const stat = (l, v, s, tone) => cards.appendChild(el("div", { class: "stat" }, el("div", { class: "l", text: l }), el("div", { class: "v " + (tone || ""), text: v }), s ? el("div", { class: "s", text: s }) : null));
  stat("EXECUÇÕES", String(finished.length), all.length - finished.length ? (all.length - finished.length) + " em curso" : "concluídas no acervo", "ok");
  stat("FALHAS", String(failed.length), (finished.length ? pct(failed.length / finished.length) : "0%") + " · " + partial.length + " parcial(is)", failed.length ? "err" : "ok");
  stat("DURAÇÃO MÉDIA", ms(avg), "por execução concluída");
  stat("PONTOS DE ATENÇÃO", String((state.insights || []).length), urgent + " alto(s)/crítico(s)", urgent ? "err" : "");
  stat("AVISOS DE HONESTIDADE", String(warnings), "o que o Trace2Local não viu", warnings ? "err" : "");
  pad.appendChild(cards);

  const grid = el("div", { class: "inv" });
  const left = el("div");
  const right = el("div");
  grid.append(left, right);
  pad.appendChild(grid);

  // histórico / baseline por fluxo
  const hist = el("div", { class: "panel", id: "dash-history" }, el("h3", { text: "HISTÓRICO POR FLUXO · baseline local (p50/p95)" }));
  left.appendChild(hist);
  json("/history").then((h) => renderHistory(hist, h)).catch(() => hist.appendChild(el("div", { class: "muted", text: "Histórico indisponível." })));

  // mais lentas / falhas
  const slow = el("div", { class: "panel" }, el("h3", { text: "MAIS LENTAS" }));
  [...finished].sort((a, b) => (b.duration || 0) - (a.duration || 0)).slice(0, 6).forEach((s) => slow.appendChild(execRow(s, ms(s.duration))));
  if (!finished.length) slow.appendChild(el("div", { class: "muted", text: "Nenhuma execução ainda." }));
  left.appendChild(slow);
  const fails = el("div", { class: "panel" }, el("h3", { text: "FALHAS RECENTES" }));
  failed.slice(0, 8).forEach((s) => fails.appendChild(execRow(s, STATUS[s.status])));
  if (!failed.length) fails.appendChild(el("div", { class: "muted", text: "Nenhuma falha registrada." }));
  left.appendChild(fails);

  // inteligência
  const intel = el("div", { class: "panel", id: "dash-intel" }, el("h3", { text: "INTELIGÊNCIA · motores de micro-decisão e regras preditivas" }));
  right.appendChild(intel);
  const st = state.intelligence || await loadIntelligence();
  renderIntelligence(intel, st);

  const trig = el("div", { class: "panel" }, el("h3", { text: "POR GATILHO" }));
  const by = {};
  finished.forEach((s) => { by[s.trigger || "EXTERNAL"] = (by[s.trigger || "EXTERNAL"] || 0) + 1; });
  const tc = el("div", { class: "cards" });
  Object.entries(by).forEach(([t, n]) => tc.appendChild(el("div", { class: "stat" }, el("div", { class: "l", text: (TRIGGER[t] || t).toUpperCase() }), el("div", { class: "v", text: String(n) }))));
  trig.appendChild(tc);
  right.appendChild(trig);
  if (state.focusFlowExec) {
    const id = state.focusFlowExec;
    state.focusFlowExec = null;
    setTimeout(() => {
      const target = pad.querySelector('[data-has-exec~="' + CSS.escape(id) + '"]');
      if (target) { target.scrollIntoView({ block: "center" }); target.classList.add("active"); }
    }, 300);
  }
}

function execRow(s, value) {
  const row = el("div", { class: "dash-row", tabindex: "0", role: "button" },
    el("span", { class: "chip " + (STATUS_CHIP[s.status] || ""), text: STATUS[s.status] || s.status }),
    el("span", { class: "grow", text: s.rootLabel || shortId(s.executionId) }),
    el("span", { class: "muted", text: TRIGGER[s.trigger] || s.trigger || "" }),
    el("span", { class: "mono", text: value }));
  row.addEventListener("click", () => cb.examine && cb.examine(s.executionId));
  return row;
}

function renderHistory(panel, h) {
  const flows = (h && h.flows) || [];
  if (!flows.length) {
    panel.appendChild(el("div", { class: "muted", text: "Sem histórico ainda — cada execução concluída alimenta o baseline local (.trace2local/history). Ele sobrevive à limpeza do acervo." }));
    return;
  }
  for (const f of flows.sort((a, b) => b.samples - a.samples)) {
    const c = f.comparison;
    const change = c && c.change ? c.change : 0;
    const tone = change > 0.25 ? "delta-bad" : change < -0.15 ? "delta-good" : "muted";
    const block = el("div", { class: "story-step", "data-has-exec": (f.series || []).map((s) => s.executionId).join(" ") },
      el("div", { class: "n", text: String(f.samples) }),
      el("div", null,
        el("div", { class: "row" }, el("strong", { class: "grow", text: f.flowKey }),
          el("span", { class: "mono muted", text: "p50 " + ms(f.p50) + " · p95 " + ms(f.p95) })),
        c ? el("div", { class: "meta" },
          el("span", { text: (c.mode === "dia-anterior" ? "ontem → hoje" : "baseline → últimas") + ": " + ms(c.baselineP50) + " → " + ms(c.recentP50) }),
          el("span", { class: tone, text: (change > 0 ? "+" : "") + Math.round(change * 100) + "%" })) : null,
        sparkline(f.series || [])));
    block.addEventListener("click", (e) => {
      const dot = e.target.closest("[data-exec]");
      if (dot && cb.examine) cb.examine(dot.dataset.exec);
    });
    panel.appendChild(block);
  }
}

function sparkline(series) {
  const W = 320, H = 46;
  const s = svg("svg", { class: "spark-svg", viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: "none", role: "img", "aria-label": "Série de durações" });
  if (series.length < 2) return s;
  const max = Math.max(1, ...series.map((p) => p.totalMs));
  const X = (i) => (i / (series.length - 1)) * (W - 8) + 4;
  const Y = (v) => H - 4 - (v / max) * (H - 10);
  const line = series.map((p, i) => (i ? "L" : "M") + X(i).toFixed(1) + "," + Y(p.totalMs).toFixed(1)).join(" ");
  s.appendChild(svg("path", { class: "spark-area", d: line + ` L${X(series.length - 1)},${H} L${X(0)},${H} Z` }));
  s.appendChild(svg("path", { class: "spark-line", d: line }));
  series.forEach((p, i) => {
    const dot = svg("circle", { class: "spark-dot" + (p.errors ? " err" : ""), cx: X(i), cy: Y(p.totalMs), r: p.errors ? 3 : 2, fill: p.errors ? null : "#38dfff", "data-exec": p.executionId });
    dot.appendChild(svg("title", { text: ms(p.totalMs) + (p.queueWaitMs ? " · fila " + ms(p.queueWaitMs) : "") + (p.dbMs ? " · banco " + ms(p.dbMs) : "") + " — " + relTime(p.at) }));
    s.appendChild(dot);
  });
  return s;
}

function renderIntelligence(panel, st) {
  if (!st) {
    panel.appendChild(el("div", { class: "muted", text: "Status indisponível." }));
    return;
  }
  const e = st.engine || {}, p = st.pipeline || {};
  const kv = el("div", { class: "kv" });
  const add = (k, v) => kv.append(el("span", { class: "k", text: k }), el("span", { class: "v", text: v }));
  add("modo", (e.mode || "?") + " → efetivo " + (e.effectiveMode || "?"));
  add("Jev", e.keyConfigured ? "chave configurada · egress " + (e.egressEnabled ? e.egress : "desligado") + " · " + (e.endpointHost || "") : "sem chave — motor determinístico local (" + (e.deterministicVersion || "") + ")");
  add("circuito", (e.circuit || "?") + (e.lastFailure ? " · última falha: " + truncate(e.lastFailure, 80) : ""));
  add("chamadas Jev", (e.liveCalls || 0) + " · falhas " + (e.liveFailures || 0) + " · lat. média " + ms(e.avgLatencyMs || 0));
  add("tokens hoje", (e.inputTokensToday || 0) + " / " + (e.maxInputTokensPerDay || "∞") + " · ~US$ " + (e.estimatedCostTodayUsd || 0));
  add("respostas por motor", Object.entries(e.answersByEngine || {}).map(([k, v]) => engineName(k) + " " + v).join(" · ") || "—");
  if (e.cassette) add("cassete", (e.cassette.entries || 0) + " entradas");
  add("pipeline preditivo", p.enabled === false ? "desligado" : "fila " + (p.queueDepth || 0) + "/" + (p.queueCapacity || 0) + " · analisadas " + (p.analyzed || 0) + " · descartes " + (p.dropped || 0) + " · timeouts " + (p.timeouts || 0));
  add("baseline", (p.historyFlows || 0) + " fluxo(s) · " + (p.historySamples || 0) + " amostra(s)");
  add("insights", (p.insights || 0) + " · silenciados " + (p.suppressed || 0));
  add("LLM (opcional)", st.llm && st.llm.available ? st.llm.description : "desligado (explicações por template local)");
  add("glossário", (st.glossaryTerms || 0) + " termo(s) de negócio");
  panel.appendChild(kv);
  const fb = st.feedback || {};
  const rows = Object.entries(fb);
  if (rows.length) {
    const t = el("table", { class: "diff" }, el("thead", null, el("tr", null, el("th", { text: "ANALISADOR" }), el("th", { text: "ÚTIL" }), el("th", { text: "DESCARTADO" }), el("th", { text: "PRECISÃO (Beta)" }))));
    const tb = el("tbody");
    rows.forEach(([a, f]) => tb.appendChild(el("tr", null, el("td", { text: a }), el("td", { text: String(f.useful) }), el("td", { text: String(f.dismissed) }), el("td", { text: pct(f.precision || 0) }))));
    t.appendChild(tb);
    panel.appendChild(el("div", { class: "sec" }, el("h4", { text: "APRENDIZADO PELO SEU FEEDBACK" }), t));
  }
  panel.appendChild(el("div", { class: "muted", text: "Local-first: sem configuração explícita nada sai da máquina; com Jev habilitado, só estado minimizado e redigido (sem payload, sem segredo)." }));
}

/* ============================================================= COMPARAR */
export function renderCompare() {
  const host = clear($("view-compare"));
  const pad = el("div", { class: "view-pad" });
  host.appendChild(pad);
  pad.appendChild(head("Comparar execuções", "diff de passos, durações e mutações"));
  const all = [...state.executions.values()].map((e) => e.summary).filter((s) => s && s.status !== "RUNNING")
    .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || ""));
  if (all.length < 2) {
    pad.appendChild(el("div", { class: "empty", text: "São necessárias pelo menos 2 execuções concluídas." }));
    return;
  }
  const opt = (s) => el("option", { value: s.executionId, text: shortId(s.executionId) + " · " + (s.rootLabel || "?") + " · " + (STATUS[s.status] || s.status) + " · " + ms(s.duration) });
  const selA = el("select", { "aria-label": "Execução A" }, ...all.map(opt));
  const selB = el("select", { "aria-label": "Execução B" }, ...all.map(opt));
  state.compare.a = state.compare.a && state.executions.has(state.compare.a) ? state.compare.a : all[1].executionId;
  state.compare.b = state.compare.b && state.executions.has(state.compare.b) ? state.compare.b : (state.selectedId || all[0].executionId);
  selA.value = state.compare.a;
  selB.value = state.compare.b;
  const out = el("div");
  const go = el("button", { type: "button", class: "primary" }, "Comparar");
  const run = () => { state.compare.a = selA.value; state.compare.b = selB.value; runCompare(out, selA.value, selB.value); };
  go.addEventListener("click", run);
  pad.appendChild(el("div", { class: "row panel" }, el("span", { text: "A" }), selA, el("strong", { text: "vs" }), el("span", { text: "B" }), selB, go));
  pad.appendChild(out);
  run();
}

async function runCompare(out, a, b) {
  clear(out);
  if (a === b) { out.appendChild(el("div", { class: "empty", text: "Escolha duas execuções diferentes." })); return; }
  out.appendChild(el("div", { class: "empty", text: "Carregando…" }));
  const [ea, eb] = await Promise.all([loadExecution(a), loadExecution(b)]);
  clear(out);
  if (!ea || !eb) { out.appendChild(el("div", { class: "honest", text: "Execução fora do acervo." })); return; }
  const agg = (exec) => {
    const m = new Map();
    for (const n of exec.nodes.values()) {
      const k = n.label || "?";
      const cur = m.get(k) || { count: 0, total: 0, errors: 0 };
      cur.count++;
      cur.total += typeof n.totalTime === "number" ? n.totalTime : 0;
      if (n.status === "ERROR") cur.errors++;
      m.set(k, cur);
    }
    return m;
  };
  const muts = (exec) => [...exec.nodes.values()].filter((n) => n.mutation && n.mutation.key).map((n) => (n.mutation.kind || "") + " " + n.mutation.key);
  const A = agg(ea), B = agg(eb);
  const dA = ea.summary.duration || 0, dB = eb.summary.duration || 0;
  const delta = dB - dA;
  const mA = muts(ea), mB = muts(eb);
  const cards = el("div", { class: "cards" });
  const stat = (l, v, tone, s) => cards.appendChild(el("div", { class: "stat" }, el("div", { class: "l", text: l }), el("div", { class: "v " + (tone || ""), text: v }), s ? el("div", { class: "s", text: s }) : null));
  stat("DURAÇÃO A", ms(dA));
  stat("DURAÇÃO B", ms(dB));
  stat("Δ DURAÇÃO", (delta > 0 ? "+" : "") + ms(Math.abs(delta)).replace(/^/, delta < 0 ? "−" : ""), delta > 0 ? "err" : "ok", dA ? (delta > 0 ? "+" : "") + Math.round((delta / dA) * 100) + "%" : "");
  stat("PASSOS", ea.nodes.size + " → " + eb.nodes.size);
  out.appendChild(cards);
  const onlyB = mB.filter((k) => !mA.includes(k)), onlyA = mA.filter((k) => !mB.includes(k));
  if (onlyA.length || onlyB.length) {
    out.appendChild(el("div", { class: "panel" }, el("h3", { text: "MUTAÇÕES DIFERENTES" }),
      onlyB.length ? el("div", null, el("span", { class: "chip data", text: "só em B" }), " ", onlyB.join(" · ")) : null,
      onlyA.length ? el("div", null, el("span", { class: "chip", text: "só em A" }), " ", onlyA.join(" · ")) : null));
  }
  const t = el("table", { class: "diff" }, el("thead", null, el("tr", null, el("th", { text: "PASSO" }), el("th", { text: "A" }), el("th", { text: "B" }), el("th", { text: "Δ" }))));
  const tb = el("tbody");
  for (const k of [...new Set([...A.keys(), ...B.keys()])].sort()) {
    const x = A.get(k), y = B.get(k);
    let d;
    if (x && !y) d = el("span", { class: "chip", text: "removido" });
    else if (!x && y) d = el("span", { class: "chip data", text: "novo" });
    else {
      const dd = y.total - x.total;
      d = dd === 0 ? el("span", { class: "muted", text: "=" }) : el("span", { class: dd > 0 ? "delta-bad" : "delta-good", text: (dd > 0 ? "+" : "−") + ms(Math.abs(dd)) });
    }
    tb.appendChild(el("tr", null, el("td", { text: k + ((x && x.errors) || (y && y.errors) ? " ✕" : "") }),
      el("td", { class: "mono", text: x ? ms(x.total) + " ×" + x.count : "—" }),
      el("td", { class: "mono", text: y ? ms(y.total) + " ×" + y.count : "—" }),
      el("td", null, d)));
  }
  t.appendChild(tb);
  out.appendChild(el("div", { class: "panel" }, el("h3", { text: "PASSOS" }), t));
}

/* ============================================================= INFRA */
let infraFilter = "";
export async function renderInfra() {
  const host = clear($("view-infra"));
  const pad = el("div", { class: "view-pad" });
  host.appendChild(pad);
  const refresh = el("button", { type: "button", class: "small" }, "reindexar");
  refresh.addEventListener("click", async () => { state.infra = null; renderInfra(); });
  pad.appendChild(head("Infra & DevOps", "configuração/IaC × uso real nas execuções", refresh));
  if (!state.infra) {
    pad.appendChild(el("div", { class: "empty", text: "Indexando application.yml, .env, Terraform, compose…" }));
    try { state.infra = await json("/infra"); } catch (e) { clear(pad).appendChild(el("div", { class: "honest", text: "Índice indisponível: " + e.message })); return; }
    return renderInfra();
  }
  const inf = state.infra;
  pad.appendChild(el("div", { class: "muted", text: (inf.entriesCount || 0) + " entrada(s) em " + (inf.scannedDirs || []).join(", ") + " — valores sensíveis são redigidos na indexação." }));
  const search = el("input", { type: "search", placeholder: "Filtrar (nome, valor, tipo)…", "aria-label": "Filtrar infraestrutura" });
  search.value = infraFilter;
  const list = el("div");
  search.addEventListener("input", () => { infraFilter = search.value.trim().toLowerCase(); paint(); });
  pad.append(el("div", { class: "rail-search" }, search), list);
  const paint = () => {
    clear(list);
    const byType = new Map();
    for (const e of inf.entries || []) {
      if (infraFilter && !(e.type + " " + e.name + " " + e.value).toLowerCase().includes(infraFilter)) continue;
      if (!byType.has(e.type)) byType.set(e.type, []);
      byType.get(e.type).push(e);
    }
    if (!byType.size) list.appendChild(el("div", { class: "empty", text: "Nada indexado com esse filtro." }));
    for (const [type, entries] of byType) {
      const panel = el("div", { class: "panel" }, el("h3", { text: type + " · " + entries.length }));
      for (const e of entries) {
        const card = el("div", { class: "infra-card" },
          el("div", { class: "r1" },
            el("strong", { text: e.name }),
            el("span", { class: "val", title: e.value, text: e.value }),
            e.usedByTotal ? el("span", { class: "chip ok", text: "usado " + e.usedByTotal + "×" }) : el("span", { class: "chip", title: "Nenhuma execução do acervo tocou este recurso", text: "não observado" })),
          el("div", { class: "row" },
            ...(e.sources || []).map((s) => {
              const chip = el("span", { class: "src-chip", title: s.file + " (" + s.kind + ") — copiar caminho", text: s.file.split(/[\\/]/).pop() + ":" + s.line });
              chip.addEventListener("click", () => copy(s.file + ":" + s.line, "Caminho copiado"));
              return chip;
            }),
            ...(e.usedBy || []).slice(0, 4).map((u) => {
              const b = el("button", { type: "button", class: "small ghost", title: "Abrir a execução no passo que usou este recurso" }, truncate(u.label || u.nodeId, 30));
              b.addEventListener("click", () => cb.openTrace && cb.openTrace(u.executionId, u.nodeId));
              return b;
            })));
        panel.appendChild(card);
      }
      list.appendChild(panel);
    }
  };
  paint();
}

export { CATEGORY, toast };
