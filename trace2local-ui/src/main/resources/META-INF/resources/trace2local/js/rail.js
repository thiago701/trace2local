/* Painel lateral: execuções (acervo vivo), API do projeto (launcher) e insights ranqueados. */
import { state, $, el, clear, on, emit, json, ms, relTime, shortId, toast, icon, STATUS, STATUS_CHIP, TRIGGER, CATEGORY, truncate } from "./core.js";
import { clearAll } from "./data.js";

let followTrace = () => {};

export function initRail(selectExecution, openInsight, awaitTrace) {
  if (awaitTrace) followTrace = awaitTrace;
  document.querySelectorAll(".rail-tabs button").forEach((b) =>
    b.addEventListener("click", () => switchRail(b.dataset.rail)));
  $("exec-filter").addEventListener("input", (e) => {
    state.recentFilter = e.target.value;
    renderExecutions(selectExecution);
  });
  let armed = false;
  let timer = null;
  $("btn-clear").addEventListener("click", async () => {
    const b = $("btn-clear");
    if (!armed) {
      armed = true;
      b.classList.add("danger");
      b.textContent = "Confirmar limpeza?";
      timer = setTimeout(() => { armed = false; b.classList.remove("danger"); b.textContent = "Limpar acervo"; }, 3000);
      return;
    }
    clearTimeout(timer);
    armed = false;
    b.classList.remove("danger");
    b.textContent = "Limpar acervo";
    try {
      await clearAll();
      toast("Acervo limpo — o baseline histórico foi preservado", "ok");
    } catch (e) {
      toast("Falha ao limpar: " + e.message, "error");
    }
  });
  on("executions", () => renderExecutions(selectExecution));
  on("selection", () => renderExecutions(selectExecution));
  on("endpoints", () => renderEndpoints(selectExecution));
  on("insights", () => renderInsights(openInsight));
  renderExecutions(selectExecution);
  renderEndpoints(selectExecution);
}

export function switchRail(which) {
  state.rail = which;
  document.querySelectorAll(".rail-tabs button").forEach((b) => b.classList.toggle("active", b.dataset.rail === which));
  ["exec", "api", "ins"].forEach((w) => $("rail-" + w).classList.toggle("hidden", w !== which));
}

/* ------------------------------------------------------------- execuções */
function renderExecutions(selectExecution) {
  const host = clear($("exec-list"));
  const q = (state.recentFilter || "").trim().toLowerCase();
  const all = [...state.executions.values()].filter((e) => e.summary)
    .sort((a, b) => (b.summary.startedAt || "").localeCompare(a.summary.startedAt || ""));
  const maxDur = Math.max(1, ...all.map((e) => (typeof e.summary.duration === "number" ? e.summary.duration : 0)));
  const insightsByExec = new Map();
  for (const i of state.insights || []) {
    for (const id of i.executionIds || []) {
      if (!insightsByExec.has(id)) insightsByExec.set(id, []);
      insightsByExec.get(id).push(i);
    }
  }
  let shown = 0;
  for (const exec of all) {
    const s = exec.summary;
    const hay = [s.rootLabel, s.executionId, s.status, s.trigger, TRIGGER[s.trigger], STATUS[s.status]].join(" ").toLowerCase();
    if (q && !hay.includes(q)) continue;
    shown++;
    const ins = insightsByExec.get(s.executionId) || [];
    const card = el("div", {
      class: "exec-card " + (s.status || "RUNNING") + (state.selectedId === s.executionId ? " selected" : ""),
      role: "listitem", tabindex: "0", "aria-label": (s.rootLabel || s.executionId) + " — " + (STATUS[s.status] || s.status),
    },
    el("div", { class: "l1" },
      el("span", { class: "title", text: s.rootLabel || shortId(s.executionId), title: s.rootLabel || s.executionId }),
      el("span", { class: "chip " + (STATUS_CHIP[s.status] || ""), text: STATUS[s.status] || s.status || "em curso" })),
    el("div", { class: "l2" },
      el("span", { text: TRIGGER[s.trigger] || s.trigger || "externo" }),
      el("span", { class: "mono", text: ms(s.duration) }),
      el("span", { text: (s.nodeCount || exec.nodes.size || 0) + " passos" }),
      el("span", { class: "grow" }),
      ins.length ? el("span", { class: "ins", title: ins.length + " ponto(s) de atenção: " + ins.map((i) => i.title).join(" · ") },
        ...ins.slice(0, 3).map((i) => el("span", { text: (CATEGORY[i.category] || {}).g || "•" }))) : null,
      el("span", { text: relTime(s.startedAt) })),
    el("div", { class: "spark" }, el("i")));
    const pctW = typeof s.duration === "number" ? Math.max(2, Math.min(100, (s.duration / maxDur) * 100)) : 0;
    card.querySelector(".spark i").style.width = pctW + "%";
    const go = () => selectExecution(s.executionId);
    card.addEventListener("click", go);
    card.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
    host.appendChild(card);
  }
  const cnt = $("rail-exec-count");
  cnt.textContent = String(all.length);
  cnt.classList.toggle("hidden", all.length === 0);
  if (!shown) {
    host.appendChild(el("div", { class: "empty", text: q ? "Nenhuma execução casa com o filtro." : "Nenhuma execução ainda — dispare um endpoint na aba API ou execute a aplicação; a árvore aparece aqui ao vivo." }));
  }
}

