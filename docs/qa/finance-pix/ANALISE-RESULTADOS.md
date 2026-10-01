# Análise dos resultados na stack alvo — achados, correções e próximos passos

> Stack: API Gateway (OpenAPI) → Lambda **Java 25** (JVM jlink e **GraalVM nativo**, `provided.al2023`) → DynamoDB ·
> Postgres (RDS) · SQS · SNS · 5 parceiros HTTP · Terraform · LocalStack 4.9. Cenário: [examples/finance-pix](../../../examples/finance-pix/README.md).
> Cada achado abaixo virou **correção + trava de regressão** (teste, jornada ou check do loop de usabilidade).

## 1. Coleta e montagem da árvore (o que estaria errado sem a validação)

| # | sintoma observado | causa | correção | trava |
|---|---|---|---|---|
| 1 | **Nenhum trace das Lambdas chegava** | API Gateway do LocalStack envia `X-Amzn-Trace-Id …Sampled=0`; o sampler `parentBased` obedecia e descartava tudo | sampler `alwaysOn` no runtime Lambda e no modo embedded (ferramenta local: amostragem é decisão do dev) | teste do runtime |
| 2 | Raiz **ÓRFÃ** falsa | o pai é o API Gateway (não instrumentado) | regra de raiz de entrada: um único órfão LAMBDA/HTTP_SERVER sem outra parte conhecida do trace é raiz legítima (`t2l.entry.remote_parent`) | `LateContinuationTest`, J1 L2 |
| 3 | Consumidor do SNS pendurado no passo de negócio; nó SNS órfão; execução PARTIAL | o SDK não injetava o contexto nos atributos de mensagem | propagação de mensageria pelo propagador configurado (`setUseConfiguredPropagatorForMessaging`) + `Trace2LocalMessaging` (SQS/SNS) | J3 L4 |
| 4 | Raiz rotulada só com o nome da função ou `<unspecified span name>` | gatilho da Lambda sem semântica | `LambdaTriggerSemantics`: `pix-api · POST /pix/transfers`, `pix-settlement · SQS pix-settlement`, `pix-notifier · SNS pix-events PIX_SETTLED`; status HTTP ≥ 500 = ERRO, 4xx ≠ erro | J1/J7 L2 |
| 5 | SQL rotulado só pela tabela | `sqlTarget` sem operação | `SQL: UPDATE accounts` | J3 L2 |
| 6 | Execução **fantasma "em curso"** na UI do Station | o lote OTLP traz os filhos antes da raiz: a execução nascia com id provisório e era renomeada em silêncio | evento `execution.renamed`; a UI troca a chave sem perder seleção | `LateContinuationTest.provisionalIdIsRenamed…`, loop P4 |
| 6b | Regra preditiva **IDEM-002** acusava "mesma entidade processada duas vezes" pelo consumidor de liquidação | o Δ inferido do JDBC guarda o WHERE parametrizado (`transfer_id = ? …`): transferências diferentes tinham a mesma "chave" | chave com placeholder não identifica entidade (`IdempotencyAnalyzer.concreteKey`) | cenário-controle `controle-sql-inferido-chave-template` no benchmark (0 FP) |
| 6c | **SEC-PII-001** acusava `owner.name`/`receiverName` "sem redação" | o valor já vinha mascarado pelo DICT ("J*** S***"); a regra olhava só o nome do campo | valor mascarado (asteriscos, `[REDACTED]`, `[OCULTO]`) não é vazamento | controle `controle-dado-pessoal-ja-mascarado` (0 FP) |
| 6d | Homologação de um Pix perfeito: "apta com **ressalvas** (3 regras violadas)" | (1) siglas em caixa alta no glossário ("API de iniciação… (API Gateway)", "SPI", "KYC") eram lidas como estados esperados; (2) regras sobre o caminho de falha ("falha no provedor … deve ficar FAILED", "SPI fora do ar faz a mensagem voltar para a fila") eram cobradas num fluxo sem falha | estado = valor observado nos deltas ou forma de enum; regra condicionada a falha sem falha no escopo = inconclusiva ("exercite o caso negativo") | `DecisionEngineTest.acronymsInTheRuleAreNotTakenAsExpectedStates`, `…ruleAboutTheFailurePathIsNotViolatedWhenNothingFailed`; Jev rotulado inalterado |
| 7 | Notificador `PARTIAL` com 2 raízes órfãs após reiniciar o Station | mensagens reentregues cujo produtor não foi observado (Station reiniciado no meio) | **comportamento correto e honesto** (aviso "contexto possivelmente perdido") — registrado como limitação | — |

## 2. Integrações para a stack alvo (o que faltava para instrumentar sem esforço)

