# AGENTS.md — guia para agentes (e humanos) que evoluem o Trace2Local

> Leia antes de alterar código. Vale para assistentes de código, agentes automatizados e contribuidores.
> Idioma do projeto: **pt-BR** (código em inglês onde é convenção Java; textos de UI, docs e mensagens em português).

## Mapa rápido

| módulo | papel |
|---|---|
| `trace2local-core` | modelo de execução (TVEM), ring buffer, `TraceAssembler` (inclui continuação tardia — ADR-014), `LogStore`, redaction |
| `trace2local-otel` | ponte OTel ↔ TVEM; **único** lugar com nomes de atributo OTel (`OtelAttributeNames` — ADR-008) |
| `trace2local-predictive` | Regras Assíncronas Preditivas, micro-decisões (Jev/determinístico), laudo executivo/técnico (ADR-011/013) |
| `trace2local-server` | REST + SSE + estáticos; `RequestGuard` (ADR-015); `PredictiveService` |
| `trace2local-mocks` | Mock Connect (ADR-016): source → transforms → sink no modelo do Kafka Connect, conselheiro de mocks, REST `/api/mocks` |
| `trace2local-mcp` | servidor MCP (ADR-017): cliente fino da API local para agentes — somente leitura por padrão |
| `trace2local-ui` | UI Resonance (ES modules, zero dependência externa, CSP estrita) |
| `trace2local-lambda` / `-station` / `-aws` / `-jdbc` / `-spring-boot-starter` / `-testing` | modos e integrações |
| `trace2local-architecture` | guarda-rails: ArchUnit, ACL de atributos OTel, UI offline, conformidade de CSP |
| `examples/*` | demos reais (LocalStack) — nada da lib depende deles; **stack alvo**: `examples/finance-pix` |

## Comandos

```bash
AWS_REGION=us-east-1 mvn -B install                      # tudo (unitários + guarda-rails)
mvn -pl trace2local-predictive test                       # benchmarks preditivo e Jev (offline, cassete)
mvn verify -Pit -pl examples/lambda-sqs,examples/order-service   # E2E com Testcontainers/LocalStack
cd examples/lambda-sqs && docker compose up               # caso real: Lambda + SQS (ESM) + CloudWatch
cd examples/finance-pix && ./scripts/up.sh && python3 scripts/journeys.py   # stack alvo: 12 jornadas, 6 níveis (aceite)
examples\finance-pix\scripts\up.cmd                    # stack alvo no Windows, só com Docker Desktop (-Down derruba)
cd scripts/ux-loop && node persona-loop-pix.mjs           # usabilidade por persona na stack alvo (aceite L7)
java -jar trace2local-mcp/target/trace2local-mcp-*-all.jar --help    # servidor MCP para agentes
python3 scripts/real-traces/capture.py --out DIR          # captura traces reais para o dataset
```

## Regras que não se negocia

1. **Local-first** (ADR-007/011): nada sai da máquina sem configuração explícita. Chaves só por ambiente — **nunca** em código, teste, cassete, log ou commit.
2. **UI offline e sob CSP estrita**: sem URL externa, sem `style=`/`on*=`/script inline, sem `eval`; estilo dinâmico via CSSOM (`el.style.x`); dados no DOM só por `textContent`.
3. **ACL OTel** (ADR-008): literais `"db.*"`, `"faas.*"`, `"server.*"`, `"aws.*"`… só em `trace2local-otel`.
4. **Nunca bloquear a aplicação**: trabalho pesado sai do caminho do span (fila limitada, descarte contado).
5. **Honestidade**: o que não foi observado é declarado; hipótese nunca é apresentada como fato.
6. Mutações na API exigem `X-Trace2Local: 1` (ADR-015) — clientes e testes também.
7. **Aceite multinível** ([docs/qa/ACEITE.md](docs/qa/ACEITE.md)): mudança em coleta, árvore, UI, mocks ou MCP só entra com L0–L7 verdes — jornadas da stack alvo em JVM **e** nativo, loop de personas e contraste medido. Achado vira correção + trava de regressão no mesmo PR.
8. **Simulado nunca se passa por real** (Mock Connect): selo SIM/↪, atributo `t2l.mock`, narrativa e journal; roteamento de mock é sempre opt-in.
9. **MCP somente leitura por padrão**: ferramenta nova que altera o app/mocks nasce atrás de `TRACE2LOCAL_MCP_ALLOW_MUTATIONS`.

