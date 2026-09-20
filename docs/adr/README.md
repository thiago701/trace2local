# Decisões de Arquitetura — TraceVanta

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
| [010](ADR-010-distribuicao-licenca-e-compatibilidade.md) | Apache-2.0, Central Portal, `tech.neural7.tracevanta`, SemVer a partir do 1.0 | **Aceita** (D-4 no GATE 1) | Médio |

> Todas as decisões do GATE 1 foram aprovadas em [GATE-1-DECISOES.md](GATE-1-DECISOES.md) e nenhuma permanece em "Proposta". Uma decisão só sai de "Aceita" com novo registro; decisão superada não é apagada: ganha status `Substituída por ADR-NNN` e o histórico fica.
