# Changelog

Todas as mudanças notáveis do TraceVanta são registradas aqui, desde o primeiro commit (ADR-010).
Formato: [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) · Versionamento: [SemVer](https://semver.org/) a partir do 1.0.0. **Em 0.x a API pode quebrar entre minors** (SPEC §11.3).

## [0.1.0-SNAPSHOT] — em desenvolvimento

### Loop cognitivo: encadeamento história↔nó + simulação por personas

- **Pesquisa de métodos de avaliação cognitiva** (heuristic walkthroughs para dev tools, linking & brushing em visões coordenadas, métricas GOMS/CTA) — referências em `docs/qa/EVIDENCIA-STORYTELLING-PAYMENTS.md`.
- **Encadeamento história↔nó**: numeração compartilhada entre o balão de nota do canvas e o passo da STORY; conector tracejado nó→nota; passos clicáveis (canvas + nó selecionado + pulso de 1,9 s); brushing reverso (nó selecionado destaca o passo); swatch de cor do kind em cada passo.
- **Simulação cognitiva automatizada por personas** no script de captura: PO (2 cliques, sem ruído técnico), DEV (1 clique do passo ao nó com pulso), QA (passo ⚠ FALHOU → inspector com erro) — todas passando.
- **Ruído do SDK removido da narrativa** (achado da persona PO): `StoryService.cleanErrorMessage` remove `(Service: …)`, `Request ID: …` e `(SDK Attempt Count: N)` — o passo agora diz só "este passo FALHOU: The conditional request failed." + teste unitário.
- Validação final: `mvn install` completo verde + E2E LocalStack (order-service 3/3, lambda-sqs 4/4) + captura v4/v5 com todas as checagens.

### Novo domínio demo + análise de clareza do storytelling

- **`examples/payment-service`**: novo projeto demo em domínio DIFERENTE (pagamentos Pix) — guarda de idempotência, confirmação com read-back e glossário próprio; prova que a narrativa é domínio-agnóstico (mesmo pipeline que explicou pedidos explica Pix sem mudança na lib).
- **Análise de UX do storytelling** com script dedicado (`capture-payment-story.mjs`): consistência UI↔API (5=5 labels idênticos), simplicidade (2 cliques, zero config) e heurísticas de clareza (passos legíveis, verbo de negócio, conclusão com status).
- **Melhorias de clareza aplicadas no loop** (achadas pela própria análise): passo com erro agora diz *"— este passo FALHOU: {mensagem}"*, leituras ganham *"(somente leitura)"*, e título/intro citam o endpoint ("Jornada externa — POST /pix").
- Evidências: `docs/qa/EVIDENCIA-STORYTELLING-PAYMENTS.md` + telas 19/20/21.

### v4 — Descoberta de negócio e storytelling (canvas para PO, dev e QA)

- **`StoryService` + `BusinessGlossary`** (tracevanta-server): descoberta da especificação/regras de negócio por **contexto** (kind + atributos OTel + mutação + erro), **engenharia reversa** (camelCase humanizado com mapa de verbos de negócio PT-BR) e **docs** (glossário opcional `tracevanta-business.md` no classpath — o time documenta o termo e a nota é sobrescrita).
- **`GET /api/executions/{id}/story`**: narrativa completa — intro (trigger), passos ordenados com ícone/kind/duração/mutação/erro e desfecho com status, duração e contagem de mutações.
- **Aba STORY na UI**: linha do tempo da narrativa + **COPIAR COMO MARKDOWN** (slides/PR/QA); **toggle NOTAS** desenha as anotações AO LADO de cada nó no canvas (balões tracejados) — a árvore vira ferramenta de apresentação sem sair do modo técnico.
- Glossários de demonstração no order-service e no lambda-sqs (guarda de idempotência explicada em linguagem de negócio); IT valida a nota do glossário e a conclusão da narrativa.
- Simplicidade preservada: sem glossário funciona por inferência; 2 botões novos; CSP/ADR-005 intactos. Validação no script de captura (storySteps, intro/conclusão, copyBtn, noteLines) + telas 17/18 + build completo verde.

### v3 — Dashboard, comparação e deep links (pesquisa de ferramentas similares)

- **Pesquisa aplicada**: estudadas .NET Aspire Dashboard (análogo local mais próximo), Jaeger (diff de traces), SigNoz/Grafana (vista unificada) — ver `docs/qa/EVIDENCIA-UX.md` com as referências.
- **Aba DASHBOARD**: agregados do acervo em tempo real — execuções concluídas, falhas (n + %), duração média, nós observados, avisos, top-5 mais lentas e falhas recentes clicáveis, distribuição por trigger. Tudo client-side a partir dos summaries existentes (zero configuração nova).
- **Aba COMPARAR (A/B diff)**: duas execuções lado a lado — Δ de duração (abs + %), Δ de nós, tabela de spans por rótulo com marcas NOVO/REMOVIDO/=, e mutações presentes só em A ou só em B. Regressões de performance/código saltam aos olhos em segundos.
- **Deep link `?execution=<id>`**: URL compartilhável que abre direto na execução; a seleção reflete na URL sem recarregar.
- Simplicidade preservada: 3 abas, zero propriedade nova, zero dependência nova, CSP/ADR-005 intactos; o fluxo CANVAS continua o padrão. Validação no script de captura (statCards/slowRows/compareRows/deep-link) + telas 15/16 + build completo verde.

### Fechamento de desvios da SPEC (uso corporativo)

- **`UpdateItem` before+after — DESVIO FECHADO** (`TraceVantaAws.instrumentWithReadBack`, opcional): a API do DynamoDB devolve UM conjunto por chamada; o novo modo faz `ALL_OLD` (before) + releitura pós-update **dentro do span** via cliente cru (sem span aninhado, correlação correta) → delta EXACT com before E after e deltas de campo. Validado no LocalStack real (J3: `before.pk` + `after.status=BILLED` + delta `status`). O padrão `instrument` permanece como antes (after EXACT, before declarado).
- **SSE `execution.completed` — DESVIO FECHADO** (§5.2): payload agora carrega campos FLAT `status`/`duration` (ISO-8601 `PT0.042S`)/`metrics` + o payload completo aninhado em `execution` (extensão compatível — a UI atual continua funcionando sem mudança).
- **`port=0` — descoberta programática**: `GET /api/meta` agora inclui a porta REAL (`"port": N`), essencial em ambientes corporativos com porta efêmera (CI, múltiplas instâncias).
- Tabela de desvios do README reescrita com status claro: FECHADO × rota de fechamento × adiado (springdoc, catálogo Lambda, before-image, ScopedValue — cada um com justificativa).

### Monitoramento de idempotência (E2E)

- **Cenário E2E de idempotência** (`examples/lambda-sqs`): `IdempotentProcessor` com guarda de idempotência (BUSINESS span `IdempotencyGuard` + escrita condicional `attribute_not_exists`) — 1ª chamada cria (delta `CREATE/EXACT`), 2ª chamada com a MESMA chave é recusada **sem efeito colateral** (3 chamadas → 2 itens no DynamoDB), e o canvas conta a história: guarda OK + nó DynamoDB **VERMELHO** com `ConditionalCheckFailedException` e **sem delta** (a prova visual de que nada foi escrito).
- **Ingest OTLP lê eventos `exception`**: `OtlpTraceReceiver` extrai `exception.type`/`exception.message`/`exception.stacktrace` dos eventos do span — antes, spans de terceiros (AWS SDK etc.) ficavam vermelhos SEM mensagem; agora o `ErrorInfo` chega completo ao inspector. Teste unitário + `IdempotencyJourneyIT` (4/4 ITs verdes no módulo).
- Evidências: `docs/qa/EVIDENCIA-IDEMPOTENCIA.md`, `evidence-idempotency-*.json`, telas 13/14.

### Estrutura profissional de repositório (OSS-ready)

- **Auditoria de domínio**: confirmado que nenhum módulo da lib carrega conceito de negócio (nada de "pedido"/"cliente" fora dos exemplos) — o núcleo é domínio-agnóstico por construção; documentado em `docs/ARQUITETURA.md`.
- **`docs/ARQUITETURA.md`**: visão de módulos, regras de dependência (travadas por ArchUnit), fluxo de runtime em mermaid, tabela de pontos de extensão (SPI) e modelo de segurança.
- **Maven Wrapper** (`mvnw`/`mvnw.cmd`, Maven 3.9.9) — build sem Maven instalado, como nos grandes repos Java.
- **`CONTRIBUTING.md`** (convenções, definição de pronto, release) e **`CODE_OF_CONDUCT.md`** (Contributor Covenant 2.1).
- **Templates de issue** (bug/feature) e **template de PR** + **Dependabot** (Maven + Actions, com bumps de OTel/AWS/Boot travados para auditoria manual).
- **README reescrito** no estilo dos repos mais populares: hero com badges, índice, features, demonstração com telas, quickstarts por modo, diagrama mermaid, tabelas de configuração/compatibilidade/desvios, links de documentação, contribuição e licença.

### UI v2 — redesign de UX/usabilidade (loop com evidências)

- **Cards de execução ricos** na lista de recentes: rótulo da raiz, badge de status textual (OK/FALHOU/PARCIAL/EM CURSO), chip de trigger colorido, duração + nº de nós + tempo relativo e barra de duração proporcional; busca/filtro por rótulo/status/trigger com estado vazio.
- **Waterfall no canvas**: cada nó ganha barra de tempo (total + self) proporcional à duração da execução; cards maiores com nome do kind por extenso, chip colorido e indicador de mutação (Δ).
- **Auto-fit**: primeira renderização de cada execução encaixa a árvore na viewport (zoom/pan do usuário preservados depois).
- **Resumo da execução no header do canvas**: status, trigger, duração, nº de nós e contador de avisos.
- **Inspector moderno**: seções colapsáveis (ATRIBUTOS colapsa quando > 6 com contador), filtro de atributos, botões COPIAR para erro/before/after/payload, erro em bloco vermelho destacado.
- **Toasts** não bloqueantes no lugar de `alert()`; **skeleton** de carregamento ao trocar de execução.
- **Legenda** de tipos de nó e **atalhos** em popovers; favicon local (fim do 404 de favicon.ico).
- **Acessibilidade/estética**: `:focus-visible`, `prefers-reduced-motion`, scrollbars estilizadas, design tokens consolidados, estados vazios.
- Restrições mantidas: ADR-005 (zero referência externa — `UiOfflineTest` verde) e CSP sem estilo/script inline (removeu inclusive o `style=` remanescente do estado vazio de endpoints).
- Validação: captura com **consistência UI↔API preservada** (labels idênticos) + checagens de UX v2 no script (`capture-lambda-station.mjs`) + zero erros de console. Plano e evidências: `docs/qa/UX-PLANO-MELHORIA.md`, `docs/qa/EVIDENCIA-UX.md`, telas 07–12.

### Segurança e profissionalização (revisão para entrega)

- **Token de ingest opcional** (`tracevanta.station.token` / `TRACEVANTA_STATION_TOKEN`): com token definido, `/v1/traces` e `/tvingest/v1/mutations` exigem `Authorization: Bearer` (comparação em tempo constante, 401 + desafio `WWW-Authenticate`); Lambda e starter enviam o header automaticamente; o Station avisa quando exposto sem token (ADR-007/§8.1). Testes: 401/200 no servidor + Bearer capturado no exportador OTLP.
- **Hardening HTTP**: `Referrer-Policy: no-referrer` em todas as respostas e `Cache-Control: no-store` nas respostas de API/estáticas (execuções carregam payloads — nunca cachear).
- **Redaction ampliada**: chaves `jwt`/`otp`/`totp`/`pwd`/`privateKey` + padrões de valor para tokens GitHub (`ghp_…`) e hashes bcrypt/argon2 (corpus de teste ampliado).
- **Auditoria de CVEs** (GitHub Advisory DB + NVD + OSV, set/2026): nenhuma versão pinada afetada por CVE conhecido — Jackson 2.22.2 e AssertJ 3.27.7 são exatamente as versões corrigidas (não rebaixar); nota de supply-chain registrada para jqwik 1.10.1 (protestware removido, sem CVE; dependência dev-only). Documentado em `SECURITY.md`.
- **`SECURITY.md`** com modelo de ameaças, limites declarados e processo de reporte; **`NOTICE`** Apache.

### Build profissional

- **`tracevanta-bom` completo**: agora exporta os artefatos do projeto E os BOMs de terceiros (OTel, AWS SDK, Jackson, Spring Boot, JUnit) + versões de teste — consumidor declara dependências SEM versão.
- **Enforcer ativo**: Maven ≥ 3.9, JDK ≥ 21, convergência de versões (`requireUpperBoundDeps`).
- **Builds reproduzíveis**: `project.build.outputTimestamp` fixo por release.
- **Metadados de publicação**: `scm`, `issueManagement`, `ciManagement`, `distributionManagement` (OSSRH) e **perfil `release`** (fontes + javadoc + assinatura GPG).
- **CI (GitHub Actions)**: matriz JDK 21/25 + job E2E (LocalStack via Testcontainers) para os dois exemplos.
- Exemplos consomem versões do BOM (sem pins duplicados de Testcontainers); artefatos do protótipo movidos para `docs/archive/`.

### Adicionado (projeto de teste/validação serverless)

- **`examples/lambda-sqs`** — novo projeto de teste e validação: **AWS Lambda (runtime java21) + DynamoDB + SQS no LocalStack**, configurado com a lib TraceVanta em modo Companion. O handler grava no DynamoDB via `TraceVantaAws.instrument` (delta EXACT) e publica no SQS (nó de produtor); o runtime Lambda abre o span raiz SERVER com `faas.name` (→ nó LAMBDA), correlaciona as mutações e faz flush síncrono OTLP + `/tvingest/v1/mutations` para o Station (ADR-002/§4.12).
- **`LambdaSqsJourneyIT`** (perfil `-Pit`, Testcontainers + Station em processo): invoca o handler como o runtime Lambda faria e verifica o item REAL no DynamoDB, a mensagem REAL na fila SQS e a árvore no Station — raiz `LAMBDA` "order-processor", trigger `LAMBDA_EVENT`, nó `DYNAMODB` com mutação `EXACT` (chave `ORDER-L1`) e nó `SQS`. Evidência: `docs/qa/evidence-lambda-sqs.json`.
- **Fluxo completo no emulador Lambda** (`docker-compose.yml` + `scripts/init-localstack.sh`): fat jar via maven-shade (Serviços unificados, `aws-lambda-java-core` provided), `create-function`/`invoke` reais no LocalStack 4.2 e Station em container com healthcheck.

### Corrigido (modo Companion Lambda)

- **Nó LAMBDA na árvore**: o `DefaultSemanticMapper` agora mapeia `faas.name`/`faas.invocation_id` → `NodeKind.LAMBDA` (antes caía em HTTP_SERVER).
- **Trigger e execution id no ingest OTLP do Station**: `tv.trigger` e `tv.execution.id` do span raiz agora são parseados (`TraceVantaAttributes.parseTrigger`) — execução Lambda aparece com `LAMBDA_EVENT` e o id do request.
- **Aviso `EVENTS_DROPPED` suprimido no caminho OTLP**: o protocolo não carrega evento de início de span, então a ausência do start é POR DESENHO (`SpanEndEvent.startDeliberatelyAbsent`) — execuções OTLP puras fecham `COMPLETED` sem falsos avisos de descarte (SPEC §5.3).
- **Runtime Lambda registra o SDK no `TraceVantaOtel`**: sem Spring na Lambda, o registro próprio era ninguém fazia — o interceptor lazy do AWS SDK resolvia o `GlobalOpenTelemetry` noop e os nós DynamoDB/SQS não apareciam.
- **`TraceVantaLambda.configFromEnv()` público** com fallback para a propriedade `tracevanta.station.endpoint` (testes) além da env `TRACEVANTA_STATION_ENDPOINT`.

### Corrigido (loop de melhorias do cenário lambda-sqs)

- **Duração de execução NEGATIVA** no modo Companion/Lambda (-255 ms): a duração era medida pela ordem de PROCESSAMENTO dos eventos — a mutação chega depois dos spans, mas é capturada DURANTE eles. Agora a duração é a JANELA DOS SPANS (min início → max fim), com piso em zero para relógios divergentes (I2). Teste de regressão em `TraceAssemblerTest`.
- **Identidade de execução estável**: um span tardio com outro `tv.execution.id` (ex.: consumidor SQS continuando o trace) renomeava a execução no meio do caminho — o primeiro id explícito (span raiz) agora vence.
- **Erro da Lambda mudo na árvore**: o runtime marcava o status ERROR sem descrição e o ingest OTLP constrói o `ErrorInfo` do `status.message` — agora o runtime grava `String.valueOf(t)` como descrição; a execução vermelha mostra a exceção.
- **Jornada de erro coberta** (J2): `fail=true` no evento lança após o PutItem — execução FAILED com a raiz vermelha e o ramo DynamoDB OK (sucesso parcial visível). Evidência: `evidence-lambda-sqs-failure.json` + telas 10/11.
- **J3 — consumidor SQS continua a MESMA árvore** (o "JC-3" do mundo Lambda, §4.11): novo hook `TraceVantaLambdaHandler.remoteParentOf(input, ctx)` (o span raiz da invocação vira filho do parent remoto) + `OrderBillingProcessor` parseando o `AWSTraceHeader`. Árvore fundida `LAMBDA → SQS → LAMBDA → DYNAMODB (UPDATE)` em UMA execução. Evidência: `evidence-lambda-sqs-consumer.json` + tela 12.
- **Descoberta do loop**: a instrumentação automática do AWS SDK v2 **não injeta `AWSTraceHeader` no SendMessage direto do SQS** (diferente do publish do SNS) — o span de produtor do demo é MANUAL (atributos `messaging.*`) e o header vai no atributo de sistema da mensagem.
- **Validação de consistência UI↔API**: `capture-lambda-station.mjs` compara os labels do canvas com a API REST do Station (contagem e textos) e falha o script se divergirem — evidência visual agora é verificada, não só capturada.

### Testado

- **E2E ponta a ponta** (`examples/order-service` → `OrderJourneyIT`, perfil `-Pit`, Testcontainers + LocalStack real): JC-1 disparada pelo Request Launcher da UI com delta EXACT e redaction; JC-2 confirm com `ConditionalCheckFailedException` na árvore; JC-3 ramo SNS ORPHANED; NFR-4 (disparo → `execution.started`) medido. Evidências em `docs/qa/`.
- **Cenário completo LocalStack** (DynamoDB + SNS + SQS com fanout + `BillingConsumer` na app): a mensagem SNS→SQS carrega o `AWSTraceHeader` (verificado empiricamente no LocalStack 4.2); o consumidor continua o MESMO trace e o ramo **SQS → BillOrder → DynamoDB** aparece NA MESMA ÁRVORE do produtor — árvore de 8 nós/7 níveis em ~1,5 s.
- **StationJourneyIT** (modo Companion): app sem servidor embedded exportando OTLP/HTTP para o Station; árvore aparece no Station **sem delta** (degradação prevista da §5.3) com SNS órfão declarado.

### Corrigido (loop de evidência visual da UI)

- **Árvore da UI reconstruída por `parentId`** (`rebuildTree` imperativo no app.js): os nós do estado são snapshots congelados em momentos diferentes — os `children` dos snapshots parciais não são confiáveis; o canvas agora remonta a árvore a partir do parentId, como o assembler faz (I1).
- **`renderRecent()` sempre refletindo o acervo**: o item FAILED de uma execução não selecionada não aparecia na lista "Recent Executions".
- **Captura de telas automatizada** (`scripts/screenshots/capture.mjs`, headless Edge via CDP): 6 telas reais da UI (catálogo, árvore JC-1 com consumidor, inspector de delta, inspector SQS, execução vermelha JC-2 e inspector do erro) em `docs/qa/screenshots/`.

### Corrigido (loop de ajustes do E2E)

- **Binding de propriedades dotted da SPEC §5.4 quebrado no Boot 4.1** (`tracevanta.redaction.mode`, `station.endpoint`, `aws.dynamodb.capture-before`…) — config silenciosamente ignorada. Fix: grupos aninhados em `TraceVantaProperties` + `PropertyBindingContractTest` que trava todos os nomes documentados.
- **Assembler adia a conclusão** enquanto há produtor SNS sem consumidor (janela de quiescência) — o consumidor ligado por Link/parent remoto chega e é reparentado na MESMA execução (E8/JC-3).
- **Envelope do fanout SNS→SQS** tratado no consumidor demo (`{"Type":"Notification","Message":"{...}"}`) — o parse anterior descartava a mensagem silenciosamente.
- **Span manual de receive no consumidor demo** (o `telemetry.wrap(client)` do SQS exige resolução eager do OTel, incompatível com o registro lazy do TraceVanta) — parent remoto via `AWSTraceHeader`.
- **Atributos array no ingest OTLP do Station** renderizados com colchetes (`"DynamoDB: [orders]"`) — agora join por vírgula, igual à ponte SpanData.
- **Teste do launcher hermético** (registro estático `TraceVantaOtel` limpo entre testes do mesmo fork).

### Corrigido (bugs encontrados pelo próprio E2E)

- **Instrumentação AWS resolvida lazy**: os beans do usuário são instanciados antes dos da autoconfiguração — o interceptor OTel capturava o `GlobalOpenTelemetry` ainda travado em noop e os spans do DynamoDB/SNS não apareciam. Agora o SDK é resolvido na primeira chamada real.
- **Path variables codificadas no launcher**: `ORDER#88291` virava fragmento de URL (`#`) e o endpoint de confirm recebia o id truncado.
- **Heurística de chave do delta** (`PutItem` sem chave explícita): preferência determinística por `pk`/`id`/`*Id` em vez do primeiro atributo.

### Corrigido (auditoria de conformidade pós-implementação)

- **Kill switch** `tracevanta.enabled=false` agora desliga tudo (condição de autoconfiguração — SPEC §7.3).
- **Gate de loopback enforced**: bind fora do loopback sem `allow-non-loopback=true` falha o boot (starter e Station) — SPEC §8.1.
- **Delta do DynamoDB capturado no `modifyResponse`** (o `afterExecution` do SDK v2 recebe a resposta já restaurada — a captura anterior perdia o `before`/`after` no fluxo real) — ADR-003/R-01/I3.
- Links OTLP do Station repassados ao assembler (correlação SNS→SQS no modo Companion — §4.11).
- Redaction aplicada no ingest OTLP do Station (§8.3).
- Literais `code.*` movidos para `OtelAttributeNames`; teste de fronteira do ADR-008 agora cobre o prefixo `code`.
- Detecção de modo (ADR-002, regra 3): `AWS_LAMBDA_FUNCTION_NAME` ou `tracevanta.station.endpoint` ⇒ sem servidor embedded + exportador OTLP para o Station.
- Launcher: headers do cliente aplicados ANTES da injeção W3C; `traceparent`/`baggage` do cliente nunca sobrescrevem os do disparo; baggage `tracevanta.trigger=ui` propagada (§4.9).
- Catálogo usa `getPatternValues()` — endpoints com `{pathVariable}` aparecem (E3).
- `jdbc.mutation-capture=before-image` rejeitado com erro explícito (não implementado na v0.1).
- `GlobalOpenTelemetry` trancado pelo primeiro `get()` (noop) — os instrumentos agora leem o registro próprio `TraceVantaOtel.get()`, com fallback para o global do dev.
- SDK do OTel não substitui mais um global já configurado pelo dev.
- `selfTime` subtrai apenas filhos sobrepostos (I2 fiel à SPEC §4.6); órfãos anexam no START do pai (fora de ordem).
- `/api/health` expõe `internalErrors` (ADR-006, consequência 2); `/api/meta` inclui `runtime`.
- CSP sem `unsafe-inline`; `capture-before` forçado `false` fora de dev (D-3); marcação de órfão "aguardando consumo" restrita a SNS; truncamento de atributos alinhado a `payload.max-bytes`; UI com um disparo por sessão (§4.9).
- Lambda: `TRACEVANTA_STATION_ENDPOINT` obrigatório com erro claro (ADR-002); exemplo `confirm` relê o item (resposta restaurada pelo R-01).

### Adicionado (implementação inicial após aprovação do GATE 1)

- `tracevanta-bom` — BOM para o consumidor fixar versões.
- `tracevanta-core` — TVEM (SPEC §4.6), ring buffer com descarte na borda (ADR-006), assembler tolerante a eventos fora de ordem (invariantes I1–I3), redaction na origem (ADR-007) e SPI `TraceVantaExtension` (SPEC §4.7).
- `tracevanta-otel` — `TraceVantaSpanProcessor`, `SemanticMapper` (camada anti-corrupção, ADR-008) e `TraceVantaThreadFactory`.
- `tracevanta-ui` — assets offline da UI (WebJar em `META-INF/resources/tracevanta`), sem build step e sem referência externa (ADR-005).
- `tracevanta-server` — REST + SSE sobre `com.sun.net.httpserver`, coalescência de 20 frames/s, heartbeat, `Last-Event-ID` (ADR-004).
- `tracevanta-spring-boot-starter` — autoconfiguração Embedded, catálogo de endpoints, Request Launcher com SSRF-guard, guarda de produção (SPEC §8.4), `@TraceVanta` para métodos de negócio, RuntimeHints (ADR-005).
- `tracevanta-aws` — delta DynamoDB EXACT via `ExecutionInterceptor` com `ReturnValues` elevado e resposta restaurada (ADR-003, decisão D-3), semântica SNS/SQS.
- `tracevanta-jdbc` — semântica SQL sobre atributos estáveis `db.*` e delta `inferred` por parse leve do comando (padrão `off`).
- `tracevanta-lambda` — `TraceVantaLambdaHandler` com flush síncrono no fim da invocação (ADR-002).
- `tracevanta-station` — modo Companion: OTLP/HTTP em `/v1/traces` + `/tvingest/v1/mutations`, árvore multi-serviço.
- `tracevanta-testing` — extensão JUnit 5 e asserções sobre o TVEM.
- `tracevanta-architecture` — regras ArchUnit da SPEC §4.3 e do ADR-008.
- `examples/order-service` — jornadas JC-1/JC-2/JC-3 contra LocalStack, `docker-compose.yml` e perfil de Native Image (M5).

### Decisões

- GATE 1 aprovado (ver `docs/adr/GATE-1-DECISOES.md`): D-1 Java 21 baseline; D-2 Station na v0.1; D-3 `capture-before` ligado em dev com aviso de boot; D-4 `tech.neural7.tracevanta`; D-5 grafia TraceVanta.
