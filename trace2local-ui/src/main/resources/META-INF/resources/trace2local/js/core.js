/* Trace2Local — núcleo da UI: estado, API, barramento de eventos, formatação.
   Vanilla JS (ES modules), zero dependência externa (ADR-005). */

const base = location.pathname.replace(/\/(index\.html)?$/, "").replace(/\/$/, "");
export const API = base + "/api";
export const SVG_NS = "http://www.w3.org/2000/svg";

/* ------------------------------------------------------------- estado global */
export const state = {
  meta: null,
  endpoints: [],
  executions: new Map(),      // id -> {summary, nodes: Map, completed, warnings, full}
  selectedId: null,
  selectedNodeId: null,
  view: "anatomy",
  recentFilter: "",
  logs: new Map(),            // id -> logs json
  assist: new Map(),          // id -> assistente (técnico + executivo + insights)
  story: new Map(),           // id -> story json
  insights: [],               // top global
  topology: null,
  intelligence: null,
  infra: null,
  highlight: null,            // {nodeIds:Set, reason} — destaque cruzado entre visões
  playhead: null,             // ms desde o início (linha do tempo / varredura)
  compare: { a: null, b: null },
  rail: "exec",
};
window.__t2l = state; // auxílio de depuração/evidência (somente leitura)

/* ------------------------------------------------------------- barramento */
const listeners = new Map();
export function on(evt, fn) {
  if (!listeners.has(evt)) listeners.set(evt, new Set());
  listeners.get(evt).add(fn);
  return () => listeners.get(evt).delete(fn);
}
export function emit(evt, payload) {
  (listeners.get(evt) || []).forEach((fn) => {
    try { fn(payload); } catch (e) { console.error("[t2l]", evt, e); }
  });
}

/* ------------------------------------------------------------- API */
export async function api(path, opts = {}) {
  const init = Object.assign({ credentials: "same-origin" }, opts);
  init.headers = Object.assign({}, opts.headers || {});
  const method = (init.method || "GET").toUpperCase();
  if (method !== "GET" && method !== "HEAD") {
    // prova de mesma origem exigida pelo servidor (proteção CSRF — RequestGuard)
    init.headers["X-Trace2Local"] = "1";
  }
  const res = await fetch(API + path, init);
  if (!res.ok) {
    let msg = "";
    try { msg = (await res.json()).error || ""; } catch (e) { /* corpo não-JSON */ }
    throw new Error(res.status + (msg ? " — " + msg : ""));
  }
  return res;
}
export async function json(path, opts) {
  return (await api(path, opts)).json();
}

/* ------------------------------------------------------------- DOM */
export const $ = (id) => document.getElementById(id);
export function el(tag, attrs, ...children) {
  const n = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === "class") n.className = v;
      else if (k === "text") n.textContent = v;
      else if (k.startsWith("on") && typeof v === "function") n.addEventListener(k.slice(2), v);
      else if (k === "dataset") Object.assign(n.dataset, v);
      else n.setAttribute(k, v === true ? "" : v);
    }
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    n.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  }
  return n;
}
export function svg(tag, attrs, ...children) {
  const n = document.createElementNS(SVG_NS, tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === "class") n.setAttribute("class", v);
      else if (k === "text") n.textContent = v;
      else if (k.startsWith("on") && typeof v === "function") n.addEventListener(k.slice(2), v);
      else n.setAttribute(k, v);
    }
  }
  for (const c of children.flat()) {
    if (c) n.appendChild(c);
  }
  return n;
}
export function clear(node) {
  while (node && node.firstChild) node.removeChild(node.firstChild);
  return node;
}
export function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
/** Ícone de traço (24×24) a partir de um path — sem fonte de ícones externa. */
export function icon(d, cls) {
  const s = svg("svg", { class: "i" + (cls ? " " + cls : ""), viewBox: "0 0 24 24", "aria-hidden": "true" });
  for (const part of String(d).split("|")) s.appendChild(svg("path", { d: part }));
  return s;
}
export const ICONS = {
  play: "M7 5l12 7-12 7z",
  pause: "M8 5v14|M16 5v14",
  back: "M11 19l-7-7 7-7|M20 12H4",
  fit: "M4 9V4h5|M20 9V4h-5|M4 15v5h5|M20 15v5h-5",
  plus: "M12 5v14|M5 12h14",
  minus: "M5 12h14",
  copy: "M8 8h11v11H8z|M5 16V5h11",
  scan: "M3 12h18|M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18",
  list: "M8 6h13|M8 12h13|M8 18h13|M3 6h.01|M3 12h.01|M3 18h.01",
  target: "M12 2v4|M12 18v4|M2 12h4|M18 12h4|M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8",
  x: "M6 6l12 12|M18 6 6 18",
  eye: "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z|M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
  history: "M3 12a9 9 0 1 0 3-6.7|M3 4v5h5|M12 7v5l3 2",
  spark: "M12 2l2.2 6.8H21l-5.6 4 2.2 6.8L12 15.6 6.4 19.6l2.2-6.8L3 8.8h6.8z",
  check: "M5 12l5 5L20 7",
  thumbUp: "M7 10v10|M7 10l4-7a2 2 0 0 1 3 2l-1 5h6a2 2 0 0 1 2 2l-2 7H7",
  thumbDown: "M7 14V4|M7 14l4 7a2 2 0 0 0 3-2l-1-5h6a2 2 0 0 0 2-2l-2-7H7",
  mute: "M11 5 6 9H3v6h3l5 4z|M22 9l-6 6|M16 9l6 6",
  dl: "M12 4v12|M7 11l5 5 5-5|M5 20h14",
};

