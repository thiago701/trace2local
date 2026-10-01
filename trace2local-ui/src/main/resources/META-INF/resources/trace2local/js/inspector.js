/* INSPETOR — gaveta lateral do passo selecionado: resumo (papel, criticidade,
   nota de negócio, erro), delta de dados, payloads, logs CloudWatch do passo,
   insights que o citam como evidência e a infraestrutura declarada que ele usa. */
import { state, $, el, clear, on, emit, json, ms, clock, truncate, kindOf, icon, ICONS, copy, selectedExec, buildTree, isAsyncConsumer, CATEGORY, mockOf } from "./core.js";
import { suggestionsFor, enabled as mocksEnabled } from "./mocks.js";
import { loadStory } from "./data.js";
import { insightCard, engineName } from "./investigate.js";

let drawer, tab = "summary";
let cb = {};

export function initInspector(callbacks) {
  cb = callbacks || {};
  drawer = $("drawer");
  on("node", () => render());
  on("selection", () => { if (!state.selectedNodeId) close(); });
  on("assist", (id) => { if (id === state.selectedId && isOpen()) render(); });
  on("logs", (id) => { if (id === state.selectedId && isOpen() && tab === "logs") render(); });
  on("execution", (id) => { if (id === state.selectedId && isOpen()) render(); });
}

export function isOpen() {
  return drawer.classList.contains("open");
}
export function close() {
  drawer.classList.remove("open");
  drawer.setAttribute("aria-hidden", "true");
}

function currentNode() {
  const exec = selectedExec();
  if (!exec || !state.selectedNodeId) return null;
  const tree = buildTree(exec);
  return tree.byId.get(state.selectedNodeId) || null;
}

export async function render() {
  const n = currentNode();
  if (!n) { close(); return; }
  drawer.classList.add("open");
  drawer.setAttribute("aria-hidden", "false");
  clear(drawer);
  const m = kindOf(n.kind);
  const closeBtn = el("button", { type: "button", class: "icon ghost", title: "Fechar (Esc)", "aria-label": "Fechar inspetor" }, icon(ICONS.x));
  closeBtn.addEventListener("click", () => { state.selectedNodeId = null; close(); emit("node"); });
  drawer.appendChild(el("div", { class: "dr-head" },
    el("div", { class: "row" },
      el("span", { class: "chip " + m.cls }, icon(m.icon), m.name),
      mockOf(n) ? el("span", { class: "chip sim", title: "resposta do Mock Connect", text: mockOf(n).simulated ? "SIM" : "↪ REPASSE" }) : null,
      n.status === "ERROR" ? el("span", { class: "chip err", text: "erro" }) : n.status === "PENDING" ? el("span", { class: "chip scan", text: "em curso" }) : null,
      n.mutation && n.mutation.kind !== "READ_ONLY" ? el("span", { class: "chip data", text: "Δ " + String(n.mutation.kind).toLowerCase() }) : null,
      el("span", { class: "grow" }), closeBtn),
    el("div", { class: "dr-title", text: n.label || "?" }),
    el("div", { class: "muted mono", text: ms(n.durMs) + " · self " + ms(n.selfTime) + " · início +" + ms(n.startMs)
      + (isAsyncConsumer(n) ? " · fila " + ms(Math.max(0, n.startMs - n.parent.endMs)) : "") })));

  const counts = { logs: logsOf(n).length, insights: insightsOf(n).length, infra: infraOf(n).length };
  const tabs = el("div", { class: "dr-tabs", role: "tablist" });
  for (const [k, l] of [["summary", "Resumo"], ["data", "Dados"], ["payload", "Payload"], ["logs", "Logs" + (counts.logs ? " " + counts.logs : "")],
    ["insights", "Insights" + (counts.insights ? " " + counts.insights : "")], ["infra", "Infra" + (counts.infra ? " " + counts.infra : "")]]) {
    const b = el("button", { type: "button", role: "tab", class: tab === k ? "active" : null, "aria-selected": String(tab === k) }, l);
    b.addEventListener("click", () => { tab = k; render(); });
    tabs.appendChild(b);
  }
  drawer.appendChild(tabs);
  const body = el("div", { class: "dr-body", role: "tabpanel" });
  drawer.appendChild(body);
  if (tab === "summary") await summary(body, n);
  else if (tab === "data") data(body, n);
  else if (tab === "payload") payload(body, n);
  else if (tab === "logs") logs(body, n);
  else if (tab === "insights") insights(body, n);
  else infra(body, n);
}

