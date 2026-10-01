# Validação com casos reais — Java + AWS Lambda + LocalStack

> Data: 2026-09-30 · Ambiente: Docker 29, **LocalStack 4.2** (DynamoDB, SQS, Lambda, CloudWatch Logs), runtime **java21** (imagem `public.ecr.aws/lambda/java:21`), Station em container, JDK 21.
> Cenário: [`examples/lambda-sqs`](../../examples/lambda-sqs/) (`docker compose up`) — `order-processor` (DynamoDB + SQS) → **event source mapping** → `order-billing` (DynamoDB), `idempotent-processor` (escrita condicional). Duas rodadas: **limpa** e **reprocessamento** (mesmos IDs).

## 1. Objetivos × evidência

| # | objetivo pedido | como foi validado | resultado | evidência |
|---|---|---|---|---|
| 1 | UX moderna, alta usabilidade, futurista/minimalista | UI v3 (Resonance) + loop por persona (dev 1º contato, dev async, QA/PO, tech lead) sobre o Station vivo | **42/42 checks** | [report](screenshots/v3-ressonancia/report.md) · telas 01–15 |
| 2 | Árvore aprimorada + linha do tempo didática com CloudWatch | árvore com mini-Gantt, papéis, Δ dados, caminho crítico, pílula "fila"; linha do tempo com capítulos, espera hachurada, cursor narrado e logs CloudWatch reais | START antes do log da função, capítulo "⧗ espera na fila", narração "AGORA" até o desfecho | telas 03, 06, 07 |
| 3 | Entender o ecossistema e o caminho dos dados em runtime (LocalStack) | Anatomia por zonas + árvore assíncrona completa + delta antes→depois | consumidor real **na mesma árvore**; delta `status ∅ → BILLED` | telas 01, 02, 05 |
| 4 | Visual inovador ("ressonância magnética") | anéis por zona, varredura viva, "contraste" (partículas na ordem real dos spans), cortes (árvore/tempo/laudo) | — | telas 01, 02 |
| 5 | Assistência Técnica + Executiva para homologação | Investigação: desfecho, prontidão, risco (motor + confiança), checklist, laudo Markdown; hotspots, caminho crítico, anomalias | recusa de crédito → "Falha de negócio · bloqueada"; reentrega → "Recusa protegida · apta · risco baixo" | telas 08–10 |
| 6 | Cruzar regras de negócio × fluxo da árvore | glossário montado no Station (`trace2local-business.md`) → veredito por regra + "ver no fluxo" | regra do order-processor **violada** (pedido recusado foi gravado antes da validação) | tela 08 |
| 7 | Simular em loop a usabilidade/transparência | `scripts/ux-loop/persona-loop.mjs` — 5 iterações com correções entre elas | 37 → 39 → 40 → 42/42 | seção 3 |
| 8 | Preparar para fronteiras externas | zonas `core/boundary/external/declared` na topologia; anel externo e "declarado no IaC" | legenda declara "fronteira externa: nenhuma chamada observada" | tela 01 |
| 9 | Validar com casos reais Lambda + LocalStack | compose real + ITs Testcontainers + dataset de traces reais | ITs lambda-sqs **4/4**, order-service **3/3**; `RealTraceRegressionTest` **4/4** | seção 2 |
| 10 | Inteligência ampliada com Jev | micro-decisões em 6 famílias; benchmark rotulado; Jev ao vivo em traces reais | política 0,96; real: 0 falhas, ~250 ms/lote, ~US$ 0,001 | [BENCHMARK-JEV](BENCHMARK-JEV.md) · [REAL-TRACES-JEV](REAL-TRACES-JEV.md) |
| 11 | Uso determinístico sem chave | padrão `auto → deterministic`; CI sem chave | idêntico na UI; benchmark em replay | `DecisionEngineTest` |
| 12 | Segurança para empresa corporativa | revisão + ADR-015 + guarda-rails | CSRF 403, rebinding 421, CSP sem violação | [SEGURANCA-CORPORATIVA](../SEGURANCA-CORPORATIVA.md) |
| 13 | Regras Assíncronas Preditivas | módulo isolado + 33 cenários + traces reais | precisão/recall 1,00; 0 FP em controle | [BENCHMARK-PREDITIVO](BENCHMARK-PREDITIVO.md) |

## 2. O que o caso real revelou (e o que foi corrigido)

Nenhum destes problemas aparecia nos testes sintéticos — todos vieram de rodar a lib contra Lambda/SQS/CloudWatch reais no LocalStack.

