/* INVESTIGAÇÃO — o "laudo" da execução em duas visões que somam esforços:
   EXECUTIVA (desfecho, prontidão para homologação, risco, regras de negócio,
   checklist auditável) e TÉCNICA (papéis, hotspots, caminho crítico, anomalias,
   insights preditivos). Toda decisão mostra o MOTOR que decidiu (fato, regra
   determinística, Jev, fusão) e a confiança — fato ≠ correlação ≠ hipótese. */
import { state, $, el, clear, on, emit, json, ms, pct, truncate, kindOf, icon, ICONS, toast, copy, CATEGORY, STATUS, selectedExec, shortId } from "./core.js";
import { loadAssist, loadInsights } from "./data.js";

let host;
let mode = "both";
let cb = {};

export function initInvestigate(callbacks) {
  cb = callbacks || {};
  host = $("view-investigate");
  on("selection", () => { if (state.view === "investigate") render(); });
  on("assist", (id) => { if (id === state.selectedId && state.view === "investigate") render(); });
  on("insights", () => { if (state.view === "investigate" && state.focusInsight) render(); });
}

/* ------------------------------------------------------------- render */
export async function render() {
  clear(host);
  const pad = el("div", { class: "view-pad" });
  host.appendChild(pad);

  // insight em foco (vindo do painel INSIGHTS / anatomia / paleta)
  const focus = state.focusInsight && (state.insights || []).find((i) => i.fingerprint === state.focusInsight);
  if (focus) {
    const head = el("div", { class: "row" },
      el("h3", { class: "grow", text: "INSIGHT EM FOCO" }),
      el("button", { type: "button", class: "small ghost", onclick: () => { state.focusInsight = null; render(); } }, icon(ICONS.x), " fechar"));
    pad.appendChild(el("div", { class: "panel" }, head, insightCard(focus, { focus: true, open: true })));
  }

  const exec = selectedExec();
  if (!exec) {
    pad.appendChild(el("div", { class: "empty", text: "Selecione uma execução: a Investigação monta o laudo executivo (homologação de regras de negócio) e o técnico (gargalos, anomalias e insights preditivos)." }));
    return;
  }
  if (!exec.completed) {
    pad.appendChild(el("div", { class: "empty", text: "Execução em curso — o laudo é emitido quando ela concluir (a árvore e a linha do tempo já mostram o progresso ao vivo)." }));
    return;
  }
  let a = state.assist.get(state.selectedId);
  if (!a) {
    pad.appendChild(el("div", { class: "empty", text: "Emitindo o laudo… (micro-decisões locais; Jev somente se habilitado)" }));
    a = await loadAssist(state.selectedId);
    if (!a) {
      clear(pad).appendChild(el("div", { class: "honest", text: "Laudo indisponível para esta execução (motor de inteligência desligado ou execução fora do acervo)." }));
      return;
    }
    return; // o evento "assist" re-renderiza
  }

  // barra de ferramentas
  const seg = el("div", { class: "seg", role: "group", "aria-label": "Visões do laudo" });
  for (const [k, l] of [["both", "Executiva + Técnica"], ["exec", "Executiva"], ["tech", "Técnica"]]) {
    const b = el("button", { type: "button", class: mode === k ? "active" : null, "aria-pressed": String(mode === k) }, l);
    b.addEventListener("click", () => { mode = k; render(); });
    seg.appendChild(b);
  }
  const md = el("button", { type: "button", class: "small", title: "Copiar o laudo de homologação em Markdown (para PR, Jira, ata)" }, icon(ICONS.copy), " laudo de homologação");
  md.addEventListener("click", () => copy(homologationMarkdown(exec, a), "Laudo de homologação copiado (Markdown)"));
  pad.appendChild(el("div", { class: "row", id: "inv-toolbar" }, seg, el("span", { class: "grow" }), provenanceSummary(a), md));

  const grid = el("div", { class: "inv" + (mode === "exec" ? " only-exec" : mode === "tech" ? " only-tech" : "") });
  grid.appendChild(executiveColumn(exec, a));
  grid.appendChild(technicalColumn(exec, a));
  pad.appendChild(grid);
}