/* ------------------------------------------------------------- resumo */
async function summary(body, n) {
  const a = state.assist.get(state.selectedId);
  const role = a && a.technical && a.technical.roles && a.technical.roles[n.nodeId];
  const crit = a && a.technical && a.technical.criticality && a.technical.criticality[n.nodeId];
  if (role || crit) {
    const sec = el("div", { class: "sec" }, el("h4", { text: "LEITURA DO ASSISTENTE" }));
    if (role) sec.appendChild(el("div", { class: "row" }, el("span", { class: "chip", text: "papel: " + role.label }),
      el("span", { class: "prov" }, el("span", { class: "e", text: engineName(role.engine) }), " · " + Math.round((role.confidence || 0) * 100) + "%")));
    if (crit) sec.appendChild(el("div", { class: "row" }, el("span", { class: "chip", text: "criticidade: " + crit.label }),
      el("span", { class: "prov" }, el("span", { class: "e", text: engineName(crit.engine) }), " · " + Math.round((crit.confidence || 0) * 100) + "%")));
    if (role && role.rationale) sec.appendChild(el("div", { class: "muted", text: role.rationale }));
    body.appendChild(sec);
  }
  const story = await loadStory(state.selectedId);
  const step = story && (story.steps || []).find((s) => s.nodeId === n.nodeId);
  if (step && step.text) body.appendChild(el("div", { class: "sec" }, el("h4", { text: "NA LINGUAGEM DO NEGÓCIO" }), el("div", { class: "note-box", text: step.text })));
  if (n.error) {
    body.appendChild(el("div", { class: "sec" }, el("h4", { text: "ERRO" }),
      el("div", { class: "err-box" }, el("div", { class: "et", text: n.error.type || "erro" }), el("div", { class: "em", text: n.error.message || "" }),
        n.error.stack ? el("details", null, el("summary", { text: "stack" }), el("pre", { class: "json", text: n.error.stack })) : null)));
  }
  if (isAsyncConsumer(n)) {
    body.appendChild(el("div", { class: "sec" }, el("h4", { text: "ASSÍNCRONO" }),
      el("div", { text: "Consumidor de " + kindOf(n.parent.kind).name + " “" + n.parent.label + "”: a mensagem esperou " + ms(Math.max(0, n.startMs - n.parent.endMs)) + " na fila antes do processamento." })));
  }
  mockSection(body, n);
  // atributos
  const attrs = n.attributes || {};
  const keys = Object.keys(attrs).sort();
  const sec = el("div", { class: "sec" }, el("h4", null, "ATRIBUTOS", el("span", { class: "muted", text: keys.length + "" })));
  const filter = el("input", { type: "search", class: "attr-filter", placeholder: "Filtrar atributos…", "aria-label": "Filtrar atributos" });
  const kv = el("div", { class: "kv" });
  const paint = () => {
    clear(kv);
    const q = filter.value.trim().toLowerCase();
    for (const k of keys) {
      if (q && !(k + " " + attrs[k]).toLowerCase().includes(q)) continue;
      kv.append(el("span", { class: "k", text: k }), el("span", { class: "v", text: truncate(attrs[k], 400) }));
    }
  };
  filter.addEventListener("input", paint);
  paint();
  sec.append(filter, kv);
  body.appendChild(sec);
  body.appendChild(el("div", { class: "row" },
    el("button", { type: "button", class: "small ghost", onclick: () => copy(n.nodeId, "spanId copiado") }, icon(ICONS.copy), " spanId"),
    el("button", { type: "button", class: "small ghost", onclick: () => cb.goto && cb.goto("timeline") }, "ver na linha do tempo"),
    el("button", { type: "button", class: "small ghost", onclick: () => cb.openComponentOf && cb.openComponentOf(n.nodeId) }, "ver na anatomia")));
}

/* ------------------------------------------------------------- Mock Connect do passo */
function hostOf(a) {
  if (!a) return null;
  if (a["server.address"]) return a["server.address"];
  if (a["net.peer.name"]) return a["net.peer.name"];
  const u = a["url.full"] || a["http.url"];
  if (u) { try { return new URL(u).hostname; } catch (e) { return null; } }
  return null;
}

