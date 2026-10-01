/**
 * LOOP DE USABILIDADE POR PERSONA — Trace2Local Resonance sobre a STACK ALVO (examples/finance-pix:
 * Lambda Java 25 + API Gateway + DynamoDB + Postgres + SQS + SNS + 5 parceiros mockados).
 *
 * Critério de aceite da UI (docs/qa/ACEITE.md, nível L7): cada passo vira um CHECK com evidência
 * e screenshot. Rode DEPOIS de scripts/journeys.py (o acervo precisa das execuções das jornadas):
 *
 *   cd examples/finance-pix && python3 scripts/journeys.py
 *   cd scripts/ux-loop && node persona-loop-pix.mjs
 *
 * Personas: dev em primeiro contato · dev investigando a transferência · dev validando variações
 * (Mock Connect) · dev disparando pelo contrato · QA/PO homologando · tech lead triando.
 * Mais: identidade visual, contraste WCAG medido, teclado, CSP, mobile.
 */
import { BASE, check, snap, sleep, api, launch, newPage, ready, view, lastToast, contrastAudit, expectingErrors, report, consoleErrors } from "./harness.mjs";

const P1 = "dev-1º contato", P2 = "dev-investiga", P3 = "dev-mocks", P4 = "dev-dispara", P5 = "qa-po", P6 = "tech-lead";
const A11Y = "a11y", SEC = "segurança", ID = "identidade";
const contrastFailures = [];

async function audit(page, where, scope) {
  const r = await contrastAudit(page, scope);
  contrastFailures.push(...r.failures.map((f) => ({ where, ...f })));
  return r;
}

