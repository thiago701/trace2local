/**
 * Captura e ANÁLISE do storytelling no novo domínio de PAGAMENTOS (Pix):
 * valida consistência UI↔API, simplicidade (zero config, poucos cliques) e
 * clareza da narrativa (passos em linguagem de negócio, glossário aplicado).
 *
 * Uso: node capture-payment-story.mjs
 *   (requer o payment-service rodando: app :8080, Trace2Local :9876)
 * Saída: ../../docs/qa/screenshots/19..21-*.png + relatório JSON no console
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
const BASE = "http://127.0.0.1:9876/trace2local";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars"],
});
const page = await browser.newPage();
await page.setViewport({ width: 1680, height: 1000, deviceScaleFactor: 2 });
const jsErrors = [];
page.on("pageerror", (e) => jsErrors.push(e.message));

await page.goto(BASE + "/", { waitUntil: "networkidle2", timeout: 45000 });
await page.waitForFunction(
  () => document.getElementById("conn-text")?.textContent === "CONNECTED",
  { timeout: 30000 },
);
await sleep(1000);

// ---- SIMPLICIDADE: endpoints descobertos e fluxo de 2 cliques
const simplicity = await page.evaluate(async () => {
  const endpoints = await (await fetch("/trace2local/api/endpoints")).json();
  const meta = await (await fetch("/trace2local/api/meta")).json();
  return {
    endpoints: endpoints.map((e) => e.endpointId),
    metaPort: meta.port,
    tabs: [...document.querySelectorAll("#tabs .tab")].map((t) => t.textContent),
  };
});
console.log("SIMPLICIDADE:", JSON.stringify(simplicity));

// seleciona a execução do DUPLICADO (DynamoDB ERROR sem delta + guarda)
const dupId = await page.evaluate(async () => {
  const list = await (await fetch("/trace2local/api/executions?limit=50")).json();
  const hasErrorDynamo = (nodes) => {
    for (const n of nodes) {
      if (n.kind === "DYNAMODB" && n.status === "ERROR") return n;
      const found = hasErrorDynamo(n.children || []);
      if (found) return found;
    }
    return null;
  };
  for (const s of list) {
    if (s.status !== "FAILED") continue;
    const full = await (await fetch("/trace2local/api/executions/" + s.executionId)).json();
    const errNode = hasErrorDynamo(full.roots || []);
    const hasProcess = JSON.stringify(full).includes("ProcessarPagamento");
    if (errNode && hasProcess && !errNode.mutation) {
      const item = [...document.querySelectorAll(".exec-card")]
        .find((r) => r.textContent.includes(s.executionId));
      if (item) {
        item.click();
        return s.executionId;
      }
    }
  }
  return null;
});
if (!dupId) {
  console.log("!! execução do duplicado não encontrada");
  await browser.close();
  process.exit(2);
}
await page.waitForFunction(
  () => document.querySelectorAll("g.tv-node .label").length >= 3,
  { timeout: 15000 },
);
await sleep(1400);

// ---- CONSISTÊNCIA: canvas × API
const consistency = await page.evaluate(async (executionId) => {
  const api = await (await fetch("/trace2local/api/executions/" + executionId)).json();
  const apiLabels = [];
  const walk = (nodes) => {
    for (const n of nodes) {
      apiLabels.push({ label: n.label, kind: n.kind, status: n.status, hasMutation: !!n.mutation });
      walk(n.children || []);
    }
  };
  walk(api.roots || []);
  const domLabels = [...document.querySelectorAll("g.tv-node .label")].map((l) => l.textContent);
  const norm = (a) => [...a].sort().join(" || ");
  return {
    executionId,
    labelsEqual: domLabels.length === apiLabels.length && norm(domLabels) === norm(apiLabels.map((x) => x.label)),
    domCount: domLabels.length,
    apiNodes: apiLabels,
  };
}, dupId);
console.log("CONSISTÊNCIA:", JSON.stringify({
  executionId: consistency.executionId,
  labelsEqual: consistency.labelsEqual,
  dom: consistency.domCount,
  api: consistency.apiNodes.length,
}));
await page.screenshot({ path: out("19-payment-tree.png") });
console.log("19-payment-tree.png");

// ---- CLAREZA: narrativa (STORY tab)
await page.evaluate(() => document.getElementById("tab-story").click());
await sleep(1200);
const story = await page.evaluate(async () => {
  const title = document.querySelector("#story-view .story-header .title")?.textContent || "";
  const intro = document.querySelector("#story-view .story-header .intro")?.textContent || "";
  const conclusion = document.querySelector("#story-view .story-conclusion")?.textContent || "";
  const steps = [...document.querySelectorAll("#story-view .story-step")].map((s) => ({
    text: s.querySelector(".text")?.textContent || "",
    meta: s.querySelector(".meta")?.textContent.replace(/\s+/g, " ").trim() || "",
  }));
  const copyBtn = document.querySelector("#story-view #btn-copy-story") !== null;
  return { title, intro, conclusion, steps, copyBtn };
});
const clarity = {
  title: story.title,
  intro: story.intro,
  conclusion: story.conclusion,
  stepsCount: story.steps.length,
  copyBtn: story.copyBtn,
  // heurísticas de clareza: todo passo com texto de tamanho razoável e sem ruído técnico cru
  allStepsReadable: story.steps.every((s) => s.text.length >= 20 && s.text.length <= 220),
  hasBusinessVerb: story.steps.some((s) => s.text.includes("Processa o Pix") || s.text.includes("Avisa o pagador")),
  hasConclusionStatus: /sucesso|erro|parcial/i.test(story.conclusion),
  steps: story.steps,
};
console.log("CLAREZA:", JSON.stringify({
  title: clarity.title,
  intro: clarity.intro,
  conclusion: clarity.conclusion,
  stepsCount: clarity.stepsCount,
  copyBtn: clarity.copyBtn,
  allStepsReadable: clarity.allStepsReadable,
  hasBusinessVerb: clarity.hasBusinessVerb,
  hasConclusionStatus: clarity.hasConclusionStatus,
  stepTexts: clarity.steps.map((s) => s.text),
}));
await page.screenshot({ path: out("20-payment-story.png") });
console.log("20-payment-story.png");

// ---- NOTAS ao lado dos nós (canvas)
await page.evaluate(() => document.getElementById("tab-canvas").click());
await sleep(500);
await page.evaluate(() => document.getElementById("btn-notes").click());
await sleep(1200);
const notes = await page.evaluate(() => ({
  noteLines: document.querySelectorAll("g.tv-node .note-line").length,
  sample: [...document.querySelectorAll("g.tv-node .note-line")].slice(0, 3).map((n) => n.textContent),
}));
console.log("NOTAS:", JSON.stringify(notes));
await page.screenshot({ path: out("21-payment-notes.png") });
console.log("21-payment-notes.png");

// ---- SIMULAÇÃO COGNITIVA (cognitive walkthrough + linking & brushing + métricas)
console.log("SIMULAÇÃO COGNITIVA (personas)…");
const walk = await page.evaluate(async () => {
  const results = {};
  const execId = window.__t2lState?.selectedExecutionId;
  const exec = await (await fetch("/trace2local/api/executions/" + execId)).json();
  const byId = {};
  const walkNodes = (nodes) => {
    for (const n of nodes) {
      byId[n.nodeId] = n.label;
      walkNodes(n.children || []);
    }
  };
  walkNodes(exec.roots || []);

  // PERSONA PO (não técnico): lê a história — sem ruído técnico nos passos
  document.getElementById("tab-story").click();
  await new Promise((r) => setTimeout(r, 700));
  const steps = [...document.querySelectorAll("#story-view .story-step")];
  results.poSteps = steps.length;
  results.poNoUuidNoise = steps.every((s) =>
    !/[0-9a-f]{8}-[0-9a-f]{4}-/i.test(s.querySelector(".text")?.textContent || ""));
  results.poClicks = 2; // 1 selecionar execução + 1 aba STORY

  // PERSONA DEV (linking & brushing): 1 clique no passo 2 leva ao nó no canvas
  const step2 = steps[1];
  const nodeId = step2.dataset.nodeId;
  const expectedLabel = byId[nodeId];
  step2.click();
  await new Promise((r) => setTimeout(r, 450));
  const selectedLabel = document.querySelector("g.tv-node.selected .label")?.textContent || "";
  results.devOneClickLinksNode = !document.getElementById("canvas").classList.contains("hidden")
    && selectedLabel === expectedLabel && !!expectedLabel;
  results.devPulseOnClick = document.querySelector("g.tv-node.selected .box")
    ?.getAttribute("class")?.includes("pulse") || false;
  results.devClicks = 1;

  // PERSONA QA: acha o passo com falha → inspector com o erro + tag ⚠
  document.getElementById("tab-story").click();
  await new Promise((r) => setTimeout(r, 700));
  const failedStep = [...document.querySelectorAll("#story-view .story-step")]
    .find((s) => s.textContent.includes("FALHOU"));
  results.qaFoundFailedStep = !!failedStep;
  results.qaErrorTag = failedStep ? failedStep.querySelector(".error-tag") !== null : false;
  if (failedStep) {
    failedStep.click();
    await new Promise((r) => setTimeout(r, 500));
    results.qaInspectorShowsError = document.body.textContent.includes("conditional request failed");
  }
  results.qaClicks = 2;
  return results;
});
console.log("  " + JSON.stringify(walk, null, 2).replace(/\n/g, "\n  "));

const passed =
  consistency.labelsEqual
  && simplicity.endpoints.length >= 3
  && clarity.stepsCount >= 4
  && clarity.allStepsReadable
  && clarity.hasBusinessVerb
  && clarity.hasConclusionStatus
  && notes.noteLines >= 3
  && jsErrors.length === 0
  && walk.poNoUuidNoise
  && walk.devOneClickLinksNode
  && walk.devPulseOnClick
  && walk.qaFoundFailedStep
  && walk.qaErrorTag
  && walk.qaInspectorShowsError;

console.log("RESULTADO:", passed ? "TODAS AS VALIDAÇÕES PASSARAM ✓" : "FALHAS DETECTADAS ✗");
if (jsErrors.length) {
  console.log("erros de JS:", JSON.stringify(jsErrors));
}
await browser.close();
process.exit(passed ? 0 : 3);