/** Chamada externa: diz se a resposta foi simulada e oferece o mock sugerido para o parceiro. */
function mockSection(body, n) {
  const sim = mockOf(n);
  if (n.kind !== "HTTP_CLIENT" && !sim) return;
  if (!sim && !mocksEnabled()) return;
  const sec = el("div", { class: "sec mk-sec" }, el("h4", null, "MOCK CONNECT"));
  body.appendChild(sec);
  if (sim) {
    sec.appendChild(el("div", { text: sim.simulated
      ? "A resposta deste passo veio do Mock Connect — não da API real."
      : "Repasse: a API real respondeu; o Mock Connect só observou (nenhuma variação aplicada)." }));
    const kv = el("div", { class: "kv" });
    for (const [k, l] of [["binding", "binding"], ["stub", "stub"], ["variation", "variação"], ["routed", "roteado no cliente"]]) {
      if (sim[k]) kv.append(el("span", { class: "k", text: l }), el("span", { class: "v", text: sim[k] }));
    }
    sec.appendChild(kv);
    sec.appendChild(el("div", { class: "row" },
      el("button", { type: "button", class: "small", onclick: () => cb.openMocks && cb.openMocks() }, "ver bindings")));
  }
  const host = hostOf(n.attributes);
  if (!host) return;
  suggestionsFor(host).then((list) => {
    if (!sec.isConnected) return;
    if (!list.length) {
      if (!sim) {
        sec.appendChild(el("div", { class: "muted", text: "Nenhuma sugestão para " + host + " agora. Se esta API não existir no seu ambiente local, plugue um mock e valide variações do JSON sem depender dela." }));
        sec.appendChild(el("div", { class: "row" },
          el("button", { type: "button", class: "small", onclick: () => cb.openMocks && cb.openMocks() }, "abrir Mock Connect")));
      }
      return;
    }
    for (const sg of list.slice(0, 3)) {
      const go = el("button", { type: "button", class: "small primary" }, sg.activeBinding ? "ajustar variações" : "ver sugestão");
      go.addEventListener("click", () => cb.openMocks && cb.openMocks(sg.id));
      sec.appendChild(el("div", { class: "sg" },
        el("div", { class: "t", text: sg.title }),
        sg.activeBinding ? el("div", { class: "row" }, el("span", { class: "chip ok", text: "mock plugado" }), el("span", { class: "mono muted", text: sg.activeBinding })) : null,
        el("div", { class: "r", text: truncate(sg.why, 180) }),
        el("div", { class: "row" }, go, el("span", { class: "muted", text: (sg.variations || []).length + " variação(ões) prontas" }))));
    }
  }).catch(() => {});
}

/* ------------------------------------------------------------- dados (delta) */
function data(body, n) {
  const mu = n.mutation;
  if (!mu) {
    body.appendChild(el("div", { class: "muted", text: n.kind === "DYNAMODB" || n.kind === "SQL"
      ? "Sem delta capturado para esta operação (leitura, ou mutação sem captura — ver avisos de honestidade)."
      : "Este passo não altera dados persistidos." }));
    return;
  }
  body.appendChild(el("div", { class: "sec" }, el("h4", { text: "OPERAÇÃO" }),
    el("div", { class: "kv" },
      el("span", { class: "k", text: "tipo" }), el("span", { class: "v", text: mu.kind }),
      el("span", { class: "k", text: "alvo" }), el("span", { class: "v", text: mu.target || "—" }),
      el("span", { class: "k", text: "chave" }), el("span", { class: "v", text: mu.key || "—" }),
      el("span", { class: "k", text: "fidelidade" }), el("span", { class: "v", text: FIDELITY[mu.fidelity] || mu.fidelity || "—" }))));
  if (mu.fidelity === "INFERRED" && !(mu.deltas || []).length && mu.before == null && mu.after == null) {
    // invariante I3: o inferido nunca se passa por observado — e o dev sabe o que falta
    body.appendChild(el("div", { class: "honest", text: "Operação, tabela e chave inferidas do SQL; os valores antes → depois não vêm do driver JDBC. Para ver o efeito no dado, confira a linha no banco ou o passo de leitura seguinte." }));
  }
  const deltas = mu.deltas || [];
  if (deltas.length) {
    const sec = el("div", { class: "sec" }, el("h4", { text: "CAMPOS ALTERADOS · antes → depois" }));
    for (const d of deltas) {
      sec.appendChild(el("div", { class: "delta" },
        el("div", { class: "f", text: d.path }),
        el("div", { class: "b", text: d.before == null ? "∅" : JSON.stringify(d.before) }),
        el("div", { class: "arrow", text: "→" }),
        el("div", { class: "a", text: d.after == null ? "∅" : JSON.stringify(d.after) })));
    }
    body.appendChild(sec);
  }
  if (mu.before != null) body.appendChild(el("div", { class: "sec" }, el("h4", { text: "ANTES" }), el("pre", { class: "json", text: pretty(mu.before) })));
  if (mu.after != null) body.appendChild(el("div", { class: "sec" }, el("h4", { text: "DEPOIS" }), el("pre", { class: "json", text: pretty(mu.after) })));
}

