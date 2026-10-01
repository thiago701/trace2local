<!-- gerado por PredictiveBenchmarkTest (trace2local-predictive) — não editar à mão; rode o teste para atualizar -->
# Benchmark — Regras Assíncronas Preditivas

> Reproduzir: `mvn -pl trace2local-predictive test -Dtest=PredictiveBenchmarkTest` (determinístico, sem chave, offline).
> Vazão do `submit` (20 000 execuções, fila 64, analisador lento): `submit avg=0.595us max=3749.839us dropped=19935 n=20000` — o caminho da aplicação nunca bloqueia.

Motor de decisão: **Jev determinístico** (sem chave — reprodutível). Cenários: 35 (15 de controle).

**Precisão global 1.000 · Recall global 1.000 · FP em cenários-controle: 0**

## Por regra

| regra | TP | FP | FN | precisão | recall | FPR |
|---|---|---|---|---|---|---|
| ARCH-HOT-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| ASYNC-ORPH-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| DB-N1-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| DB-RED-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| IAC-DRIFT-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| IAC-SEC-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| IDEM-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| IDEM-002 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PERF-ASYNC-001 | 2 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PERF-OUT-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PERF-REG-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PRED-CONS-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PRED-ERR-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| PRED-SHAPE-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| RES-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| RES-002 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| SEC-LOG-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| SEC-PII-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| SEC-URL-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |
| TEST-COV-001 | 1 | 0 | 0 | 1.00 | 1.00 | 0.000 |

## Por cenário

| cenário | problema inserido | esperado | produzido | ms |
|---|---|---|---|---|
| sqs-lenta | Espera de 4,1 s entre publicação na fila e consumo pela Lambda | PERF-ASYNC-001 | PERF-ASYNC-001 | 1.73 |
| sqs-rapida | Controle: mesmo fluxo com 40 ms de espera | — | — | 1.14 |
| n-mais-1 | 121 consultas para 120 entidades (laço com GetItem) | DB-N1-001 | DB-N1-001 | 3.72 |
| consultas-redundantes | Mesmo GetItem de ORDER-9 em três camadas | DB-RED-001 | DB-RED-001 | 2.00 |
| ausencia-de-idempotencia | Reprocessamento sobrescreve ORDER-7 sem ConditionExpression | IDEM-001 | IDEM-001 | 0.81 |
| duplicacao-de-processamento | order-billing cobra ORDER-8 duas vezes (reentrega) | IDEM-002 | IDEM-002 | 0.97 |
| idempotencia-protegida | Controle: duplicado recusado pela guarda condicional | — | — | 0.67 |
| controle-sql-inferido-chave-template | Mesmo UPDATE parametrizado (transfer_id = ?) para duas transferências distintas | — | — | 0.73 |
| regressao-de-performance | Mediana do fluxo foi de ~420 ms (ontem) para ~890 ms (hoje) | PERF-REG-001 | PERF-REG-001 | 0.80 |
| execucao-fora-da-curva | Uma execução de ~2 s num fluxo de ~300 ms (banco lento) | PERF-OUT-001 | PERF-OUT-001 | 3.19 |
| ausencia-de-retry-e-circuit-breaker | Chamada ao parceiro PIX no caminho crítico sem proteção | RES-001 | RES-001 | 1.43 |
| resiliencia-presente | Controle: a classe de origem tem @CircuitBreaker | — | — | 0.45 |
| timeout-inadequado | Parceiro estoura o read timeout (há @Retry, mas o tempo esgota) | RES-002 | RES-002 | 0.69 |
| exposicao-de-dados-sensiveis | A aplicação loga e-mail e token em texto puro | SEC-LOG-001 | SEC-LOG-001 | 1.51 |
| dado-pessoal-em-payload | Nome e telefone trafegam sem redação | SEC-PII-001 | SEC-PII-001 | 1.63 |
| controle-dado-pessoal-ja-mascarado | Nome e CPF chegam mascarados do parceiro e são gravados assim | — | — | 1.01 |
| segredo-em-url | API key do parceiro na query string | SEC-URL-001 | SEC-URL-001 | 0.58 |
| alto-acoplamento | OrderHub participa de todos os fluxos e fala com 7 tabelas + 4 rotas | ARCH-HOT-001 | ARCH-HOT-001 | 2.69 |
| falhas-crescentes | Checkout passou de 0% para 75% de falhas nas últimas 8 execuções | PRED-ERR-001 | PRED-ERR-001 | 2.34 |
| consumidor-parou | Seis execuções com consumidor; a sétima publica e ninguém consome | PRED-CONS-001 | PRED-CONS-001 | 0.85 |
| publicacao-sem-consumidor | Primeira execução publica sem consumidor observado (JC-3) | ASYNC-ORPH-001 | ASYNC-ORPH-001 | 0.39 |
| mudanca-de-comportamento | O fluxo passou a gravar numa tabela nova (audit) | PRED-SHAPE-001 | PRED-SHAPE-001 | 1.36 |
| divergencia-terraform | dev/hml/prod divergem em escopo, timeout, flag e há senha literal | IAC-DRIFT-001, IAC-SEC-001 | IAC-DRIFT-001, IAC-SEC-001 | 26.57 |
| terraform-coerente | Controle: só nome de ambiente e dimensionamento diferem | — | — | 3.23 |
| quality-gate-quebrado | Cobertura 86% com gate jacoco:check de 90% | TEST-COV-001 | TEST-COV-001 | 3.35 |
| quality-gate-ok | Controle: cobertura 93% com gate de 90% | — | — | 0.56 |
| caminho-feliz | Controle: fluxo síncrono+assíncrono saudável | — | — | 0.84 |
| gargalo-assincrono-historico | Espera de 380 ms — abaixo do limiar absoluto, mas ~6× a mediana histórica da mesma fila | PERF-ASYNC-001 | PERF-ASYNC-001 | 1.35 |
| controle-5-leituras | Controle: 5 GetItem distintos (abaixo do limiar de N+1) | — | — | 0.74 |
| controle-batch | Controle: o mesmo relatório com BatchGetItem (já corrigido) | — | — | 0.44 |
| controle-ruido | Controle: fluxo ruidoso (260–450 ms) com execução de 420 ms | — | — | 0.28 |
| controle-localstack | Controle: cliente HTTP chamando o LocalStack (fronteira local) | — | — | 0.41 |
| controle-logs-inocentes | Controle: logs com palavras sensíveis mas sem valor sensível | — | — | 0.31 |
| controle-nomes-tecnicos | Controle: campos 'tableName/queueName/target/charge' não são dado pessoal | — | — | 0.64 |
| controle-criacao-nova-before-vazio | Chaves novas (ORDER-11, ORDER-12) com before = {} do interceptor | — | — | 0.41 |

Custo por análise: mediana 0.85 ms · p95 3.35 ms · máx 26.57 ms (worker assíncrono, fora do ingest).
