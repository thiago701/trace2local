/**
 * Captura das telas da UI do TraceVanta STATION (modo Companion) para o cenário
 * lambda-sqs + VALIDAÇÃO DE CONSISTÊNCIA: o que a UI desenha no canvas é
 * comparado nó a nó com a API REST do Station (labels e contagem) — o script
 * falha (exit 2) se divergirem.
 *
 * Uso: node capture-lambda-station.mjs
 *   (requer o Station em :19877 com as execuções do compose + LambdaSqsDemoRun)
 * Saída: ../../docs/qa/screenshots/07..12-*.png + relatório no console
 */
import puppeteer from "puppeteer-core";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const OUT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..", "..", "docs", "qa", "screenshots",
);
mkdirSync(OUT, { recursive: true });
const out = (name) => path.join(OUT, name);

const EDGE = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe";
const BASE = "http://127.0.0.1:19877/tracevanta";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function clickNodeByLabel(page, labelPart, statusClass) {
  for (let attempt = 0; attempt < 10; attempt++) {
    const clicked = await page.evaluate(({ labelPart, statusClass }) => {
      const nodes = [...document.querySelectorAll("g.tv-node")];
      const node = nodes.find((g) => {
        const label = g.querySelector(".label")?.textContent || "";
        const box = g.querySelector(".box")?.getAttribute("class") || "";
        return label.includes(labelPart) && (!statusClass || box.includes(statusClass));
      });
      if (node) {
        node.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        return true;
      }
      return false;
    }, { labelPart, statusClass });
    if (clicked) return true;
    await sleep(600);
  }
  return false;
}

// --- validação de consistência: UI (canvas) x API (Station)
async function consistencyReport(page, executionId) {
  return page.evaluate(async (executionId) => {
    const api = await (await fetch("/tracevanta/api/executions/" + executionId)).json();
    const apiLabels = [];
    const walk = (nodes) => {
      for (const n of nodes) {
        apiLabels.push(n.label);
        walk(n.children || []);
      }
    };
    walk(api.roots || []);
    const domLabels = [...document.querySelectorAll("g.tv-node .label")]
      .map((l) => l.textContent);
    const norm = (a) => [...a].sort().join(" || ");
    const equal =
      domLabels.length === apiLabels.length
      && norm(domLabels) === norm(apiLabels);
    return {
      executionId,
      equal,
      dom: domLabels.sort(),
      api: apiLabels.sort(),
      nodeCount: { dom: domLabels.length, api: apiLabels.length },
    };
  }, executionId);
}

function printReport(report) {
  console.log(
    `  consistência ${report.executionId}: ` +
    `UI=${report.nodeCount.dom} nós, API=${report.nodeCount.api} nós → ` +
    (report.equal ? "OK (labels idênticos)" : "DIVERGENTE"),
  );
  if (!report.equal) {
    console.log("    UI :", JSON.stringify(report.dom));
    console.log("    API:", JSON.stringify(report.api));
  }
  return report.equal;
}

async function selectRecentFailed(page) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const selected = await page.evaluate(() => {
      const items = [...document.querySelectorAll(".exec-card")];
      const failed = items.filter((r) => r.querySelector(".st")?.classList.contains("FAILED"));
      // prefere a jornada de erro da função order-processor (há FAILEDs de outros cenários)
      const item = failed.find((r) => r.textContent.includes("order-processor")) || failed[0];
      if (!item) return false;
      item.click();
      return true;
    });
    if (selected) return true;
    await sleep(500);
  }
  // fallback pela API (a lista de cards pode re-renderizar durante eventos SSE)
  return page.evaluate(async () => {
    const list = await (await fetch("/tracevanta/api/executions?limit=50")).json();
    const failed = list.filter((s) => s.status === "FAILED");
    const s = failed.find((x) => (x.rootLabel || "").includes("order-processor")) || failed[0];
    if (!s) return false;
    const item = [...document.querySelectorAll(".exec-card")]
      .find((r) => r.textContent.includes(s.executionId));
    if (!item) return false;
    item.click();
    return true;
  });
}

