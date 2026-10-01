/* ANATOMIA — o "corte axial" do ecossistema: anéis concêntricos por zona
   (núcleo = código da aplicação · fronteira local = AWS/LocalStack · fronteira
   externa = parceiros fora da máquina · declarado = IaC nunca observado).
   A execução selecionada acende os órgãos que tocou e um "contraste" (partículas)
   percorre as conexões na ordem real dos spans. */
import { state, $, el, svg, clear, on, emit, ms, truncate, kindOf, icon, ICONS, tip, CATEGORY, buildTree, selectedExec, shortId, STATUS, relTime } from "./core.js";

const ZONES = [
  { id: "core", name: "Núcleo", hint: "Código da aplicação — serviços, funções e regras" },
  { id: "boundary", name: "Fronteira local", hint: "Recursos AWS/LocalStack — tabelas, filas, tópicos" },
  { id: "external", name: "Fronteira externa", hint: "Parceiros e APIs fora da máquina" },
  { id: "declared", name: "Declarado (IaC)", hint: "Existe no Terraform/IaC e nunca apareceu em execução" },
];
const reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const view = { x: 0, y: 0, k: 1 };
let host, svgRoot, vp, side, listHost, sweep;
let layoutCache = null;          // {pos: Map(id -> {x,y,r,c}), rings, edges}
let selectedComp = null;
let filter = "";
let showList = false;
let showFlow = !reduceMotion;
let fittedKey = null;
let raf = 0;
let callbacks = {};