| # | necessidade | entrega |
|---|---|---|
| 8 | Java 25 no LocalStack (sem runtime `java25`) | runtime custom `provided.al2023` com laço próprio da Runtime API; JRE por **jlink** a partir do Temurin (o JDK GraalVM inflava a imagem com `libjvmcicompiler`, +41 MB) e arquivo CDS |
| 9 | Nativo | treino com `native-image-agent` contra o compose real → `reachability-metadata.json`; compilação em `native-image-community:25` (glibc 2.34 = AL2023); o treino limpa o acervo do Station ao final |
| 10 | Contexto de trace a partir de eventos | `Trace2LocalTraceContext`: `traceparent`/`baggage` de API Gateway, SQS e SNS + `AWSTraceHeader` |
| 11 | Chamadas a parceiros | `Trace2LocalHttp` (payload com redaction, marca de mock, roteamento opcional para o Mock Connect) |
| 12 | Passos de negócio | `Trace2LocalBusiness` (spans de negócio sem citar nomes de atributos OTel — ADR-008) |
| 13 | Produção sem custo | sem endpoint do Station o runtime vira *pass-through* (sem exporter, sem thread) |
| 14 | Flush que falha calado | orçamento de flush + `WARN Trace2Local: o envio ao Station não confirmou…` |
| 15 | Δ do JDBC na Lambda | `TRACE2LOCAL_JDBC_MUTATION_CAPTURE=inferred` por variável de ambiente |
| 16 | Terraform travando no LocalStack | provider AWS fixado em `~> 5.100` (o 6.x trava em `WarmThroughput` do DynamoDB) |

## 3. Mock Connect (novo — [ADR-016](../../adr/ADR-016-mock-connect.md))

| # | achado | correção |
|---|---|---|
| 17 | "aplicar" sem variações aplicava todas | só as variações escolhidas; vazio = mock base |
| 18 | campo categórico com 3 valores não era reconhecido como "decide o fluxo" | heurística *enum-like* + teste |
| 19 | treino do agent nativo poluía o conselheiro com hosts `localhost` | limpeza pós-treino + sugestão por host + título com o alvo quando a mesma API aparece em hosts diferentes |
| 20 | journal lido antes de gravado (teste intermitente) | espera curta no teste; o journal é gravado logo após a resposta (documentado) |

## 4. UI Resonance (achados do loop de personas)

| # | achado | correção |
|---|---|---|
| 21 | disparo pelo contrato (Station) não abria a execução | a UI segue o `traceId` devolvido pelo disparo |
| 22 | narrativa "Chamada externa GET **para ao serviço**." | nota nomeia o parceiro, a rota, o status e se a resposta foi **simulada** |
| 23 | Anatomia com componentes fantasmas do IaC (`dynamodb:transfers`, `lambda:fn`) | índice de infra usa o **nome real** do recurso; nome dinâmico (`each.key`, `${…}`) não vira "nunca observado" |
| 24 | Δ SQL "fidelidade INFERRED" sem explicação | rótulos de fidelidade + aviso do que não foi capturado |
| 25 | trocar Sempre/Sob demanda rolava a tela; repintura em segundo plano trocava o nó sob o clique | troca no lugar, rolagem/foco preservados, repintura só se o dado mudou |
| 26 | sugestão já resolvida continuava ALTA e vermelha no contador | marcada RESOLVIDA, vai para o fim; contador só de pendentes |
| 27 | minimapa sobre a legenda com o inspetor aberto; visão ativa fora da vista em 390 px; `--faint` 4,1:1 sobre `--hover`; emojis como ícones | minimapa some com o inspetor; barra rola até a visão ativa; `--faint #838da0` (≥ 4,7:1); ícones lineares |

## 5. Números

| | JVM 25 (jlink) | nativo GraalVM 25 |
|---|---|---|
| jornadas (61 verificações, 6 níveis) | **61/61** | **61/61** |
| cold start (1ª chamada, ponta a ponta) | 2 191 ms | **808 ms** |
| POST quente p50 / p95 | 228 / 288 ms | **180 / 211 ms** |
| pacote | 68 MB | **26 MB** |

Usabilidade: **69/69** (6 personas, contraste 0 falha, 0 erro de console, 0 violação de CSP). Detalhes em [ACEITE.md](../ACEITE.md).

## 6. Próximos passos recomendados

1. **Rota do contrato nas chamadas externas**: casar a URL com o OpenAPI do parceiro (o `ContractCatalog` já indexa)
   para preencher `http.route` no cliente — narrativa e agrupamento por operação ficam exatos.
2. **Acervo persistente opcional no Station** (hoje em memória): reinício no meio de um fluxo gera execuções PARTIAL honestas, mas sem o produtor.
3. **Roteamento de mock para clientes não-Java** (sidecar/proxy explícito) — hoje: URL base.
4. **Servidor MCP** do Trace2Local para agentes de código (consultar execuções, insights e mocks; ver `trace2local-mcp`).
5. **SLO por parceiro** no anel externo da Anatomia (o conselheiro já mede p95 por host).
