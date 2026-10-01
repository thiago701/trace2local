/* Trace2Local — visão MOCKS (Mock Connect, ADR-016): sugestões do conselheiro com
   variações prontas, bindings (estilo Kafka Connect), journal de chamadas e plugins.
   Tudo via /api/mocks (mutações com X-Trace2Local: 1 — core.api). DOM só por textContent. */
import { state, $, el, clear, on, json, api, API, toast, copy, icon, ICONS, relTime, shortId, truncate } from "./core.js";

let cb = {};
let tab = "suggestions";
const data = { overview: null, suggestions: [], journal: [], plugins: [], loadedAt: 0 };
const ui = { chosen: new Map(), mode: new Map(), expanded: new Set(), confirmDelete: null, lastApplied: null };

const KIND_LABEL = {
  UNAVAILABLE_DEPENDENCY: "API indisponível aqui",
  RESPONSE_DRIVES_FLOW: "Resposta decide o fluxo",
  HAPPY_PATH_ONLY: "Só o caminho feliz",
  SLOW_DEPENDENCY: "Dependência lenta",
  CONTRACT_DRIFT: "Fora do contrato",
};
const SEV = { HIGH: ["err", "ALTA"], MEDIUM: ["warn", "MÉDIA"], LOW: ["info", "BAIXA"] };
const CAT = {
  BRANCH: "Valores que mudam o fluxo",
  CONTRACT: "Previstos no contrato, nunca vistos",
  EDGE: "Bordas do JSON",
  RESILIENCE: "Resiliência (erro, tempo, rede)",
};
const STATE_CHIP = { RUNNING: ["ok", "ativo"], PAUSED: ["warn", "pausado"], FAILED: ["err", "falhou"] };

export function initMocks(nav) {
  cb = nav;
  let t = null;
  // o conselheiro lê cada execução concluída: a contagem no botão "Mocks" acompanha
  on("completed", () => {
    if (!enabled()) return;
    clearTimeout(t);
    t = setTimeout(() => {
      const before = signature();
      load().then(() => { if (state.view === "mocks" && signature() !== before) paint(); });
    }, 1200);
  });
}

export function enabled() {
  return !!(state.meta && (state.meta.capabilities || []).includes("mocks"));
}

export async function load() {
  if (!enabled()) return;
  const [overview, suggestions, journal, plugins] = await Promise.all([
    json("/mocks").catch(() => null),
    json("/mocks/suggestions").catch(() => []),
    json("/mocks/journal?limit=80").catch(() => []),
    data.plugins.length ? Promise.resolve(data.plugins) : json("/mocks/plugins").catch(() => []),
  ]);
  Object.assign(data, { overview, suggestions, journal, plugins, loadedAt: Date.now() });
  badge();
}

/** Contagem de sugestões no botão da visão (vermelho quando há API indisponível/alta). */
function badge() {
  const b = $("mocks-count");
  if (!b) return;
  const open = pending();
  b.textContent = String(open.length);
  b.classList.toggle("hidden", open.length === 0);
  b.classList.toggle("err", open.some((s) => s.severity === "HIGH"));
  b.title = open.length + " sugestão(ões) de mock pendente(s)";
}

/** Sugestões ainda sem mock plugado (as já plugadas continuam visíveis, mas não pedem ação). */
function pending() {
  return data.suggestions.filter((s) => !s.activeBinding);
}

/** Pendentes primeiro (ALTA → BAIXA), depois as já resolvidas por um binding. */
function ordered() {
  const rank = { HIGH: 0, MEDIUM: 1, LOW: 2 };
  return [...data.suggestions].sort((a, b) => (!!a.activeBinding - !!b.activeBinding) || ((rank[a.severity] ?? 3) - (rank[b.severity] ?? 3)));
}

/** Sugestões que tocam um host (inspector do passo). */
export async function suggestionsFor(host) {
  if (!enabled() || !host) return [];
  if (Date.now() - data.loadedAt > 5000) await load();
  return data.suggestions.filter((s) => String(s.target || "").split(":")[0] === host);
}

export function focusSuggestion(id) {
  tab = "suggestions";
  ui.expanded.add(id);
  ui.focus = id;
}

