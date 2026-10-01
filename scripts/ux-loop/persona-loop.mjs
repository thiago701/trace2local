/**
 * LOOP DE USABILIDADE POR PERSONA — Trace2Local Resonance (UI v4) — cenário examples/lambda-sqs.
 *
 * Simula 4 personas reais sobre um Station/app VIVO (ex.: examples/lambda-sqs com
 * LocalStack): dev em primeiro contato, dev investigando o fluxo assíncrono, QA/PO
 * homologando regras e tech lead triando insights. Cada passo vira um CHECK
 * (passou/falhou + evidência) e um screenshot; ao final, um relatório Markdown.
 *
 *   T2L_BASE=http://127.0.0.1:19877/trace2local node persona-loop.mjs
 *   T2L_UI_DIR=../../trace2local-ui/src/main/resources/META-INF/resources/trace2local  (opcional)
 *
 * Com T2L_UI_DIR, os assets da UI vêm da ÁRVORE DE TRABALHO (o servidor continua
 * respondendo — cabeçalhos/CSP reais preservados): ciclo de UX sem rebuild.
 * Saída: ./out/<timestamp>/ (png + report.md + report.json)
 */
import { chromium } from "playwright";
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.T2L_BASE || "http://127.0.0.1:19877/trace2local").replace(/\/$/, "");
const UI_DIR = process.env.T2L_UI_DIR ? path.resolve(HERE, process.env.T2L_UI_DIR) : null;
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.resolve(process.env.T2L_OUT || path.join(HERE, "out", STAMP));
mkdirSync(OUT, { recursive: true });

const checks = [];
const consoleErrors = [];
const cspViolations = [];
let shot = 0;

function check(persona, name, ok, detail) {
  checks.push({ persona, name, ok: !!ok, detail: detail || "" });
  console.log((ok ? "  ✓ " : "  ✗ ") + "[" + persona + "] " + name + (detail ? " — " + detail : ""));
}
async function snap(page, name) {
  const file = String(++shot).padStart(2, "0") + "-" + name + ".png";
  await page.screenshot({ path: path.join(OUT, file) });
  return file;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(page, p) {
  return page.evaluate(async (u) => (await fetch(u)).json(), BASE + "/api" + p);
}

async function newPage(browser, viewport) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 1, colorScheme: "dark", reducedMotion: "no-preference" });
  const page = await ctx.newPage();
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push("pageerror: " + e.message));
  await page.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", (e) => {
      window.__csp = (window.__csp || []).concat([e.violatedDirective + " " + (e.blockedURI || "") + " " + (e.sourceFile || "") + ":" + (e.lineNumber || "")]);
    });
  });
  if (UI_DIR) {
    // assets da árvore de trabalho, cabeçalhos (CSP!) do servidor real
    await page.route(BASE + "/**", async (route) => {
      const url = new URL(route.request().url());
      const rel = url.pathname.replace(new URL(BASE).pathname, "").replace(/^\//, "") || "index.html";
      if (rel.startsWith("api/")) return route.continue();
      const file = path.join(UI_DIR, rel === "" ? "index.html" : rel);
      if (!existsSync(file)) return route.continue();
      const response = await route.fetch();
      return route.fulfill({ response, body: readFileSync(file) });
    });
  }
  return page;
}

async function ready(page) {
  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.__t2lReady === true, null, { timeout: 15000 });
}

