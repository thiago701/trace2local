<!-- gerado por JevMicroDecisionBenchmarkTest — não editar à mão; rode o teste para atualizar -->
# Benchmark Jev — micro-decisões da lib

> Reproduzir offline: `mvn -pl trace2local-predictive test -Dtest=JevMicroDecisionBenchmarkTest` (replay do cassete `src/test/resources/jev-cassette-benchmark.jsonl`).
> Ao vivo (regrava o cassete): `JEV_API_KEY=… mvn … -Dtest=JevMicroDecisionBenchmarkTest`.
> **Aviso de método:** o motor determinístico foi ajustado com este mesmo gabarito — leia a coluna "determinístico" como teto otimista; a coluna Jev é independente.

Fonte das respostas do modelo: **Jev API ao vivo (gravando cassete)** · 106 itens rotulados · 60 requisições · 9 ms no total

| tarefa | n | determinístico | Jev | cascata τ=0,5 | τ=0,7 | τ=0,8 | τ=0,9 | Jev conf≥0,9 (n / acerto) | **política do produto** |
|---|---|---|---|---|---|---|---|---|---|
| classificação de log | 36 | 0.94 | 0.94 | 0.94 | 0.97 | 0.97 | 0.97 | 29 / 1.00 | **0.97** |
| dado pessoal (PII) | 28 | 1.00 | 0.93 | 1.00 | 1.00 | 1.00 | 1.00 | 6 / 1.00 | **1.00** |
| divergência IaC intencional? | 12 | 0.75 | 0.58 | 0.83 | 0.75 | 0.83 | 0.75 | — | **0.75** |
| papel arquitetural | 12 | 0.75 | 0.83 | 0.92 | 0.92 | 0.92 | 1.00 | 8 / 1.00 | **1.00** |
| desfecho da execução | 10 | 1.00 | 0.50 | 0.50 | 0.50 | 0.50 | 0.50 | 10 / 0.50 | **1.00** |
| veredito de regra (homologação) | 8 | 1.00 | 0.13 | 0.13 | 0.13 | 0.13 | 0.13 | 8 / 0.13 | **1.00** |
| **TOTAL** | 106 | 0.92 | 0.78 | 0.84 | 0.84 | 0.85 | 0.85 | 61 / 0.80 | **0.96** |

## Erros do Jev (para calibrar a fusão)

| tarefa | pergunta | esperado | Jev | conf. | determinístico |
|---|---|---|---|---|---|
| classificação de log | Classify this log line (level INFO): "Starting OrderServiceApplication using Java 21.0.10 … | diagnostico | plataforma | 0.75 | evento-de-negocio |
| classificação de log | Classify this log line (level INFO): "Tomcat started on port 8080 (http) with context path… | diagnostico | plataforma | 0.51 | diagnostico |
| dado pessoal (PII) | The JSON field 'rg' most likely holds personal data about a person (PII). | sim | não | 0.10 | sim |
| dado pessoal (PII) | The JSON field 'region' most likely holds personal data about a person (PII). | não | sim | 0.10 | não |
| divergência IaC intencional? | This difference between environments is an intentional, environment-specific setting (for … | não | sim | 0.36 | não |
| divergência IaC intencional? | This difference between environments is an intentional, environment-specific setting (for … | não | sim | 0.12 | não |
| divergência IaC intencional? | This difference between environments is an intentional, environment-specific setting (for … | não | sim | 0.10 | não |
| divergência IaC intencional? | This difference between environments is an intentional, environment-specific setting (for … | não | sim | 0.76 | não |
| divergência IaC intencional? | This difference between environments is an intentional, environment-specific setting (for … | não | sim | 0.42 | sim |
| papel arquitetural | What is the architectural role of step 7 ('OrderService.create')? | orquestracao | persistencia | 0.85 | orquestracao |
| papel arquitetural | What is the architectural role of step 8 ('ProcessarPagamento')? | orquestracao | integracao-externa | 0.39 | orquestracao |
| desfecho da execução | What best describes the outcome of this execution? | recusa-protegida | falha-tecnica | 1.00 | recusa-protegida |
| desfecho da execução | What best describes the outcome of this execution? | falha-de-negocio | falha-tecnica | 1.00 | falha-de-negocio |
| desfecho da execução | What best describes the outcome of this execution? | falha-de-negocio | falha-tecnica | 1.00 | falha-de-negocio |
| desfecho da execução | What best describes the outcome of this execution? | falha-de-negocio | falha-tecnica | 1.00 | falha-de-negocio |
| desfecho da execução | What best describes the outcome of this execution? | falha-de-negocio | falha-tecnica | 1.00 | falha-de-negocio |
| veredito de regra (homologação) | Business rule 'idempotencyguard': "Guarda de idempotência — a mesma chave só grava uma vez… | respeitada | nao-exercitada | 0.96 | respeitada |
| veredito de regra (homologação) | Business rule 'confirmarpagamento': "Confirma o Pix (regra: só um pagamento PENDING pode s… | respeitada | nao-exercitada | 0.96 | respeitada |
| veredito de regra (homologação) | Business rule 'confirmarpagamento': "Confirma o Pix (regra: só um pagamento PENDING pode s… | violada | nao-exercitada | 0.96 | violada |
| veredito de regra (homologação) | Documented behaviour 'order-processor': "Função que recebe o pedido, grava no DynamoDB e p… | respeitada | nao-exercitada | 0.96 | respeitada |
| veredito de regra (homologação) | Documented behaviour 'order-processor': "Função que recebe o pedido, grava no DynamoDB e p… | violada | nao-exercitada | 0.96 | violada |
| veredito de regra (homologação) | Documented behaviour 'order-billing': "Consumidor que cobra o pedido e o marca como BILLED… | respeitada | nao-exercitada | 0.96 | respeitada |
| veredito de regra (homologação) | Documented behaviour 'order-billing': "Consumidor que cobra o pedido e o marca como BILLED… | violada | nao-exercitada | 0.96 | violada |

Status do motor: {mode=record, effectiveMode=record, keyConfigured=true, egressEnabled=true, egress=structural, endpointHost=api.typesafe.ai, model=jev-latest, acceptThreshold=0.8, circuit=fechado, liveCalls=0, liveFailures=0, avgLatencyMs=0, inputTokensToday=0, maxInputTokensPerDay=5000000, estimatedCostTodayUsd=0.0, lastFailure=null, answersByEngine={jev-replay=106}, cassette={hits=106, misses=0, file=src/test/resources/jev-cassette-benchmark.jsonl, entries=106}, deterministicVersion=t2l-deterministic-1.0}
