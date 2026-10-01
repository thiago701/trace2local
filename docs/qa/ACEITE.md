# Critério de aceite do Trace2Local — consistência multinível + usabilidade

> Vale para toda versão que muda coleta, montagem da árvore, UI, Mock Connect ou integrações.
> Uma versão só é aceita com **todos os níveis verdes** — inclusive em **JVM e nativo** na stack alvo.
> Última rodada: **2026-10-01** · stack alvo [examples/finance-pix](../../examples/finance-pix/README.md).

## Resumo da última rodada

| nível | o que prova | como | resultado |
|---|---|---|---|
| **L0** build e guarda-rails | unidades, contratos, ACL de atributos OTel (ADR-008), regras de dependência, UI offline, CSP | `AWS_REGION=us-east-1 mvn -B install` | **227 testes, 0 falhas** (1 ignorado: Jev ao vivo sem chave) em 16 módulos |
| **L1** contrato | status e corpo HTTP conforme `openapi/pix-api.yaml` | `scripts/journeys.py` | **13/13** |
| **L2** árvore | passos presentes/ausentes, ordem de negócio, estados, rótulos, raiz não órfã, mock marcado | idem | **17/17** |
| **L3** dados | o Δ da árvore bate com o Postgres e o DynamoDB **reais**; débito único | idem | **13/13** |
| **L4** assíncrono | SQS → liquidação → SNS → notificação na **mesma árvore**; reentrega visível | idem | **3/3** |
| **L5** mocks | conselheiro sugere, plug, variação sob demanda (baggage), falha transitória | idem | **7/7** |
| **L6** ferramenta | narrativa, laudo (Pix perfeito = apto, sem violação falsa), regras preditivas sem falso positivo, topologia (5 parceiros), anatomia sem fantasmas do IaC, IaC indexado, logs das 3 funções | idem | **8/8** |
| **L7** usabilidade | 6 personas + identidade, contraste medido, teclado, CSP, mobile | `scripts/ux-loop/persona-loop-pix.mjs` | **69/69** |
| **L7b** agentes (MCP) | cliente MCP real: diagnosticar falha, comparar, laudo, variação sob demanda, limpeza | `docs/qa/MCP-E2E.md` | **14/14** |
| **L8** desempenho | cold start e latência quente, JVM × nativo | `scripts/bench.py` | nativo 808 ms × JVM 2 191 ms (cold); p50 quente 180 × 228 ms |

**Stack alvo, JVM (Java 25 jlink) e nativo (GraalVM 25 AOT): 61/61 cada** —
[VALIDACAO-JVM.md](finance-pix/VALIDACAO-JVM.md) · [VALIDACAO-NATIVO.md](finance-pix/VALIDACAO-NATIVO.md) ·
[BENCH-COLD-START.md](finance-pix/BENCH-COLD-START.md) · [UX-LOOP-PERSONAS.md](finance-pix/UX-LOOP-PERSONAS.md).

## L0 — build e guarda-rails

| módulo | testes | | módulo | testes |
|---|---|---|---|---|
| core | 36 | | spring-boot-starter | 22 |
| server | 24 | | otel | 20 |
| mocks | 22 | | predictive | 17 (+1 ignorado) |
| mcp | 16 | | lambda | 15 |
| aws | 12 | | jdbc | 11 |
| station | 9 | | architecture | 8 |
| examples (order-service, lambda-sqs) | 10 | | testing / maven-plugin | 3 / 2 |
| **total** | **227** | | | |

Guarda-rails que **bloqueiam** o merge: `ProjectNamingTest` (nenhum resto do nome anterior do projeto; todo provider de `ServiceLoader` aponta para classe existente), `AntiCorruptionLayerTest` (nenhum literal `db.*`/`faas.*`/`server.*`/`aws.*`
fora de `trace2local-otel`, varrendo inclusive `examples/`), `DependencyRulesTest`, `UiOfflineTest` (zero URL externa),
`UiCspComplianceTest` (sem `style=`/`on*=`/script inline, sem `eval`).