function provenanceSummary(a) {
  const st = a.stats || {};
  const be = st.byEngine || {};
  const parts = Object.entries(be).map(([k, v]) => engineName(k) + " " + v);
  return el("span", { class: "prov", title: "Quem decidiu cada uma das " + (st.questions || 0) + " micro-decisões deste laudo (" + (st.latencyMs || 0) + " ms)" },
    "decisões: ", parts.join(" · ") || "—", st.needsReview ? el("span", { class: "chip warn", text: st.needsReview + " p/ revisão humana" }) : null);
}

export function engineName(e) {
  return { jev: "Jev", "jev-replay": "Jev (cassete)", replay: "Jev (cassete)", "jev-deterministic": "regra local",
    deterministic: "regra local", fact: "fato", fusion: "fusão Jev+regra" }[e] || e || "?";
}
function engineCls(e) {
  return { jev: "jev", "jev-replay": "jev", replay: "jev", fact: "fact", "jev-deterministic": "det", deterministic: "det", fusion: "fusion" }[e] || "";
}

function prov(d) {
  if (!d) return null;
  return el("div", { class: "prov", title: d.rationale || "" },
    el("span", { class: "e " + engineCls(d.engine), text: engineName(d.engine) }),
    el("span", { text: "· " + Math.round((d.confidence || 0) * 100) + "%" }),
    d.alternative ? el("span", { text: "· alt. " + engineName(d.alternative.engine) + ": " + d.alternative.label }) : null,
    d.needsReview ? el("span", { class: "chip warn", text: "revisar" }) : null);
}

function meter(level, max, cls) {
  const m = el("div", { class: "meter", role: "meter", "aria-valuemin": "0", "aria-valuemax": String(max), "aria-valuenow": String(level) });
  for (let i = 0; i <= max; i++) m.appendChild(el("i", { class: i <= level ? "on" : null }));
  return m;
}