(async () => {
  const browser = await launch();
  const t0 = Date.now();
  let page = await newPage(browser, { width: 1440, height: 900 });

  // ================================================================ P1 — primeiro contato
  console.log("\nPERSONA 1 · Dev em primeiro contato (o que é isto? o que meu sistema tem?)");
  const tLoad = Date.now();
  await ready(page, "?view=anatomy");
  const loadMs = Date.now() - tLoad;
  check(P1, "UI pronta em < 3 s", loadMs < 3000, loadMs + " ms");
  const brand = (await page.textContent(".brand small")).trim();
  const title = await page.title();
  check(ID, "Marca RESONANCE no topo e no título da aba", brand === "RESONANCE" && /Resonance/.test(title), brand + " · " + title);
  const legacyName = await page.evaluate(() => /Resson[âa]ncia/i.test(document.body.innerText));
  check(ID, "Nenhum resquício do nome antigo (Ressonância) na interface", !legacyName, "");
  const idt = await page.evaluate(() => {
    const cs = (s) => getComputedStyle(document.querySelector(s));
    const h1 = cs(".brand h1");
    return { h1Upper: h1.textTransform === "uppercase" || document.querySelector(".brand h1").textContent === document.querySelector(".brand h1").textContent.toUpperCase(),
      h1Track: parseFloat(h1.letterSpacing) / parseFloat(h1.fontSize), h1Color: h1.color, bg: getComputedStyle(document.body).backgroundColor,
      bodyLine: parseFloat(getComputedStyle(document.body).lineHeight) / parseFloat(getComputedStyle(document.body).fontSize) };
  });
  check(ID, "Títulos industriais: caixa alta, tracking aberto, branco puro", idt.h1Upper && idt.h1Track >= 0.08 && /255, 255, 255/.test(idt.h1Color), JSON.stringify(idt));
  check(ID, "Microcópia com entrelinha espaçosa (≥ 1,45)", idt.bodyLine >= 1.45, idt.bodyLine.toFixed(2));
  await page.waitForSelector("#anatomy-vp .a-node", { timeout: 8000 }).catch(() => {});
  const comps = await page.$$eval("#anatomy-vp .a-node", (n) => n.map((x) => x.getAttribute("data-id")));
  const ext = comps.filter((c) => c.startsWith("ext:"));
  check(P1, "Anatomia mostra o ecossistema (3 Lambdas, tabelas, fila, tópico, Postgres)", comps.filter((c) => c.startsWith("lambda:")).length >= 3 && comps.some((c) => c.startsWith("sqs:")) && comps.some((c) => c.startsWith("sns:")) && comps.some((c) => c.startsWith("sql:")), comps.length + " componente(s)");
  check(P1, "Os 5 parceiros externos aparecem na fronteira externa", ext.length >= 5, ext.join(", "));
  const declared = comps.filter((c) => /:fn$|dynamodb:transfers$|dynamodb:idempotency$/.test(c));
  check(P1, "Sem componente fantasma do IaC (rótulo local do Terraform)", declared.length === 0, declared.join(", ") || "nenhum");
  const mocksBtn = await page.$eval('#views button[data-view="mocks"]', (b) => ({ visible: b.offsetParent !== null, badge: (b.querySelector(".badge-count:not(.hidden)") || {}).textContent || "" }));
  check(P1, "Visão Mocks descobrível no topo (com contagem de sugestões pendentes)", mocksBtn.visible, "badge=" + (mocksBtn.badge || "—"));
  const engine = await page.textContent("#engine-chip");
  check(P1, "Status do motor de decisão visível", /motor/.test(engine), engine);
  await snap(page, "anatomia-finance-pix");
  await audit(page, "anatomia");

  // ================================================================ P2 — investigando a transferência aprovada
  console.log("\nPERSONA 2 · Dev: o Pix aprovado passou por onde? (API → DICT/KYC/antifraude → SQS → SPI → SNS → notificação)");
  const list = await api(page, "/executions?limit=100");
  const settled = list.filter((e) => e.status === "COMPLETED" && e.nodeCount >= 30 && /POST \/pix\/transfers/.test(e.rootLabel || ""))
    .sort((a, b) => (b.startedAt || "").localeCompare(a.startedAt || ""));
  // a do J3 (KYC simulado, liquidação na 1ª tentativa) é a de 34 passos mais antiga
  const exec = settled.find((e) => e.nodeCount === 34) || settled[0];
  check(P2, "Há transferência liquidada de ponta a ponta no acervo", !!exec, exec ? exec.executionId + " · " + exec.nodeCount + " passos · " + exec.duration + " ms" : "rode scripts/journeys.py antes");
  if (exec) {
    await ready(page, "?execution=" + exec.executionId + "&view=tree");
    await page.waitForSelector("#tree-vp .t-node", { timeout: 8000 });
    await sleep(400);
    const nodes = await page.$$eval("#tree-vp .t-node", (n) => n.length);
    check(P2, "Árvore desenha todos os passos (deep link ?execution=)", nodes === exec.nodeCount, nodes + " de " + exec.nodeCount);
    const waits = await page.$$eval("#tree-vp .t-wait-txt", (n) => n.map((x) => x.textContent));
    check(P2, "Arestas assíncronas (SQS e SNS) rotuladas com a espera", waits.length >= 2, waits.join(" · "));
    const strip = (await page.textContent("#exec-strip")).replace(/\s+/g, " ");
    check(P2, "Faixa da execução: status, gatilho, duração, passos e manchete", /concluída/.test(strip) && /duração/.test(strip) && /passos/.test(strip), strip.slice(0, 160));
    const sims = await page.$$eval("#tree-vp .t-node.sim", (n) => n.map((g) => g.getAttribute("aria-label")));
    check(P2, "Passo atendido pelo mock marcado SIM e anunciado ao leitor de tela", sims.length >= 1 && sims.every((l) => /simulada/.test(l)), sims.join(" | "));
    await snap(page, "arvore-pix-liquidado");
    const simNode = await page.$("#tree-vp .t-node.sim");
    if (simNode) {
      await simNode.click();
      await page.waitForSelector("#drawer.open", { timeout: 3000 });
      await sleep(700);
      const dr = (await page.textContent("#drawer")).replace(/\s+/g, " ");
      check(P2, "Inspetor diz que a resposta veio do Mock Connect (binding + stub)", /SIM/.test(dr) && /veio do Mock Connect/.test(dr) && /binding/.test(dr), dr.slice(0, 200));
      check(P2, "Nota de negócio da chamada externa nomeia o parceiro (sem 'para ao serviço')", !/para ao servi/.test(dr) && /Chamada externa GET a KYC/.test(dr), (dr.match(/Chamada externa[^.]*\./) || [""])[0]);
      const hidden = await page.evaluate(() => {
        const g = document.querySelector("#tree-vp .t-node.sel"), d = document.getElementById("drawer");
        if (!g || !d) return true;
        return d.classList.contains("open") && g.getBoundingClientRect().right > d.getBoundingClientRect().left;
      });
      check(P2, "Passo selecionado não fica escondido sob o inspetor", !hidden, "");
      const legendClash = await page.evaluate(() => {
        const mm = document.querySelector(".minimap");
        return !!mm && mm.offsetParent !== null && getComputedStyle(mm).display !== "none" && document.getElementById("drawer").classList.contains("open");
      });
      check(P2, "Minimapa não cobre a legenda com o inspetor aberto", !legendClash, "");
      await snap(page, "inspetor-kyc-simulado");
      await audit(page, "inspetor", "#drawer");
      await page.keyboard.press("Escape");
    }
    // delta de dados do débito
    const sqlNodes = await page.$$("#tree-vp .t-node");
    let opened = false;
    for (const g of sqlNodes) {
      const label = (await g.getAttribute("aria-label")) || "";
      if (/^SQL: UPDATE accounts/.test(label)) { await g.click(); opened = true; break; }
    }
    if (opened) {
      await page.waitForSelector("#drawer.open", { timeout: 3000 });
      await page.click('#drawer .dr-tabs button:has-text("Dados")');
      await sleep(250);
      const dados = (await page.textContent("#drawer")).replace(/\s+/g, " ");
      check(P2, "Δ do Postgres honesto: operação + tabela + chave, e o que NÃO foi capturado explicado", /UPDATE/.test(dados) && /accounts/.test(dados) && /inferida do SQL/.test(dados) && /não vêm do driver/.test(dados), dados.slice(0, 220));
      await snap(page, "inspetor-delta-saldo");
      await page.keyboard.press("Escape");
    } else {
      check(P2, "Passo SQL UPDATE accounts presente", false, "não encontrado");
    }
    const tv = await view(page, "timeline");
    check(P2, "Troca de visão < 1 s", tv < 1000, tv + " ms");
    await page.waitForSelector(".tl-table table", { timeout: 8000 }).catch(() => {});
    await sleep(600);
    const rows = await page.$$eval(".tl-table tbody tr, .tl-table tr", (r) => r.map((x) => x.textContent));
    const starts = rows.filter((t) => /START RequestId/.test(t)).length;
    check(P2, "Logs CloudWatch das 3 funções correlacionados na mesma linha do tempo", starts >= 3, starts + " START · " + rows.length + " linha(s)");
    const chapters = await page.$$eval(".tl-chap", (c) => c.length);
    check(P2, "Capítulos didáticos na linha do tempo", chapters >= 3, chapters + " capítulo(s)");
    await snap(page, "linha-do-tempo-pix");
  }

  // ================================================================ P3 — validando variações (Mock Connect)
  console.log("\nPERSONA 3 · Dev: quero simular o parceiro e validar variações do JSON no meu serviço");
  const tKey = Date.now();
  await page.keyboard.press("9");
  await page.waitForSelector("#view-mocks:not(.hidden) .mk-head", { timeout: 4000 });
  check(P3, "Tecla 9 abre o Mock Connect em < 1 s", Date.now() - tKey < 1000, (Date.now() - tKey) + " ms");
  await page.waitForSelector(".mk-card", { timeout: 6000 }).catch(() => {});
  const cards = await page.$$eval(".mk-card", (c) => c.map((x) => ({ id: x.id, handled: x.classList.contains("handled"), title: x.querySelector(".tt").textContent, ev: x.querySelectorAll(".ev tr").length, vars: x.querySelectorAll(".mk-var").length })));
  check(P3, "Sugestões com POR QUÊ + evidência navegável", cards.length >= 1 && cards.every((c) => c.ev >= 1), cards.map((c) => c.title.slice(0, 50) + " (" + c.ev + " ev)").join(" | "));
  const firstHandled = cards.findIndex((c) => c.handled);
  check(P3, "Pendentes primeiro; plugadas marcadas RESOLVIDA no fim", firstHandled === -1 || cards.slice(firstHandled).every((c) => c.handled), cards.map((c) => (c.handled ? "✓" : "•")).join(""));
  const target = cards.find((c) => !c.handled && c.vars >= 1) || cards.find((c) => c.vars >= 1);
  await snap(page, "mocks-sugestoes");
  await audit(page, "mocks-sugestoes", "#view-mocks");
  let created = null;
  if (target) {
    const sel = "#" + target.id;
    // evidência → árvore no passo, e volta
    await page.click(sel + " .ev button");
    await page.waitForSelector("#view-tree:not(.hidden)", { timeout: 4000 });
    await page.waitForSelector("#drawer.open", { timeout: 4000 }).catch(() => {});
    check(P3, "[abrir] da evidência leva ao passo exato na árvore", await page.isVisible("#drawer.open"), await page.textContent("#drawer .dr-title").catch(() => ""));
    await page.keyboard.press("Escape");
    await page.keyboard.press("9");
    await page.waitForSelector(sel, { timeout: 4000 });
    // escolhe uma variação; o rótulo do botão acompanha
    await sleep(400); // o refresh da visão ao entrar
    const varId = await page.$eval(sel + " .mk-var .vid", (c) => c.textContent);
    await page.locator(sel + " .mk-var input[type=checkbox]").first().check();
    const actLabel = await page.textContent(sel + " .mk-acts button.primary");
    check(P3, "Selecionar variação atualiza a ação (quantas serão aplicadas)", /1 variação/.test(actLabel), actLabel);
    const host = await page.$("#view-mocks");
    await page.$eval(sel + ' .mk-mode button[data-mode="on-demand"]', (b) => b.scrollIntoView({ block: "center" }));
    await sleep(100);
    const y0 = await host.evaluate((h) => h.scrollTop);
    await page.click(sel + ' .mk-mode button[data-mode="on-demand"]');
    await sleep(150);
    const y1 = await host.evaluate((h) => h.scrollTop);
    const checked = await page.getAttribute(sel + ' .mk-mode button[data-mode="on-demand"]', "aria-checked");
    check(P3, "Modo 'Sob demanda' é um radio acessível e não pula a rolagem", checked === "true" && Math.abs(y1 - y0) < 2, "aria-checked=" + checked + " · Δy=" + (y1 - y0));
    const actBox = await page.$eval(sel + " .mk-acts button.primary", (b) => { const r = b.getBoundingClientRect(); return { h: r.height, w: r.width }; });
    check(A11Y, "Botão de ação principal com alvo ≥ 32 px", actBox.h >= 32, JSON.stringify(actBox));
    await page.click(sel + " .mk-acts button.primary");
    await page.waitForSelector(sel + " .mk-howto", { timeout: 8000 }).catch(() => {});
    const how = (await page.textContent(sel + " .mk-howto").catch(() => "")).replace(/\s+/g, " ");
    const toast = await lastToast(page);
    check(P3, "Plugar: feedback imediato (toast) com o binding ativo", /ativo/.test(toast), toast);
    check(P3, "'Como usar agora' entrega o cabeçalho baggage pronto para copiar", how.includes("baggage: t2l.mock=" + varId), how.slice(0, 200));
    await snap(page, "mocks-plugado-sob-demanda");
    // bindings
    await page.click("#mk-tab-bindings");
    await page.waitForSelector(".mk-binding", { timeout: 4000 });
    const bindings = await page.$$eval(".mk-binding", (b) => b.map((x) => ({ name: x.querySelector(".mk-bhead strong").textContent, state: x.querySelector(".mk-bhead .chip").textContent })));
    created = bindings.find((b) => b.name !== "mock-kyc-limites" && /ativo/.test(b.state)) || null;
    check(P3, "Binding aparece ativo na aba Bindings (estado + fonte → destino)", !!created, bindings.map((b) => b.name + ":" + b.state).join(", "));
    if (created) {
      const card = '.mk-binding:has(strong:text-is("' + created.name + '"))';
      await page.click(card + ' button:has-text("ver stubs efetivos")');
      await page.waitForSelector(card + " .mk-stubs table", { timeout: 4000 }).catch(() => {});
      const stubRows = await page.$$eval(card + " .mk-stubs tr", (r) => r.length).catch(() => 0);
      check(P3, "Stubs efetivos inspecionáveis (requisição → resposta/variação)", stubRows >= 2, (stubRows - 1) + " stub(s)");
      const exp = await page.getAttribute(card + " a.btn", "href");
      check(P3, "Exportar para WireMock a um clique", /\/export$/.test(exp || ""), exp);
      await snap(page, "mocks-bindings");
    }
    // journal
    await page.click("#mk-tab-journal");
    await sleep(200);
    const jrows = await page.$$eval(".mk-journal tr", (r) => r.length).catch(() => 0);
    check(P3, "Journal: cada chamada atendida (binding, stub, variação, resultado)", jrows >= 2, (jrows - 1) + " chamada(s)");
    // plugins
    await page.click("#mk-tab-plugins");
    await sleep(200);
    const plug = await page.$$eval(".mk-plugin", (p) => p.length);
    const secs = await page.$$eval("#view-mocks .panel h3", (h) => h.map((x) => x.textContent));
    check(P3, "Catálogo de plugins por tipo (fonte · transformação · predicado · destino)", plug >= 20 && secs.length === 4, plug + " plugins · " + secs.join(" / "));
    await snap(page, "mocks-plugins");
    await audit(page, "mocks-plugins", "#view-mocks");
    // editor: validação por chave antes de salvar
    await page.click("#mk-tab-editor");
    await page.waitForSelector(".mk-editor");
    const bad = JSON.stringify({ name: "x", config: { source: "openapi", transforms: "a", "transforms.a.type": "set-field" } });
    await page.fill(".mk-editor", bad);
    await page.click('button:has-text("Validar")');
    await page.waitForSelector(".mk-validate .chip", { timeout: 4000 });
    const errs = (await page.textContent(".mk-validate")).replace(/\s+/g, " ");
    check(P3, "Editor valida chave a chave (estilo Connect) e aponta o que falta", /problema/.test(errs) && /target/.test(errs), errs.slice(0, 200));
    await page.fill(".mk-editor", "");
    await page.click('#mk-tab-editor');
    await snap(page, "mocks-editor-validacao");
    // remove o binding criado (confirmação em dois passos)
    if (created) {
      await page.click("#mk-tab-bindings");
      const card = '.mk-binding:has(strong:text-is("' + created.name + '"))';
      await page.click(card + " button.danger");
      const confirmLabel = await page.textContent(card + " button.danger");
      await page.click(card + " button.danger");
      await sleep(600);
      const still = await page.$(card);
      check(P3, "Remover exige confirmação em dois passos e limpa o binding", /confirmar/.test(confirmLabel) && !still, confirmLabel);
    }
  } else {
    check(P3, "Há sugestão com variações para validar", false, "nenhuma");
  }

  // ================================================================ P4 — disparando pelo contrato
  console.log("\nPERSONA 4 · Dev: disparar o endpoint pelo contrato OpenAPI, sem Postman");
  await page.click('.rail-tabs button[data-rail="api"]');
  await sleep(200);
  const eps = await page.$$eval("#endpoints .endpoint", (e) => e.map((x) => x.querySelector(".ep-path").textContent));
  check(P4, "Aba API lista os endpoints do contrato", eps.length >= 2, eps.join(" · "));
  const getIdx = eps.findIndex((p) => /\{transferId\}/.test(p));
  if (getIdx >= 0) {
    const ep = (await page.$$("#endpoints .endpoint"))[getIdx];
    await (await ep.$(".ep-head")).click();
    const params = await ep.$$eval(".ep-param label", (l) => l.map((x) => x.textContent));
    check(P4, "Parâmetro de path do contrato vira campo (obrigatório marcado)", params.some((t) => /transferId/.test(t) && /\*/.test(t)), params.join(" | "));
    const input = await ep.$(".ep-param input");
    await input.fill("");
    await (await ep.$("button.primary")).click();
    await sleep(200);
    const focusIsInput = await page.evaluate(() => document.activeElement && document.activeElement.closest(".ep-param") !== null);
    check(P4, "Sem o obrigatório: aviso claro e foco no campo (nada é disparado)", focusIsInput && /obrigatório/.test(await lastToast(page)), await lastToast(page));
    const before = await page.evaluate(() => window.__t2l.selectedId);
    await input.fill("pix-ux-loop-inexistente");
    await (await ep.$("button.primary")).click();
    await sleep(300);
    check(P4, "Disparo pelo Station informa que segue pelo traceId", /Disparado/.test(await lastToast(page)), await lastToast(page));
    const arrived = await page.waitForFunction((b) => {
      const s = window.__t2l;
      const e = s.selectedId && s.executions.get(s.selectedId);
      return s.selectedId && s.selectedId !== b && e && e.summary && /GET/.test(e.summary.rootLabel || "") ? s.selectedId : null;
    }, before, { timeout: 45000 }).then((h) => h.jsonValue()).catch(() => null);
    check(P4, "A execução disparada abre sozinha quando chega da Lambda", !!arrived, arrived || "não chegou em 45 s");
    await sleep(2500);
    const ghosts = await page.evaluate(() => [...window.__t2l.executions.values()]
      .filter((e) => e.summary && !e.completed && Date.now() - Date.parse(e.summary.startedAt || 0) > 2000).map((e) => e.summary.executionId));
    check(P4, "Painel sem execução fantasma 'em curso' (id provisório trocado pelo da raiz)", ghosts.length === 0, ghosts.join(", ") || "nenhuma");
    await snap(page, "disparo-pelo-contrato");
  }
  const postIdx = eps.findIndex((p) => /^\/pix\/transfers$/.test(p.trim()));
  if (postIdx >= 0) {
    const ep = (await page.$$("#endpoints .endpoint"))[postIdx];
    await (await ep.$(".ep-head")).click();
    const ph = await ep.$$eval(".ep-param input", (i) => i.map((x) => x.placeholder));
    check(P4, "Idempotency-Key explicado (gerado a cada disparo) — não bloqueia", ph.some((p) => /gerado/.test(p)), ph.join(" | "));
    const body = await ep.$eval("textarea", (t) => t.value).catch(() => "");
    check(P4, "Corpo de exemplo vem do contrato", /payerAccountId/.test(body), body.replace(/\s+/g, " ").slice(0, 100));
  }
  await audit(page, "rail-api", "#rail");

  // ================================================================ P5 — QA/PO homologando
  console.log("\nPERSONA 5 · QA/PO: a regra de negócio foi respeitada? posso homologar?");
  const review = list.find((e) => /POST/.test(e.rootLabel || "") && e.nodeCount >= 10 && e.nodeCount < 30 && e.status === "COMPLETED");
  if (review) {
    await ready(page, "?execution=" + review.executionId + "&view=investigate");
    await page.waitForSelector(".inv .headline", { timeout: 10000 }).catch(() => {});
    const head = await page.textContent(".inv .headline").catch(() => "");
    check(P5, "Manchete executiva em linguagem de negócio", head.length > 20, head);
    const verdicts = await page.$$eval(".rules .verdict", (v) => v.map((x) => x.textContent));
    check(P5, "Regras do glossário (trace2local-business.md) com veredito", verdicts.length >= 1, verdicts.join(", "));
    const ck = await page.$$eval(".checklist li", (v) => v.length);
    check(P5, "Checklist de homologação auditável", ck >= 5, ck + " itens");
    await snap(page, "investigacao-pix-revisao");
    await audit(page, "investigacao", "#view-investigate");
    await view(page, "story");
    await sleep(500);
    const story = (await page.textContent("#view-story")).replace(/\s+/g, " ");
    check(P5, "Narrativa de negócio sem frase quebrada", story.length > 200 && !/para ao servi/.test(story), story.slice(0, 160));
  } else {
    check(P5, "Há transferência em revisão/recusada para homologar", false, "nenhuma");
  }

  // ================================================================ P6 — tech lead
  console.log("\nPERSONA 6 · Tech lead: o que merece atenção agora?");
  await ready(page, "?view=dashboard");
  await page.click('.rail-tabs button[data-rail="ins"]');
  await sleep(300);
  const ins = await page.$$eval("#ins-list .ins-mini", (v) => v.map((x) => ({ t: x.textContent, icon: !!x.querySelector("svg.i") })));
  check(P6, "Insights ranqueados no painel lateral", ins.length >= 1, ins.length + " · " + (ins[0] ? ins[0].t.slice(0, 90) : ""));
  check(ID, "Categorias com ícone linear (sem emoji colorido)", ins.every((i) => i.icon), "");
  const emoji = await page.evaluate(() => /[\u{1F300}-\u{1FAFF}]/u.test(document.body.innerText));
  check(ID, "Nenhum emoji pictográfico na interface", !emoji, "");
  await page.waitForSelector("#dash-intel .kv", { timeout: 6000 }).catch(() => {});
  const intel = (await page.textContent("#dash-intel").catch(() => "")).replace(/\s+/g, " ");
  check(P6, "Painel: motor, pipeline preditivo e baseline", /pipeline preditivo/.test(intel) && /baseline/.test(intel), intel.slice(0, 140));
  await snap(page, "painel-tech-lead");
  await page.keyboard.press("Control+K");
  await page.fill(".pal input", "antifraude");
  await sleep(200);
  const hits = await page.$$eval(".pal li", (v) => v.map((x) => x.textContent));
  check(P6, "Ctrl+K encontra o parceiro/etapa por nome", hits.some((t) => /antifraude/i.test(t)), hits.slice(0, 3).join(" | "));
  await page.fill(".pal input", "mock");
  await sleep(150);
  const mh = await page.$$eval(".pal li", (v) => v.map((x) => x.textContent));
  check(P6, "Ctrl+K leva ao Mock Connect", mh.some((t) => /Mock Connect|Plugar mock/.test(t)), mh.slice(0, 2).join(" | "));
  await page.keyboard.press("Escape");

  // ================================================================ acessibilidade, teclado, segurança
  console.log("\nAcessibilidade / identidade / segurança");
  for (const v of ["anatomy", "tree", "timeline", "investigate", "story", "dashboard", "compare", "infra", "mocks"]) {
    await view(page, v);
    await sleep(250);
  }
  const unnamed = await page.$$eval("button", (bs) => bs.filter((b) => b.offsetParent !== null && !(b.textContent || "").trim() && !b.getAttribute("aria-label") && !b.getAttribute("title")).length);
  check(A11Y, "Todo botão visível tem nome acessível", unnamed === 0, unnamed + " sem nome");
  const keys = [];
  for (let k = 1; k <= 9; k++) {
    await page.keyboard.press(String(k));
    await sleep(120);
    keys.push(await page.evaluate(() => window.__t2l.view));
  }
  check(A11Y, "Teclas 1–9 alcançam as 9 visões", new Set(keys).size === 9, keys.join(","));
  await page.keyboard.press("Tab");
  await page.keyboard.press("Tab");
  const focus = await page.evaluate(() => {
    const a = document.activeElement;
    if (!a || a === document.body) return null;
    const cs = getComputedStyle(a);
    return { tag: a.tagName, outline: cs.outlineStyle, w: cs.outlineWidth, shadow: cs.boxShadow !== "none" };
  });
  check(A11Y, "Foco de teclado visível (contorno ciano)", focus && focus.outline !== "none" && parseFloat(focus.w) >= 2, JSON.stringify(focus));
  const reduced = await page.evaluate(() => [...document.styleSheets].some((s) => { try { return [...s.cssRules].some((r) => r.media && /prefers-reduced-motion/.test(r.media.mediaText)); } catch (e) { return false; } }));
  check(A11Y, "prefers-reduced-motion respeitado", reduced, "");
  const csp = await page.evaluate(() => window.__csp || []);
  check(SEC, "Zero violação de CSP (style/script inline)", csp.length === 0, csp.slice(0, 3).join(" | "));
  const headers = await page.evaluate(async (u) => {
    const r = await fetch(u);
    return { csp: r.headers.get("content-security-policy") };
  }, BASE + "/");
  check(SEC, "CSP estrita servida (default-src 'self', sem unsafe-inline)", /default-src 'self'/.test(headers.csp || "") && !/unsafe-inline/.test(headers.csp || ""), headers.csp);
  const csrf = await expectingErrors(() => page.evaluate(async (u) => (await fetch(u, { method: "DELETE" })).status, BASE + "/api/mocks/bindings/qualquer"));
  check(SEC, "Mutação do Mock Connect sem X-Trace2Local é recusada (CSRF)", csrf === 403, "DELETE sem cabeçalho → " + csrf);

  // contraste medido em todas as telas percorridas
  const worst = contrastFailures.sort((a, b) => a.ratio - b.ratio);
  check(A11Y, "Contraste WCAG AA em todo texto visível auditado", worst.length === 0,
    worst.length ? worst.slice(0, 6).map((f) => f.where + ": “" + f.text + "” " + f.ratio + "<" + f.need + " (" + f.sel + ")").join(" · ") : "0 falhas");

  // mobile
  const mob = await newPage(browser, { width: 390, height: 844 });
  await ready(mob, "?view=mocks");
  await sleep(700);
  const overflow = await mob.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check(A11Y, "Sem rolagem horizontal em 390 px (visão Mocks)", !overflow, "");
  const clash = await mob.evaluate(() => {
    const a = document.getElementById("btn-palette").getBoundingClientRect();
    return [...document.querySelectorAll("#topbar > *")].filter((e) => e.id !== "btn-palette" && e.offsetParent !== null)
      .some((e) => { const b = e.getBoundingClientRect(); return b.width > 0 && b.left < a.right - 1 && b.right > a.left + 1; });
  });
  check(A11Y, "Topo sem sobreposição em 390 px", !clash, "");
  const activeVisible = await mob.evaluate(() => {
    const b = document.querySelector('#views button[aria-current="page"]'), bar = document.getElementById("views");
    const r = b.getBoundingClientRect(), br = bar.getBoundingClientRect();
    return r.left >= br.left - 1 && r.right <= br.right + 1;
  });
  check(A11Y, "Em 390 px a visão ativa fica visível na barra rolável", activeVisible, "");
  await snap(mob, "mobile-390-mocks");

  check("geral", "Nenhum erro de console", consoleErrors.length === 0, consoleErrors.slice(0, 3).join(" | "));
  await browser.close();
  const ok = report("Loop de usabilidade por persona — finance-pix (stack alvo)", t0);
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