/** Visão Mocks: pinta o que já tem e atualiza do Station (refresh ao entrar na visão). */
export async function render(opts = {}) {
  if (!enabled() || !data.loadedAt) return paint();
  paint();
  if (opts.refresh) {
    // repinta só se algo mudou: um clique do dev nunca cai num nó que acabou de ser trocado
    const before = signature();
    await load();
    if (state.view === "mocks" && signature() !== before) paint();
  }
}

function signature() {
  const o = data.overview || {};
  return JSON.stringify([o.bindings, (o.routes || []).length, o.journalTotal, data.suggestions.map((s) => [s.id, s.activeBinding, s.severity, (s.evidence || []).length]), data.journal.length]);
}

async function paint() {
  const host = $("view-mocks");
  const keepY = host.scrollTop;
  const keepFocus = document.activeElement && host.contains(document.activeElement) ? document.activeElement.id : null;
  const focusing = !!ui.focus;
  clear(host);
  requestAnimationFrame(() => {
    if (!focusing) host.scrollTop = keepY;
    if (keepFocus && document.getElementById(keepFocus)) document.getElementById(keepFocus).focus({ preventScroll: true });
  });
  const pad = el("div", { class: "view-pad mocks" });
  host.appendChild(pad);
  if (!enabled()) {
    pad.appendChild(el("div", { class: "empty mk-off" },
      el("strong", { text: "Mock Connect desligado neste modo." }), el("br"),
      "Ligue no Station (padrão: TRACE2LOCAL_MOCKS=on) e aponte TRACE2LOCAL_MOCKS_DIR para os contratos dos parceiros."));
    return;
  }
  if (!data.loadedAt) {
    pad.appendChild(el("div", { class: "muted", text: "Carregando o Mock Connect…" }));
    await load();
    return paint();
  }
  const o = data.overview || {};
  const bindings = o.bindings || [];
  const running = bindings.filter((b) => b.state === "RUNNING").length;
  pad.appendChild(el("div", { class: "mk-head" },
    el("div", { class: "grow" },
      el("h2", { text: "Mock Connect" }),
      el("div", { class: "mk-sub", text: "Plugue mocks no lugar de APIs indisponíveis e valide variações do JSON no seu serviço — fontes → transformações → destinos." })),
    el("button", { type: "button", class: "small", onclick: async () => { await load(); paint(); } }, icon(ICONS.history), "Atualizar")));
  pad.appendChild(el("div", { class: "cards mk-stats" },
    stat("Bindings ativos", running + " / " + bindings.length, running ? "ok" : null),
    stat("Rotas no cliente", String((o.routes || []).length), null, "chamadas desviadas por Trace2LocalHttp"),
    stat("Chamadas atendidas", String(o.journalTotal || 0)),
    stat("Contratos", String(o.contracts || 0), null, "OpenAPI em TRACE2LOCAL_MOCKS_DIR"),
    stat("Sugestões pendentes", String(pending().length), pending().some((s) => s.severity === "HIGH") ? "err" : null,
      data.suggestions.length - pending().length ? (data.suggestions.length - pending().length) + " já plugada(s)" : null)));

  const seg = el("nav", { class: "seg mk-tabs", "aria-label": "Seções do Mock Connect" });
  for (const [k, l] of [["suggestions", "Sugestões (" + data.suggestions.length + ")"], ["bindings", "Bindings (" + bindings.length + ")"],
    ["journal", "Journal"], ["plugins", "Plugins (" + data.plugins.length + ")"], ["editor", "Novo binding"]]) {
    const b = el("button", { type: "button", id: "mk-tab-" + k, class: tab === k ? "active" : null, "aria-current": tab === k ? "page" : "false" }, l);
    b.addEventListener("click", () => { tab = k; paint(); });
    seg.appendChild(b);
  }
  pad.appendChild(seg);
  const body = el("div", { class: "mk-body" });
  pad.appendChild(body);
  if (tab === "suggestions") renderSuggestions(body);
  else if (tab === "bindings") renderBindings(body, bindings);
  else if (tab === "journal") renderJournal(body);
  else if (tab === "plugins") renderPlugins(body);
  else renderEditor(body);
  if (ui.focus) {
    const f = document.getElementById("sg-" + ui.focus);
    if (f) f.scrollIntoView({ block: "center" });
    ui.focus = null;
  }
}

