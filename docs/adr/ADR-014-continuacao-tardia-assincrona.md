# ADR-014 — Continuação tardia: o consumidor assíncrono real volta para a árvore do produtor

- **Status:** Aceita (2026-09-30)
- **Relacionadas:** SPEC §4.11 (correlação assíncrona), ADR-006 (quiescência), ADR-012 (logs)

## Contexto

A SPEC §4.11 promete que o consumidor de uma fila aparece **na mesma árvore** do produtor. Os testes de integração validavam isso com o consumidor chamado logo em seguida, dentro da janela de quiescência. **O caso real quebrou a promessa**: com `event source mapping` SQS → Lambda no LocalStack, o consumidor roda de 1 a 10 s depois (polling da fila + *cold start* da JVM). O produtor já tinha sido concluído e o consumidor nascia numa execução separada, `PARTIAL`, com raiz `ORPHANED` e aviso "contexto possivelmente perdido" — falso e confuso.

## Decisão

**Fusão na conclusão.** Quando uma execução vai ser concluída com órfãos, o `TraceAssembler` procura uma execução **já concluída do mesmo `traceId`**, dentro da janela `lateContinuationMs` (padrão 10 min; `TRACE2LOCAL_LATE_CONTINUATION_MS`; 0 desliga), que **contenha o pai** de algum órfão. Havendo, ela é "descongelada" (mesmo `executionId`), recebe os nós, é concluída de novo e um evento **`execution.merged`** avisa a UI para descartar a execução tardia e recarregar a do produtor.

- **Robusto à ordem do lote OTLP** (filho chega antes do pai): a decisão é tomada na conclusão, não no primeiro span.
- **Sem fusão por coincidência**: mesmo `traceId` sem elo causal (ex.: cliente externo reaproveitando `traceparent`) continua separado.
- **Duração e início** passam a ser a janela real dos spans (o início usa o span mais antigo, não o primeiro evento processado — no ingest OTLP o primeiro evento é o FIM de algum span).
- **Efeitos derivados atualizados**: o histórico substitui a amostra da execução; insights por-execução que a re-análise não sustenta são **retratados** (ex.: "publicação sem consumidor" depois que o consumidor apareceu).

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| Aumentar a quiescência | Atrasa toda execução; não resolve atrasos de minutos (retries/visibilidade) |
| Reabrir no primeiro span tardio | Falha quando o filho chega antes do pai no lote OTLP |
| Ligar execuções na UI | A árvore continuaria quebrada para analisadores, histórico e exportação |

## Consequências

No caso real, os 3 pedidos felizes passaram de 2 execuções (uma `PARTIAL`) para **1 árvore de 5 passos** com a espera na fila medida (5–9 s com *cold start*), e as Regras Preditivas passaram a atribuir corretamente a latência à fila (`PERF-ASYNC-001`). Testes: `LateContinuationTest` (fusão com filho antes do pai, sem fusão por coincidência, janela desligada). **Custo:** a execução do produtor muda de forma depois de concluída (a UI e os consumidores do SSE precisam tratar `execution.merged`).
