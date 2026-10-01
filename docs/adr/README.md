# Decisões de Arquitetura — Trace2Local

Registro das decisões estruturais (ADR = *Architecture Decision Record*). Uma decisão entra aqui quando **é cara de reverter**: muda o modelo de dados, a superfície pública, a topologia de processos ou a promessa do produto.

Formato: contexto → decisão → alternativas descartadas → consequências (inclusive as ruins).

| # | Decisão | Status | Reverter custa |
| :--- | :--- | :--- | :--- |
| [001](ADR-001-base-de-instrumentacao.md) | OpenTelemetry SDK como base, camada semântica própria por cima | Aceita | **Alto** — refaz toda a coleta |
| [002](ADR-002-dois-modos-de-execucao.md) | Dois modos: Embedded e Companion/Station | Aceita (D-2 no GATE 1) | **Alto** — muda a topologia |
| [003](ADR-003-canal-lateral-de-mutacao-de-dados.md) | Delta de dados fora do span, em canal lateral; `ReturnValues` elevado no DynamoDB | **Aceita** (D-3 no GATE 1) | Médio — canal é isolado |
| [004](ADR-004-transporte-sse.md) | SSE com um único `EventSource` por aba | Aceita | Baixo — contrato local |
| [005](ADR-005-empacotamento-ui-e-runtime-hints.md) | UI como WebJar + `RuntimeHintsRegistrar` desde o dia 1 | Aceita | Médio |
| [006](ADR-006-ring-buffer-e-backpressure.md) | Fila limitada com descarte na borda; sem LMAX Disruptor | Aceita | Baixo |
| [007](ADR-007-local-first-sem-autenticacao.md) | Loopback-only, sem autenticação, redaction na origem | Aceita | **Alto** — é a promessa do produto |
| [008](ADR-008-camada-semantica-anticorrupcao.md) | Nenhum nome de atributo OTel fora do módulo de ponte | Aceita | Médio |
| [009](ADR-009-baseline-jdk-e-matriz-de-suporte.md) | Baseline de bytecode e matriz de versões suportadas | **Aceita** (D-1 no GATE 1) | **Alto** |
| [010](ADR-010-distribuicao-licenca-e-compatibilidade.md) | Apache-2.0, Central Portal, `tech.neural7.trace2local`, SemVer a partir do 1.0 | **Aceita** (D-4 no GATE 1) | Médio |
| [011](ADR-011-motores-de-micro-decisao-jev.md) | Micro-decisões por modelos pequenos (Jev) com piso determinístico local e fusão calibrada | Aceita | Médio — SPI isola o motor |
| [012](ADR-012-logs-cloudwatch-na-linha-do-tempo.md) | Logs estilo CloudWatch presos ao span/RequestId, alinhados à invocação | Aceita | Médio — novo contrato de log |
| [013](ADR-013-regras-assincronas-preditivas.md) | Regras Assíncronas Preditivas como submódulo plugável e não bloqueante | Aceita | Médio — módulo isolado |
| [014](ADR-014-continuacao-tardia-assincrona.md) | Continuação tardia: consumidor assíncrono real fundido na árvore do produtor | Aceita | Médio — muda a forma de execuções concluídas |
| [015](ADR-015-endurecimento-corporativo-ui-api.md) | RequestGuard (Host allowlist, anti-CSRF, token opcional), CSP estrita, egress só por POST | Aceita — complementa a 007 | Baixo |
| [016](ADR-016-mock-connect.md) | Mock Connect: mocks de API plugáveis (source → transforms → sink, como o Kafka Connect) sugeridos pelos traces | Aceita | Médio — módulo isolado, contrato `X-Trace2Local-Mock` |
| [017](ADR-017-servidor-mcp.md) | Servidor MCP como cliente fino da API local, somente leitura por padrão | Aceita | Baixo — módulo isolado, sem dependência do core |

> Todas as decisões do GATE 1 foram aprovadas em [GATE-1-DECISOES.md](GATE-1-DECISOES.md) e nenhuma permanece em "Proposta". Uma decisão só sai de "Aceita" com novo registro; decisão superada não é apagada: ganha status `Substituída por ADR-NNN` e o histórico fica.