async function selectExecutionWithNode(page, label, excludeLabel) {
  return page.evaluate(async ({ label, excludeLabel }) => {
    const list = await (await fetch("/tracevanta/api/executions?limit=50")).json();
    const search = (nodes) => {
      for (const n of nodes) {
        if ((n.label || "").includes(label)) return true;
        if (search(n.children || [])) return true;
      }
      return false;
    };
    const excluded = (nodes) => {
      for (const n of nodes) {
        if ((n.label || "").includes(excludeLabel)) return true;
        if (excluded(n.children || [])) return true;
      }
      return false;
    };
    for (const summary of list) {
      if (summary.status !== "COMPLETED") continue;
      const full = await (await fetch("/tracevanta/api/executions/" + summary.executionId)).json();
      if (search(full.roots || []) && (!excludeLabel || !excluded(full.roots || []))) {
        const item = [...document.querySelectorAll(".exec-card")]
          .find((r) => r.textContent.includes(summary.executionId));
        if (item) {
          item.click();
          return summary.executionId;
        }
      }
    }
    return null;
  }, { label, excludeLabel });
}

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars"],
});
const page = await browser.newPage();
await page.setViewport({ width: 1680, height: 1000, deviceScaleFactor: 2 });

console.log("abrindo a UI do Station…");
await page.goto(BASE + "/", { waitUntil: "networkidle2", timeout: 45000 });
await page.waitForFunction(
  () => document.getElementById("conn-text")?.textContent === "CONNECTED",
  { timeout: 30000 },
);
await sleep(800);
await page.screenshot({ path: out("07-lambda-station-overview.png") });
console.log("07-lambda-station-overview.png");

let allConsistent = true;

// ---- J1: árvore feliz (LAMBDA → DynamoDB + SQS), sem o ramo do consumidor
console.log("selecionando a jornada feliz…");
const happyId = await selectExecutionWithNode(page, "order-processor", "order-billing");
if (!happyId) {
  console.log("!! execução da jornada feliz não encontrada");
  await browser.close();
  process.exit(2);
}
await page.waitForFunction(
  () => [...document.querySelectorAll(".tv-node .label")].some(
    (n) => n.textContent.includes("order-processor")),
  { timeout: 15000 },
);
await sleep(1500);
const happyReport = await consistencyReport(page, happyId);
allConsistent = printReport(happyReport) && allConsistent;
await page.screenshot({ path: out("08-lambda-tree-dynamo-sqs.png") });
console.log("08-lambda-tree-dynamo-sqs.png");

const clickedDelta = await clickNodeByLabel(page, "DynamoDB: orders", null);
await page.waitForFunction(
  () => document.body.textContent.includes("EXACT"),
  { timeout: 8000 },
).catch(() => false);
await sleep(900);
if (clickedDelta) {
  await page.screenshot({ path: out("09-lambda-inspector-delta.png") });
  console.log("09-lambda-inspector-delta.png");
} else {
  console.log("!! nó DynamoDB não encontrado");
}

// ---- J2: execução vermelha (raiz FAILED com sucesso parcial)
console.log("selecionando a jornada de erro…");
const failFound = await selectRecentFailed(page);
if (!failFound) {
  console.log("!! execução FAILED não encontrada na lista de recentes");
  await browser.close();
  process.exit(2);
}
const failId = await page.evaluate(() => window.__tvState?.selectedExecutionId || null);
await page.waitForFunction(
  () => [...document.querySelectorAll(".tv-node .label")].some(
    (n) => n.textContent.includes("order-processor")),
  { timeout: 15000 },
);
await sleep(1500);
if (failId) {
  const failReport = await consistencyReport(page, failId);
  allConsistent = printReport(failReport) && allConsistent;
}
await page.screenshot({ path: out("10-lambda-tree-failure.png") });
console.log("10-lambda-tree-failure.png");

const clickedError = await clickNodeByLabel(page, "order-processor", "error");
await page.waitForFunction(
  () => document.body.textContent.includes("limite de crédito"),
  { timeout: 8000 },
).catch(() => false);
await sleep(900);
if (clickedError) {
  await page.screenshot({ path: out("11-lambda-inspector-error.png") });
  console.log("11-lambda-inspector-error.png");
} else {
  console.log("!! nó vermelho da raiz não encontrado");
}