function stat(label, value, cls, sub) {
  return el("div", { class: "stat" }, el("div", { class: "l", text: label }), el("div", { class: "v" + (cls ? " " + cls : ""), text: value }),
    sub ? el("div", { class: "s", text: sub }) : null);
}

/* ------------------------------------------------------------- sugestões */
function renderSuggestions(body) {
  if (!data.suggestions.length) {
    body.appendChild(el("div", { class: "empty", text: "Nenhuma sugestão agora. O conselheiro lê as execuções: API fora do ar, resposta que decide o fluxo, só caminho feliz, lentidão ou divergência de contrato aparecem aqui com evidência." }));
    return;
  }
  for (const s of ordered()) body.appendChild(suggestionCard(s));
}

function suggestionCard(s) {
  const [sevCls, sevTxt] = SEV[s.severity] || ["info", s.severity];
  const chosen = ui.chosen.get(s.id) || new Set();
  ui.chosen.set(s.id, chosen);
  const mode = ui.mode.get(s.id) || (s.kind === "RESPONSE_DRIVES_FLOW" ? "on-demand" : "exclusive");
  ui.mode.set(s.id, mode);
  const card = el("article", { class: "mk-card insight " + s.severity + (s.activeBinding ? " handled" : ""), id: "sg-" + s.id });
  card.appendChild(el("div", { class: "ih" },
    el("div", { class: "glyph mk-glyph" }, icon(s.kind === "UNAVAILABLE_DEPENDENCY" ? ICONS.target : s.kind === "RESPONSE_DRIVES_FLOW" ? ICONS.spark : ICONS.scan)),
    el("div", { class: "grow" },
      el("div", { class: "tt", text: s.title }),
      el("div", { class: "meta" },
        s.activeBinding ? el("span", { class: "chip ok", title: "mock plugado — a sugestão não pede mais ação", text: "RESOLVIDA" }) : el("span", { class: "chip " + sevCls, text: sevTxt }),
        el("span", { class: "chip", text: KIND_LABEL[s.kind] || s.kind }),
        el("span", { class: "chip mono", text: s.target }),
        s.activeBinding ? el("span", { class: "chip scan", text: "plugado: " + s.activeBinding }) : null))));
  card.appendChild(el("div", { class: "blk fact" }, el("div", { class: "bt", text: "POR QUÊ" }), el("div", { text: s.why })));
  if ((s.evidence || []).length) {
    const ev = el("table", { class: "ev" });
    for (const e of s.evidence) {
      const go = el("button", { type: "button", class: "small ghost", title: "Abrir o passo na árvore" }, "abrir");
      go.addEventListener("click", () => cb.openTrace && cb.openTrace(e.executionId, e.nodeId));
      ev.appendChild(el("tr", null, el("td", { class: "l", text: e.text }), el("td", { class: "v", text: shortId(e.executionId) }), el("td", { class: "go" }, go)));
    }
    card.appendChild(el("div", { class: "blk corr" }, el("div", { class: "bt", text: "EVIDÊNCIA" }), ev));
  }
  // variações agrupadas por categoria
  if ((s.variations || []).length) {
    const blk = el("div", { class: "blk rec" }, el("div", { class: "bt", text: "VARIAÇÕES PARA VALIDAR" }));
    const groups = new Map();
    for (const v of s.variations) {
      if (!groups.has(v.category)) groups.set(v.category, []);
      groups.get(v.category).push(v);
    }
    for (const [cat, list] of groups) {
      const g = el("fieldset", { class: "mk-vars" }, el("legend", { text: CAT[cat] || cat }));
      for (const v of list) {
        const id = "v-" + s.id + "-" + v.id;
        const box = el("input", { type: "checkbox", id, checked: chosen.has(v.id) });
        box.addEventListener("change", () => { if (box.checked) chosen.add(v.id); else chosen.delete(v.id); refreshAction(); });
        g.appendChild(el("label", { class: "mk-var", for: id }, box,
          el("span", { class: "grow" }, el("span", { class: "t", text: v.title }), el("span", { class: "r", text: v.rationale })),
          v.predicate ? el("span", { class: "chip", title: "tem predicado próprio (ex.: n-ésima chamada) — não é selecionável por baggage", text: "própria" }) : null,
          el("code", { class: "vid", text: v.id })));
      }
      blk.appendChild(g);
    }
    card.appendChild(blk);
  }
  // modo + ação
  const modeSeg = el("div", { class: "seg mk-mode", role: "radiogroup", "aria-label": "Quando aplicar as variações" });
  for (const [k, l, t] of [["exclusive", "Sempre", "a variação vale para toda chamada ao parceiro"],
    ["on-demand", "Sob demanda", "só quando a requisição traz o cabeçalho baggage: t2l.mock=<id>"]]) {
    const b = el("button", { type: "button", role: "radio", "aria-checked": String(mode === k), class: mode === k ? "active" : null, title: t, dataset: { mode: k } }, l);
    // troca no lugar (sem repintar a visão): foco e rolagem ficam onde o dev está
    b.addEventListener("click", () => {
      ui.mode.set(s.id, k);
      modeSeg.querySelectorAll("button").forEach((x) => {
        const on = x.dataset.mode === k;
        x.classList.toggle("active", on);
        x.setAttribute("aria-checked", String(on));
      });
    });
    modeSeg.appendChild(b);
  }
  const action = el("button", { type: "button", class: "primary" });
  const refreshAction = () => {
    const n = chosen.size;
    action.textContent = s.activeBinding ? (n ? "Aplicar " + n + " variação(ões)" : "Voltar à resposta base") : (n ? "Plugar mock + " + n + " variação(ões)" : "Plugar mock");
  };
  refreshAction();
  action.addEventListener("click", () => apply(s, [...chosen], ui.mode.get(s.id), action));
  card.appendChild(el("div", { class: "acts mk-acts" }, modeSeg, el("span", { class: "grow" }),
    el("details", { class: "mk-cfg" }, el("summary", { text: "config (formato Connect)" }), el("pre", { class: "json", text: JSON.stringify(s.bindingConfig, null, 2) })),
    action));
  if (ui.lastApplied && ui.lastApplied.id === s.id) card.appendChild(howTo(s, ui.lastApplied));
  return card;
}