/* ------------------------------------------------------------- visão executiva */
function executiveColumn(exec, a) {
  const x = a.executive || {};
  const col = el("div", { class: "exec" });
  const outcome = x.outcome || {};
  const outcomeTone = { sucesso: "ok", "recusa-protegida": "info", "falha-de-negocio": "warn", "falha-tecnica": "err", incompleto: "warn" }[outcome.choice] || "";

  const verdict = el("div", { class: "panel" },
    el("h3", null, "VISÃO EXECUTIVA", el("span", { class: "grow" }), el("span", { class: "chip " + outcomeTone, text: outcome.label || "—" })),
    el("p", { class: "headline", text: x.headline || "" }),
    el("p", { class: "summary", text: x.summary || "" }));
  const gauges = el("div", { class: "gauges" });
  const g1 = el("div", { class: "gauge" }, el("div", { class: "lab", text: "DESFECHO" }), el("div", { class: "val", text: outcome.label || "—" }),
    meter(Math.round((outcome.confidence || 0) * 4), 4), el("div", { class: "src" }, prov(outcome)));
  const r = x.readiness || {};
  const g2 = el("div", { class: "gauge ready" }, el("div", { class: "lab", text: "PRONTIDÃO P/ HOMOLOGAR" }), el("div", { class: "val", text: r.label || "—" }),
    meter(r.level ?? 0, r.max ?? 3), el("div", { class: "src" }, prov(r)));
  const k = x.risk || {};
  const g3 = el("div", { class: "gauge risk" + ((k.level ?? 0) >= 2 ? " high" : "") }, el("div", { class: "lab", text: "RISCO" }), el("div", { class: "val", text: k.label || "—" }),
    meter(k.level ?? 0, k.max ?? 3), el("div", { class: "src" }, prov(k)));
  gauges.append(g1, g2, g3);
  verdict.appendChild(gauges);
  verdict.appendChild(el("div", { class: "row" },
    yesNo("Dados alterados", x.dataChanged), yesNo("Efeitos assíncronos completos", x.asyncComplete)));
  col.appendChild(verdict);

  // regras de negócio x árvore de funcionalidade
  const rules = x.rules || [];
  const t = x.rulesTally || {};
  const rp = el("div", { class: "panel" },
    el("h3", { text: "REGRAS DE NEGÓCIO × FLUXO DA FUNCIONALIDADE" }),
    el("div", { class: "row tally" },
      el("span", { class: "chip ok", text: (t.respeitada || 0) + " respeitada(s)" }),
      t.violada ? el("span", { class: "chip err", text: t.violada + " violada(s)" }) : null,
      t["nao-exercitada"] ? el("span", { class: "chip", text: t["nao-exercitada"] + " não exercitada(s)" }) : null,
      t.inconclusiva ? el("span", { class: "chip warn", text: t.inconclusiva + " inconclusiva(s)" }) : null));
  if (!rules.length) {
    rp.appendChild(el("div", { class: "honest", text: "Sem glossário de negócio: crie trace2local-business.md (ou TRACE2LOCAL_BUSINESS_FILE) com termos e regras — o laudo cruza cada regra com os passos da árvore e emite um veredito auditável." }));
  } else {
    const tb = el("table", { class: "rules" });
    for (const rule of rules) {
      const tr = el("tr", null,
        el("td", null,
          el("div", { class: "term", text: rule.term + (rule.explicit ? "" : " (termo)") }),
          el("div", { class: "txt", text: truncate(rule.text, 220) }),
          el("div", { class: "why", text: rule.rationale || "" }),
          el("div", { class: "row" }, prov(rule),
            (rule.nodeIds || []).length ? el("button", { type: "button", class: "small ghost", onclick: () => highlight(rule.nodeIds, "regra " + rule.term) }, icon(ICONS.eye), " ver no fluxo (" + rule.nodeIds.length + ")") : null)),
        el("td", null, el("span", { class: "verdict " + rule.choice, text: rule.label })));
      tb.appendChild(tr);
    }
    rp.appendChild(tb);
  }
  col.appendChild(rp);

  // checklist
  const ck = el("ul", { class: "checklist" });
  for (const c of x.checklist || []) {
    ck.appendChild(el("li", null,
      el("span", { class: "ck " + (c.ok ? "ok" : "no"), text: c.ok ? "✓" : "✕", "aria-label": c.ok ? "ok" : "pendente" }),
      el("div", { class: "grow" }, el("div", { text: c.item }), el("div", { class: "muted", text: c.detail }))));
  }
  col.appendChild(el("div", { class: "panel" }, el("h3", { text: "CHECKLIST DE HOMOLOGAÇÃO · determinístico, auditável" }), ck));
  return col;
}

function yesNo(label, d) {
  if (!d) return null;
  return el("span", { class: "chip " + (d.yes ? "data" : ""), title: (d.rationale || "") + " — " + engineName(d.engine) + " " + Math.round((d.confidence || 0) * 100) + "%" },
    label + ": " + (d.yes ? "sim" : "não"));
}

