/* ÁRVORE — corte estrutural da execução (esquerda → direita), com mini-Gantt em
   cada passo, papel arquitetural, delta de dados, marcadores de insight, caminho
   crítico e espera em fila entre produtor e consumidor. */
import { state, $, el, svg, clear, on, emit, ms, truncate, kindOf, buildTree, selectedExec, isAsyncConsumer, icon, ICONS, tip, CATEGORY, mockOf } from "./core.js";

const W = 252, H = 80, GX = 70, GY = 18;
const view = { x: 40, y: 40, k: 1 };
let fitted = null;
let collapsed = new Set();
let filter = "";
let showCritical = true;
let showNotes = false;
let host, svgRoot, vp, mm;
let lastLayout = null;

export function initTree(onSelectNode) {
  host = $("view-tree");
  host.appendChild(el("div", { class: "grid-bg" }));
  const canvas = el("div", { class: "svg-host", id: "tree-host", tabindex: "0", "aria-label": "Árvore da execução — setas navegam, Enter inspeciona, espaço recolhe" });
  svgRoot = svg("svg", { width: "100%", height: "100%" });
  svgRoot.appendChild(svg("defs", null,
    svg("filter", { id: "glow" }, svg("feGaussianBlur", { stdDeviation: "3" }))));
  vp = svg("g", { id: "tree-vp" });
  svgRoot.appendChild(vp);
  canvas.appendChild(svgRoot);
  host.appendChild(canvas);

  const tools = el("div", { class: "float-tools" });
  const search = el("input", { type: "search", placeholder: "Buscar passo…", "aria-label": "Buscar passo na árvore" });
  search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); render(); });
  const bCrit = el("button", { type: "button", class: "small active", title: "Destacar caminho crítico" }, "caminho crítico");
  bCrit.addEventListener("click", () => { showCritical = !showCritical; bCrit.classList.toggle("active", showCritical); render(); });
  const bNotes = el("button", { type: "button", class: "small", title: "Notas de negócio ao lado dos passos" }, "notas");
  bNotes.addEventListener("click", async () => {
    showNotes = !showNotes;
    bNotes.classList.toggle("active", showNotes);
    if (showNotes && state.selectedId) {
      const { loadStory } = await import("./data.js");
      await loadStory(state.selectedId);
    }
    render();
  });
  const zin = el("button", { type: "button", class: "icon", title: "Aproximar (+)", "aria-label": "Aproximar" }, icon(ICONS.plus));
  const zout = el("button", { type: "button", class: "icon", title: "Afastar (-)", "aria-label": "Afastar" }, icon(ICONS.minus));
  const zfit = el("button", { type: "button", class: "icon", title: "Enquadrar (F)", "aria-label": "Enquadrar" }, icon(ICONS.fit));
  zin.addEventListener("click", () => zoom(1.2));
  zout.addEventListener("click", () => zoom(1 / 1.2));
  zfit.addEventListener("click", () => { fit(); apply(); });
  tools.append(search, bCrit, bNotes, zin, zout, zfit);
  host.appendChild(tools);

  const legend = el("div", { class: "legend", "aria-label": "Legenda" });
  for (const k of ["HTTP_SERVER", "BUSINESS", "DYNAMODB", "SQS", "SNS", "LAMBDA", "HTTP_CLIENT", "SQL"]) {
    const m = kindOf(k);
    legend.appendChild(el("span", { class: "chip " + m.cls }, icon(m.icon), m.name));
  }
  legend.appendChild(el("span", { class: "chip warn", text: "- - espera em fila" }));
  host.appendChild(legend);

  mm = el("div", { class: "minimap", "aria-hidden": "true" });
  host.appendChild(mm);

  host.appendChild(el("div", { class: "idle", id: "tree-idle" },
    el("div", null,
      el("div", { class: "rings" }, el("span"), el("span"), el("span"), el("span")),
      el("h2", { text: "Scanner pronto" }),
      el("p", { text: "Escolha uma execução no painel ou dispare um endpoint: o corte estrutural aparece aqui, passo a passo, ao vivo." }))));

  // pan / zoom
  let drag = null;
  canvas.addEventListener("mousedown", (e) => {
    if (e.target.closest(".t-node")) return;
    drag = { x: e.clientX - view.x, y: e.clientY - view.y };
    canvas.classList.add("dragging");
  });
  window.addEventListener("mouseup", () => { drag = null; canvas.classList.remove("dragging"); });
  window.addEventListener("mousemove", (e) => {
    if (!drag) return;
    view.x = e.clientX - drag.x;
    view.y = e.clientY - drag.y;
    apply();
  });
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    const r = canvas.getBoundingClientRect();
    zoom(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });
  canvas.addEventListener("keydown", (e) => keyNav(e, onSelectNode));

  vp.addEventListener("click", (e) => {
    const tw = e.target.closest(".twisty-hit");
    if (tw) {
      const id = tw.dataset.nodeId;
      if (collapsed.has(id)) collapsed.delete(id); else collapsed.add(id);
      render();
      return;
    }
    const g = e.target.closest(".t-node");
    if (g) onSelectNode(g.dataset.nodeId);
  });
  vp.addEventListener("mousemove", (e) => {
    const g = e.target.closest(".t-node");
    if (!g || !lastLayout) { tip(e, null); return; }
    const n = lastLayout.byId.get(g.dataset.nodeId);
    if (!n) return;
    tip(e, "<b>" + escapeHtml(n.label || "?") + "</b><br>" + escapeHtml(kindOf(n.kind).name) + " · " + ms(n.durMs)
      + " (self " + ms(n.selfTime) + ")<br>início +" + ms(n.startMs) + (n.error ? "<br><span class='tip-err'>✕ " + escapeHtml(n.error.type || "") + "</span>" : ""));
  });
  vp.addEventListener("mouseleave", (e) => tip(e, null));

  on("execution", (id) => { if (id === state.selectedId && state.view === "tree") render(); });
  on("selection", () => { collapsed = new Set(); if (state.view === "tree") render(); });
  on("node", () => { if (state.view === "tree") render(); });
  on("highlight", () => { if (state.view === "tree") render(); });
  on("assist", (id) => { if (id === state.selectedId && state.view === "tree") render(); });
  window.addEventListener("resize", () => { if (state.view === "tree") apply(); });
}

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** Layout tidy esquerda→direita: folhas em linhas sequenciais; pais centralizados nos filhos. */
function layout(tree) {
  let row = 0;
  const place = (n) => {
    n.x = n.depth * (W + GX);
    const kids = collapsed.has(n.nodeId) ? [] : n.children;
    if (!kids.length) {
      n.y = row * (H + GY);
      row++;
    } else {
      kids.forEach(place);
      n.y = (kids[0].y + kids[kids.length - 1].y) / 2;
    }
  };
  tree.roots.forEach((r) => { place(r); row += 0.6; });
  const visible = [];
  const walk = (n) => { visible.push(n); if (!collapsed.has(n.nodeId)) n.children.forEach(walk); };
  tree.roots.forEach(walk);
  return visible;
}