async function apply(s, variations, mode, btn) {
  btn.disabled = true;
  const label = btn.textContent;
  btn.textContent = "Aplicando…";
  try {
    const info = await json("/mocks/suggestions/" + encodeURIComponent(s.id) + "/apply", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ variations, mode }),
    });
    const st = info.status || {};
    ui.lastApplied = { id: s.id, mode, variations, endpoint: st.endpoint, name: info.name, state: st.state };
    if (st.state === "RUNNING") toast("Mock " + info.name + " ativo — " + (st.stubs || 0) + " stub(s)" + (variations.length ? ", " + variations.length + " variação(ões)" : ""), "ok");
    else toast("Binding " + info.name + " " + (st.state || "") + ": " + (st.trace || "veja Bindings"), "error");
    await load();
  } catch (e) {
    toast("Não foi possível aplicar: " + e.message, "error");
  } finally {
    btn.disabled = false;
    btn.textContent = label;
    paint();
  }
}

function howTo(s, applied) {
  const box = el("div", { class: "mk-howto", role: "note" }, el("div", { class: "bt", text: "COMO USAR AGORA" }));
  box.appendChild(el("p", { text: "Clientes instrumentados com Trace2LocalHttp e TRACE2LOCAL_MOCKS_ROUTING=on já chamam o mock — o passo aparece na árvore marcado como SIMULADO." }));
  if (applied.endpoint) {
    box.appendChild(el("div", { class: "row" }, el("span", { class: "muted", text: "ou aponte a URL base para" }),
      el("code", { class: "mono", text: applied.endpoint }),
      el("button", { type: "button", class: "small ghost", onclick: () => copy(applied.endpoint, "Endpoint copiado") }, icon(ICONS.copy))));
  }
  if (applied.mode === "on-demand" && applied.variations.length) {
    for (const v of applied.variations) {
      const h = "baggage: t2l.mock=" + v;
      box.appendChild(el("div", { class: "row" }, el("code", { class: "mono", text: h }),
        el("button", { type: "button", class: "small ghost", onclick: () => copy(h, "Cabeçalho copiado") }, icon(ICONS.copy), "copiar")));
    }
    box.appendChild(el("p", { class: "muted", text: "Envie o cabeçalho na chamada ao SEU serviço: o baggage W3C atravessa os serviços instrumentados até o parceiro simulado." }));
  }
  return box;
}