/* ------------------------------------------------------------- API do projeto (launcher) */
function renderEndpoints(selectExecution) {
  const host = clear($("endpoints"));
  const app = state.meta && state.meta.app && !["station", "?"].includes(state.meta.app) ? state.meta.app : null;
  if (app) {
    $("api-app").textContent = app;
    $("api-app").classList.remove("hidden");
  }
  if (!state.endpoints.length) {
    host.appendChild(el("div", { class: "empty" },
      el("strong", { text: app ? "O projeto " + app + " não expõe API HTTP descoberta." : "Nenhuma API HTTP descoberta neste modo." }),
      el("br"),
      "A observação continua: dispare a aplicação (Lambda, fila, teste) e acompanhe em Execuções."));
    return;
  }
  for (const ep of state.endpoints) {
    const hasBody = !["GET", "DELETE", "HEAD"].includes(ep.method);
    const ta = el("textarea", { spellcheck: "false", "aria-label": "Corpo JSON de " + ep.method + " " + ep.path });
    ta.value = ep.sampleBody || (hasBody ? "{}" : "");
    // parâmetros declarados no contrato (path/query/header): o obrigatório é pedido antes do disparo
    const params = (ep.parameters || []).filter((p) => ["path", "query", "header"].includes(p.in));
    const inputs = new Map();
    const paramBox = params.length ? el("div", { class: "ep-params" }) : null;
    params.forEach((p, idx) => {
      const id = "ep-" + ep.endpointId.replace(/[^a-zA-Z0-9_-]/g, "_") + "-p" + idx;
      const generated = p.example && p.example.startsWith("(gerado");
      const input = el("input", { id, type: "text", autocomplete: "off", spellcheck: "false",
        placeholder: generated ? p.example : (p.example ? "ex.: " + p.example : p.required ? "obrigatório" : "opcional"),
        "aria-required": p.required && !generated ? "true" : null, title: p.description || null });
      if (p.example && !generated) input.value = p.example;
      inputs.set(p, input);
      paramBox.appendChild(el("div", { class: "ep-param" },
        el("label", { for: id }, el("span", { class: "in", text: p.in }), el("span", { class: "mono", text: p.name }),
          p.required && !generated ? el("span", { class: "req", title: "obrigatório", text: "*" }) : null,
          p.description ? el("span", { class: "muted grow", text: truncate(p.description, 60) }) : null),
        input));
    });
    const btn = el("button", { type: "button", class: "primary" }, "▶ Executar");
    const body = el("div", { class: "ep-body hidden" },
      paramBox,
      hasBody && !ep.requestSchema ? el("div", { class: "ep-note", text: "Schema não inferido — corpo vazio editável (nunca um schema inventado)." }) : null,
      hasBody || ep.sampleBody ? ta : null,
      el("div", { class: "row" }, btn, el("span", { class: "muted", text: ep.handler || "" })));
    const item = el("div", { class: "endpoint", role: "listitem" },
      el("div", { class: "ep-head", tabindex: "0" },
        el("span", { class: "method " + ep.method, text: ep.method }),
        el("span", { class: "ep-path grow", text: ep.path, title: ep.path }),
        el("span", { class: "muted", text: "▾" })),
      body);
    const toggle = () => { body.classList.toggle("hidden"); item.classList.toggle("open"); };
    item.querySelector(".ep-head").addEventListener("click", toggle);
    item.querySelector(".ep-head").addEventListener("keydown", (e) => { if (e.key === "Enter") toggle(); });
    btn.addEventListener("click", async () => {
      let payload = null;
      const headers = {}, pathVariables = {};
      for (const [p, input] of inputs) {
        const v = input.value.trim();
        if (!v) {
          const generated = p.example && p.example.startsWith("(gerado");
          if (p.required && !generated) {
            toast("Informe " + p.name + " (" + p.in + ") — obrigatório no contrato", "error");
            input.focus();
            return;
          }
          continue;
        }
        if (p.in === "path") pathVariables[p.name] = v; else headers[p.name] = v;
      }
      if (ta.isConnected && ta.value.trim()) {
        try { payload = JSON.parse(ta.value); } catch (e) {
          toast("JSON inválido no corpo: " + e.message, "error");
          ta.focus();
          return;
        }
      }
      btn.disabled = true;
      btn.textContent = "Disparando…";
      try {
        const r = await json("/execute", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ endpointId: ep.endpointId, body: payload, headers, pathVariables }),
        });
        if (r.executionId) {
          toast("Execução " + shortId(r.executionId) + " disparada — acompanhando ao vivo", "ok");
          selectExecution(r.executionId, { live: true });
        } else {
          // Station: a execução nasce dentro da Lambda — a UI a encontra pelo traceId do disparo
          toast("Disparado (trace " + shortId(r.traceId) + ") — a árvore abre assim que a execução chegar", "ok");
          followTrace(r.traceId);
        }
      } catch (e) {
        toast("Falha no disparo: " + e.message, "error");
      } finally {
        btn.disabled = false;
        btn.textContent = "▶ Executar";
      }
    });
    host.appendChild(item);
  }
}