function criticalSet(tree) {
  const set = new Set();
  if (!tree.roots.length) return set;
  let cur = tree.roots[0];
  while (cur) {
    set.add(cur.nodeId);
    let next = null;
    for (const c of cur.children) if (!next || c.endMs > next.endMs) next = c;
    cur = next;
  }
  return set;
}

function insightIndex() {
  const a = state.assist.get(state.selectedId);
  const map = new Map();
  for (const i of (a && a.insights) || []) {
    for (const e of i.evidence || []) {
      const id = e.ref && e.ref.nodeId;
      if (id && (!e.ref.executionId || e.ref.executionId === state.selectedId)) {
        if (!map.has(id)) map.set(id, []);
        if (!map.get(id).includes(i)) map.get(id).push(i);
      }
    }
  }
  return map;
}

export function render() {
  const exec = selectedExec();
  clear(vp);
  const idle = $("tree-idle");
  if (!exec || !exec.nodes.size) {
    idle.classList.remove("hidden");
    clear(mm);
    lastLayout = null;
    return;
  }
  idle.classList.add("hidden");
  const tree = buildTree(exec);
  const visible = layout(tree);
  lastLayout = tree;
  const total = Math.max(1, ...tree.flat.map((n) => n.endMs));
  const crit = showCritical ? criticalSet(tree) : new Set();
  const assist = state.assist.get(state.selectedId);
  const roles = (assist && assist.technical && assist.technical.roles) || {};
  const ins = insightIndex();
  const story = showNotes ? state.story.get(state.selectedId) : null;
  const notes = new Map(((story && story.steps) || []).map((s) => [s.nodeId, s]));
  const hl = state.highlight && state.highlight.nodeIds;

  // arestas
  for (const n of visible) {
    if (collapsed.has(n.nodeId)) continue;
    for (const c of n.children) {
      const x1 = n.x + W, y1 = n.y + H / 2, x2 = c.x, y2 = c.y + H / 2;
      const mx = (x1 + x2) / 2;
      const async = isAsyncConsumer(c);
      const cls = "t-edge" + (async ? " async" : "") + (c.status === "ERROR" ? " err" : "")
        + (crit.has(n.nodeId) && crit.has(c.nodeId) ? " crit" : "")
        + (hl && hl.has(n.nodeId) && hl.has(c.nodeId) ? " lit" : "");
      vp.appendChild(svg("path", { class: cls, d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}` }));
      if (async) {
        // pílula "fila Xs" colada ao consumidor (acima da aresta, longe do botão de recolher)
        const wait = Math.max(0, c.startMs - n.endMs);
        const txt = "⧗ fila " + ms(wait);
        const w = 14 + txt.length * 6.1;
        const lx = x2 - w - 8, ly = y2 - 26;
        vp.appendChild(svg("rect", { class: "t-wait-bg", x: lx, y: ly, width: w, height: 17 }));
        vp.appendChild(svg("text", { class: "t-wait-txt", x: lx + 7, y: ly + 12, text: txt }));
      }
    }
  }
  // nós
  for (const n of visible) {
    const m = kindOf(n.kind);
    const match = filter && ((n.label || "") + " " + JSON.stringify(n.attributes || {})).toLowerCase().includes(filter);
    const cls = "t-node" + (state.selectedNodeId === n.nodeId ? " sel" : "") + (n.status === "ERROR" ? " err" : "")
      + (n.status === "PENDING" ? " pending live" : "") + (n.status === "ORPHANED" ? " orph" : "")
      + (filter && !match ? " dim" : "") + (match ? " hit" : "") + (hl && !hl.has(n.nodeId) ? " dim" : "")
      + (state.pulseNodeId === n.nodeId ? " pulse" : "");
    const sim = mockOf(n);
    const g = svg("g", { class: cls + (sim && sim.simulated ? " sim" : ""), transform: `translate(${n.x},${n.y})`, "data-node-id": n.nodeId, role: "button",
      "aria-label": (n.label || "?") + ", " + m.name + ", " + ms(n.durMs) + (sim ? (sim.simulated ? ", resposta simulada pelo Mock Connect" : ", repasse pelo Mock Connect") : "") });
    g.appendChild(svg("rect", { class: "card", width: W, height: H, rx: 10 }));
    g.appendChild(svg("rect", { class: "stripe", x: 0, y: 8, width: 3.5, height: H - 16, fill: m.color }));
    // ícone + tag
    const ic = svg("g", { transform: "translate(12,9) scale(0.62)", stroke: m.color, fill: "none", "stroke-width": 2.2, "stroke-linecap": "round", "stroke-linejoin": "round" });
    for (const d of m.icon.split("|")) ic.appendChild(svg("path", { d }));
    g.appendChild(ic);
    g.appendChild(svg("text", { class: "kindtxt", x: 32, y: 21, fill: m.color, text: m.tag }));
    // Mock Connect: SIM = resposta simulada (stub/variação); ↪ = repasse para a API real
    if (sim) {
      const pass = !sim.simulated;
      g.appendChild(svg("rect", { class: "sim-bg" + (pass ? " pass" : ""), x: 56, y: 10, width: pass ? 22 : 30, height: 14, rx: 4 }));
      g.appendChild(svg("text", { class: "sim-txt" + (pass ? " pass" : ""), x: pass ? 62 : 60.5, y: 20.5, text: pass ? "↪" : "SIM" }));
    }
    // papel (assistente)
    const role = roles[n.nodeId];
    if (role && role.label) {
      const txt = role.label + (role.engine === "jev" ? " · Jev" : "");
      const w = Math.min(130, 10 + txt.length * 5.6);
      g.appendChild(svg("rect", { class: "role-bg", x: W - w - 10, y: 8, width: w, height: 16 }));
      g.appendChild(svg("text", { class: "role", x: W - w - 3, y: 19.5, text: truncate(txt, 22) }));
    }
    g.appendChild(svg("text", { class: "lbl", x: 12, y: 42, text: truncate(n.label || "?", 30) }));
    const sub = n.status === "PENDING" ? "em curso…"
      : n.error ? "✕ " + truncate(n.error.type ? n.error.type.split(".").pop() : "erro", 26) + " · " + ms(n.durMs)
        : ms(n.durMs) + " · self " + ms(n.selfTime) + " · +" + ms(n.startMs);
    g.appendChild(svg("text", { class: "sub", x: 12, y: 58, text: sub, fill: n.error ? "#ff8a9b" : null }));
    // mini-Gantt: posição do passo na execução inteira
    const bx = 12, bw = W - 24, by = H - 13;
    g.appendChild(svg("rect", { class: "bar-bg", x: bx, y: by, width: bw, height: 5 }));
    const sx = bx + (n.startMs / total) * bw;
    const tw = Math.max(1.5, (n.durMs / total) * bw);
    g.appendChild(svg("rect", { class: "bar-total", x: sx, y: by, width: Math.min(tw, bx + bw - sx), height: 5 }));
    const self = typeof n.selfTime === "number" ? n.selfTime : 0;
    if (self > 0) g.appendChild(svg("rect", { class: "bar-self", x: sx, y: by, width: Math.max(1, Math.min(tw, (self / total) * bw)), height: 5 }));
    // delta de dados
    if (n.mutation) {
      const k = n.mutation.kind === "READ_ONLY" ? "R" : (n.mutation.kind || "Δ").slice(0, 1);
      g.appendChild(svg("rect", { class: "mut", x: W - 30, y: H - 34, width: 20, height: 14, rx: 4 }));
      g.appendChild(svg("text", { class: "mut-txt", x: W - 24, y: H - 24, text: "Δ" + (k === "R" ? "" : "") }));
    }
    // marcadores de insight
    const list = ins.get(n.nodeId) || [];
    list.slice(0, 3).forEach((i, idx) => {
      const sev = i.severity === "HIGH" || i.severity === "CRITICAL" ? "#ff6b80" : i.severity === "MEDIUM" ? "#ffbe55" : "#7ab4ff";
      const cx = W - 8 - idx * 18, cy = -6;
      g.appendChild(svg("circle", { class: "ins-dot", cx, cy, r: 9, fill: sev }));
      g.appendChild(svg("text", { x: cx - 5, y: cy + 4, "font-size": 10, text: (CATEGORY[i.category] || {}).g || "!" }));
    });
    // recolher/expandir
    if (n.children.length) {
      const c = collapsed.has(n.nodeId);
      const tw2 = svg("g", { class: "twisty-hit", "data-node-id": n.nodeId, transform: `translate(${W + 4},${H / 2 - 9})` });
      tw2.appendChild(svg("rect", { class: "twisty", width: 18, height: 18, rx: 5 }));
      tw2.appendChild(svg("text", { class: "twisty-txt", x: c ? 4.5 : 5.5, y: 13, text: c ? "+" + n.children.length : "−" }));
      g.appendChild(tw2);
    }
    vp.appendChild(g);
    // nota de negócio
    const note = notes.get(n.nodeId);
    if (note && note.text) {
      const lines = wrap(note.text, 46).slice(0, 4);
      const ng = svg("g", { transform: `translate(${n.x},${n.y + H + 4})` });
      ng.appendChild(svg("rect", { class: "t-note-bg", width: W, height: lines.length * 13 + 10 }));
      lines.forEach((l, i) => ng.appendChild(svg("text", { class: "t-note", x: 10, y: 15 + i * 13, text: l })));
      vp.appendChild(ng);
    }
  }
  if (fitted !== state.selectedId) {
    fit(visible);
    fitted = state.selectedId;
  }
  apply();
  renderMinimap(visible);
}

function wrap(text, width) {
  const words = String(text).split(/\s+/);
  const out = [];
  let cur = "";
  for (const w of words) {
    if ((cur + " " + w).trim().length > width) { if (cur) out.push(cur); cur = w; } else cur = (cur + " " + w).trim();
  }
  if (cur) out.push(cur);
  return out;
}

function bounds(nodes) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const n of nodes) {
    x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y - 14);
    x1 = Math.max(x1, n.x + W + 26); y1 = Math.max(y1, n.y + H + (showNotes ? 60 : 0));
  }
  return { x0, y0, x1, y1 };
}

function fit(nodes) {
  nodes = nodes || (lastLayout ? layout(lastLayout) : []);
  if (!nodes.length) return;
  const b = bounds(nodes);
  const cw = host.clientWidth || 900, ch = host.clientHeight || 600;
  const k = Math.min(1.15, (cw - 80) / (b.x1 - b.x0), (ch - 120) / (b.y1 - b.y0));
  view.k = Math.max(0.15, k);
  view.x = (cw - (b.x1 - b.x0) * view.k) / 2 - b.x0 * view.k;
  view.y = Math.max(56, (ch - (b.y1 - b.y0) * view.k) / 2) - b.y0 * view.k;
}

function zoom(f, cx, cy) {
  const cw = host.clientWidth, ch = host.clientHeight;
  cx = cx ?? cw / 2;
  cy = cy ?? ch / 2;
  const k = Math.max(0.12, Math.min(3, view.k * f));
  view.x = cx - ((cx - view.x) * k) / view.k;
  view.y = cy - ((cy - view.y) * k) / view.k;
  view.k = k;
  apply();
}

function apply() {
  vp.setAttribute("transform", `translate(${view.x},${view.y}) scale(${view.k})`);
  if (lastLayout) renderMinimap();
}

function renderMinimap(visibleNodes) {
  if (!lastLayout) return;
  const nodes = visibleNodes || layout(lastLayout);
  if (!nodes.length) return;
  const b = bounds(nodes);
  const s = svg("svg", { viewBox: `${b.x0 - 20} ${b.y0 - 20} ${b.x1 - b.x0 + 40} ${b.y1 - b.y0 + 40}`, preserveAspectRatio: "xMidYMid meet" });
  for (const n of nodes) s.appendChild(svg("rect", { class: "mm-node" + (n.status === "ERROR" ? " err" : ""), x: n.x, y: n.y, width: W, height: H, rx: 12 }));
  const cw = host.clientWidth, ch = host.clientHeight;
  s.appendChild(svg("rect", { class: "mm-view", x: -view.x / view.k, y: -view.y / view.k, width: cw / view.k, height: ch / view.k }));
  clear(mm).appendChild(s);
}

function keyNav(e, onSelectNode) {
  if (!lastLayout) return;
  const cur = lastLayout.byId.get(state.selectedNodeId) || lastLayout.roots[0];
  if (!cur) return;
  let next = null;
  if (e.key === "ArrowRight") next = cur.children[0];
  else if (e.key === "ArrowLeft") next = cur.parent;
  else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    const sibs = cur.parent ? cur.parent.children : lastLayout.roots;
    const i = sibs.indexOf(cur) + (e.key === "ArrowDown" ? 1 : -1);
    next = sibs[Math.max(0, Math.min(sibs.length - 1, i))];
  } else if (e.key === " ") {
    e.preventDefault();
    if (collapsed.has(cur.nodeId)) collapsed.delete(cur.nodeId); else collapsed.add(cur.nodeId);
    render();
    return;
  } else if (e.key === "f" || e.key === "F") {
    fit(); apply(); return;
  } else if (e.key === "+" || e.key === "=") { zoom(1.2); return; }
  else if (e.key === "-") { zoom(1 / 1.2); return; }
  if (next) {
    e.preventDefault();
    onSelectNode(next.nodeId);
    centerOn(next.nodeId);
  }
}

/** Largura útil do canvas: o inspector (gaveta) cobre a faixa da direita quando aberto. */
function visibleWidth() {
  const d = document.getElementById("drawer");
  const covered = d && d.classList.contains("open") && window.innerWidth > 860 ? d.offsetWidth : 0;
  return Math.max(240, host.clientWidth - covered);
}

export function centerOn(nodeId) {
  if (!lastLayout) return;
  const n = lastLayout.byId.get(nodeId);
  if (!n || n.x === undefined) return;
  const cw = visibleWidth(), ch = host.clientHeight;
  view.x = cw / 2 - (n.x + W / 2) * view.k;
  view.y = ch / 2 - (n.y + H / 2) * view.k;
  apply();
}

/** Traz o passo para a área visível só se ele estiver (parcialmente) escondido — sem "pular" a câmera à toa. */
export function ensureVisible(nodeId) {
  if (!lastLayout) return;
  const n = lastLayout.byId.get(nodeId);
  if (!n || n.x === undefined) return;
  const x0 = view.x + n.x * view.k, x1 = view.x + (n.x + W) * view.k;
  const y0 = view.y + n.y * view.k, y1 = view.y + (n.y + H) * view.k;
  const m = 24;
  if (x0 < m || x1 > visibleWidth() - m || y0 < 48 || y1 > host.clientHeight - m) centerOn(nodeId);
}

export function refit() {
  fitted = null;
}
export { emit };