Regressões novas desta rodada (cada achado virou teste): `LateContinuationTest.provisionalIdIsRenamedWhenTheRootArrivesAfterItsChildren`,
`StoryServiceTest.externalCallNamesThePartnerRouteStatusAndMockOrigin`, `InfraIndexerTest.terraformUsesTheRealResourceNameNotTheLocalLabel`,
`EmbeddedMockServerTest` (journal assíncrono sem corrida), `DecisionEngineTest.acronymsInTheRuleAreNotTakenAsExpectedStates` e
`…ruleAboutTheFailurePathIsNotViolatedWhenNothingFailed`, cenários-controle `controle-sql-inferido-chave-template` e
`controle-dado-pessoal-ja-mascarado` (benchmark preditivo 35 cenários / 15 controles, 0 FP), `McpServerTest` (16).

## L1–L6 — jornadas na stack alvo

12 jornadas reais (API Gateway → Lambda → DynamoDB/Postgres/SQS/SNS/parceiros) + visões da ferramenta, cada uma
conferida **contra o estado real** (psql no Postgres, `GetItem` no DynamoDB), não só contra o que a UI diz:

| jornada | o que exercita |
|---|---|
| J1 KYC indisponível | 502 com parceiro identificado; nó vermelho com a exceção de rede; Δ CREATE→DELETE da idempotência; conselheiro sugere mock (ALTA) |
| J2 plugar mock | binding RUNNING e rota publicada para o cliente |
| J3 aprovado e liquidado | ordem de negócio, SQS/SNS na mesma árvore, KYC **SIMULADO**, estado final SETTLED, débito único, logs das 3 funções |
| J4/J5 idempotência | reenvio devolve a mesma resposta sem efeitos; conflito 409 |
| J6/J7/J8/J9 regras | revisão por valor atípico, recusa por fraude com compensação (4xx ≠ ERRO), saldo insuficiente, chave inexistente |
| J10 consulta | 200/404 |
| J11 variação sob demanda | `/decision` decide o fluxo → variação "ausente" por baggage → IN_REVIEW; sem baggage, API real; repasse **não** marcado como simulado |
| J12 falha transitória | 503 só na 1ª chamada do SPI → retry por reentrega na mesma árvore (1ª vermelha, 2ª verde), débito único |

## L7 — usabilidade (critérios de aceite)

| critério | limiar | medido |
|---|---|---|
| UI pronta (deep link) | < 3 s | ~0,3 s |
| troca de visão / tecla 9 → Mocks | < 1 s | ~0,1 s / ~0,05 s |
| tarefas das 6 personas concluídas sem ajuda | 100 % | 100 % |
| contraste WCAG AA de todo texto visível auditado | 0 falha | 0 falha ([tabela de tokens](../UX-RESONANCE.md#contraste-medido-wcag-2x)) |
| botões com nome acessível | 100 % | 100 % |
| foco de teclado visível · teclas 1–9 · `prefers-reduced-motion` | sim | sim |
| alvo das ações principais | ≥ 32 px | 34 px |
| 390 px: sem rolagem horizontal, topo sem sobreposição, visão ativa à vista | sim | sim |
| erros de console · violações de CSP | 0 · 0 | 0 · 0 |
| CSP servida sem `unsafe-inline`; mutação sem `X-Trace2Local` | estrita · 403 | estrita · 403 |
| identidade: marca RESONANCE, títulos industriais, ícones lineares, zero emoji, nada do nome antigo | sim | sim |

## Como reproduzir

```bash
AWS_REGION=us-east-1 mvn -B install                                    # L0
cd examples/finance-pix && ./scripts/up.sh                             # stack (LocalStack, Postgres, parceiros, Station)
./scripts/build.sh && TERRAFORM=terraform ./scripts/deploy.sh jvm      # ou build-native.sh + deploy.sh native
python3 scripts/journeys.py --report target/validation/FINANCE-PIX-VALIDACAO.md   # L1–L6
python3 scripts/bench.py                                               # L8
cd ../../scripts/ux-loop && npm i && node persona-loop-pix.mjs         # L7
```

## Regras do aceite

1. Achado em qualquer nível vira **correção + trava de regressão** no mesmo PR (teste unitário, jornada ou check do loop).
2. Nenhum limiar é afrouxado para "passar"; mudança de limiar exige justificativa registrada aqui.
3. JVM **e** nativo: mudança no caminho do Lambda (runtime, reflexão, recursos) roda as jornadas nos dois modos.
4. Relatórios desta pasta são regenerados pelos scripts — nunca editados à mão.