/* ------------------------------------------------------------- insights ranqueados */
function renderInsights(openInsight) {
  const host = clear($("ins-list"));
  const list = state.insights || [];
  const cnt = $("rail-ins-count");
  const urgent = list.filter((i) => i.severity === "HIGH" || i.severity === "CRITICAL").length;
  cnt.textContent = String(list.length);
  cnt.classList.toggle("hidden", list.length === 0);
  cnt.classList.toggle("err", urgent > 0);
  if (!list.length) {
    host.appendChild(el("div", { class: "empty", text: "Nenhum ponto de atenção relevante. As Regras Preditivas analisam cada execução em segundo plano — silêncio é bom sinal." }));
    return;
  }
  for (const i of list) {
    const c = CATEGORY[i.category] || { g: "•", l: i.category };
    const item = el("div", { class: "ins-mini", role: "listitem", tabindex: "0" },
      el("div", { class: "row" }, c.icon ? icon(c.icon, "cat") : el("span", { text: c.g }), el("span", { class: "t grow", text: i.title })),
      el("div", { class: "m" },
        el("span", { class: "chip " + sevChip(i.severity), text: sevLabel(i.severity) }),
        el("span", { text: Math.round(i.confidence * 100) + "% · " + i.confidenceBand }),
        i.occurrences > 1 ? el("span", { text: "×" + i.occurrences }) : null,
        el("span", { class: "muted", text: i.id })),
      el("div", { class: "m", text: truncate(i.observation, 140) }));
    const go = () => openInsight(i);
    item.addEventListener("click", go);
    item.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
    host.appendChild(item);
  }
  if (state.insightsSuppressed) {
    host.appendChild(el("div", { class: "muted", text: state.insightsSuppressed + " insight(s) silenciado(s) pelo seu feedback." }));
  }
}

export function sevChip(sev) {
  return { CRITICAL: "err", HIGH: "err", MEDIUM: "warn", LOW: "info", INFO: "" }[sev] || "";
}
export function sevLabel(sev) {
  return { CRITICAL: "crítico", HIGH: "alto", MEDIUM: "médio", LOW: "baixo", INFO: "info" }[sev] || sev;
}
export { emit };