// ---- J3: árvore fundida do consumidor (SQS → LAMBDA order-billing)
console.log("selecionando a árvore do consumidor…");
const mergedId = await selectExecutionWithNode(page, "order-billing", null);
if (!mergedId) {
  console.log("!! execução com o consumidor (order-billing) não encontrada");
  await browser.close();
  process.exit(2);
}
await page.waitForFunction(
  () => [...document.querySelectorAll(".tv-node .label")].some(
    (n) => n.textContent.includes("order-billing")),
  { timeout: 15000 },
);
await sleep(1500);
const mergedReport = await consistencyReport(page, mergedId);
allConsistent = printReport(mergedReport) && allConsistent;
await page.screenshot({ path: out("12-lambda-tree-consumer.png") });
console.log("12-lambda-tree-consumer.png");

// ---- v3: dashboard e comparação (telas)
await page.evaluate(() => document.getElementById("tab-dashboard").click());
await sleep(900);
await page.screenshot({ path: out("15-dashboard.png") });
console.log("15-dashboard.png");
await page.evaluate(() => document.getElementById("tab-compare").click());
await sleep(1400);
await page.screenshot({ path: out("16-compare.png") });
console.log("16-compare.png");
await page.evaluate(() => document.getElementById("tab-canvas").click());
await sleep(300);

// ---- v4: storytelling (telas)
await page.evaluate(() => document.getElementById("tab-story").click());
await sleep(1000);
await page.screenshot({ path: out("17-story.png") });
console.log("17-story.png");
await page.evaluate(() => document.getElementById("tab-canvas").click());
await sleep(400);
await page.evaluate(() => document.getElementById("btn-notes").click());
await sleep(1000);
await page.screenshot({ path: out("18-canvas-notes.png") });
console.log("18-canvas-notes.png");
await page.evaluate(() => document.getElementById("btn-notes").click());
await sleep(300);

// ---- checagens de UX v2 (evidência funcional, além das telas)
console.log("checagens de UX v2…");
const ux = await page.evaluate(() => {
  const results = {};
  results.waterfallBars = document.querySelectorAll("g.tv-node .timebar-total").length;
  results.summaryVisible = !document.getElementById("exec-summary").classList.contains("hidden");
  results.summaryText = document.getElementById("exec-summary").textContent.replace(/\s+/g, " ").trim();
  results.richCards = document.querySelectorAll(".exec-card .root-label").length;
  results.triggerChips = document.querySelectorAll(".exec-card .chip").length;
  results.statusBadges = document.querySelectorAll(".exec-card .status-badge").length;
  // busca: filtra a lista de execuções
  const filter = document.getElementById("recent-filter");
  filter.value = "zzz-nao-existe";
  filter.dispatchEvent(new Event("input", { bubbles: true }));
  results.filterEmptyShown = document.querySelector("#recent .empty-note") !== null;
  filter.value = "";
  filter.dispatchEvent(new Event("input", { bubbles: true }));
  // legenda
  document.getElementById("btn-legend").click();
  results.legendItems = document.querySelectorAll("#legend-pop .lg-kind").length;
  document.getElementById("btn-legend").click();
  // inspector colapsável: seleciona o nó raiz e verifica seções + contagem de atributos
  const root = document.querySelector("g.tv-node");
  if (root) root.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  const secs = document.querySelectorAll("#inspector-body .insp-section").length;
  const collapsible = document.querySelectorAll("#inspector-body h3.clickable").length;
  const copyBtns = document.querySelectorAll("#inspector-body .copy-btn").length;
  results.inspectorSections = secs;
  results.inspectorCollapsibleHeaders = collapsible;
  results.inspectorCopyButtons = copyBtns;
  return results;
});
console.log("  " + JSON.stringify(ux, null, 2).replace(/\n/g, "\n  "));
const uxOk =
  ux.waterfallBars > 0
  && ux.summaryVisible
  && ux.richCards > 0
  && ux.triggerChips > 0
  && ux.statusBadges > 0
  && ux.filterEmptyShown
  && ux.legendItems >= 5
  && ux.inspectorSections >= 2
  && ux.inspectorCollapsibleHeaders >= 1;
