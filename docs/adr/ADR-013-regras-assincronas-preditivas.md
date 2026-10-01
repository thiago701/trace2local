# ADR-013 — Regras Assíncronas Preditivas como submódulo plugável e não bloqueante

- **Status:** Aceita (2026-09-30)
- **Relacionadas:** ADR-006 (backpressure), ADR-007 (local-first), ADR-011 (micro-decisões), ADR-014 (continuação tardia)
- **Detalhamento técnico:** [docs/PREDICTIVE.md](../PREDICTIVE.md)

## Contexto

O Trace2Local mostra **o que aconteceu**. O pedido foi ir além: correlacionar sinais (trace, logs, dados, IaC, cobertura, histórico) e antecipar problemas — gargalo assíncrono, regressão, N+1, idempotência, resiliência, dado sensível em log, deriva de Terraform, cobertura abaixo do gate — **sem** pesar no caminho da aplicação, **sem** enviar código/trace para fora, e **sem** apresentar hipótese como fato.

## Decisão

1. **Novo módulo `trace2local-predictive`**, dependente só de `core` (+ Jackson e `java.net.http`). O servidor liga o pipeline ao assembler; nenhum outro módulo o conhece. Desligável por `TRACE2LOCAL_PREDICTIVE_ENABLED=false`.
2. **Pipeline assíncrono** `Trace → Correlação → Detecção → Predição → Insight → Recomendação`: `submit` é um `offer` O(1) numa fila limitada (descarte contado, nunca bloqueia — medido: média < 1 µs); um worker em *virtual thread* faz lotes com *debounce*, analisadores rodam com paralelismo limitado e **timeout por analisador**; análises de acervo (CORPUS) com *debounce*; análise de projeto (PROJECT) reexecutada por *fingerprint* de mtime.
3. **Contrato `PredictiveAnalyzer`** (`name`, `scope` EXECUTION/CORPUS/PROJECT, `analyze(AnalysisContext)`), carregado também por `ServiceLoader` (plugins de terceiros).
4. **`Insight` com natureza explícita**: `FACT` · `CORRELATION` · `HYPOTHESIS`; campos `observation` (fato), `correlation`, `hypothesis`, `recommendations`, `evidence` (obrigatória — *Evidence First*, com referência navegável a execução/passo/arquivo:linha/componente), `confidence` + faixa (≥0,999 *fato observado*, ≥0,90 *forte evidência*, ≥0,75 *provável*, ≥0,55 *atenção*, abaixo *hipótese fraca*), `severity`, `category`, `affectedComponents`, `analyzer`, `decidedBy`.
5. **Ranking** = `1 − e^(−3·bruto)`, com bruto = impacto(categoria) × peso(severidade) × confiança × frequência(log ocorrências) × criticidade do fluxo × novidade × precisão observada do analisador (Beta(3,1) atualizada pelo feedback). **Deduplicação** por *fingerprint*, ocorrências acumuladas, **supressões** (`MUTE`; `EXPECTED` 7 dias; `DISMISS` 24 h ou até piorar de severidade), **retratação** quando a re-análise da mesma execução não sustenta mais o achado.
6. **Baseline local** (`FlowHistory`): amostras por fluxo (p50/p95, espera em fila, banco, forma), comparação *dia anterior × hoje* ou *janela*, persistida em `.trace2local/history/` (desligável: `TRACE2LOCAL_HISTORY=off`); a re-conclusão de uma execução **substitui** a amostra.
7. **Decisões finas via ADR-011** (Jev opcional, determinístico por padrão); **LLM só para explicar**, opcional, com endpoint externo bloqueado sem `TRACE2LOCAL_LLM_ALLOW_EXTERNAL=true` e acionável apenas por POST.
8. **Promoção experimental de regras** (AGENTS.md): hipótese → cenário injetado → medição (precisão, recall, FPR, custo, tempo) → decisão. O dataset inclui **cenários sintéticos com controles** e **traces reais** capturados do LocalStack.

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| Analisar no thread do span | Viola a promessa de overhead (ADR-006) |
| Motor de regras genérico (Drools etc.) | Peso de dependência e curva de aprendizado; os sinais são específicos |
| LLM/agente como detector | Caro, não determinístico, egress de contexto; reservado para explicação |
| Insights sem natureza/evidência | Gera desconfiança — "o que é fato aqui?" |

## Consequências

**Medido:** 33 cenários (13 de controle) — precisão 1,00, recall 1,00, 0 falso positivo em controle; traces reais: rodada limpa sem achado de idempotência e reprocessamento revelando IDEM-001/IDEM-002 verdadeiros. **Custos:** mais um módulo; precisão por regra precisa ser re-medida a cada regra nova; análises de projeto são heurísticas por regex (sem AST) — declarado.