export function initAnatomy(cb) {
  callbacks = cb || {};
  host = $("view-anatomy");
  host.appendChild(el("div", { class: "grid-bg" }));
  const canvas = el("div", { class: "svg-host", tabindex: "0", "aria-label": "Anatomia do ecossistema — clique num componente para examiná-lo" });
  svgRoot = svg("svg", { width: "100%", height: "100%" });
  const defs = svg("defs", null,
    svg("radialGradient", { id: "sweepGrad", cx: "0", cy: "0", r: "1", gradientUnits: "objectBoundingBox" },
      svg("stop", { offset: "0", "stop-color": "#38dfff", "stop-opacity": "0.0" }),
      svg("stop", { offset: "1", "stop-color": "#38dfff", "stop-opacity": "0.16" })));
  svgRoot.appendChild(defs);
  vp = svg("g", { id: "anatomy-vp" });
  svgRoot.appendChild(vp);
  canvas.appendChild(svgRoot);
  host.appendChild(canvas);

  listHost = el("div", { class: "view-pad hidden", id: "anatomy-list" });
  host.appendChild(listHost);

  // legenda: zonas + leitura rápida
  const legend = el("div", { class: "legend", id: "anatomy-legend" });
  host.appendChild(legend);

  // ferramentas
  const tools = el("div", { class: "float-tools" });
  const search = el("input", { type: "search", placeholder: "Buscar componente…", "aria-label": "Buscar componente na anatomia" });
  search.addEventListener("input", () => { filter = search.value.trim().toLowerCase(); render(); });
  const bFlow = el("button", { type: "button", class: "small" + (showFlow ? " active" : ""), title: "Contraste: partículas percorrem o fluxo da execução selecionada" }, "contraste");
  bFlow.addEventListener("click", () => { showFlow = !showFlow; bFlow.classList.toggle("active", showFlow); render(); });
  const bList = el("button", { type: "button", class: "small", title: "Alternativa acessível em lista" }, icon(ICONS.list), " lista");
  bList.addEventListener("click", () => { showList = !showList; bList.classList.toggle("active", showList); render(); });
  const zin = el("button", { type: "button", class: "icon", title: "Aproximar", "aria-label": "Aproximar" }, icon(ICONS.plus));
  const zout = el("button", { type: "button", class: "icon", title: "Afastar", "aria-label": "Afastar" }, icon(ICONS.minus));
  const zfit = el("button", { type: "button", class: "icon", title: "Enquadrar (F)", "aria-label": "Enquadrar" }, icon(ICONS.fit));
  zin.addEventListener("click", () => zoom(1.2));
  zout.addEventListener("click", () => zoom(1 / 1.2));
  zfit.addEventListener("click", () => { fit(); apply(); });
  tools.append(search, bFlow, bList, zin, zout, zfit);
  host.appendChild(tools);

  side = el("aside", { class: "anatomy-side hidden", "aria-label": "Laudo do componente" });
  host.appendChild(side);

  host.appendChild(el("div", { class: "idle", id: "anatomy-idle" },
    el("div", null,
      el("div", { class: "rings" }, el("span"), el("span"), el("span"), el("span")),
      el("h2", { text: "Resonance pronta" }),
      el("p", { text: "Execute a aplicação (endpoint, Lambda, fila ou teste). Cada execução revela os órgãos do ecossistema — serviços, tabelas, filas, tópicos e parceiros — e as conexões entre eles." }))));

  // pan / zoom
  let drag = null;
  canvas.addEventListener("mousedown", (e) => {
    if (e.target.closest(".a-node")) return;
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
  canvas.addEventListener("keydown", (e) => {
    if (e.key === "f" || e.key === "F") { fit(); apply(); }
    else if (e.key === "+" || e.key === "=") zoom(1.2);
    else if (e.key === "-") zoom(1 / 1.2);
    else if (e.key === "Escape") selectComponent(null);
  });
  vp.addEventListener("click", (e) => {
    const g = e.target.closest(".a-node");
    selectComponent(g ? g.dataset.id : null);
  });
  vp.addEventListener("mousemove", (e) => {
    const g = e.target.closest(".a-node");
    if (!g || !state.topology) { tip(e, null); return; }
    const c = (state.topology.components || []).find((x) => x.id === g.dataset.id);
    if (!c) return;
    const z = ZONES.find((x) => x.id === c.zone);
    tip(e, "<b>" + esc(c.label) + "</b> <span class='tip-muted'>" + esc(kindOf(c.kind).name) + " · " + esc(z ? z.name : c.zone) + "</span><br>"
      + (c.zone === "declared" ? "nunca observado · " + esc(c.source || "IaC")
        : c.calls + " chamada(s) · p50 " + ms(c.p50) + " · p95 " + ms(c.p95)
          + (c.errors ? "<br><span class='tip-err'>✕ " + c.errors + " erro(s)</span>" : "")
          + (c.insights ? "<br>" + c.insights + " ponto(s) de atenção" : "")));
  });
  vp.addEventListener("mouseleave", (e) => tip(e, null));

  on("topology", () => { if (state.view === "anatomy") render(); });
  on("selection", () => { if (state.view === "anatomy") render(); });
  on("assist", (id) => { if (id === state.selectedId && state.view === "anatomy") render(); });
  on("insights", () => { if (state.view === "anatomy" && selectedComp) renderSide(); });
  on("playhead", () => { if (state.view === "anatomy") paintPlayhead(); });
  let rz = null;
  window.addEventListener("resize", () => {
    clearTimeout(rz);
    rz = setTimeout(() => { if (state.view === "anatomy") { fit(); apply(); } }, 120);
  });
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ------------------------------------------------------------- layout radial */
const circMean = (angles) => {
  if (!angles.length) return NaN;
  let sx = 0, sy = 0;
  for (const a of angles) { sx += Math.cos(a); sy += Math.sin(a); }
  return Math.atan2(sy, sx);
};

function computeLayout(topo) {
  const comps = topo.components || [];
  const byZone = { core: [], boundary: [], external: [], declared: [] };
  for (const c of comps) (byZone[c.zone] || byZone.core).push(c);
  const neighbors = new Map();
  for (const e of topo.edges || []) {
    if (!neighbors.has(e.from)) neighbors.set(e.from, new Set());
    if (!neighbors.has(e.to)) neighbors.set(e.to, new Set());
    neighbors.get(e.from).add(e.to);
    neighbors.get(e.to).add(e.from);
  }
  const pos = new Map();
  const rings = [];
  const radiusFor = (n, spacing, min) => Math.max(min, (n * spacing) / (2 * Math.PI));
  const sizeOf = (c) => (c.zone === "declared" ? 12 : 15 + Math.min(14, 3.5 * Math.log2(1 + (c.calls || 0))));

  // núcleo: ordem por fluxo (entradas primeiro) — 1 componente fica no centro
  const core = byZone.core.slice().sort((a, b) => (b.calls - a.calls) || a.id.localeCompare(b.id));
  let r = 0;
  if (core.length === 1) {
    pos.set(core[0].id, { x: 0, y: 0, r: sizeOf(core[0]), c: core[0], a: -Math.PI / 2 });
    r = 120;
    rings.push({ zone: "core", r: 90 });
  } else if (core.length > 1) {
    r = radiusFor(core.length, 150, 130);
    core.forEach((c, i) => {
      const a = -Math.PI / 2 + (i * 2 * Math.PI) / core.length;
      pos.set(c.id, { x: r * Math.cos(a), y: r * Math.sin(a), r: sizeOf(c), c, a });
    });
    rings.push({ zone: "core", r: r + 60 });
    r += 60;
  }
  const place = (list, zone, gap, spacing) => {
    if (!list.length) return;
    const R = Math.max(r + gap, radiusFor(list.length, spacing, r + gap));
    // ângulo desejado = média circular dos vizinhos já posicionados
    const want = list.map((c) => {
      const angs = [...(neighbors.get(c.id) || [])].map((id) => pos.get(id)).filter(Boolean).map((p) => p.a);
      return { c, a: circMean(angs) };
    });
    const known = want.filter((w) => !Number.isNaN(w.a)).sort((x, y) => x.a - y.a);
    const unknown = want.filter((w) => Number.isNaN(w.a)).sort((x, y) => x.c.id.localeCompare(y.c.id));
    const ordered = known.concat(unknown);
    const n = ordered.length;
    const step = (2 * Math.PI) / n;
    // rotação que melhor alinha os slots aos ângulos desejados
    const offsets = known.map((w) => w.a - ordered.indexOf(w) * step);
    const a0 = offsets.length ? circMean(offsets) : -Math.PI / 2;
    ordered.forEach((w, i) => {
      const a = a0 + i * step;
      pos.set(w.c.id, { x: R * Math.cos(a), y: R * Math.sin(a), r: sizeOf(w.c), c: w.c, a });
    });
    rings.push({ zone, r: R });
    r = R;
  };
  place(byZone.boundary, "boundary", 130, 140);
  place(byZone.external, "external", 130, 150);
  place(byZone.declared.sort((a, b) => a.kind.localeCompare(b.kind) || a.id.localeCompare(b.id)), "declared", 110, 120);
  // caixa real do desenho (nós + rótulos + anéis) — o enquadramento usa ESTA caixa
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of pos.values()) {
    x0 = Math.min(x0, p.x - Math.max(p.r, 70)); x1 = Math.max(x1, p.x + Math.max(p.r, 70));
    y0 = Math.min(y0, p.y - p.r - 14); y1 = Math.max(y1, p.y + p.r + 36);
  }
  for (const ring of rings) {
    x0 = Math.min(x0, -ring.r); x1 = Math.max(x1, ring.r); y0 = Math.min(y0, -ring.r - 18); y1 = Math.max(y1, ring.r);
  }
  return { pos, rings, edges: topo.edges || [], maxR: r + 40, box: { x0, y0, x1, y1 } };
}

/* ------------------------------------------------------------- componentes da execução selecionada */
function selectedComponents() {
  const out = { set: new Set(), byNode: new Map() };
  if (!state.selectedId) return out;
  const a = state.assist.get(state.selectedId);
  if (a && a.components) {
    for (const [nodeId, comp] of Object.entries(a.components)) {
      out.set.add(comp);
      out.byNode.set(nodeId, comp);
    }
  } else if (state.topology) {
    for (const c of state.topology.components || []) {
      if ((c.executionIds || []).includes(state.selectedId)) out.set.add(c.id);
    }
  }
  return out;
}

function edgePath(p1, p2) {
  // curva puxada para o centro: feixes passam "por dentro" do corpo, como fibras
  const mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
  const pull = Math.hypot(p1.x - p2.x, p1.y - p2.y) < 60 ? 1 : 0.62;
  const cx = mx * pull, cy = my * pull;
  return `M${p1.x},${p1.y} Q${cx},${cy} ${p2.x},${p2.y}`;
}

export function render() {
  cancelAnimationFrame(raf);
  const topo = state.topology;
  const comps = (topo && topo.components) || [];
  const idle = $("anatomy-idle");
  renderLegend(topo);
  if (!comps.length) {
    clear(vp);
    idle.classList.remove("hidden");
    side.classList.add("hidden");
    listHost.classList.add("hidden");
    return;
  }
  idle.classList.add("hidden");
  if (showList) {
    host.querySelector(".svg-host").classList.add("hidden");
    listHost.classList.remove("hidden");
    renderList(topo);
    return;
  }
  host.querySelector(".svg-host").classList.remove("hidden");
  listHost.classList.add("hidden");

  layoutCache = computeLayout(topo);
  const { pos, rings, edges } = layoutCache;
  const sel = selectedComponents();
  const hasSel = sel.set.size > 0;
  clear(vp);

  // anéis de zona
  const ringG = svg("g", { class: "rings" });
  for (const ring of rings) {
    ringG.appendChild(svg("circle", { class: "zone-ring " + ring.zone, r: ring.r }));
    const z = ZONES.find((x) => x.id === ring.zone);
    ringG.appendChild(svg("text", { class: "zone-label " + ring.zone, x: 0, y: -ring.r - 8, "text-anchor": "middle", text: z ? z.name : ring.zone }));
  }
  vp.appendChild(ringG);

  // varredura (radar) — viva enquanto há execução em curso
  const live = [...state.executions.values()].some((e) => e.summary && e.summary.status === "RUNNING");
  const R = layoutCache.maxR;
  const a1 = -Math.PI / 2, a2 = a1 + 0.7;
  sweep = svg("path", {
    class: "a-sweep" + (live && !reduceMotion ? " on" : ""),
    d: `M0,0 L${R * Math.cos(a1)},${R * Math.sin(a1)} A${R},${R} 0 0 1 ${R * Math.cos(a2)},${R * Math.sin(a2)} Z`,
  });
  vp.appendChild(sweep);

  // arestas
  const edgeG = svg("g", { class: "edges" });
  const edgeEls = new Map();
  for (const e of edges) {
    const p1 = pos.get(e.from), p2 = pos.get(e.to);
    if (!p1 || !p2) continue;
    const lit = hasSel && sel.set.has(e.from) && sel.set.has(e.to);
    const w = 1 + Math.min(3, Math.log2(1 + e.calls) * 0.6);
    const path = svg("path", {
      class: "a-edge" + (e.async ? " async" : "") + (e.errors ? " err" : "") + (lit ? " lit" : ""),
      d: edgePath(p1, p2), "stroke-width": w,
    });
    if (hasSel && !lit) path.setAttribute("opacity", "0.25");
    edgeG.appendChild(path);
    edgeEls.set(e.from + "→" + e.to, path);
    if (e.async && e.waitP50 > 0 && (!hasSel || lit)) {
      const mx = ((p1.x + p2.x) / 2) * 0.62, my = ((p1.y + p2.y) / 2) * 0.62;
      edgeG.appendChild(svg("text", { class: "t-wait", x: mx + 6, y: my - 4, text: "fila p50 " + ms(e.waitP50) }));
    }
  }
  vp.appendChild(edgeG);

  // nós
  const nodeG = svg("g", { class: "nodes" });
  for (const [id, p] of pos) {
    const c = p.c;
    const m = kindOf(c.kind);
    const match = filter && (c.label + " " + c.id).toLowerCase().includes(filter);
    const cls = "a-node" + (c.zone === "declared" ? " declared" : "") + (hasSel && sel.set.has(id) ? " lit" : "")
      + ((hasSel && !sel.set.has(id)) || (filter && !match) ? " dim" : "") + (selectedComp === id ? " sel" : "");
    const g = svg("g", { class: cls, transform: `translate(${p.x},${p.y})`, "data-id": id, role: "button",
      "aria-label": c.label + ", " + m.name + (c.errors ? ", " + c.errors + " erros" : "") });
    g.appendChild(svg("circle", { class: "halo", r: p.r + 6, stroke: m.color, color: m.color }));
    g.appendChild(svg("circle", { class: "body", r: p.r, fill: m.color }));
    const ic = svg("g", { transform: `translate(${-p.r * 0.55},${-p.r * 0.55}) scale(${(p.r * 1.1) / 24})`, stroke: "#03141a", fill: "none", "stroke-width": 2.4, "stroke-linecap": "round", "stroke-linejoin": "round" });
    for (const d of m.icon.split("|")) ic.appendChild(svg("path", { d }));
    if (c.zone !== "declared") g.appendChild(ic);
    g.appendChild(svg("text", { class: "name", y: p.r + 16, "text-anchor": "middle", text: truncate(c.label, 26) }));
    g.appendChild(svg("text", { class: "stat", y: p.r + 29, "text-anchor": "middle",
      text: c.zone === "declared" ? "declarado · nunca visto" : c.calls + "× · p95 " + ms(c.p95) }));
    if (c.errors) {
      g.appendChild(svg("circle", { class: "alert err", cx: -p.r * 0.75, cy: -p.r * 0.75, r: 8 }));
      g.appendChild(svg("text", { class: "alert-txt", x: -p.r * 0.75 - 3, y: -p.r * 0.75 + 3.5, text: "!" }));
    }
    if (c.insights) {
      g.appendChild(svg("circle", { class: "alert", cx: p.r * 0.78, cy: -p.r * 0.78, r: 9 }));
      g.appendChild(svg("text", { class: "alert-txt", x: p.r * 0.78 - 5, y: -p.r * 0.78 + 3.5, text: (CATEGORY[c.insightCategory] || {}).g || String(c.insights) }));
    }
    nodeG.appendChild(g);
  }
  vp.appendChild(nodeG);
  vp.appendChild(svg("g", { id: "a-particles" }));

  const key = comps.length + ":" + (topo.edges || []).length + ":" + host.clientWidth + "x" + host.clientHeight;
  if (fittedKey !== key && host.clientWidth > 0) { fit(); fittedKey = key; }
  apply();
  if (selectedComp) renderSide(); else side.classList.add("hidden");
  if (hasSel && showFlow && !reduceMotion) animateFlow(sel, edgeEls);
  paintPlayhead();
}

/* ------------------------------------------------------------- contraste: partículas na ordem real dos spans */
function animateFlow(sel, edgeEls) {
  const exec = selectedExec();
  if (!exec || !exec.nodes.size) return;
  const tree = buildTree(exec);
  const total = Math.max(1, ...tree.flat.map((n) => n.endMs));
  const hops = [];
  for (const n of tree.flat) {
    if (!n.parent) continue;
    const from = sel.byNode.get(n.parent.nodeId), to = sel.byNode.get(n.nodeId);
    if (!from || !to || from === to) continue;
    const path = edgeEls.get(from + "→" + to);
    if (!path) continue;
    hops.push({ path, len: path.getTotalLength(), at: n.startMs / total, err: n.status === "ERROR" });
  }
  if (!hops.length) return;
  const layer = $("a-particles");
  const dots = hops.map((h) => {
    const d = svg("circle", { class: "a-particle", r: 4.5 });
    if (h.err) d.setAttribute("fill", "#ff6b80");
    layer.appendChild(d);
    return d;
  });
  const CYCLE = 4200, TRAVEL = 0.16;
  const t0 = performance.now();
  const frame = (now) => {
    if (state.view !== "anatomy" || !document.body.contains(layer)) return;
    const t = ((now - t0) % CYCLE) / CYCLE;
    hops.forEach((h, i) => {
      const start = h.at * (1 - TRAVEL);
      const u = (t - start) / TRAVEL;
      if (u < 0 || u > 1) { dots[i].setAttribute("opacity", "0"); return; }
      const p = h.path.getPointAtLength(h.len * u);
      dots[i].setAttribute("cx", p.x);
      dots[i].setAttribute("cy", p.y);
      dots[i].setAttribute("opacity", String(Math.sin(Math.PI * u) * 0.9 + 0.1));
    });
    raf = requestAnimationFrame(frame);
  };
  raf = requestAnimationFrame(frame);
}

/* sincroniza com a linha do tempo: o componente ativo no instante do cursor pulsa */
function paintPlayhead() {
  if (!layoutCache || state.playhead == null) return;
  const exec = selectedExec();
  const a = state.selectedId && state.assist.get(state.selectedId);
  if (!exec || !a || !a.components) return;
  const tree = buildTree(exec);
  const active = new Set();
  for (const n of tree.flat) {
    if (state.playhead >= n.startMs && state.playhead <= Math.max(n.endMs, n.startMs + 1)) active.add(a.components[n.nodeId]);
  }
  vp.querySelectorAll(".a-node").forEach((g) => g.classList.toggle("sel", active.has(g.dataset.id) || g.dataset.id === selectedComp));
}

/* ------------------------------------------------------------- legenda */
function renderLegend(topo) {
  const legend = clear($("anatomy-legend"));
  const comps = (topo && topo.components) || [];
  const count = (z) => comps.filter((c) => c.zone === z).length;
  for (const z of ZONES) {
    const n = count(z.id);
    if (!n && z.id === "declared") continue;
    const chip = el("span", { class: "chip", title: z.hint }, el("span", { class: "dot zone-dot-" + z.id }), z.name + " · " + n);
    legend.appendChild(chip);
  }
  if (topo) {
    legend.appendChild(el("span", { class: "chip", title: "Acervo usado para montar a anatomia (últimas execuções)" },
      (topo.executions || 0) + " execuções · " + (topo.flows || 0) + " fluxos"));
  }
  if (count("external") === 0 && comps.length) {
    legend.appendChild(el("span", { class: "chip", title: "Chamadas a hosts fora da máquina aparecerão no anel externo" }, "fronteira externa: nenhuma chamada observada"));
  }
}

/* ------------------------------------------------------------- laudo lateral do componente */
export function selectComponent(id) {
  selectedComp = id;
  vp.querySelectorAll(".a-node").forEach((g) => g.classList.toggle("sel", g.dataset.id === id));
  if (!id) { side.classList.add("hidden"); return; }
  renderSide();
}

function renderSide() {
  const topo = state.topology;
  const c = topo && (topo.components || []).find((x) => x.id === selectedComp);
  if (!c) { side.classList.add("hidden"); return; }
  const m = kindOf(c.kind);
  const z = ZONES.find((x) => x.id === c.zone);
  clear(side).classList.remove("hidden");
  const close = el("button", { type: "button", class: "icon ghost", "aria-label": "Fechar laudo" }, icon(ICONS.x));
  close.addEventListener("click", () => selectComponent(null));
  side.appendChild(el("div", { class: "row" },
    el("div", { class: "grow" },
      el("div", { class: "muted", text: "LAUDO DO COMPONENTE" }),
      el("h3", { text: c.label })),
    close));
  side.appendChild(el("div", { class: "row" },
    el("span", { class: "chip " + m.cls }, icon(m.icon), m.name),
    el("span", { class: "chip", title: z ? z.hint : "" }, z ? z.name : c.zone)));

  if (c.zone === "declared") {
    side.appendChild(el("div", { class: "honest" },
      "Declarado no IaC (" + (c.source || "?") + ") e nunca observado em execução. Pode ser um recurso morto, um fluxo ainda não exercitado nos testes ou um ambiente divergente."));
    return;
  }
  const kv = el("div", { class: "kv" });
  const add = (k, v) => kv.append(el("span", { class: "k", text: k }), el("span", { class: "v", text: v }));
  add("id", c.id);
  add("chamadas", String(c.calls));
  add("erros", String(c.errors) + (c.calls ? " (" + Math.round((100 * c.errors) / c.calls) + "%)" : ""));
  add("latência p50", ms(c.p50));
  add("latência p95", ms(c.p95));
  add("fluxos", String(c.flows || 0));
  add("vizinhos", String(c.neighbors || 0));
  side.appendChild(kv);

  // conexões
  const edges = (topo.edges || []).filter((e) => e.from === c.id || e.to === c.id);
  if (edges.length) {
    const sec = el("div", { class: "sec" }, el("h4", { text: "CONEXÕES" }));
    for (const e of edges) {
      const other = e.from === c.id ? e.to : e.from;
      const oc = (topo.components || []).find((x) => x.id === other);
      const row = el("div", { class: "dash-row", tabindex: "0" },
        el("span", { class: "muted", text: e.from === c.id ? "→" : "←" }),
        el("span", { class: "grow", text: oc ? oc.label : other }),
        e.async ? el("span", { class: "chip warn", text: "assíncrono" + (e.waitP50 ? " · fila " + ms(e.waitP50) : "") }) : null,
        el("span", { class: "mono muted", text: e.calls + "×" }),
        e.errors ? el("span", { class: "chip err", text: e.errors + " erro(s)" }) : null);
      row.addEventListener("click", () => selectComponent(other));
      sec.appendChild(row);
    }
    side.appendChild(sec);
  }

  // insights relacionados
  const related = (state.insights || []).filter((i) => (i.affectedComponents || []).includes(c.id));
  if (related.length) {
    const sec = el("div", { class: "sec" }, el("h4", { text: "PONTOS DE ATENÇÃO" }));
    for (const i of related.slice(0, 6)) {
      const item = el("div", { class: "ins-mini", tabindex: "0" },
        el("div", { class: "row" }, el("span", { text: (CATEGORY[i.category] || {}).g || "•" }), el("span", { class: "t grow", text: i.title })),
        el("div", { class: "m", text: Math.round(i.confidence * 100) + "% · " + i.confidenceBand + " · " + i.id }));
      item.addEventListener("click", () => callbacks.openInsight && callbacks.openInsight(i));
      sec.appendChild(item);
    }
    side.appendChild(sec);
  }

  // execuções que tocaram o componente
  const execs = (c.executionIds || []).map((id) => state.executions.get(id)).filter((e) => e && e.summary)
    .sort((a, b) => (b.summary.startedAt || "").localeCompare(a.summary.startedAt || "")).slice(0, 8);
  if (execs.length) {
    const sec = el("div", { class: "sec" }, el("h4", { text: "EXECUÇÕES QUE PASSARAM AQUI" }));
    for (const e of execs) {
      const s = e.summary;
      const row = el("div", { class: "dash-row", tabindex: "0" },
        el("span", { class: "chip " + (s.status === "FAILED" ? "err" : s.status === "COMPLETED" ? "ok" : "warn"), text: STATUS[s.status] || s.status }),
        el("span", { class: "grow", text: s.rootLabel || shortId(s.executionId) }),
        el("span", { class: "mono muted", text: ms(s.duration) }),
        el("span", { class: "muted", text: relTime(s.startedAt) }));
      row.addEventListener("click", () => callbacks.examine && callbacks.examine(s.executionId, c.id));
      sec.appendChild(row);
    }
    side.appendChild(sec);
  }
}

/* ------------------------------------------------------------- alternativa acessível (lista por zona) */
function renderList(topo) {
  clear(listHost);
  listHost.appendChild(el("p", { class: "muted", text: "Leitura em lista da anatomia — mesma informação do mapa, ordenada por zona e volume." }));
  const wrap = el("div", { class: "list-alt" });
  for (const z of ZONES) {
    const list = (topo.components || []).filter((c) => c.zone === z.id).sort((a, b) => b.calls - a.calls);
    if (!list.length) continue;
    wrap.appendChild(el("h4", { class: "zone-h", text: z.name.toUpperCase() + " — " + z.hint }));
    for (const c of list) {
      const m = kindOf(c.kind);
      const row = el("div", { class: "dash-row", tabindex: "0", role: "button" },
        el("span", { class: "chip " + m.cls }, m.tag),
        el("span", { class: "grow", text: c.label }),
        c.zone === "declared" ? el("span", { class: "muted", text: c.source || "IaC" })
          : el("span", { class: "mono muted", text: c.calls + "× · p95 " + ms(c.p95) }),
        c.errors ? el("span", { class: "chip err", text: c.errors + " erro(s)" }) : null,
        c.insights ? el("span", { class: "chip warn", text: c.insights + " alerta(s)" }) : null);
      row.addEventListener("click", () => { showList = false; render(); selectComponent(c.id); });
      wrap.appendChild(row);
    }
  }
  listHost.appendChild(wrap);
}

/* ------------------------------------------------------------- câmera */
function fit() {
  if (!layoutCache) return;
  const b = layoutCache.box;
  const cw = host.clientWidth || 900, ch = host.clientHeight || 600;
  const side = selectedComp && cw > 860 ? 340 : 0;
  const top = 64; // legenda
  const w = Math.max(1, b.x1 - b.x0), h = Math.max(1, b.y1 - b.y0);
  view.k = Math.max(0.25, Math.min(1.6, (cw - side - 40) / w, (ch - top - 70) / h));
  view.x = (cw - side) / 2 - ((b.x0 + b.x1) / 2) * view.k;
  view.y = top + (ch - top - 60) / 2 - ((b.y0 + b.y1) / 2) * view.k;
}
function zoom(f, cx, cy) {
  const cw = host.clientWidth, ch = host.clientHeight;
  cx = cx ?? cw / 2;
  cy = cy ?? ch / 2;
  const k = Math.max(0.15, Math.min(3, view.k * f));
  view.x = cx - ((cx - view.x) * k) / view.k;
  view.y = cy - ((cy - view.y) * k) / view.k;
  view.k = k;
  apply();
}
function apply() {
  vp.setAttribute("transform", `translate(${view.x},${view.y}) scale(${view.k})`);
}
export function refit() { fittedKey = null; }
export { emit };