/* ------------------------------------------------------------- visão técnica */
function technicalColumn(exec, a) {
  const t = a.technical || {};
  const col = el("div", { class: "tech" });

  // hotspots (ranking numérico)
  const hp = el("div", { class: "panel" }, el("h3", { text: "HOTSPOTS · latência própria × criticidade × erro" }));
  const maxScore = Math.max(0.001, ...(t.hotspots || []).map((h) => h.score));
  for (const h of t.hotspots || []) {
    const bar = el("div", { class: "bar" }, el("i", { class: h.failed ? "err" : null }));
    bar.firstChild.style.width = Math.max(3, (h.score / maxScore) * 100) + "%";
    const row = el("div", { class: "hot", tabindex: "0", role: "button", title: "self " + ms(h.selfMs) + " de " + ms(h.totalMs) + " · " + pct(h.share) + " da execução" },
      el("span", { class: "rk", text: "#" + h.rank }),
      el("span", { class: "nowrap", text: truncate(h.label, 48) + " · " + kindOf(h.kind).tag }),
      bar,
      el("span", { class: "sc", text: ms(h.selfMs) }));
    row.addEventListener("click", () => cb.selectNode && cb.selectNode(h.nodeId));
    row.addEventListener("keydown", (e) => { if (e.key === "Enter") cb.selectNode && cb.selectNode(h.nodeId); });
    hp.appendChild(row);
  }
  col.appendChild(hp);

  // caminho crítico
  const path = (t.criticalPath || []).map((id) => exec.nodes.get(id)).filter(Boolean);
  if (path.length) {
    const cp = el("div", { class: "panel" },
      el("h3", null, "CAMINHO CRÍTICO", el("span", { class: "grow" }),
        el("button", { type: "button", class: "small ghost", onclick: () => highlight(t.criticalPath, "caminho crítico") }, icon(ICONS.eye), " destacar")));
    const chain = el("div", { class: "row" });
    path.forEach((n, i) => {
      if (i) chain.appendChild(el("span", { class: "muted", text: "→" }));
      const chip = el("button", { type: "button", class: "small ghost", title: ms(n.totalTime) }, kindOf(n.kind).tag + " " + truncate(n.label, 28));
      chip.addEventListener("click", () => cb.selectNode && cb.selectNode(n.nodeId));
      chain.appendChild(chip);
    });
    cp.appendChild(chain);
    col.appendChild(cp);
  }

  // anomalias
  const an = t.anomalies || [];
  const ap = el("div", { class: "panel" }, el("h3", { text: "ANOMALIAS DA EXECUÇÃO" }));
  if (!an.length) ap.appendChild(el("div", { class: "muted", text: "Nenhuma — sem erro inesperado e sem publicação órfã." }));
  for (const x of an) {
    const row = el("div", { class: "dash-row", tabindex: "0" },
      el("span", { class: "chip " + (x.severity === "alta" ? "err" : x.severity === "média" ? "warn" : "info"), text: x.severity }),
      el("div", { class: "grow" }, el("div", { text: x.title }), el("div", { class: "muted mono", text: truncate(x.detail, 160) })));
    if (x.nodeId) row.addEventListener("click", () => cb.selectNode && cb.selectNode(x.nodeId));
    ap.appendChild(row);
  }
  col.appendChild(ap);

  // insights preditivos desta execução
  const ins = a.insights || [];
  const ip = el("div", { class: "panel" }, el("h3", null, "REGRAS PREDITIVAS · pontos de atenção", el("span", { class: "grow" }),
    el("span", { class: "muted", text: ins.length ? ins.length + " ranqueado(s)" : "" })));
  if (!ins.length) ip.appendChild(el("div", { class: "muted", text: "Nenhum ponto de atenção acima do limiar de ranking para esta execução. As análises de acervo (regressão, tendência) aparecem conforme o histórico cresce." }));
  ins.forEach((i, idx) => ip.appendChild(insightCard(i, { open: idx === 0 })));
  col.appendChild(ip);

  // papéis inferidos
  const roles = t.roles || {};
  const rp = el("div", { class: "panel" }, el("h3", { text: "PAPÉIS ARQUITETURAIS · micro-decisões por passo" }));
  const tb = el("table", { class: "rules" });
  for (const [nodeId, r] of Object.entries(roles)) {
    const n = exec.nodes.get(nodeId);
    if (!n) continue;
    const tr = el("tr", null,
      el("td", null, el("div", { class: "term", text: truncate(n.label, 50) }), el("div", { class: "why", text: truncate(r.rationale || "", 160) })),
      el("td", null, el("span", { class: "chip", text: r.label }), prov(r)));
    tr.addEventListener("click", () => cb.selectNode && cb.selectNode(nodeId));
    tb.appendChild(tr);
  }
  rp.appendChild(tb);
  col.appendChild(rp);
  return col;
}