| # | achado | impacto | correção | trava de regressão |
|---|---|---|---|---|
| R1 | Consumidor via *event source mapping* chega 1–10 s depois (polling + cold start), **após a quiescência**: nascia execução separada `PARTIAL` com aviso falso de "contexto perdido" | promessa §4.11 quebrada; latência da fila invisível | **continuação tardia** (ADR-014): fusão na conclusão, robusta a filho-antes-do-pai, sem fusão por coincidência; evento `execution.merged` | `LateContinuationTest` (3) · `RealTraceRegressionTest` |
| R2 | No ingest OTLP o primeiro evento é o **fim** de um span: `startedAt` da execução ficava atrasado | logs com offset negativo; t0 errado na linha do tempo | início = span mais antigo | traces reais |
| R3 | `IDEM-001` disparava em chave **nova**: o interceptor DynamoDB devolve `before = {}` (mapa vazio do SDK) | falso positivo "sobrescreveu registro existente" em toda criação | analisador trata vazio como ausência + interceptor normaliza vazio → `null` | controle `controle-criacao-nova-before-vazio` no benchmark |
| R4 | LocalStack carimba START/END/REPORT **na ingestão** | narrativa com START depois do log da função | alinhamento às bordas do span da invocação, marcado `≈` com carimbo observado | check "START antes do primeiro log" no loop |
| R5 | Exceção do runtime chega **uma linha por evento**, sem nível | 8 linhas INFO de stack poluindo a história | dobra de stack trace + detecção de exceção ⇒ 1 evento `ERROR` | `CloudWatchLogsTailTest.foldsRuntimeStackTraceIntoOneErrorEvent` |
| R6 | Jev ao vivo divergiu do determinístico e **estava certo**: frames de stack lidos como "evento de negócio"; "reentrega ignorada" como "erro técnico"; recusa protegida com risco "alto" | laudo executivo pessimista; classes de log erradas | vocabulário de idempotência, detecção de frame, risco/prontidão de recusa protegida | 4 linhas reais viraram gabarito do benchmark Jev |
| R7 | Build incremental gerava **fat jar velho** (shade sobre jar já sombreado) | Station com classes antigas (risco "alto" voltou) | `maven-jar-plugin forceCreation` no Station e no bundle | verificado por inspeção de classe no jar |
| R8 | IT do order-service passou a receber 403 | — | comportamento esperado do RequestGuard: cliente envia `X-Trace2Local: 1` | `OrderJourneyIT` verde |
| R9 | Demo sem nenhum log; consumidor só simulado | linha do tempo vazia; ESM não exercitado | logs de negócio no stdout, consumidor real por ESM, glossário montado, Station só no loopback | compose |

### Achados do produto sobre o código de exemplo (mantidos como demonstração)

- **Reprocessamento sem guarda** (`IDEM-001`, alto): o `order-processor` regrava o pedido com `PutItem` incondicional — na 2ª rodada isso **apagou o `status: BILLED`** já gravado pelo consumidor.
- **Cobrança duplicada** (`IDEM-002`, alto): o `order-billing` aplicou efeito na mesma entidade em duas execuções.
- **Efeito parcial antes da validação** (risco alto + regra violada): o pedido recusado por crédito foi gravado no DynamoDB antes da recusa.
- **Latência dominada pela fila** (`PERF-ASYNC-001`): 98% do tempo entre a publicação e o início do consumidor (polling do ESM + cold start).
- O `idempotent-processor` (escrita condicional) **não** gerou achado: guarda funcionando = "Recusa protegida · apta · risco baixo".

## 3. Loop de usabilidade por persona

`scripts/ux-loop/persona-loop.mjs` (Playwright) contra o Station vivo, com os assets da árvore de trabalho servidos sobre os **cabeçalhos reais** (CSP incluída).

| iteração | resultado | o que mudou |
|---|---|---|
| 1 | travou no 2º passo | inspector cobria o passo seguinte da árvore → câmera passa a respeitar a área útil ao lado da gaveta + navegação por teclado validada |
| 2 | 37/40 | sobreposição em 390 px, 403 da sonda CSRF contado como erro, ordem START/app |
| 3 | 39/40 | grade com `minmax(0,1fr)`, topo mobile, pílula "⧗ fila", varredura só com execução viva, "FN" no lugar de "λ λ", legenda narrada da plataforma |
| 4 | 40/40 | anatomia enquadrada pela caixa real do desenho e fontes maiores; capítulo sintético "espera na fila" |
| 5 | **42/42** | gaveta do passo fecha em visões sem passo (Painel/Infra/Comparar); checks novos de sobreposição |

## 4. Como reproduzir

```bash
mvn -pl trace2local-station -am -DskipTests package
mvn -f examples/lambda-sqs/pom.xml -DskipTests package
cd examples/lambda-sqs && docker compose up            # rodada limpa
docker compose up --no-deps init-localstack           # reprocessamento (mesmos IDs)
# UI: http://localhost:19877/trace2local
cd ../../scripts/ux-loop && npm i && node persona-loop.mjs
python3 ../real-traces/capture.py --out /tmp/traces    # novos traces reais para o dataset
mvn -f ../../pom.xml verify -Pit -pl examples/lambda-sqs,examples/order-service   # E2E Testcontainers
```