/* ------------------------------------------------------------- formatação */
export function ms(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return "—";
  const n = typeof v === "number" ? v : Number(v.seconds || 0) * 1000 + Number(v.nano || 0) / 1e6;
  if (n === 0) return "0 ms";
  if (n < 1) return n.toFixed(2).replace(".", ",") + " ms";
  if (n < 1000) return Math.round(n) + " ms";
  if (n < 120000) return (n / 1000).toFixed(n < 10000 ? 2 : 1).replace(".", ",") + " s";
  return (n / 60000).toFixed(1).replace(".", ",") + " min";
}
export function pct(r) {
  return (Math.round(r * 1000) / 10).toString().replace(".", ",") + "%";
}
export function relTime(iso) {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 45) return "agora";
  if (s < 3600) return "há " + Math.round(s / 60) + " min";
  if (s < 86400) return "há " + Math.round(s / 3600) + " h";
  return "há " + Math.round(s / 86400) + " d";
}
export function clock(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("pt-BR", { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}
export function shortId(id) {
  const s = String(id || "");
  return s.length > 14 ? s.slice(0, 6) + "…" + s.slice(-5) : s;
}
export function truncate(s, n) {
  s = String(s ?? "");
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

/* ------------------------------------------------------------- tipos de nó */
export const KIND = {
  HTTP_SERVER: { tag: "HTTP", name: "Entrada HTTP", cls: "k-http", color: "#7ab4ff", icon: "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18|M3 12h18|M12 3c3 3 3 15 0 18|M12 3c-3 3-3 15 0 18" },
  HTTP_CLIENT: { tag: "EXT", name: "Chamada externa", cls: "k-http", color: "#7ab4ff", icon: "M14 4h6v6|M20 4l-9 9|M18 14v5H5V6h5" },
  BUSINESS: { tag: "BIZ", name: "Negócio", cls: "k-business", color: "#b39dff", icon: "M12 3l9 9-9 9-9-9z" },
  DYNAMODB: { tag: "DDB", name: "DynamoDB", cls: "k-dynamodb", color: "#35e0bd", icon: "M5 6c0-1.7 3.1-3 7-3s7 1.3 7 3-3.1 3-7 3-7-1.3-7-3z|M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6|M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3" },
  SQS: { tag: "SQS", name: "Fila SQS", cls: "k-sqs", color: "#ffd25a", icon: "M3 7h18v4H3z|M3 13h18v4H3z|M7 9h.01|M7 15h.01" },
  SNS: { tag: "SNS", name: "Tópico SNS", cls: "k-sns", color: "#ffa75a", icon: "M12 12a2 2 0 1 0 0-.01|M7 7a7 7 0 0 0 0 10|M17 7a7 7 0 0 1 0 10|M4 4a11 11 0 0 0 0 16|M20 4a11 11 0 0 1 0 16" },
  SQL: { tag: "SQL", name: "Banco SQL", cls: "k-sql", color: "#5ee3f0", icon: "M5 6c0-1.7 3.1-3 7-3s7 1.3 7 3-3.1 3-7 3-7-1.3-7-3z|M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6" },
  LAMBDA: { tag: "FN", name: "Função Lambda", cls: "k-lambda", color: "#ff86bf", icon: "M6 20l6-10|M8 4h3l7 16" },
  UNKNOWN: { tag: "?", name: "Span", cls: "k-unknown", color: "#919bad", icon: "M12 12h.01" },
};
export function kindOf(k) {
  return KIND[k] || KIND.UNKNOWN;
}
export const TRIGGER = { UI_DISPATCH: "UI", EXTERNAL: "externo", LAMBDA_EVENT: "evento Lambda", TEST: "teste" };
export const STATUS = { COMPLETED: "concluída", FAILED: "falhou", PARTIAL: "parcial", ORPHANED: "órfã", RUNNING: "em curso" };
export const STATUS_CHIP = { COMPLETED: "ok", FAILED: "err", PARTIAL: "warn", ORPHANED: "warn", RUNNING: "scan" };
/* categorias de insight: símbolo monocromático (texto/SVG pequeno) + ícone linear —
   nunca emoji (a identidade usa traço fino com brilho técnico, não pictogramas coloridos) */
export const CATEGORY = {
  PERFORMANCE: { g: "ϟ", l: "Performance", icon: "M13 2 4 14h7l-1 8 9-12h-7z" },
  ARCHITECTURE: { g: "◇", l: "Arquitetura", icon: "M12 3 2 8l10 5 10-5z|M2 16l10 5 10-5" },
  SECURITY: { g: "◈", l: "Segurança", icon: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z|M9 12l2 2 4-4" },
  TESTS: { g: "✓", l: "Testes", icon: "M9 3h6|M10 3v6L4.2 19a1.5 1.5 0 0 0 1.3 2h13a1.5 1.5 0 0 0 1.3-2L14 9V3|M7 15h10" },
  INFRASTRUCTURE: { g: "☁", l: "Infraestrutura", icon: "M7 18a5 5 0 1 1 1-9.9A6 6 0 0 1 19 10a4 4 0 0 1-1 8z" },
  RESILIENCE: { g: "↻", l: "Resiliência", icon: "M20 11a8 8 0 0 0-14.9-3|M4 4v4h4|M4 13a8 8 0 0 0 14.9 3|M20 20v-4h-4" },
  DATA: { g: "▤", l: "Dados", icon: "M5 6c0-1.7 3.1-3 7-3s7 1.3 7 3-3.1 3-7 3-7-1.3-7-3z|M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6|M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3" },
  PREDICTION: { g: "◎", l: "Predição", icon: "M3 12h4l3-8 4 16 3-8h4" },
};
export const CHAPTER_COLOR = {
  entrada: "#7ab4ff", "regra-de-negocio": "#b39dff", orquestracao: "#8b7cf6", persistencia: "#35e0bd",
  mensageria: "#ffd25a", "consumo-assincrono": "#ff86bf", "integracao-externa": "#ffa75a", desfecho: "#38dfff",
};

/* ------------------------------------------------------------- toasts / tooltip */
export function toast(message, type) {
  const box = el("div", { class: "toast" + (type ? " " + type : ""), text: message });
  $("toasts").appendChild(box);
  setTimeout(() => {
    box.classList.add("out");
    setTimeout(() => box.remove(), 350);
  }, 4200);
}
export function tip(evt, html) {
  const t = $("tooltip");
  if (!html) { t.classList.add("hidden"); return; }
  t.innerHTML = html;
  t.classList.remove("hidden");
  const x = Math.min(window.innerWidth - t.offsetWidth - 12, evt.clientX + 14);
  const y = Math.min(window.innerHeight - t.offsetHeight - 12, evt.clientY + 14);
  t.style.left = x + "px";
  t.style.top = y + "px";
}
export function copy(text, okMsg) {
  navigator.clipboard.writeText(text || "")
    .then(() => toast(okMsg || "Copiado", "ok"))
    .catch(() => toast("Não foi possível copiar", "error"));
}

/* ------------------------------------------------------------- árvore da execução */
/** Reconstrói a árvore a partir do parentId (snapshots do SSE não têm children confiáveis). */
export function buildTree(exec) {
  const nodes = [...exec.nodes.values()].map((n) => Object.assign({}, n, { children: [] }));
  const byId = new Map(nodes.map((n) => [n.nodeId, n]));
  const roots = [];
  for (const n of nodes) {
    const p = n.parentId != null ? byId.get(n.parentId) : null;
    if (p) p.children.push(n); else roots.push(n);
  }
  const byStart = (a, b) => (a.startedAt || "").localeCompare(b.startedAt || "");
  const sortRec = (list) => { list.sort(byStart); list.forEach((c) => sortRec(c.children)); };
  sortRec(roots);
  const flat = [];
  const walk = (n, depth, parent) => {
    n.depth = depth;
    n.parent = parent;
    flat.push(n);
    n.children.forEach((c) => walk(c, depth + 1, n));
  };
  roots.forEach((r) => walk(r, 0, null));
  const t0 = exec.summary && exec.summary.startedAt ? Date.parse(exec.summary.startedAt)
    : Math.min(...flat.map((n) => Date.parse(n.startedAt) || Infinity));
  for (const n of flat) {
    n.startMs = Math.max(0, (Date.parse(n.startedAt) || t0) - t0);
    n.durMs = typeof n.totalTime === "number" ? n.totalTime : 0;
    n.endMs = n.startMs + n.durMs;
  }
  return { roots, flat, byId, t0 };
}

export function selectedExec() {
  return state.selectedId ? state.executions.get(state.selectedId) : null;
}

/** Nó é consumidor assíncrono (filho de publicação SQS/SNS)? */
export function isAsyncConsumer(n) {
  return n.parent && (n.parent.kind === "SQS" || n.parent.kind === "SNS")
    && (n.kind === "LAMBDA" || n.kind === "HTTP_SERVER");
}

/** Marca do Mock Connect no passo (atributo t2l.mock: "binding=…; stub=…; variation=…"). */
export function mockOf(n) {
  const raw = n && n.attributes && n.attributes["t2l.mock"];
  if (!raw) return null;
  const out = { raw };
  for (const part of String(raw).split(";")) {
    const i = part.indexOf("=");
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  // repasse sem variação = resposta REAL da API (o mock só observou)
  out.simulated = !(out.passthrough === "true" || out.proxy === "true") || !!out.variation;
  return out;
}