function highlight(nodeIds, reason) {
  state.highlight = { nodeIds: new Set(nodeIds || []), reason };
  emit("highlight");
  if (cb.goto) cb.goto("tree");
  toast("Destaque: " + reason + " — Esc limpa", "ok");
}

/* ------------------------------------------------------------- cartão de insight */
const NATURE = { FACT: ["fact", "FATO"], CORRELATION: ["corr", "CORRELAÇÃO"], HYPOTHESIS: ["hyp", "HIPÓTESE"] };
const SEV = { CRITICAL: ["err", "crítico"], HIGH: ["err", "alto"], MEDIUM: ["warn", "médio"], LOW: ["info", "baixo"], INFO: ["", "info"] };

export function insightCard(i, opts = {}) {
  const c = CATEGORY[i.category] || { g: i.glyph || "•", l: i.categoryLabel || i.category };
  const nat = NATURE[i.nature] || ["", i.nature];
  const sev = SEV[i.severity] || ["", i.severity];
  const card = el("article", { class: "insight " + i.severity + (opts.focus ? " focus" : ""), "aria-label": i.title });
  const confBar = el("span", { class: "bar" }, el("i"));
  confBar.firstChild.style.width = Math.round((i.confidence || 0) * 100) + "%";
  card.appendChild(el("div", { class: "ih" },
    c.icon ? el("div", { class: "glyph", "aria-hidden": "true" }, icon(c.icon)) : el("div", { class: "glyph", "aria-hidden": "true", text: c.g }),
    el("div", { class: "grow" },
      el("div", { class: "tt", text: i.title }),
      el("div", { class: "meta" },
        el("span", { class: "chip " + sev[0], text: sev[1] }),
        el("span", { class: "chip", text: i.categoryLabel || c.l }),
        el("span", { class: "chip " + nat[0], title: "Natureza da afirmação", text: nat[1] }),
        el("span", { class: "conf", title: "Confiança calibrada pelo analisador e pelo seu feedback" }, confBar, Math.round((i.confidence || 0) * 100) + "% · " + (i.confidenceBand || "")),
        i.occurrences > 1 ? el("span", { class: "chip", text: "×" + i.occurrences }) : null,
        el("span", { class: "muted mono", text: i.id + " · " + (i.analyzer || "") + (i.decidedBy ? " · " + i.decidedBy : "") })))));

  const body = el("div", { class: opts.open ? null : "hidden" });
  body.appendChild(el("div", { class: "blk fact" }, el("div", { class: "bt", text: "FATO OBSERVADO" }), el("div", { text: i.observation || "" })));
  if (i.correlation) body.appendChild(el("div", { class: "blk corr" }, el("div", { class: "bt", text: "CORRELAÇÃO" }), el("div", { text: i.correlation })));
  if (i.hypothesis) body.appendChild(el("div", { class: "blk hyp" }, el("div", { class: "bt", text: "HIPÓTESE · a confirmar" }), el("div", { text: i.hypothesis })));
  if ((i.recommendations || []).length) {
    body.appendChild(el("div", { class: "blk rec" }, el("div", { class: "bt", text: "RECOMENDAÇÕES" }),
      el("ol", null, ...i.recommendations.map((r) => el("li", { text: r })))));
  }
  const evTable = el("table", { class: "ev hidden" });
  for (const e of i.evidence || []) {
    const go = el("td", { class: "go" });
    const ref = e.ref || {};
    if (ref.nodeId || ref.executionId) {
      go.appendChild(el("button", { type: "button", class: "small ghost", onclick: () => cb.openTrace && cb.openTrace(ref.executionId || (i.executionIds || [])[0], ref.nodeId) }, "trace"));
    }
    if (ref.component) {
      go.appendChild(el("button", { type: "button", class: "small ghost", onclick: () => cb.openComponent && cb.openComponent(ref.component) }, "anatomia"));
    }
    if (ref.file) {
      go.appendChild(el("button", { type: "button", class: "small ghost", title: "Copiar caminho", onclick: () => copy(ref.file + (ref.line ? ":" + ref.line : ""), "Caminho copiado") }, truncate(ref.file.split(/[\\/]/).pop(), 22) + (ref.line ? ":" + ref.line : "")));
    }
    evTable.appendChild(el("tr", null,
      el("td", null, el("span", { class: "chip", text: e.kind })),
      el("td", { class: "l", text: e.label }),
      el("td", { class: "v", text: truncate(e.value, 140) }),
      go));
  }
  body.appendChild(evTable);
  const explainBox = el("div", { class: "explain hidden", "aria-live": "polite" });
  body.appendChild(explainBox);

  // ações
  const acts = el("div", { class: "acts" });
  const bEv = el("button", { type: "button", class: "small" }, icon(ICONS.eye), " Ver evidências (" + (i.evidence || []).length + ")");
  bEv.addEventListener("click", () => { body.classList.remove("hidden"); evTable.classList.toggle("hidden"); });
  const bTrace = (i.executionIds || []).length ? el("button", { type: "button", class: "small" }, icon(ICONS.target), " Ver trace") : null;
  if (bTrace) bTrace.addEventListener("click", () => cb.openTrace && cb.openTrace(i.executionIds[0], firstNode(i)));
  const bHist = el("button", { type: "button", class: "small" }, icon(ICONS.history), " Ver histórico");
  bHist.addEventListener("click", () => cb.openHistory && cb.openHistory(i));
  const bExp = el("button", { type: "button", class: "small ghost", title: "Explicação investigativa (template local; LLM só se configurado)" }, icon(ICONS.spark), " Explicar");
  bExp.addEventListener("click", () => explain(i, false, explainBox, body));
  acts.append(bEv, bTrace, bHist, bExp);
  if (state.intelligence && state.intelligence.llm && state.intelligence.llm.available) {
    const bLlm = el("button", { type: "button", class: "small ghost", title: state.intelligence.llm.description || "LLM opcional" }, "LLM");
    bLlm.addEventListener("click", () => explain(i, true, explainBox, body));
    acts.appendChild(bLlm);
  }
  const fb = el("div", { class: "fb", role: "group", "aria-label": "Feedback do insight" });
  for (const [action, ic, title] of [["USEFUL", ICONS.thumbUp, "Útil — reforça o analisador"], ["DISMISS", ICONS.thumbDown, "Falso positivo — reduz a confiança do analisador"],
    ["EXPECTED", ICONS.check, "Comportamento esperado — suprime este achado"], ["MUTE", ICONS.mute, "Silenciar este achado"]]) {
    const b = el("button", { type: "button", class: "icon ghost", title, "aria-label": title }, icon(ic));
    b.addEventListener("click", () => feedback(i, action));
    fb.appendChild(b);
  }
  acts.appendChild(fb);
  if (!opts.open) {
    const more = el("button", { type: "button", class: "small ghost" }, "detalhes");
    more.addEventListener("click", () => { body.classList.toggle("hidden"); more.textContent = body.classList.contains("hidden") ? "detalhes" : "recolher"; });
    acts.insertBefore(more, acts.firstChild);
  }
  card.append(body, acts);
  return card;
}