---

## Predictive Async Rules — Continuous Evolution

As Regras Assíncronas Preditivas ([docs/PREDICTIVE.md](docs/PREDICTIVE.md)) só têm valor enquanto forem **precisas, explicáveis e baratas**. Quem altera o módulo `trace2local-predictive` assume estes deveres contínuos:

1. **Medir antes de afirmar.** Toda mudança em analisador, limiar, vocabulário ou política de fusão roda `PredictiveBenchmarkTest`, `RealTraceRegressionTest` e `JevMicroDecisionBenchmarkTest`; os relatórios em `docs/qa/` são atualizados no mesmo PR.
2. **Nenhuma regra entra só porque parece interessante.** Regra nova exige hipótese escrita, cenário que a dispara **e** cenário-controle que não deve dispará-la, e passa pelo processo de promoção abaixo.
3. **Zero falso positivo em controle.** Um FP em cenário-controle bloqueia o merge; o controle nunca é afrouxado para "passar".
4. **Todo erro real vira caso de teste.** Falso positivo/negativo visto em uso (feedback DISMISS, issue, loop de validação) entra no dataset — sintético ou trace real capturado (sem dados pessoais).
5. **Evidence First sempre.** Insight sem evidência navegável (execução/passo/arquivo:linha/componente) não é emitido; observação ≠ correlação ≠ hipótese ≠ recomendação.
6. **Confiança honesta.** A faixa exibida deve refletir a força real do sinal; não inflar confiança para subir no ranking.
7. **Não-bloqueio verificado.** `submit` permanece O(1) e sem bloqueio; cada analisador respeita o timeout; o tempo médio por analisador aparece em `/api/intelligence` e não pode regredir sem justificativa.
8. **Privacidade por construção.** Nenhum analisador ou pergunta de modelo envia código-fonte, trace, payload ou segredo para fora sem configuração e autorização explícitas; ao acrescentar campos ao estado enviado ao Jev, revisar o `EgressSanitizer` e o teste de sanitização.
9. **Modelos são re-medidos a cada versão.** Mudou o modelo Jev (ou o LLM de explicação)? Regravar o cassete com `JEV_API_KEY` e revisar a `FusionPolicy` por família com os números novos.
10. **O feedback do dev manda.** Precisão observada (Beta por analisador) abaixo do limiar por duas semanas ⇒ recalibrar, rebaixar a severidade ou aposentar a regra.
11. **Aposentar é tão legítimo quanto criar.** Regra que não paga seu custo (FP, tempo, ruído) é removida com registro no CHANGELOG e no catálogo.
12. **Custo sob controle.** Chamadas, tokens e custo estimado do Jev ficam visíveis no Painel; orçamento e disjuntor nunca são removidos.
13. **Documentação viva.** Catálogo (PREDICTIVE.md §8), benchmarks e ADRs andam junto com o código; decisão estrutural nova ⇒ ADR.
14. **Validação com casos reais recorrente.** A cada ciclo relevante, rodar o cenário real (`examples/lambda-sqs`) e o loop de usabilidade; achados viram correções + travas de regressão (ver [docs/qa/VALIDACAO-CASOS-REAIS.md](docs/qa/VALIDACAO-CASOS-REAIS.md)).

### Processo de promoção experimental

```
hipótese → cenário injetado (+ controle) → implementação atrás de flag
        → medição: precisão · recall · FPR · custo (tokens/chamadas) · tempo por análise · impacto (severidade × frequência)
        → decisão: PROMOVER | ITERAR | DESCARTAR  (registrada no PR e no CHANGELOG)
```

| critério para PROMOVER | limiar |
|---|---|
| precisão nos cenários da regra | ≥ 0,90 |
| recall nos cenários da regra | ≥ 0,80 |
| falso positivo em cenários-controle | **0** |
| tempo médio por execução analisada | ≤ 5 ms (EXECUTION) · ≤ 50 ms (CORPUS/PROJECT) |
| egress | nenhum além do estado estrutural já aprovado |
| explicabilidade | observação + evidência + recomendação acionável |

Regras experimentais ficam desligadas por padrão (`TRACE2LOCAL_PREDICTIVE_DISABLED` / `disabled` na config) até a promoção.