function payload(body, n) {
  const p = n.payload;
  if (!p || (!p.request && !p.response)) {
    body.appendChild(el("div", { class: "muted", text: "Sem payload capturado (redigido e truncado na origem; trace2local.payload.max-bytes)." }));
    return;
  }
  if (p.request) body.appendChild(el("div", { class: "sec" }, el("h4", null, "REQUISIÇÃO", el("span", { class: "grow" }),
    el("button", { type: "button", class: "small ghost", onclick: () => copy(p.request, "Requisição copiada") }, icon(ICONS.copy))), el("pre", { class: "json", text: pretty(p.request) })));
  if (p.response) body.appendChild(el("div", { class: "sec" }, el("h4", null, "RESPOSTA", el("span", { class: "grow" }),
    el("button", { type: "button", class: "small ghost", onclick: () => copy(p.response, "Resposta copiada") }, icon(ICONS.copy))), el("pre", { class: "json", text: pretty(p.response) })));
}

const FIDELITY = {
  EXACT: "exata — observada na resposta",
  INFERRED: "inferida do SQL (sem valores)",
  UNAVAILABLE: "indisponível na origem",
};

function pretty(v) {
  if (typeof v !== "string") return JSON.stringify(v, null, 2);
  try { return JSON.stringify(JSON.parse(v), null, 2); } catch (e) { return v; }
}

/* ------------------------------------------------------------- logs do passo */
function logsOf(n) {
  const j = state.logs.get(state.selectedId);
  if (!j) return [];
  const rid = n.attributes && n.attributes["faas.invocation_id"];
  return (j.lines || []).filter((l) => l.spanId === n.nodeId || (!l.spanId && rid && l.requestId === rid));
}

function logs(body, n) {
  const lines = logsOf(n);
  if (!lines.length) {
    body.appendChild(el("div", { class: "muted", text: state.logs.has(state.selectedId)
      ? "Nenhuma linha de log presa a este passo (spanId no MDC, ou RequestId Lambda)."
      : "Carregando logs…" }));
    return;
  }
  const a = state.assist.get(state.selectedId);
  for (const l of lines) {
    const lvl = l.platform ? "PLATFORM" : (l.level || "INFO").toUpperCase();
    const c = a && a.logs ? a.logs[String(l.index)] : null;
    body.appendChild(el("div", { class: "sec" },
      el("div", { class: "row" },
        el("span", { class: "lvl " + lvl, text: l.platform ? "PLATAF." : lvl }),
        c ? el("span", { class: "chip", title: c.rationale || "", text: c.label }) : null,
        el("span", { class: "muted mono", text: clock(l.timestamp) + " · +" + ms(l.offsetMs) }),
        el("span", { class: "grow" }),
        el("span", { class: "src", text: l.logGroup || l.source || "" })),
      el("pre", { class: "json", text: l.message })));
  }
}

/* ------------------------------------------------------------- insights do passo */
function insightsOf(n) {
  const a = state.assist.get(state.selectedId);
  return ((a && a.insights) || []).filter((i) => (i.evidence || []).some((e) => e.ref && e.ref.nodeId === n.nodeId));
}
function insights(body, n) {
  const list = insightsOf(n);
  if (!list.length) {
    body.appendChild(el("div", { class: "muted", text: "Nenhum insight preditivo cita este passo como evidência." }));
    return;
  }
  list.forEach((i, idx) => body.appendChild(insightCard(i, { open: idx === 0 })));
}

/* ------------------------------------------------------------- infra declarada */
function infraOf(n) {
  const inf = state.infra;
  if (!inf) return [];
  return (inf.entries || []).filter((e) => (e.usedBy || []).some((u) => u.nodeId === n.nodeId && u.executionId === state.selectedId));
}
function infra(body, n) {
  if (!state.infra) {
    body.appendChild(el("div", { class: "muted", text: "Carregando o índice de infraestrutura…" }));
    json("/infra").then((r) => { state.infra = r; if (isOpen() && tab === "infra") render(); }).catch(() => {});
    return;
  }
  const list = infraOf(n);
  if (!list.length) {
    body.appendChild(el("div", { class: "muted", text: "Nenhuma configuração/IaC indexada referencia este passo (application.yml, .env, Terraform, compose)." }));
    return;
  }
  for (const e of list) {
    body.appendChild(el("div", { class: "infra-card" },
      el("div", { class: "r1" }, el("span", { class: "chip", text: e.type }), el("strong", { text: e.name })),
      el("div", { class: "val", title: e.value, text: e.value }),
      el("div", { class: "row" }, ...(e.sources || []).map((s) => {
        const chip = el("span", { class: "src-chip", title: "Copiar caminho", text: s.file.split(/[\\/]/).pop() + ":" + s.line });
        chip.addEventListener("click", () => copy(s.file + ":" + s.line, "Caminho copiado"));
        return chip;
      }))));
  }
}

export { CATEGORY };
