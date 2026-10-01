/**
 * Infra comum dos loops de usabilidade (Playwright): checks com evidência, screenshots,
 * coleta de erros de console/CSP, auditoria de contraste WCAG e relatório Markdown/JSON.
 *
 *   T2L_BASE   URL da UI (padrão http://127.0.0.1:19877/trace2local)
 *   T2L_UI_DIR assets da ÁRVORE DE TRABALHO servidos com os cabeçalhos (CSP) reais do servidor
 *   T2L_OUT    pasta de saída (padrão ./out/<timestamp>)
 */
import { chromium } from "playwright";
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const BASE = (process.env.T2L_BASE || "http://127.0.0.1:19877/trace2local").replace(/\/$/, "");
const UI_DIR = process.env.T2L_UI_DIR ? path.resolve(HERE, process.env.T2L_UI_DIR) : null;
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
export const OUT = path.resolve(process.env.T2L_OUT || path.join(HERE, "out", STAMP));
mkdirSync(OUT, { recursive: true });

export const checks = [];
export const consoleErrors = [];
let shot = 0;
let expectedErrors = 0;

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function check(persona, name, ok, detail) {
  checks.push({ persona, name, ok: !!ok, detail: detail == null ? "" : String(detail) });
  console.log((ok ? "  ✓ " : "  ✗ ") + "[" + persona + "] " + name + (detail ? " — " + String(detail).slice(0, 200) : ""));
  return !!ok;
}

export async function snap(page, name) {
  const file = String(++shot).padStart(2, "0") + "-" + name + ".png";
  await page.screenshot({ path: path.join(OUT, file) });
  return file;
}

/** Sondas que provocam erro de propósito (403 do CSRF, 404 de recurso inexistente) não contam. */
export async function expectingErrors(fn) {
  const before = consoleErrors.length;
  const r = await fn();
  await sleep(250);
  expectedErrors += consoleErrors.length - before;
  consoleErrors.splice(before);
  return r;
}

export async function api(page, p, init) {
  return page.evaluate(async ([u, i]) => {
    const r = await fetch(u, i || undefined);
    const t = await r.text();
    try { return JSON.parse(t); } catch (e) { return { status: r.status, text: t }; }
  }, [BASE + "/api" + p, init || null]);
}

export async function launch() {
  return chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
}

export async function newPage(browser, viewport) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 1, colorScheme: "dark", reducedMotion: "no-preference",
    permissions: ["clipboard-read", "clipboard-write"] });
  const page = await ctx.newPage();
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push("pageerror: " + e.message));
  await page.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", (e) => {
      window.__csp = (window.__csp || []).concat([e.violatedDirective + " " + (e.blockedURI || "") + " " + (e.sourceFile || "") + ":" + (e.lineNumber || "")]);
    });
  });
  if (UI_DIR) {
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

export async function ready(page, query) {
  await page.goto(BASE + "/" + (query || ""), { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.__t2lReady === true, null, { timeout: 15000 });
}

export async function view(page, v) {
  const t0 = Date.now();
  await page.click('#views button[data-view="' + v + '"]');
  await page.waitForSelector("#view-" + v + ":not(.hidden)");
  return Date.now() - t0;
}

export async function lastToast(page) {
  return page.$$eval("#toasts .toast", (t) => (t.length ? t[t.length - 1].textContent : "")).catch(() => "");
}

/**
 * Auditoria de contraste WCAG 2.x sobre o texto VISÍVEL da página (ou de um seletor):
 * cor do texto × fundo efetivo (mistura alfa subindo pelos ancestrais). Texto grande
 * (≥ 24 px, ou ≥ 18,66 px em negrito) exige 3:1; o resto, 4,5:1. Ignora texto desabilitado
 * (WCAG 1.4.3 isenta componentes inativos) e decorativo (aria-hidden).
 */
export async function contrastAudit(page, scope) {
  return page.evaluate((sel) => {
    const root = sel ? document.querySelector(sel) : document.body;
    if (!root) return { checked: 0, failures: [] };
    const parse = (c) => {
      const m = c.match(/rgba?\(([^)]+)\)/);
      if (!m) return null;
      const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
      return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
    };
    const over = (top, under) => ({
      r: top.r * top.a + under.r * (1 - top.a), g: top.g * top.a + under.g * (1 - top.a),
      b: top.b * top.a + under.b * (1 - top.a), a: 1,
    });
    const page0 = parse(getComputedStyle(document.documentElement).getPropertyValue("--bg").trim()
      .replace(/^#(..)(..)(..)$/, (_, r, g, b) => `rgb(${parseInt(r, 16)}, ${parseInt(g, 16)}, ${parseInt(b, 16)})`)) || { r: 5, g: 6, b: 8, a: 1 };
    const bgOf = (el) => {
      const stack = [];
      for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c && c.a > 0) { stack.push(c); if (c.a >= 1) break; }
      }
      let acc = page0;
      for (let i = stack.length - 1; i >= 0; i--) acc = over(stack[i], acc);
      return acc;
    };
    const lum = (c) => {
      const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
    };
    const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const failures = [];
    let checked = 0;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const seen = new Set();
    while (walker.nextNode()) {
      const t = walker.currentNode;
      if (!t.textContent.trim()) continue;
      const el = t.parentElement;
      if (!el || seen.has(el)) continue;
      seen.add(el);
      if (el.closest("[aria-hidden=true], svg, .hidden, [disabled], option")) continue;
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height || r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === "hidden" || Number(cs.opacity) === 0) continue;
      let op = 1;
      for (let n = el; n && n.nodeType === 1; n = n.parentElement) op *= Number(getComputedStyle(n).opacity);
      const bg = bgOf(el);
      let fg = parse(cs.color);
      if (!fg) continue;
      fg = over({ ...fg, a: fg.a * op }, bg);
      const size = parseFloat(cs.fontSize), bold = Number(cs.fontWeight) >= 700;
      const large = size >= 24 || (bold && size >= 18.66);
      const need = large ? 3 : 4.5;
      const got = ratio(fg, bg);
      checked++;
      if (got + 1e-6 < need) {
        failures.push({ text: t.textContent.trim().slice(0, 40), ratio: Math.round(got * 100) / 100, need, size, sel: el.tagName.toLowerCase() + (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/).join(".") : "") });
      }
    }
    return { checked, failures };
  }, scope || null);
}

export function report(title, t0) {
  const passed = checks.filter((c) => c.ok).length;
  const md = ["# " + title + " — " + new Date().toISOString(), "",
    "Base: `" + BASE + "`" + (UI_DIR ? " · assets da árvore de trabalho" : "") + " · duração " + Math.round((Date.now() - t0) / 1000) + " s", "",
    "**" + passed + "/" + checks.length + " checks passaram**" + (expectedErrors ? " · " + expectedErrors + " erro(s) de console esperados (sondas) descontados" : ""), "",
    "| persona | check | resultado | evidência |", "|---|---|---|---|",
    ...checks.map((c) => "| " + c.persona + " | " + c.name + " | " + (c.ok ? "✅" : "❌") + " | " + String(c.detail).replace(/\|/g, "\\|").replace(/\n/g, " ").slice(0, 180) + " |"),
    "", consoleErrors.length ? "## Erros de console\n\n" + consoleErrors.map((e) => "- " + e).join("\n") : "", ""].join("\n");
  writeFileSync(path.join(OUT, "report.md"), md);
  writeFileSync(path.join(OUT, "report.json"), JSON.stringify({ base: BASE, checks, consoleErrors }, null, 2));
  console.log("\n" + passed + "/" + checks.length + " checks · relatório em " + OUT);
  return passed === checks.length;
}