console.log(uxOk ? "  UX v2: todas as checagens passaram ✓" : "  UX v2: FALHA em uma ou mais checagens ✗");

// ---- checagens de v3 (dashboard, comparar, deep link)
console.log("checagens de v3 (dashboard/comparar/deep-link)…");
const v3 = await page.evaluate(async () => {
  const results = {};
  document.getElementById("tab-dashboard").click();
  await new Promise((r) => setTimeout(r, 400));
  results.statCards = document.querySelectorAll("#dashboard-view .stat-card").length;
  results.slowRows = document.querySelectorAll("#dashboard-view .dash-row").length;
  results.dashboardTotal = document.querySelector("#dashboard-view .stat-card .value")?.textContent || "";

  document.getElementById("tab-compare").click();
  await new Promise((r) => setTimeout(r, 800));
  results.compareRows = document.querySelectorAll("#compare-view .diff-table tbody tr").length;
  results.compareSummaryItems = document.querySelectorAll("#compare-view .compare-summary .cs-item").length;

  const firstId = window.__tvState?.selectedExecutionId;
  history.replaceState(null, "", "?execution=" + encodeURIComponent(firstId));
  results.deepLinkInUrl = location.search.includes("execution=");

  document.getElementById("tab-canvas").click();
  await new Promise((r) => setTimeout(r, 200));
  results.canvasVisible = !document.getElementById("canvas").classList.contains("hidden");
  return results;
});
console.log("  " + JSON.stringify(v3, null, 2).replace(/\n/g, "\n  "));
const v3Ok =
  v3.statCards >= 5
  && v3.slowRows >= 1
  && v3.compareRows > 0
  && v3.compareSummaryItems >= 3
  && v3.deepLinkInUrl
  && v3.canvasVisible;
console.log(v3Ok ? "  v3: todas as checagens passaram ✓" : "  v3: FALHA em uma ou mais checagens ✗");

await page.evaluate(() => document.getElementById("tab-canvas").click());
await sleep(300);

// ---- v4: storytelling (STORY tab + notas no canvas)
console.log("checagens de v4 (storytelling)…");
const v4 = await page.evaluate(async () => {
  const results = {};
  document.getElementById("tab-story").click();
  await new Promise((r) => setTimeout(r, 900));
  results.storySteps = document.querySelectorAll("#story-view .story-step").length;
  results.storyIntro = document.querySelector("#story-view .story-header .intro")?.textContent || "";
  results.storyConclusion = document.querySelector("#story-view .story-conclusion")?.textContent || "";
  results.copyBtn = document.querySelector("#story-view #btn-copy-story") !== null;
  document.getElementById("tab-canvas").click();
  await new Promise((r) => setTimeout(r, 300));
  document.getElementById("btn-notes").click();
  await new Promise((r) => setTimeout(r, 900));
  results.noteLines = document.querySelectorAll("g.tv-node .note-line").length;
  document.getElementById("btn-notes").click();
  await new Promise((r) => setTimeout(r, 300));
  results.notesOff = document.querySelectorAll("g.tv-node .note-line").length === 0;
  return results;
});
console.log("  " + JSON.stringify(v4, null, 2).replace(/\n/g, "\n  "));
const v4Ok =
  v4.storySteps >= 3
  && v4.storyIntro.length > 0
  && v4.storyConclusion.length > 0
  && v4.copyBtn
  && v4.noteLines >= 3
  && v4.notesOff;
console.log(v4Ok ? "  v4: storytelling validado ✓" : "  v4: FALHA em uma ou mais checagens ✗");

await browser.close();
if (!allConsistent) {
  console.log("FALHA DE CONSISTÊNCIA: UI diverge da API — ver relatório acima.");
  process.exit(2);
}
if (!uxOk) {
  console.log("FALHA DE UX v2 — ver checagens acima.");
  process.exit(3);
}
if (!v3Ok) {
  console.log("FALHA DE v3 (dashboard/comparar/deep-link) — ver checagens acima.");
  process.exit(4);
}
if (!v4Ok) {
  console.log("FALHA DE v4 (storytelling) — ver checagens acima.");
  process.exit(5);
}
console.log("pronto → docs/qa/screenshots/ (consistência UI↔API + UX v2/v3/v4 validados)");
