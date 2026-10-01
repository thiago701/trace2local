# ADR-017 — Servidor MCP como cliente fino da API local, somente leitura por padrão

- **Status:** Aceita (2026-10-01)
- **Relacionadas:** ADR-007/015 (local-first, RequestGuard), ADR-011 (egress estrutural), ADR-016 (Mock Connect), [docs/MCP.md](../MCP.md)

## Contexto

Assistentes de código e harnesses agênticos (Claude Code, Cursor, Copilot, squads de agentes) depuram e evoluem
serviços lendo código e logs soltos. O Trace2Local já tem o que falta a eles: a **execução real** montada como
árvore, com dados alterados, logs correlacionados, laudo, insights e mocks de parceiros. O padrão de fato para
expor isso a agentes é o **Model Context Protocol** (JSON-RPC 2.0; transportes stdio e Streamable HTTP).

## Decisão

1. **Módulo `trace2local-mcp`, cliente fino da API REST local** (a mesma da UI), sem depender do core: funciona com
   o modo embedded e com o Station, em qualquer JRE 21+, e não acopla o agente ao processo do app.
2. **Ferramentas desenhadas para modelo**, não espelho 1:1 da REST: saídas compactas e estáveis (*outline* com
   `[nodeId]`, marcas ✕ Δ SIM ↪ ⧗), descrições que dizem quando usar e o que encadear, `diagnose_failure` como
   porta de entrada, `structuredContent` para encadear, saída limitada com aviso.
3. **Somente leitura por padrão.** `dispatch_endpoint` e as ferramentas que alteram mocks só existem com
   `TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true`; anotações MCP (`readOnlyHint`, `destructiveHint`, `openWorldHint=false`).
4. **Política de dados explícita** (`TRACE2LOCAL_MCP_DATA`): `structural` (padrão) envia estrutura, tempos, erros e
   logs já redigidos; `full` inclui corpos de payload e valores antes/depois.
5. **Transportes**: stdio (padrão; requisições em virtual threads) e Streamable HTTP mínimo (POST JSON, sem SSE) só
   em `127.0.0.1`, com validação de `Origin` e Bearer opcional.
6. **Prompts** que encadeiam as ferramentas com a disciplina do produto (evidência primeiro, fato ≠ hipótese).

## Alternativas descartadas

- **MCP embutido no servidor do app**: acopla versão do protocolo ao app, abre mais uma superfície no processo do dev
  e não serve ao Station multi-serviço. O cliente fino cobre os dois modos com um binário.
- **SDK oficial do MCP para Java**: dependências (Reactor, Jackson modules, servlet) maiores que todo o módulo; o
  subconjunto usado (initialize, tools, prompts, ping) é pequeno e testado aqui.
- **Mutações ligadas por padrão**: um agente autônomo disparando endpoints e trocando respostas de parceiros sem o
  dev saber contraria o local-first e a ADR-015.

## Consequências

- (+) Agentes passam a responder "por que falhou?" com a causa raiz observada e evidência navegável; validado na
  stack alvo (E2E 14/14: diagnóstico, comparação, laudo, variação sob demanda, limpeza).
- (+) A squad de agentes/harness pode orquestrar mocks e disparos com opt-in, sem tocar na UI.
- (−) Dados de execução vão para o modelo do agente — mitigado por opt-in no harness, modo `structural` padrão e
  redaction na origem; documentado em MCP.md.
- (−) Sem notificações em tempo real (sem SSE) — o agente usa `wait_for_execution`; reavaliar quando harnesses
  consumirem `notifications/resources/updated` de forma ampla.
