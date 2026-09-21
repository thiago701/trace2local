/**
 * Captura das telas do monitoramento de IDEMPOTÊNCIA no Station (compose):
 * a execução do duplicado (IdempotencyGuard OK + DynamoDB ERROR sem delta)
 * e o inspector do nó vermelho — com validação de consistência UI↔API.
 *
 * Uso: node capture-idempotency.mjs
 *   (requer o Station em :19877 com as execuções do IdempotencyDemoRun)
 * Saída: ../../docs/qa/screenshots/13..14-*.png
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
const BASE = "http://127.0.0.1:19877/trace2local";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars"],
});
const page = await browser.newPage();
await page.setViewport({ width: 1680, height: 1000, deviceScaleFactor: 2 });

await page.goto(BASE + "/", { waitUntil: "networkidle2", timeout: 45000 });
await page.waitForFunction(
  () => document.getElementById("conn-text")?.textContent === "CONNECTED",
  { timeout: 30000 },
);
await sleep(800);

// seleciona a execução do DUPLICADO: tem a guarda e um nó DynamoDB ERROR
console.log("procurando a execução do duplicado (DynamoDB ERROR)…");
const selectedId = await page.evaluate(async () => {
  const list = await (await fetch("/trace2local/api/executions?limit=50")).json();
  const findErrorNode = (nodes) => {
    for (const n of nodes) {
      if (n.status === "ERROR" && (n.kind === "DYNAMODB")) return n;
      const found = findErrorNode(n.children || []);
      if (found) return found;
    }
    return null;
  };
  const hasGuard = (nodes) => {
    for (const n of nodes) {
      if ((n.label || "").includes("IdempotencyGuard")) return true;
      if (hasGuard(n.children || [])) return true;
    }
    return false;
  };
  for (const summary of list) {
    if (summary.status !== "FAILED") continue;
    const full = await (await fetch("/trace2local/api/executions/" + summary.executionId)).json();
    if (hasGuard(full.roots || []) && findErrorNode(full.roots || [])) {
      const item = [...document.querySelectorAll(".exec-card")]
        .find((r) => r.textContent.includes(summary.executionId));
      if (item) {
        item.click();
        return summary.executionId;
      }
    }
  }
  return null;
});
if (!selectedId) {
  console.log("!! execução do duplicado não encontrada — rode o IdempotencyDemoRun");
  await browser.close();
  process.exit(2);
}

await page.waitForFunction(
  () => [...document.querySelectorAll(".tv-node .label")].some(
    (n) => n.textContent.includes("IdempotencyGuard")),
  { timeout: 15000 },
);
await sleep(1500);

// consistência UI↔API
const report = await page.evaluate(async (executionId) => {
  const api = await (await fetch("/trace2local/api/executions/" + executionId)).json();
  const apiLabels = [];
  const walk = (nodes) => {
    for (const n of nodes) {
      apiLabels.push(n.label);
      walk(n.children || []);
    }
  };
  walk(api.roots || []);
  const domLabels = [...document.querySelectorAll("g.tv-node .label")].map((l) => l.textContent);
  const norm = (a) => [...a].sort().join(" || ");
  return {
    executionId,
    equal: domLabels.length === apiLabels.length && norm(domLabels) === norm(apiLabels),
    dom: domLabels.sort(),
    api: apiLabels.sort(),
  };
}, selectedId);
console.log(`  consistência ${selectedId}: UI=${report.dom.length} nós, API=${report.api.length} nós → `
  + (report.equal ? "OK (labels idênticos)" : "DIVERGENTE"));
if (!report.equal) {
  console.log("    UI :", JSON.stringify(report.dom));
  console.log("    API:", JSON.stringify(report.api));
}

await page.screenshot({ path: out("13-idempotency-duplicate-tree.png") });
console.log("13-idempotency-duplicate-tree.png");

// inspector do nó vermelho (DynamoDB ERROR) — com o erro da condição
let clickedError = false;
for (let attempt = 0; attempt < 10 && !clickedError; attempt++) {
  clickedError = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll("g.tv-node")];
    const node = nodes.find((g) =>
      g.querySelector(".box")?.getAttribute("class")?.includes("error")
      && (g.querySelector(".label")?.textContent || "").includes("DynamoDB"));
    if (node) {
      node.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      return true;
    }
    return false;
  });
  if (!clickedError) await sleep(600);
}
await page.waitForFunction(
  () => document.body.textContent.includes("conditional request failed"),
  { timeout: 8000 },
).then(() => true).catch(() => false);
await sleep(900);
if (clickedError) {
  await page.screenshot({ path: out("14-idempotency-inspector-guard.png") });
  console.log("14-idempotency-inspector-guard.png");
} else {
  console.log("!! nó DynamoDB ERROR não encontrado");
}

await browser.close();
if (!report.equal) {
  console.log("FALHA DE CONSISTÊNCIA — ver relatório acima.");
  process.exit(2);
}
console.log("pronto → docs/qa/screenshots/ (idempotência validada)");