/* ------------------------------------------------------------- bindings */
function renderBindings(body, bindings) {
  if (!bindings.length) {
    body.appendChild(el("div", { class: "empty", text: "Nenhum binding. Use “Plugar mock” numa sugestão ou crie um em Novo binding (config plana, como um conector do Kafka Connect)." }));
    return;
  }
  for (const b of bindings) {
    const [cls, txt] = STATE_CHIP[b.state] || ["", b.state];
    const open = ui.expanded.has("b:" + b.name);
    const card = el("article", { class: "mk-binding " + (b.state || "") });
    const actions = el("div", { class: "row mk-bact" });
    if (b.state === "RUNNING") actions.appendChild(btn("pausar", () => mutate("PUT", b.name, "pause")));
    if (b.state === "PAUSED") actions.appendChild(btn("retomar", () => mutate("PUT", b.name, "resume")));
    actions.appendChild(btn("reiniciar", () => mutate("POST", b.name, "restart"), "relê o contrato e zera contadores de chamada"));
    const exp = el("a", { class: "btn small ghost", href: API + "/mocks/bindings/" + encodeURIComponent(b.name) + "/export", download: "" }, icon(ICONS.dl), "exportar WireMock");
    actions.appendChild(exp);
    const del = el("button", { type: "button", class: "small danger" }, ui.confirmDelete === b.name ? "confirmar remoção" : "remover");
    del.addEventListener("click", () => {
      if (ui.confirmDelete !== b.name) { ui.confirmDelete = b.name; paint(); return; }
      ui.confirmDelete = null;
      mutate("DELETE", b.name, "");
    });
    actions.appendChild(del);
    card.appendChild(el("div", { class: "row mk-bhead" },
      el("span", { class: "chip " + cls, text: txt }),
      el("strong", { class: "mono", text: b.name }),
      el("span", { class: "muted", text: b.api + " · " + b.target }),
      el("span", { class: "grow" }),
      el("span", { class: "chip", text: b.source + " → " + b.sink }),
      b.routing ? el("span", { class: "chip scan", title: "rota publicada para o roteamento do cliente", text: "roteado" }) : null));
    card.appendChild(el("div", { class: "kv" },
      el("span", { class: "k", text: "endpoint" }), el("span", { class: "v", text: b.endpoint || "—" }),
      el("span", { class: "k", text: "stubs · chamadas" }), el("span", { class: "v", text: (b.stubs || 0) + " · " + (b.hits || 0) }),
      el("span", { class: "k", text: "transformações" }), el("span", { class: "v", text: (b.transforms || []).join(", ") || "nenhuma (resposta base)" }),
      el("span", { class: "k", text: "atualizado" }), el("span", { class: "v", text: relTime(b.updatedAt) })));
    if (b.trace) card.appendChild(el("div", { class: "err-box" }, el("div", { class: "et", text: "motivo da falha" }), el("div", { class: "em", text: b.trace })));
    if ((b.warnings || []).length) card.appendChild(el("div", { class: "honest", text: "⚠ " + b.warnings.join(" · ") }));
    const more = el("button", { type: "button", class: "small ghost" }, open ? "ocultar stubs" : "ver stubs efetivos");
    more.addEventListener("click", () => { if (open) ui.expanded.delete("b:" + b.name); else ui.expanded.add("b:" + b.name); paint(); });
    actions.prepend(more);
    card.appendChild(actions);
    if (open) {
      const host = el("div", { class: "mk-stubs", text: "carregando…" });
      card.appendChild(host);
      json("/mocks/bindings/" + encodeURIComponent(b.name) + "/stubs").then((stubs) => {
        clear(host);
        if (!stubs.length) { host.textContent = "nenhum stub publicado"; return; }
        const t = el("table", { class: "diff" }, el("tr", null, el("th", { text: "stub" }), el("th", { text: "requisição" }), el("th", { text: "resposta" }), el("th", { text: "origem" })));
        for (const s of stubs) {
          const r = s.response || {};
          t.appendChild(el("tr", null, el("td", { class: "mono", text: s.id }),
            el("td", { class: "mono", text: (s.request.method || "*") + " " + s.request.path }),
            el("td", { class: "mono", text: s.passthrough ? "repasse (API real)" : (r.status + (r.fault && r.fault !== "NONE" ? " · " + r.fault : "") + (r.delayMs ? " · +" + r.delayMs + " ms" : "") + " " + truncate(r.body || "", 80)) }),
            el("td", { class: "muted", text: truncate(s.origin || "", 40) })));
        }
        host.appendChild(t);
      }).catch((e) => { host.textContent = "falha: " + e.message; });
    }
    body.appendChild(card);
  }
}