async function view(page, v) {
  const t0 = Date.now();
  await page.click('#views button[data-view="' + v + '"]');
  await page.waitForSelector("#view-" + v + ":not(.hidden)");
  await sleep(350);
  return Date.now() - t0;
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
  const t0 = Date.now();

  // ================================================================ PERSONA 1 — dev, primeiro contato
  console.log("\nPERSONA 1 · Dev em primeiro contato (o que é isto? o que meu sistema tem?)");
  let page = await newPage(browser, { width: 1440, height: 900 });
  const tLoad = Date.now();
  await ready(page);
  const loadMs = Date.now() - tLoad;
  check("dev-1º contato", "UI pronta em < 3 s", loadMs < 3000, loadMs + " ms");
  await page.click('#views button[data-view="anatomy"]');
  await page.waitForSelector("#anatomy-vp .a-node", { timeout: 8000 }).catch(() => {});
  const comps = await page.$$eval("#anatomy-vp .a-node", (n) => n.length);
  check("dev-1º contato", "Anatomia mostra os órgãos do ecossistema", comps >= 4, comps + " componente(s)");
  const zones = await page.$$eval("#anatomy-vp .zone-label", (n) => n.map((x) => x.textContent));
  check("dev-1º contato", "Zonas legíveis (núcleo/fronteira)", zones.length >= 2, zones.join(", "));
  const legend = await page.textContent("#anatomy-legend");
  check("dev-1º contato", "Legenda declara a fronteira externa (pronta para expansão)", /externa/i.test(legend), legend.trim().slice(0, 140));
  await snap(page, "anatomia-ecossistema");
  // examinar um componente (fila)
  const queue = await page.$('#anatomy-vp .a-node[data-id^="sqs:"]');
  if (queue) {
    await queue.click();
    await sleep(300);
    const side = await page.textContent(".anatomy-side");
    check("dev-1º contato", "Laudo do componente mostra conexões e espera da fila", /CONEXÕES/.test(side) && /fila/i.test(side), side.replace(/\s+/g, " ").slice(0, 160));
    await snap(page, "anatomia-laudo-fila");
  } else {
    check("dev-1º contato", "Fila SQS aparece na anatomia", false, "nenhum componente sqs:");
  }
  const engine = await page.textContent("#engine-chip");
  check("dev-1º contato", "Status do motor de decisão visível", /motor/.test(engine), engine);

  // ================================================================ PERSONA 2 — dev investigando o fluxo assíncrono
  console.log("\nPERSONA 2 · Dev: por que o pedido demorou? (fluxo API→DynamoDB→SQS→Lambda)");
  const list = await api(page, "/executions?limit=50");
  const merged = list.filter((e) => e.nodeCount >= 5).sort((a, b) => b.duration - a.duration)[0];
  check("dev-async", "Consumidor real (event source mapping) fundido na árvore do produtor", !!merged, merged ? merged.executionId + " · " + merged.nodeCount + " nós · " + merged.duration + " ms" : "nenhuma execução com consumidor");
  if (merged) {
    await page.goto(BASE + "/?execution=" + merged.executionId + "&view=tree", { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => window.__t2lReady === true);
    await page.waitForSelector("#tree-vp .t-node", { timeout: 8000 });
    const nodes = await page.$$eval("#tree-vp .t-node", (n) => n.length);
    check("dev-async", "Árvore desenha os 5 passos (deep link ?execution=)", nodes === merged.nodeCount, nodes + " nós");
    const wait = await page.$$eval("#tree-vp .t-wait-txt", (n) => n.map((x) => x.textContent));
    check("dev-async", "Aresta assíncrona rotulada com a espera na fila", wait.length > 0, wait.join(" · "));
    const strip = await page.textContent("#exec-strip");
    check("dev-async", "Faixa da execução com status, duração e manchete", /duração/.test(strip), strip.replace(/\s+/g, " ").slice(0, 160));
    await snap(page, "arvore-fluxo-assincrono");
    // inspector do consumidor
    const consumer = await page.$$("#tree-vp .t-node");
    let opened = false;
    for (const g of consumer) {
      const label = await g.getAttribute("aria-label");
      if (/order-billing/.test(label || "")) { await g.click(); opened = true; break; }
    }
    await page.waitForSelector("#drawer.open", { timeout: 3000 }).catch(() => {});
    const dr = opened ? await page.textContent("#drawer") : "";
    check("dev-async", "Inspector do consumidor explica a espera na fila", /esperou|fila/.test(dr), dr.replace(/\s+/g, " ").slice(0, 160));
    await snap(page, "inspector-consumidor");
    // aba Dados do UpdateItem do consumidor — navegação por TECLADO (seta → desce ao filho)
    await page.focus("#tree-host");
    await page.keyboard.press("ArrowRight");
    await sleep(250);
    const selLabel = await page.$eval("#tree-vp .t-node.sel", (g) => g.getAttribute("aria-label")).catch(() => "");
    check("dev-async", "Teclado: → desce do consumidor ao passo filho", /DynamoDB/.test(selLabel), selLabel);
    const hidden = await page.evaluate(() => {
      const g = document.querySelector("#tree-vp .t-node.sel");
      const d = document.getElementById("drawer");
      if (!g || !d) return true;
      const r = g.getBoundingClientRect(), dr = d.getBoundingClientRect();
      return d.classList.contains("open") && r.right > dr.left;
    });
    check("dev-async", "Passo selecionado não fica escondido sob o inspector", !hidden, "");
    await page.click('#drawer .dr-tabs button:has-text("Dados")').catch(() => {});
    await sleep(250);
    const dados = await page.textContent("#drawer");
    check("dev-async", "Delta de dados antes → depois (BILLED)", /BILLED/.test(dados), dados.replace(/\s+/g, " ").slice(0, 160));
    await snap(page, "inspector-delta-dados");
    await page.keyboard.press("Escape");

    // linha do tempo + CloudWatch
    const tv = await view(page, "timeline");
    check("dev-async", "Troca de visão < 1 s", tv < 1000, tv + " ms");
    await page.waitForSelector(".tl-table table", { timeout: 6000 }).catch(() => {});
    const rows = await page.$$eval(".tl-table tbody tr", (r) => r.map((x) => x.textContent));
    check("dev-async", "Logs CloudWatch correlacionados (START/END/REPORT + app)", rows.some((t) => /START RequestId/.test(t)) && rows.some((t) => /BILLED/.test(t)), rows.length + " linha(s)");
    const firstStart = rows.findIndex((t) => /START RequestId/.test(t));
    const firstApp = rows.findIndex((t) => /recebido/.test(t));
    check("dev-async", "Narrativa em ordem: START antes do primeiro log da função", firstStart >= 0 && firstApp > firstStart, "START#" + firstStart + " app#" + firstApp);
    const chapters = await page.$$eval(".tl-chap", (c) => c.length);
    check("dev-async", "Capítulos didáticos na linha do tempo", chapters >= 3, chapters + " capítulo(s)");
    const hatch = await page.$$eval(".tl-wait", (c) => c.length);
    check("dev-async", "Espera em fila hachurada entre produtor e consumidor", hatch >= 1, hatch + " trecho(s)");
    await snap(page, "linha-do-tempo-cloudwatch");
    await page.click('.tl-player button[title^="Reproduzir"]');
    await sleep(2600);
    const cap = await page.textContent(".tl-caption .txt");
    check("dev-async", "Reprodução narra 'AGORA' o que acontece", cap.length > 20 && !/Aperte/.test(cap), cap.slice(0, 160));
    await snap(page, "linha-do-tempo-reproducao");
    await sleep(6000);
    const capEnd = await page.textContent(".tl-caption .txt");
    check("dev-async", "Ao final a narrativa chega ao desfecho", /Desfecho|BILLED|billing|cobran/i.test(capEnd), capEnd.slice(0, 160));
  }

  // ================================================================ PERSONA 3 — QA/PO homologando
  console.log("\nPERSONA 3 · QA/PO: a regra de negócio foi respeitada? posso homologar?");
  const failed = list.find((e) => e.status === "FAILED" && /order-processor/.test(e.rootLabel || ""));
  if (failed) {
    await page.goto(BASE + "/?execution=" + failed.executionId + "&view=investigate", { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => window.__t2lReady === true);
    await page.waitForSelector(".inv .headline", { timeout: 10000 }).catch(() => {});
    const head = await page.textContent(".inv .headline").catch(() => "");
    check("qa-po", "Manchete executiva em linguagem de negócio", head.length > 20, head);
    const verdicts = await page.$$eval(".rules .verdict", (v) => v.map((x) => x.textContent));
    check("qa-po", "Regras do glossário cruzadas com o fluxo (veredito por regra)", verdicts.length >= 1, verdicts.join(", "));
    const prov = await page.$$eval(".inv .prov .e", (v) => [...new Set(v.map((x) => x.textContent))]);
    check("qa-po", "Cada decisão mostra o motor que decidiu (fato/regra/Jev)", prov.length >= 1, prov.join(", "));
    const ck = await page.$$eval(".checklist li", (v) => v.length);
    check("qa-po", "Checklist de homologação auditável", ck >= 5, ck + " itens");
    await snap(page, "investigacao-executiva-tecnica");
    await page.click('#inv-toolbar .seg button:has-text("Executiva")');
    await sleep(200);
    await snap(page, "investigacao-so-executiva");
  } else {
    check("qa-po", "Há execução com recusa de negócio para homologar", false, "nenhuma FAILED de order-processor");
  }
  const dup = list.find((e) => /idempotent/.test(e.rootLabel || "") && e.status === "FAILED");
  if (dup) {
    await page.goto(BASE + "/?execution=" + dup.executionId + "&view=investigate", { waitUntil: "domcontentloaded" });
    await page.waitForFunction(() => window.__t2lReady === true);
    await page.waitForSelector(".inv .headline", { timeout: 10000 }).catch(() => {});
    const out = await page.textContent(".inv .exec .panel h3 .chip").catch(() => "");
    check("qa-po", "Reentrega bloqueada lida como 'recusa protegida' (não falha técnica)", /protegida/i.test(out), out);
    await snap(page, "investigacao-idempotencia-protegida");
  }

  // ================================================================ PERSONA 4 — tech lead triando insights
  console.log("\nPERSONA 4 · Tech lead: o que merece atenção agora?");
  await page.goto(BASE + "/?view=anatomy", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.__t2lReady === true);
  await page.click('.rail-tabs button[data-rail="ins"]');
  await sleep(300);
  const ins = await page.$$eval("#ins-list .ins-mini", (v) => v.map((x) => x.textContent));
  check("tech-lead", "Insights ranqueados no painel lateral", ins.length >= 1, ins.length + " · " + (ins[0] || "").slice(0, 100));
  await snap(page, "insights-ranqueados");
  if (ins.length) {
    await page.click("#ins-list .ins-mini");
    await page.waitForSelector(".insight.focus", { timeout: 6000 }).catch(() => {});
    const card = await page.textContent(".insight.focus").catch(() => "");
    check("tech-lead", "Fato · correlação · hipótese · recomendação separados", /FATO OBSERVADO/.test(card) && /RECOMENDAÇÕES/.test(card), card.replace(/\s+/g, " ").slice(0, 160));
    await page.click('.insight.focus .acts button:has-text("Ver evidências")').catch(() => {});
    await sleep(250);
    const ev = await page.$$eval(".insight.focus .ev tr", (r) => r.length).catch(() => 0);
    check("tech-lead", "Evidence first: evidências navegáveis", ev >= 1, ev + " evidência(s)");
    await page.click('.insight.focus .acts button:has-text("Explicar")').catch(() => {});
    await sleep(700);
    const exp = await page.textContent(".insight.focus .explain").catch(() => "");
    check("tech-lead", "Explicação local (sem LLM) disponível", exp.length > 40 && /template local/.test(exp), exp.replace(/\s+/g, " ").slice(0, 120));
    await snap(page, "insight-em-foco-evidencias");
    await page.click('.insight.focus .acts button:has-text("Ver trace")').catch(() => {});
    await page.waitForSelector("#view-tree:not(.hidden)", { timeout: 4000 }).catch(() => {});
    check("tech-lead", "[Ver trace] leva à árvore da execução", await page.isVisible("#view-tree"), "");
  }
  await view(page, "dashboard");
  check("tech-lead", "Inspector do passo não cobre o Painel", !(await page.isVisible("#drawer.open")), "");
  await page.waitForSelector("#dash-intel .kv", { timeout: 6000 }).catch(() => {});
  const intel = await page.textContent("#dash-intel").catch(() => "");
  check("tech-lead", "Painel de inteligência: motor, pipeline, baseline", /pipeline preditivo/.test(intel) && /baseline/.test(intel), intel.replace(/\s+/g, " ").slice(0, 160));
  const hist = await page.$$eval("#dash-history .story-step", (v) => v.length).catch(() => 0);
  check("tech-lead", "Histórico/baseline por fluxo com sparkline", hist >= 1, hist + " fluxo(s)");
  await snap(page, "painel-inteligencia-historico");

  // paleta
  await page.keyboard.press("Control+K");
  await page.fill(".pal input", "billing");
  await sleep(150);
  const hits = await page.$$eval(".pal li", (v) => v.map((x) => x.textContent));
  check("tech-lead", "Ctrl+K encontra componente/execução por nome", hits.some((t) => /billing/i.test(t)), hits.slice(0, 3).join(" | "));
  await snap(page, "paleta-de-comandos");
  await page.keyboard.press("Escape");

  // ================================================================ acessibilidade e robustez
  console.log("\nRobustez / acessibilidade");
  const unnamed = await page.$$eval("button", (bs) => bs.filter((b) => b.offsetParent !== null && !(b.textContent || "").trim() && !b.getAttribute("aria-label") && !b.getAttribute("title")).length);
  check("a11y", "Todo botão visível tem nome acessível", unnamed === 0, unnamed + " sem nome");
  const csp = await page.evaluate(() => window.__csp || []);
  cspViolations.push(...csp);
  check("segurança", "Zero violação de CSP (style/script inline)", csp.length === 0, csp.slice(0, 3).join(" | "));
  const headers = await page.evaluate(async (u) => {
    const r = await fetch(u);
    return { csp: r.headers.get("content-security-policy"), xfo: r.headers.get("x-frame-options") };
  }, BASE + "/");
  check("segurança", "CSP estrita servida (default-src 'self', sem unsafe-inline)", /default-src 'self'/.test(headers.csp || "") && !/unsafe-inline/.test(headers.csp || ""), headers.csp);
  const errorsBeforeProbe = consoleErrors.length;
  const csrf = await page.evaluate(async (u) => (await fetch(u, { method: "DELETE" })).status, BASE + "/api/executions");
  await sleep(200);
  consoleErrors.splice(errorsBeforeProbe); // o 403 da sonda CSRF é esperado — não é erro da UI
  check("segurança", "Mutação sem X-Trace2Local é recusada (CSRF)", csrf === 403, "DELETE sem cabeçalho → " + csrf);

  // mobile
  const mob = await newPage(browser, { width: 390, height: 844 });
  await ready(mob);
  await sleep(500);
  const overflow = await mob.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check("a11y", "Sem rolagem horizontal em 390 px", !overflow, "");
  const clash = await mob.evaluate(() => {
    const a = document.getElementById("btn-palette").getBoundingClientRect();
    return [...document.querySelectorAll("#topbar > *")].filter((e) => e.id !== "btn-palette" && e.offsetParent !== null)
      .some((e) => { const b = e.getBoundingClientRect(); return b.width > 0 && b.left < a.right - 1 && b.right > a.left + 1; });
  });
  check("a11y", "Topo sem sobreposição em 390 px", !clash, "");
  await snap(mob, "mobile-390");

  check("geral", "Nenhum erro de console", consoleErrors.length === 0, consoleErrors.slice(0, 3).join(" | "));
  await browser.close();

  const passed = checks.filter((c) => c.ok).length;
  const md = ["# Loop de usabilidade por persona — " + new Date().toISOString(), "",
    "Base: `" + BASE + "`" + (UI_DIR ? " · assets da árvore de trabalho" : "") + " · duração " + Math.round((Date.now() - t0) / 1000) + " s", "",
    "**" + passed + "/" + checks.length + " checks passaram**", "",
    "| persona | check | resultado | evidência |", "|---|---|---|---|",
    ...checks.map((c) => "| " + c.persona + " | " + c.name + " | " + (c.ok ? "✅" : "❌") + " | " + String(c.detail).replace(/\|/g, "\\|").replace(/\n/g, " ").slice(0, 180) + " |"),
    "", consoleErrors.length ? "## Erros de console\n\n" + consoleErrors.map((e) => "- " + e).join("\n") : "", ""].join("\n");
  writeFileSync(path.join(OUT, "report.md"), md);
  writeFileSync(path.join(OUT, "report.json"), JSON.stringify({ base: BASE, checks, consoleErrors, cspViolations }, null, 2));
  console.log("\n" + passed + "/" + checks.length + " checks · relatório em " + OUT);
  process.exit(passed === checks.length ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