function firstNode(i) {
  for (const e of i.evidence || []) if (e.ref && e.ref.nodeId) return e.ref.nodeId;
  return null;
}

async function explain(i, llm, box, body) {
  body.classList.remove("hidden");
  box.classList.remove("hidden");
  box.textContent = llm ? "Consultando o LLM configurado (dados minimizados)…" : "Montando a explicação…";
  try {
    const path = "/insights/" + encodeURIComponent(i.fingerprint) + "/explain";
    // LLM = egress + custo: só por POST (prova de mesma origem); o template local é GET
    const r = llm
      ? await json(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ llm: true }) })
      : await json(path);
    box.textContent = r.text + "\n\n— " + (r.engine === "template" ? "explicação por template local (nenhum dado saiu da máquina)" : "gerado por " + r.engine + (r.model ? " · " + r.model : ""));
  } catch (e) {
    box.textContent = "Explicação indisponível: " + e.message;
  }
}

async function feedback(i, action) {
  try {
    await json("/insights/" + encodeURIComponent(i.fingerprint) + "/feedback", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action }),
    });
    toast({ USEFUL: "Obrigado — o analisador ganha peso", DISMISS: "Marcado como falso positivo", EXPECTED: "Marcado como esperado", MUTE: "Silenciado" }[action] || "Feedback registrado", "ok");
    await loadInsights();
    (i.executionIds || []).forEach((id) => state.assist.delete(id));
    if (state.selectedId && (i.executionIds || []).includes(state.selectedId)) await loadAssist(state.selectedId, true);
    emit("insights");
  } catch (e) {
    toast("Feedback falhou: " + e.message, "error");
  }
}