function btn(label, fn, title) {
  const b = el("button", { type: "button", class: "small", title: title || null }, label);
  b.addEventListener("click", fn);
  return b;
}

async function mutate(method, name, action) {
  try {
    await api("/mocks/bindings/" + encodeURIComponent(name) + (action ? "/" + action : ""), { method });
    toast(action ? name + ": " + action : name + " removido", "ok");
  } catch (e) {
    toast("Falhou: " + e.message, "error");
  }
  await load();
  paint();
}

/* ------------------------------------------------------------- journal */
function renderJournal(body) {
  if (!data.journal.length) {
    body.appendChild(el("div", { class: "empty", text: "Nenhuma chamada atendida pelo mock ainda." }));
    return;
  }
  const wrap = el("div", { class: "tl-table mk-journal" });
  const t = el("table", null, el("tr", null, ...["quando", "binding", "requisição", "status", "stub", "variação", "resultado", "execução"].map((h) => el("th", { text: h }))));
  for (const j of data.journal) {
    const exec = findByTrace(j.traceId);
    const go = exec ? el("button", { type: "button", class: "small ghost" }, "abrir") : el("span", { class: "muted", text: j.traceId ? shortId(j.traceId) : "—" });
    if (exec) go.addEventListener("click", () => cb.openTrace && cb.openTrace(exec));
    t.appendChild(el("tr", { class: j.status >= 500 || j.status < 0 ? "ERROR" : j.outcome === "unmatched" ? "WARN" : "" },
      el("td", { class: "ts", text: relTime(j.at) }), el("td", { text: j.binding }),
      el("td", { class: "msg", text: j.method + " " + j.path }), el("td", { text: j.status < 0 ? "—" : String(j.status) }),
      el("td", { text: j.stubId || "—" }), el("td", { text: (j.applied || []).join(", ") || "—" }),
      el("td", { text: j.outcome + " · " + j.tookMs + " ms" }), el("td", null, go)));
  }
  wrap.appendChild(t);
  body.appendChild(wrap);
}

function findByTrace(traceId) {
  if (!traceId) return null;
  for (const [id, e] of state.executions) if (e.summary && e.summary.traceId === traceId) return id;
  return null;
}

