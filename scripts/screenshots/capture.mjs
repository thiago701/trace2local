/**
 * Captura as telas da UI do TraceVanta rodando no projeto local (order-service
 * + LocalStack): catálogo, árvore JC-1 com consumidor SQS, inspector com delta,
 * execução vermelha da JC-2 e inspector do erro.
 *
 * Uso: node capture.mjs   (requer a app em :9876 e o LocalStack em :4566)
 * Saída: ../../docs/qa/screenshots/*.png
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
const BASE = "http://127.0.0.1:9876/tracevanta";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function findOrderId(page) {
  return page.evaluate(async () => {
    const list = await (await fetch("/tracevanta/api/executions?limit=20")).json();
    for (const summary of list) {
      const full = await (await fetch("/tracevanta/api/executions/" + summary.executionId)).json();
      const search = (nodes) => {
        for (const n of nodes) {
          const key = n.mutation && n.mutation.key;
          if (key && key.startsWith("ORDER#")) return key;
          const found = search(n.children || []);
          if (found) return found;
        }
        return null;
      };
      const key = search(full.roots || []);
      if (key) return key;
    }
    return null;
  });
}

async function clickNodeByLabel(page, labelPart, boxClass) {
  // re-render do SVG pode acontecer no meio da consulta — tenta algumas vezes
  for (let attempt = 0; attempt < 8; attempt++) {
    const clicked = await page.evaluate(({ labelPart, boxClass }) => {
      const nodes = [...document.querySelectorAll("g.tv-node")];
      const node = nodes.find((g) => {
        const label = g.querySelector(".label")?.textContent || "";
        const box = g.querySelector(".box")?.getAttribute("class") || "";
        return label.includes(labelPart) && (!boxClass || box.includes(boxClass));
      });
      if (node) {
        node.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        return true;
      }
      return false;
    }, { labelPart, boxClass });
    if (clicked) return true;
    await sleep(600);
  }
  return false;
}

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars"],
});
const page = await browser.newPage();
await page.setViewport({ width: 1680, height: 1000, deviceScaleFactor: 2 });

console.log("abrindo a UI…");
await page.goto(BASE + "/", { waitUntil: "networkidle2", timeout: 45000 });
await page.waitForSelector(".endpoint", { timeout: 30000 });
await page.waitForFunction(
  () => document.getElementById("conn-text").textContent === "CONNECTED",
  { timeout: 30000 },
);
await sleep(800);
await page.screenshot({ path: out("01-ui-overview.png") });
console.log("01-ui-overview.png");

// ---- JC-1: abrir POST /orders (primeiro item do catálogo) e disparar pela UI
await page.evaluate(() => {
  const head = [...document.querySelectorAll(".endpoint .ep-head")].find((e) => {
    const text = e.textContent.replace(/\s+/g, "");
    return text.includes("POST/orders") && !text.includes("{");
  });
  if (head) head.dispatchEvent(new MouseEvent("click", { bubbles: true }));
});
await sleep(400);
await page.waitForSelector(".endpoint.active .primary", { timeout: 5000 });
await page.evaluate(async () => {
  // limpa o acervo e define um payload válido (o sample tem total=0 → 400)
  await fetch("/tracevanta/api/executions", { method: "DELETE" });
  const ta = document.querySelector(".endpoint.active .ep-body textarea");
  ta.value = JSON.stringify({ customerId: "UI-SHOT", total: 250 });
});
await page.evaluate(() => {
  const btn = document.querySelector(".endpoint.active .primary");
  btn.dispatchEvent(new MouseEvent("click", { bubbles: true }));
});
console.log("disparo enviado; aguardando a árvore com o consumidor…");
await page.waitForFunction(
  () => [...document.querySelectorAll(".tv-node .label")].some(
    (n) => n.textContent.includes("billing-queue")),
  { timeout: 45000 },
);
await sleep(1200);
await page.screenshot({ path: out("02-tree-jc1-consumer.png") });
console.log("02-tree-jc1-consumer.png");

// ---- inspector do delta (nó DynamoDB do PutItem)
const clickedDelta = await clickNodeByLabel(page, "DynamoDB: orders", null);
await sleep(700);
if (clickedDelta) {
  await page.screenshot({ path: out("03-inspector-delta.png") });
  console.log("03-inspector-delta.png");
} else {
  console.log("!! nó DynamoDB não encontrado");
}

// ---- inspector do ramo do consumidor (SQS)
await clickNodeByLabel(page, "billing-queue", null);
await sleep(700);
await page.screenshot({ path: out("04-inspector-sqs-consumer.png") });
console.log("04-inspector-sqs-consumer.png");

// ---- JC-2: confirm duas vezes (via API, com pathVariable) → árvore vermelha
const orderId = await findOrderId(page);
if (!orderId) {
  console.log("!! orderId não encontrado — abortando JC-2");
  process.exit(2);
}
console.log("confirmando " + orderId + " duas vezes…");
for (let i = 0; i < 2; i++) {
  await page.evaluate(async (id) => {
    await fetch("/tracevanta/api/execute", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        endpointId: "post:/orders/{id}/confirm",
        pathVariables: { id },
      }),
    });
  }, orderId);
  await sleep(2500);
}
await page.waitForFunction(
  () => [...document.querySelectorAll(".recent-item")].some(
    (r) => r.querySelector(".st")?.classList.contains("FAILED")),
  { timeout: 60000 },
);
await page.evaluate(() => {
  const item = [...document.querySelectorAll(".recent-item")].find(
    (r) => r.querySelector(".st")?.classList.contains("FAILED"),
  );
  item.click();
});
await sleep(1200);
await page.screenshot({ path: out("05-tree-jc2-conflict.png") });
console.log("05-tree-jc2-conflict.png");

const clickedError = await clickNodeByLabel(page, "DynamoDB: orders", "error");
await sleep(700);
if (clickedError) {
  await page.screenshot({ path: out("06-inspector-conflict-error.png") });
  console.log("06-inspector-conflict-error.png");
} else {
  console.log("!! nó DynamoDB com erro não encontrado");
}

await browser.close();
console.log("pronto → docs/qa/screenshots/");