/* ------------------------------------------------------------- laudo de homologação (Markdown) */
function homologationMarkdown(exec, a) {
  const x = a.executive || {};
  const s = exec.summary || {};
  const L = [];
  L.push("# Laudo de homologação — " + (s.rootLabel || shortId(s.executionId)));
  L.push("");
  L.push("> " + (x.headline || ""));
  L.push("");
  L.push("- Execução: `" + s.executionId + "` · trace `" + (s.traceId || "?") + "` · " + (STATUS[s.status] || s.status) + " · " + ms(s.duration));
  L.push("- Desfecho: **" + ((x.outcome || {}).label || "?") + "** (" + engineName((x.outcome || {}).engine) + ", " + Math.round(((x.outcome || {}).confidence || 0) * 100) + "%)");
  L.push("- Prontidão: **" + ((x.readiness || {}).label || "?") + "** · Risco: **" + ((x.risk || {}).label || "?") + "**");
  L.push("");
  L.push(x.summary || "");
  L.push("");
  if ((x.rules || []).length) {
    L.push("## Regras de negócio");
    L.push("");
    L.push("| Regra | Veredito | Motor | Justificativa |");
    L.push("|---|---|---|---|");
    for (const r of x.rules) {
      L.push("| " + mdCell(r.term) + " | " + mdCell(r.label) + " | " + engineName(r.engine) + " " + Math.round((r.confidence || 0) * 100) + "% | " + mdCell(truncate(r.rationale || "", 180)) + " |");
    }
    L.push("");
  }
  L.push("## Checklist");
  L.push("");
  for (const c of x.checklist || []) L.push("- [" + (c.ok ? "x" : " ") + "] " + c.item + " — " + c.detail);
  const ins = a.insights || [];
  if (ins.length) {
    L.push("");
    L.push("## Pontos de atenção (Regras Preditivas)");
    L.push("");
    for (const i of ins) L.push("- " + (i.glyph || "") + " **" + i.title + "** — " + i.id + ", " + Math.round(i.confidence * 100) + "% (" + i.confidenceBand + "), " + (NATURE[i.nature] || ["", i.nature])[1].toLowerCase());
  }
  L.push("");
  L.push("_Gerado pelo Trace2Local (local-first). Fato = observado no trace; correlação = associação medida; hipótese = a confirmar._");
  return L.join("\n");
}
function mdCell(s) {
  return String(s ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ");
}