/* ------------------------------------------------------------- plugins */
function renderPlugins(body) {
  const types = [["SOURCE", "Fontes de stubs"], ["TRANSFORM", "Transformações (variações)"], ["PREDICATE", "Predicados (quando aplicar)"], ["SINK", "Destinos"]];
  for (const [type, title] of types) {
    const list = data.plugins.filter((p) => p.type === type);
    if (!list.length) continue;
    const sec = el("section", { class: "panel" }, el("h3", { text: title }));
    const grid = el("div", { class: "mk-plugins" });
    for (const p of list) {
      const keys = (p.config || []).map((k) => el("li", null, el("code", { text: k.name }), el("span", { class: "muted", text: " " + k.type.toLowerCase() + (k.required ? " · obrigatório" : k.defaultValue != null ? " · padrão " + k.defaultValue : "") }),
        el("div", { class: "r", text: k.documentation })));
      grid.appendChild(el("div", { class: "mk-plugin" },
        el("div", { class: "row" }, el("strong", { class: "mono", text: p.name }), el("span", { class: "muted", text: "v" + p.version }),
          el("span", { class: "grow" }), p.dynamicOnly ? el("span", { class: "chip", title: "depende da requisição: só no destino embedded", text: "dinâmico" }) : null,
          p.origin !== "embutido" ? el("span", { class: "chip scan", text: p.origin }) : null),
        el("div", { class: "r", text: p.description }),
        keys.length ? el("details", null, el("summary", { text: keys.length + " chave(s) de config" }), el("ul", { class: "mk-keys" }, ...keys)) : null));
    }
    sec.appendChild(grid);
    body.appendChild(sec);
  }
}

/* ------------------------------------------------------------- editor (config plana) */
const EXAMPLE = {
  name: "antifraude-variacoes",
  config: {
    target: "antifraude.partner.local:8080",
    "api.name": "Antifraude",
    source: "proxy",
    transforms: "negado",
    "transforms.negado.type": "set-field",
    "transforms.negado.pointer": "/decision",
    "transforms.negado.value": "DENIED",
    "transforms.negado.predicate": "pedido",
    predicates: "pedido",
    "predicates.pedido.type": "header-matches",
    "predicates.pedido.name": "baggage",
    "predicates.pedido.regex": ".*t2l\\.mock=negado.*",
  },
};

function renderEditor(body) {
  const ta = el("textarea", { class: "mk-editor", spellcheck: "false", "aria-label": "Config do binding (JSON)" });
  ta.value = ui.editorText || JSON.stringify(EXAMPLE, null, 2);
  ta.addEventListener("input", () => { ui.editorText = ta.value; });
  const out = el("div", { class: "mk-validate", "aria-live": "polite" });
  const parse = () => {
    try { return JSON.parse(ta.value); } catch (e) { toast("JSON inválido: " + e.message, "error"); return null; }
  };
  const validate = el("button", { type: "button" }, icon(ICONS.check), "Validar");
  validate.addEventListener("click", async () => {
    const doc = parse();
    if (!doc) return;
    try {
      const r = await json("/mocks/validate", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(doc) });
      clear(out);
      if (!r.errorCount) { out.appendChild(el("div", { class: "chip ok", text: "config válida · " + r.configs.length + " chaves conferidas" })); return; }
      out.appendChild(el("div", { class: "chip err", text: r.errorCount + " problema(s)" }));
      const ul = el("ul", { class: "mk-errors" });
      for (const c of r.configs) for (const e of c.value.errors || []) ul.appendChild(el("li", null, el("code", { text: c.value.name }), " — " + e));
      out.appendChild(ul);
    } catch (e) { toast("Falha na validação: " + e.message, "error"); }
  });
  const save = el("button", { type: "button", class: "primary" }, "Criar / atualizar");
  save.addEventListener("click", async () => {
    const doc = parse();
    if (!doc || !doc.name) { toast("Informe name e config", "error"); return; }
    try {
      const info = await json("/mocks/bindings/" + encodeURIComponent(doc.name) + "/config", {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(doc.config || {}),
      });
      toast("Binding " + info.name + ": " + info.status.state, info.status.state === "RUNNING" ? "ok" : "error");
      tab = "bindings";
      await load();
      paint();
    } catch (e) {
      toast("Config recusada: " + e.message + " — use Validar para ver cada chave", "error");
    }
  });
  body.appendChild(el("section", { class: "panel" },
    el("h3", { text: "Binding em config plana" }),
    el("p", { class: "muted", text: "Mesmo formato de um conector do Kafka Connect: target, source.*, sink.*, transforms (aliases encadeados) e predicates. Valores aceitam ${env:NOME} para segredos." }),
    ta, el("div", { class: "row" }, validate, save), out));
}
