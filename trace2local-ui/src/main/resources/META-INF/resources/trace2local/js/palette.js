/* Paleta de comandos (Ctrl+K): navegar entre visões, execuções, componentes e
   insights sem tirar a mão do teclado. */
import { state, $, el, clear, kindOf, STATUS, ms, shortId, CATEGORY } from "./core.js";

let items = [];
let idx = 0;
let provider = () => [];

export function initPalette(itemsProvider) {
  provider = itemsProvider;
  $("palette").addEventListener("click", (e) => { if (e.target.id === "palette") closePalette(); });
}

export function isPaletteOpen() {
  return !$("palette").classList.contains("hidden");
}

export function openPalette() {
  const host = clear($("palette"));
  host.classList.remove("hidden");
  const input = el("input", { type: "text", placeholder: "Ir para… (visão, execução, componente, insight, comando)", "aria-label": "Buscar comando", autocomplete: "off", spellcheck: "false" });
  const list = el("ul", { role: "listbox", id: "pal-list" });
  const foot = el("div", { class: "foot" }, el("span", { text: "↑↓ navegar" }), el("span", { text: "Enter abrir" }), el("span", { text: "Esc fechar" }));
  host.appendChild(el("div", { class: "pal" }, input, list, foot));
  const all = provider();
  const paint = () => {
    const q = input.value.trim().toLowerCase();
    items = (q ? all.filter((i) => (i.kind + " " + i.label + " " + (i.hint || "")).toLowerCase().includes(q)) : all).slice(0, 40);
    idx = Math.min(idx, Math.max(0, items.length - 1));
    clear(list);
    items.forEach((it, i) => {
      const li = el("li", { role: "option", class: i === idx ? "on" : null, "aria-selected": String(i === idx) },
        el("span", { class: "k", text: it.kind }), el("span", { class: "grow", text: it.label }), it.hint ? el("span", { class: "muted", text: it.hint }) : null);
      li.addEventListener("click", () => run(it));
      li.addEventListener("mousemove", () => { if (idx !== i) { idx = i; paint(); } });
      list.appendChild(li);
    });
    if (!items.length) list.appendChild(el("li", { class: "muted", text: "Nada encontrado." }));
  };
  input.addEventListener("input", () => { idx = 0; paint(); });
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); idx = Math.min(items.length - 1, idx + 1); paint(); scroll(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); idx = Math.max(0, idx - 1); paint(); scroll(); }
    else if (e.key === "Enter") { e.preventDefault(); if (items[idx]) run(items[idx]); }
    else if (e.key === "Escape") { e.preventDefault(); closePalette(); }
  });
  idx = 0;
  paint();
  input.focus();
}

function scroll() {
  const on = $("pal-list").querySelector(".on");
  if (on) on.scrollIntoView({ block: "nearest" });
}

function run(it) {
  closePalette();
  try { it.run(); } catch (e) { console.error("[t2l] palette", e); }
}

export function closePalette() {
  clear($("palette")).classList.add("hidden");
}

/** Itens padrão: monta a lista a partir do estado atual. */
export function defaultItems(actions) {
  const out = [];
  for (const [v, l, k] of [["anatomy", "Anatomia do ecossistema", "1"], ["tree", "Árvore da execução", "2"], ["timeline", "Linha do tempo + CloudWatch", "3"],
    ["investigate", "Investigação (executiva + técnica)", "4"], ["story", "Narrativa de negócio", "5"], ["dashboard", "Painel, inteligência e histórico", "6"],
    ["compare", "Comparar execuções", "7"], ["infra", "Infra & DevOps", "8"], ["mocks", "Mock Connect — simular APIs e variações", "9"]]) {
    out.push({ kind: "VISÃO", label: l, hint: "tecla " + k, run: () => actions.goto(v) });
  }
  const execs = [...state.executions.values()].map((e) => e.summary).filter(Boolean)
    .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || "")).slice(0, 60);
  for (const s of execs) {
    out.push({ kind: "EXECUÇÃO", label: (s.rootLabel || shortId(s.executionId)), hint: (STATUS[s.status] || s.status || "") + " · " + ms(s.duration) + " · " + shortId(s.executionId), run: () => actions.examine(s.executionId) });
  }
  for (const c of (state.topology && state.topology.components) || []) {
    out.push({ kind: "COMPONENTE", label: c.label, hint: kindOf(c.kind).name + " · " + c.zone, run: () => actions.openComponent(c.id) });
  }
  for (const i of state.insights || []) {
    out.push({ kind: "INSIGHT", label: ((CATEGORY[i.category] || {}).g || "") + " " + i.title, hint: i.id + " · " + Math.round(i.confidence * 100) + "%", run: () => actions.openInsight(i) });
  }
  if (actions.mocks) {
    out.push({ kind: "COMANDO", label: "Plugar mock no lugar de uma API (sugestões)", hint: "Mocks", run: () => actions.openMocks() });
  }
  out.push({ kind: "COMANDO", label: "Mostrar/ocultar painel lateral", hint: "[", run: actions.toggleRail });
  out.push({ kind: "COMANDO", label: "Exportar execução selecionada (.tvtrace)", run: actions.exportSelected });
  out.push({ kind: "COMANDO", label: "Copiar link desta execução", run: actions.copyLink });
  out.push({ kind: "COMANDO", label: "Atalhos de teclado", hint: "?", run: actions.help });
  return out;
}
