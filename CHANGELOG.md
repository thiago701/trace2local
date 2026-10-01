# Changelog

Todas as mudanças notáveis do Trace2Local são registradas aqui, desde o primeiro commit (ADR-010).
Formato: [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) · Versionamento: [SemVer](https://semver.org/) a partir do 1.0.0. **Em 0.x a API pode quebrar entre minors** (SPEC §11.3).

## [0.1.0-SNAPSHOT] — em desenvolvimento

### Instalação e início revisados; restos do nome antigo removidos (2026-10-01)

- **Corrigido**: `mvn trace2local:configure` gravava no `pom.xml` do app a versão **do app** (`${project.version}` dentro do Mojo é o projeto que roda o goal), não a do Trace2Local — num app `0.0.1-SNAPSHOT` entrava `trace2local-bom:0.0.1-SNAPSHOT`, inexistente. O padrão agora é `${plugin.version}` (sobrescrevível com `-Dtrace2local.version`); teste `ConfigureMojoDescriptorTest` trava a regressão lendo o `plugin.xml` gerado.
- **Corrigido**: o `META-INF/services` do starter (`SdkTracerProviderConfigurer`) apontava para uma classe do nome anterior do projeto, inexistente — apps com o autoconfigure do OTel recebiam `ServiceConfigurationError` e o processor nunca era anexado. Agora aponta para `Trace2LocalOtelConfigurer` (teste `OtelConfigurerServiceLoaderTest`).
- **Renomeado**: últimos restos do nome anterior no código — nomes de bean `traceVanta*` → `trace2Local*` (**muda nomes de bean**; quem os referenciava por nome deve atualizar), parâmetro do aspecto, constante do plugin Maven, javadocs e testes de integração. Nova guarda `ProjectNamingTest` (L0): nome legado fora do histórico e provider de `ServiceLoader` sem classe falham o build.
- **Perfil `trace2local`** passa a ligar o starter (junto de `dev`, `development`, `local`, `localstack`): um único perfil liga a ferramenta **e** carrega o `application-trace2local.yml` gerado pelo `mvn trace2local:configure`, cuja mensagem final agora mostra o comando exato.
- Exemplos `order-service`/`payment-service` injetam `Trace2LocalConfig` por `ObjectProvider` — sobem fora de dev com o cliente cru (validado com `--spring.profiles.active=prod`).
- README, seção **Começar** reescrita como guia passo a passo: instalar a lib, dependência Maven **e** Gradle, iniciar com o perfil (Maven, Windows, IDE, contêiner), conferir pela linha de boot, enriquecer a árvore (tabela por integração, padrão `ObjectProvider` seguro para produção), configuração comentada, Lambda + Station com variáveis, MCP com o build do jar e "Não apareceu nada?".

### Demo no Windows só com Docker e README revisado (2026-10-01)

- `examples/finance-pix/scripts/up.ps1` (+ `up.cmd`): sobe a stack alvo no Windows usando **só o Docker Desktop** — inicia o Docker se preciso, confere portas, build em container Linux (`Dockerfile.build`, BuildKit com cache do `~/.m2`), compose, Terraform em container, chamada real ao `POST /pix/transfers`, abre a UI; `-SkipBuild`, `-Down` (remove também os containers de Lambda criados pelo LocalStack) e `-CaBundle` para proxy corporativo com inspeção TLS (Maven e Terraform confiam na CA).
- `.gitattributes`: `bootstrap-*` da Lambda, `*.sql`, `*.tf` e Dockerfiles sempre LF (CRLF quebrava o runtime custom em checkouts Windows); `*.ps1`/`*.cmd` em CRLF.
- README: status do projeto, requisitos por cenário, demo no Windows, FAQ e canais de suporte.

### Stack alvo financeira, Mock Connect e identidade Resonance (2026-10-01)

**Validação na stack alvo — [examples/finance-pix](examples/finance-pix/README.md) ([análise](docs/qa/finance-pix/ANALISE-RESULTADOS.md))**
- Serviço Pix completo: API Gateway (OpenAPI) → Lambda **Java 25** (JVM jlink + CDS **e** nativo GraalVM 25, runtime `provided.al2023`) → DynamoDB · Postgres (RDS) · SQS → Lambda · SNS → Lambda · 5 parceiros com contrato OpenAPI; Terraform no LocalStack 4.9.
- `scripts/journeys.py`: 12 jornadas reais conferidas contra o estado real em 6 níveis — **61/61 em JVM e em nativo**; `scripts/bench.py`: cold start nativo 808 ms × JVM 2 191 ms.
- Critério de aceite multinível + usabilidade: [docs/qa/ACEITE.md](docs/qa/ACEITE.md).

**Mock Connect ([ADR-016](docs/adr/ADR-016-mock-connect.md), [MOCKS.md](docs/MOCKS.md)) — novo módulo `trace2local-mocks`**
- Modelo do Kafka Connect: source → transforms (+ predicates) → sink, config plana validada por chave, `${env:…}`, `PASSWORD` mascarado, REST API espelhando a do Connect, plugins por `ServiceLoader` com classloader isolado por JAR.
- 21 plugins: sources `openapi`/`observed`/`inline`/`proxy`; transforms `set-field`/`remove-field`/`rename-field`/`set-status`/`set-header`/`latency`/`fault`/`template`; predicates `path-matches`/`method-is`/`header-matches`/`body-matches`/`call-count`/`probability`; sinks `embedded`/`wiremock`/`file`.
- **Conselheiro** a partir dos traces: API indisponível, resposta que decide o fluxo, só caminho feliz, dependência lenta, fora do contrato — com evidência navegável e variações prontas; variação **sob demanda** por W3C baggage (`t2l.mock=<id>`).
- Roteamento opcional no cliente (`Trace2LocalHttp` + `TRACE2LOCAL_MOCKS_ROUTING=on`), marca `X-Trace2Local-Mock` → `t2l.mock` → selo SIM/↪; interoperabilidade WireMock (import, export com variações assadas, publicação).

**UI Resonance v4 ([UX-RESONANCE.md](docs/UX-RESONANCE.md))**
- Subtítulo e nome: **Resonance** (antes "Ressonância").
- Identidade agnóstica a tema: preto profundo/ônix, ciano neon como único ponto focal (CTA sólido com texto escuro), títulos industriais em caixa alta, microcópia espaçada, tech-glow sutil em ícones lineares, cards elevados; nenhum emoji; contraste AA medido em todos os tokens e superfícies.
- Visão **Mocks** (tecla 9): sugestões, bindings (pausar/retomar/reiniciar/exportar/remover com confirmação), journal, plugins, editor com validação; selo **SIM/↪** na árvore; seção Mock Connect no inspetor; parâmetros do contrato na aba API; disparo pelo Station segue o `traceId`.
- Loop de usabilidade na stack alvo (`scripts/ux-loop/persona-loop-pix.mjs`, 6 personas + auditoria de contraste): **69/69**.

**Servidor MCP ([ADR-017](docs/adr/ADR-017-servidor-mcp.md), [MCP.md](docs/MCP.md)) — novo módulo `trace2local-mcp`**
- Model Context Protocol (stdio e Streamable HTTP em loopback) sobre a API local: 17 ferramentas de leitura (`diagnose_failure`, `get_execution`, `get_step`, `compare_executions`, `explain_execution`, insights, topologia, logs, Mock Connect…) + 4 de mutação com opt-in (`dispatch_endpoint`, `apply_mock_suggestion`, `put_mock_binding`, `control_mock_binding`) e 3 prompts.
- Somente leitura e dados estruturais por padrão; base só loopback; `Origin` validado no HTTP; respeita o RequestGuard. E2E na stack alvo: **14/14** ([MCP-E2E.md](docs/qa/MCP-E2E.md)).

**Lib (integrações para Lambda/serverless)**
- `Trace2LocalTraceContext` (traceparent/baggage/AWSTraceHeader de API Gateway, SQS e SNS), `LambdaTriggerSemantics`, `Trace2LocalHttp`, `Trace2LocalBusiness`, `Trace2LocalMessaging`; propagadores trace + baggage; runtime Lambda *pass-through* sem Station e com diagnóstico de flush; `TRACE2LOCAL_JDBC_MUTATION_CAPTURE` e `TRACE2LOCAL_FLUSH_TIMEOUT_MS` por ambiente.

### Corrigido
- Nenhum trace das Lambdas com `Sampled=0` vindo do API Gateway (sampler `alwaysOn` em ferramenta local).
- Raiz falsamente ÓRFÃ quando o chamador não é instrumentado; consumidor SNS fora do lugar (propagação de mensageria do SDK).
- Execução fantasma "em curso" no modo Station: novo evento `execution.renamed` quando o id provisório vira o declarado pela raiz.
- Narrativa "Chamada externa GET para ao serviço" → nomeia parceiro, rota, status e origem simulada.
- Índice de IaC usava o rótulo local do Terraform (`transfers`, `fn`) em vez do nome real do recurso — componentes fantasmas na Anatomia.
- SQL rotulado sem operação (`SQL: UPDATE accounts`).
- **IDEM-002 falso positivo** na stack alvo: o Δ inferido do JDBC usa o WHERE parametrizado (`transfer_id = ?`) como chave — transferências diferentes pareciam "a mesma entidade". Chave com placeholder não identifica entidade; novo cenário-controle `controle-sql-inferido-chave-template`.
- **SEC-PII-001 falso positivo**: valor já mascarado pelo parceiro ("J*** S***", CPF "***.456.789-**") era acusado de "sem redação" só pelo nome do campo; novo controle `controle-dado-pessoal-ja-mascarado` (benchmark: **35 cenários, 15 controles, precisão/recall 1,00, 0 FP**).
- **Homologação**: regra respeitada saía *violada* quando o texto citava siglas em caixa alta ("API de iniciação… (API Gateway)") — siglas eram lidas como estados esperados; agora estado = valor observado nos deltas ou forma de enum (SETTLED, IN_REVIEW); regra sobre o **caminho de falha** ("falha no provedor … deve ficar FAILED") não é violada quando nada falhou — fica inconclusiva com a dica de exercitar o caso negativo. Testes `acronymsInTheRuleAreNotTakenAsExpectedStates` e `ruleAboutTheFailurePathIsNotViolatedWhenNothingFailed`; benchmark Jev rotulado inalterado (política 0,96).


### UI v3 (Resonance) + Inteligência + Regras Assíncronas Preditivas + prontidão corporativa (2026-09-30)

**UI/UX (reescrita — [docs/UX-RESONANCE.md](docs/UX-RESONANCE.md))**
- Nova UI em ES modules (sem build step, zero dependência externa, CSP estrita): **Anatomia** (anéis por zona: núcleo · fronteira local · fronteira externa · declarado no IaC; "contraste" percorrendo as conexões na ordem real dos spans), **Árvore** (esquerda→direita, mini-Gantt, papel arquitetural, Δ de dados, marcadores de insight, caminho crítico, pílula "⧗ fila", minimapa, teclado), **Linha do tempo** (capítulos + "espera na fila", espera hachurada, cursor narrado "AGORA", logs CloudWatch com nível/classe/log group/stream), **Investigação** (visões executiva + técnica), Narrativa, Painel (inteligência + baseline), Comparar, Infra; inspector por passo; paleta **Ctrl+K**; barra de status; deep link `?execution=&view=`.
- **Loop de usabilidade por persona** (`scripts/ux-loop`, Playwright) contra o Station vivo: 42/42 checks (inclui CSP servida, anti-CSRF, mobile 390 px, nomes acessíveis).

**Logs estilo CloudWatch ([ADR-012](docs/adr/ADR-012-logs-cloudwatch-na-linha-do-tempo.md))**
- `LogEntry`/`LogStore` no core (índice por trace e RequestId, dedupe preferindo a linha real, redaction de texto livre); captura do stdout da Lambda com START/END/REPORT; ingest `/t2lingest/v1/logs`; **tail do CloudWatch do LocalStack** no Station (RequestId entre START/END, **dobra de stack trace** num único ERROR); alinhamento das linhas de plataforma ao span da invocação (marcado `≈`).

**Inteligência ([ADR-011](docs/adr/ADR-011-motores-de-micro-decisao-jev.md)) e Regras Preditivas ([ADR-013](docs/adr/ADR-013-regras-assincronas-preditivas.md), [PREDICTIVE.md](docs/PREDICTIVE.md))**
- Novo módulo **`trace2local-predictive`**: pipeline assíncrono não bloqueante, 12 analisadores / **20 regras** (performance assíncrona, regressão, outlier, N+1, leituras redundantes, idempotência, resiliência, dado sensível, consumidor órfão/parado, hotspot, tendência de erro, mudança de forma, deriva/segredo em Terraform, cobertura vs gate), ranking com feedback/supressões/retratação, baseline por fluxo persistido.
- **Micro-decisões** com cascata cassete → **Jev** (opt-in por chave) → determinístico local, política de fusão calibrada por benchmark, egress estrutural sanitizado, orçamento e disjuntor; laudo executivo/técnico por execução; explicação por template local ou LLM opcional (só por POST).
- Benchmarks: preditivo 33 cenários (13 controles) com precisão/recall 1,00; Jev 106 itens rotulados (política 0,96); **traces reais** do LocalStack como regressão; Jev ao vivo em traces reais (0 falhas, ~US$ 0,001).
- [AGENTS.md](AGENTS.md) com a seção **Predictive Async Rules — Continuous Evolution** e o processo de promoção experimental.

**Correções vindas do caso real Lambda + LocalStack ([VALIDACAO-CASOS-REAIS.md](docs/qa/VALIDACAO-CASOS-REAIS.md))**
- **Continuação tardia** ([ADR-014](docs/adr/ADR-014-continuacao-tardia-assincrona.md)): consumidor via *event source mapping* que chega após a quiescência é fundido na árvore do produtor (`execution.merged`); antes nascia execução `PARTIAL` com aviso falso.
- Início da execução = span mais antigo (no ingest OTLP o primeiro evento é um fim de span).
- `IDEM-001` não dispara mais em chave nova (`before = {}` do SDK) — analisador e interceptor DynamoDB corrigidos + controle no benchmark.
- Motor determinístico: frames de stack = diagnóstico; "reentrega ignorada" = evento de negócio; recusa protegida = risco baixo/apta (divergências reveladas pelo Jev ao vivo, viraram gabarito).
- Build: `forceCreation` do jar antes do shade (fat jar não reaproveita classes velhas em build incremental).
- `OtelAttributeNames.SERVER_ADDRESS`; ACL do ADR-008 passa a cobrir `faas.*` e `server.*`.

**Segurança ([ADR-015](docs/adr/ADR-015-endurecimento-corporativo-ui-api.md), [SEGURANCA-CORPORATIVA.md](docs/SEGURANCA-CORPORATIVA.md))**
- `RequestGuard`: allowlist de `Host` (DNS rebinding → 421), anti-CSRF (`X-Trace2Local: 1` + Origin → 403), token de UI opcional/gerado no perfil `corporate` com cookie `HttpOnly; SameSite=Strict` (`Secure` opcional); CSP endurecida + COOP/CORP/Permissions-Policy; respostas sem detalhe interno; SSE limitado; XSS da UI antiga eliminado; `InfraIndexer` sem credenciais de URL nem symlinks; `UiCspComplianceTest`.
- Demo `lambda-sqs`: consumidor real por ESM, logs de negócio, glossário montado, portas só em `127.0.0.1`, Station não-root a partir do jar pronto.
- **Quebra de contrato (0.x):** clientes de `POST/DELETE` na API precisam enviar `X-Trace2Local: 1`.


### UI por projeto (API DO PROJETO)

- O Trace2Local roda localmente no contexto de UM projeto principal: a seção de endpoints da sidebar virou **API DO PROJETO** com pill do nome do app importador (ex.: `payment-service`, via `spring.application.name`; oculta no modo station).
- Fim da frase genérica "a UI opera em modo somente-observação": sem API HTTP, o aviso agora é **por projeto** — *"O projeto X não expõe API HTTP descoberta. A observação continua por projeto…"* (spans/bancos/mensageria/Lambdas seguem observados); fallback "Este projeto…" quando o nome ainda não chegou ou é `station`/`?`.
- Validação nos scripts de captura: `projectPill === "payment-service"` (payment) e nota de projeto sem "somente-observação" (station) + `UiOfflineTest` verde.

### Ações de detalhamento de INFRA/DEVOPS (aba INFRA)

- **`InfraIndexer`** (server): engenharia reversa dos arquivos de infra — `*.tf` (recursos AWS + URLs/ARNs), `docker-compose*.yml`, `.env*` e `application*.yml` — cataloga URLs, ARNs, variáveis de ambiente e recursos Terraform com a **fonte exata (arquivo:linha)**; valores de chaves sensíveis são mascarados com `[OCULTO]` (critério do Redactor, ADR-007).
- **`GET /api/infra`**: catálogo + **cruzamento com o acervo** — para cada recurso, quais execuções/nós o utilizaram (drill-down infra → execução → nó).
- **Aba INFRA na UI** (UX revisada): cards com badge de tipo, valor copiável, chips de fonte que copiam `arquivo:linha`, resumo, busca/filtro, "N usos" com **VER USOS** clicável que seleciona o nó no canvas; **inspector** ganha a seção INFRA & DEVOPS por nó com botão VER NA ABA INFRA; estado vazio didático.
- Config: `trace2local.infra.scan-dirs` (env `TRACE2LOCAL_INFRA_SCAN_DIRS`); demo com `terraform/main.tf` + `.env` no payment-service.
- Testes: `InfraIndexerTest` (fonte linha-a-linha + máscara de segredo), contrato de propriedades; validação de usabilidade no script de captura (7 cards, 8 chips de fonte, drill-down até o nó selecionado, 0 erros JS) + tela 22.

### Renomeação do projeto: TraceVanta → Trace2Local (refatoramento em cascata)

- **Maven**: groupId `tech.neural7.tracevanta` → `tech.neural7.trace2local`; artifactIds `tracevanta-*` → `trace2local-*` (todos os módulos da lib + plugin); diretórios dos módulos renomeados.
- **Java**: pacotes, classes (`Trace2LocalConfig`, `Trace2LocalHttpServer`, `Trace2LocalAws`, `Trace2LocalLogs`…), anotação `@Trace2Local`, módulo de testes — 46 arquivos renomeados + conteúdo de 228 arquivos (case-sensitive).
- **Config/API**: prefixo `trace2local.*`, envs `TRACE2LOCAL_*`, base path da UI `/trace2local`, atributos de span `t2l.trigger`/`t2l.execution.id`/`t2l.business`/`t2l.payload.*`, ingest `/t2lingest/v1/mutations`, marcador `[TRACE2LOCAL_REDACTED]`, glossário `trace2local-business.md`.
- **UI/docs**: marca TRACE2LOCAL, README/SPEC/ADRs/evidências e scripts de captura atualizados.
- **GitHub**: repositório renomeado `thiago701/tracevanta` → `thiago701/trace2local` (remote + scm/URLs atualizados).
- Validação: `mvn clean install` completo verde (17 módulos), E2E LocalStack lambda-sqs 4/4, capturas v2/v3/v4 passando na UI `/trace2local` e persona walkthrough do payment-service — tudo com o novo nome.

### Instalador Maven + logs portáteis Datadog/OpenTelemetry

- **`trace2local-maven-plugin`** (novo módulo): `analyze` faz engenharia reversa por bytecode (ASM) — endpoints Spring, `@Trace2Local`, serviços AWS SDK v2, JDBC — e audita a higiene de logs com sugestões (System.out/printStackTrace/SLF4J); gera `target/trace2local/canvas-map.md` e `report.md`. `configure` adiciona BOM+starter ao `pom.xml` (com backup, via MavenXpp3), cria `trace2local-business.md`, `application-trace2local.yml` e `logback-spring.xml` com o padrão de correlação — idempotente, nunca sobrescreve.
- **`Trace2LocalLogs`** (starter): injeta no MDC, por requisição, `trace_id`/`span_id` (hex OTel) e `dd.trace_id`/`dd.span_id` (decimal unsigned 64 bits Datadog) — os MESMOS logs correlacionam no trace local e em pipelines Datadog/OTel. Validado ao vivo no payment-service: `INFO PaymentController - trace_id=259c… span_id=87b3… dd.trace_id=1675… dd.span_id=9778… - Pix PIX-LOG2 criado`.
- **README com seção didática do instalador** + README do plugin com limitações declaradas; plugin registrado no reactor e no BOM; testes unitários (scanner ASM com fixtures, relatórios, conversão decimal/hex, MDC).
- Bugs achados no loop: `String.valueOf(long)` imprime com SINAL (dd.trace_id virava "-1" — corrigido com `Long.toUnsignedString`), `slf4j-simple` usa NOPMDCAdapter por design (testes migrados para logback-classic), índice errado do owner AWS no scanner.

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

- **`StoryService` + `BusinessGlossary`** (trace2local-server): descoberta da especificação/regras de negócio por **contexto** (kind + atributos OTel + mutação + erro), **engenharia reversa** (camelCase humanizado com mapa de verbos de negócio PT-BR) e **docs** (glossário opcional `trace2local-business.md` no classpath — o time documenta o termo e a nota é sobrescrita).
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

- **`UpdateItem` before+after — DESVIO FECHADO** (`Trace2LocalAws.instrumentWithReadBack`, opcional): a API do DynamoDB devolve UM conjunto por chamada; o novo modo faz `ALL_OLD` (before) + releitura pós-update **dentro do span** via cliente cru (sem span aninhado, correlação correta) → delta EXACT com before E after e deltas de campo. Validado no LocalStack real (J3: `before.pk` + `after.status=BILLED` + delta `status`). O padrão `instrument` permanece como antes (after EXACT, before declarado).
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

- **Token de ingest opcional** (`trace2local.station.token` / `TRACE2LOCAL_STATION_TOKEN`): com token definido, `/v1/traces` e `/t2lingest/v1/mutations` exigem `Authorization: Bearer` (comparação em tempo constante, 401 + desafio `WWW-Authenticate`); Lambda e starter enviam o header automaticamente; o Station avisa quando exposto sem token (ADR-007/§8.1). Testes: 401/200 no servidor + Bearer capturado no exportador OTLP.
- **Hardening HTTP**: `Referrer-Policy: no-referrer` em todas as respostas e `Cache-Control: no-store` nas respostas de API/estáticas (execuções carregam payloads — nunca cachear).
- **Redaction ampliada**: chaves `jwt`/`otp`/`totp`/`pwd`/`privateKey` + padrões de valor para tokens GitHub (`ghp_…`) e hashes bcrypt/argon2 (corpus de teste ampliado).
- **Auditoria de CVEs** (GitHub Advisory DB + NVD + OSV, set/2026): nenhuma versão pinada afetada por CVE conhecido — Jackson 2.22.2 e AssertJ 3.27.7 são exatamente as versões corrigidas (não rebaixar); nota de supply-chain registrada para jqwik 1.10.1 (protestware removido, sem CVE; dependência dev-only). Documentado em `SECURITY.md`.
- **`SECURITY.md`** com modelo de ameaças, limites declarados e processo de reporte; **`NOTICE`** Apache.

### Build profissional

- **`trace2local-bom` completo**: agora exporta os artefatos do projeto E os BOMs de terceiros (OTel, AWS SDK, Jackson, Spring Boot, JUnit) + versões de teste — consumidor declara dependências SEM versão.
- **Enforcer ativo**: Maven ≥ 3.9, JDK ≥ 21, convergência de versões (`requireUpperBoundDeps`).
- **Builds reproduzíveis**: `project.build.outputTimestamp` fixo por release.
- **Metadados de publicação**: `scm`, `issueManagement`, `ciManagement`, `distributionManagement` (OSSRH) e **perfil `release`** (fontes + javadoc + assinatura GPG).
- **CI (GitHub Actions)**: matriz JDK 21/25 + job E2E (LocalStack via Testcontainers) para os dois exemplos.
- Exemplos consomem versões do BOM (sem pins duplicados de Testcontainers); artefatos do protótipo movidos para `docs/archive/`.

### Adicionado (projeto de teste/validação serverless)

- **`examples/lambda-sqs`** — novo projeto de teste e validação: **AWS Lambda (runtime java21) + DynamoDB + SQS no LocalStack**, configurado com a lib Trace2Local em modo Companion. O handler grava no DynamoDB via `Trace2LocalAws.instrument` (delta EXACT) e publica no SQS (nó de produtor); o runtime Lambda abre o span raiz SERVER com `faas.name` (→ nó LAMBDA), correlaciona as mutações e faz flush síncrono OTLP + `/t2lingest/v1/mutations` para o Station (ADR-002/§4.12).
- **`LambdaSqsJourneyIT`** (perfil `-Pit`, Testcontainers + Station em processo): invoca o handler como o runtime Lambda faria e verifica o item REAL no DynamoDB, a mensagem REAL na fila SQS e a árvore no Station — raiz `LAMBDA` "order-processor", trigger `LAMBDA_EVENT`, nó `DYNAMODB` com mutação `EXACT` (chave `ORDER-L1`) e nó `SQS`. Evidência: `docs/qa/evidence-lambda-sqs.json`.
- **Fluxo completo no emulador Lambda** (`docker-compose.yml` + `scripts/init-localstack.sh`): fat jar via maven-shade (Serviços unificados, `aws-lambda-java-core` provided), `create-function`/`invoke` reais no LocalStack 4.2 e Station em container com healthcheck.

### Corrigido (modo Companion Lambda)

- **Nó LAMBDA na árvore**: o `DefaultSemanticMapper` agora mapeia `faas.name`/`faas.invocation_id` → `NodeKind.LAMBDA` (antes caía em HTTP_SERVER).
- **Trigger e execution id no ingest OTLP do Station**: `t2l.trigger` e `t2l.execution.id` do span raiz agora são parseados (`Trace2LocalAttributes.parseTrigger`) — execução Lambda aparece com `LAMBDA_EVENT` e o id do request.
- **Aviso `EVENTS_DROPPED` suprimido no caminho OTLP**: o protocolo não carrega evento de início de span, então a ausência do start é POR DESENHO (`SpanEndEvent.startDeliberatelyAbsent`) — execuções OTLP puras fecham `COMPLETED` sem falsos avisos de descarte (SPEC §5.3).
- **Runtime Lambda registra o SDK no `Trace2LocalOtel`**: sem Spring na Lambda, o registro próprio era ninguém fazia — o interceptor lazy do AWS SDK resolvia o `GlobalOpenTelemetry` noop e os nós DynamoDB/SQS não apareciam.
- **`Trace2LocalLambda.configFromEnv()` público** com fallback para a propriedade `trace2local.station.endpoint` (testes) além da env `TRACE2LOCAL_STATION_ENDPOINT`.

### Corrigido (loop de melhorias do cenário lambda-sqs)

- **Duração de execução NEGATIVA** no modo Companion/Lambda (-255 ms): a duração era medida pela ordem de PROCESSAMENTO dos eventos — a mutação chega depois dos spans, mas é capturada DURANTE eles. Agora a duração é a JANELA DOS SPANS (min início → max fim), com piso em zero para relógios divergentes (I2). Teste de regressão em `TraceAssemblerTest`.
- **Identidade de execução estável**: um span tardio com outro `t2l.execution.id` (ex.: consumidor SQS continuando o trace) renomeava a execução no meio do caminho — o primeiro id explícito (span raiz) agora vence.
- **Erro da Lambda mudo na árvore**: o runtime marcava o status ERROR sem descrição e o ingest OTLP constrói o `ErrorInfo` do `status.message` — agora o runtime grava `String.valueOf(t)` como descrição; a execução vermelha mostra a exceção.
- **Jornada de erro coberta** (J2): `fail=true` no evento lança após o PutItem — execução FAILED com a raiz vermelha e o ramo DynamoDB OK (sucesso parcial visível). Evidência: `evidence-lambda-sqs-failure.json` + telas 10/11.
- **J3 — consumidor SQS continua a MESMA árvore** (o "JC-3" do mundo Lambda, §4.11): novo hook `Trace2LocalLambdaHandler.remoteParentOf(input, ctx)` (o span raiz da invocação vira filho do parent remoto) + `OrderBillingProcessor` parseando o `AWSTraceHeader`. Árvore fundida `LAMBDA → SQS → LAMBDA → DYNAMODB (UPDATE)` em UMA execução. Evidência: `evidence-lambda-sqs-consumer.json` + tela 12.
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

- **Binding de propriedades dotted da SPEC §5.4 quebrado no Boot 4.1** (`trace2local.redaction.mode`, `station.endpoint`, `aws.dynamodb.capture-before`…) — config silenciosamente ignorada. Fix: grupos aninhados em `Trace2LocalProperties` + `PropertyBindingContractTest` que trava todos os nomes documentados.
- **Assembler adia a conclusão** enquanto há produtor SNS sem consumidor (janela de quiescência) — o consumidor ligado por Link/parent remoto chega e é reparentado na MESMA execução (E8/JC-3).
- **Envelope do fanout SNS→SQS** tratado no consumidor demo (`{"Type":"Notification","Message":"{...}"}`) — o parse anterior descartava a mensagem silenciosamente.
- **Span manual de receive no consumidor demo** (o `telemetry.wrap(client)` do SQS exige resolução eager do OTel, incompatível com o registro lazy do Trace2Local) — parent remoto via `AWSTraceHeader`.
- **Atributos array no ingest OTLP do Station** renderizados com colchetes (`"DynamoDB: [orders]"`) — agora join por vírgula, igual à ponte SpanData.
- **Teste do launcher hermético** (registro estático `Trace2LocalOtel` limpo entre testes do mesmo fork).

### Corrigido (bugs encontrados pelo próprio E2E)

- **Instrumentação AWS resolvida lazy**: os beans do usuário são instanciados antes dos da autoconfiguração — o interceptor OTel capturava o `GlobalOpenTelemetry` ainda travado em noop e os spans do DynamoDB/SNS não apareciam. Agora o SDK é resolvido na primeira chamada real.
- **Path variables codificadas no launcher**: `ORDER#88291` virava fragmento de URL (`#`) e o endpoint de confirm recebia o id truncado.
- **Heurística de chave do delta** (`PutItem` sem chave explícita): preferência determinística por `pk`/`id`/`*Id` em vez do primeiro atributo.

### Corrigido (auditoria de conformidade pós-implementação)

- **Kill switch** `trace2local.enabled=false` agora desliga tudo (condição de autoconfiguração — SPEC §7.3).
- **Gate de loopback enforced**: bind fora do loopback sem `allow-non-loopback=true` falha o boot (starter e Station) — SPEC §8.1.
- **Delta do DynamoDB capturado no `modifyResponse`** (o `afterExecution` do SDK v2 recebe a resposta já restaurada — a captura anterior perdia o `before`/`after` no fluxo real) — ADR-003/R-01/I3.
- Links OTLP do Station repassados ao assembler (correlação SNS→SQS no modo Companion — §4.11).
- Redaction aplicada no ingest OTLP do Station (§8.3).
- Literais `code.*` movidos para `OtelAttributeNames`; teste de fronteira do ADR-008 agora cobre o prefixo `code`.
- Detecção de modo (ADR-002, regra 3): `AWS_LAMBDA_FUNCTION_NAME` ou `trace2local.station.endpoint` ⇒ sem servidor embedded + exportador OTLP para o Station.
- Launcher: headers do cliente aplicados ANTES da injeção W3C; `traceparent`/`baggage` do cliente nunca sobrescrevem os do disparo; baggage `trace2local.trigger=ui` propagada (§4.9).
- Catálogo usa `getPatternValues()` — endpoints com `{pathVariable}` aparecem (E3).
- `jdbc.mutation-capture=before-image` rejeitado com erro explícito (não implementado na v0.1).
- `GlobalOpenTelemetry` trancado pelo primeiro `get()` (noop) — os instrumentos agora leem o registro próprio `Trace2LocalOtel.get()`, com fallback para o global do dev.
- SDK do OTel não substitui mais um global já configurado pelo dev.
- `selfTime` subtrai apenas filhos sobrepostos (I2 fiel à SPEC §4.6); órfãos anexam no START do pai (fora de ordem).
- `/api/health` expõe `internalErrors` (ADR-006, consequência 2); `/api/meta` inclui `runtime`.
- CSP sem `unsafe-inline`; `capture-before` forçado `false` fora de dev (D-3); marcação de órfão "aguardando consumo" restrita a SNS; truncamento de atributos alinhado a `payload.max-bytes`; UI com um disparo por sessão (§4.9).
- Lambda: `TRACE2LOCAL_STATION_ENDPOINT` obrigatório com erro claro (ADR-002); exemplo `confirm` relê o item (resposta restaurada pelo R-01).

### Adicionado (implementação inicial após aprovação do GATE 1)

- `trace2local-bom` — BOM para o consumidor fixar versões.
- `trace2local-core` — TVEM (SPEC §4.6), ring buffer com descarte na borda (ADR-006), assembler tolerante a eventos fora de ordem (invariantes I1–I3), redaction na origem (ADR-007) e SPI `Trace2LocalExtension` (SPEC §4.7).
- `trace2local-otel` — `Trace2LocalSpanProcessor`, `SemanticMapper` (camada anti-corrupção, ADR-008) e `Trace2LocalThreadFactory`.
- `trace2local-ui` — assets offline da UI (WebJar em `META-INF/resources/trace2local`), sem build step e sem referência externa (ADR-005).
- `trace2local-server` — REST + SSE sobre `com.sun.net.httpserver`, coalescência de 20 frames/s, heartbeat, `Last-Event-ID` (ADR-004).
- `trace2local-spring-boot-starter` — autoconfiguração Embedded, catálogo de endpoints, Request Launcher com SSRF-guard, guarda de produção (SPEC §8.4), `@Trace2Local` para métodos de negócio, RuntimeHints (ADR-005).
- `trace2local-aws` — delta DynamoDB EXACT via `ExecutionInterceptor` com `ReturnValues` elevado e resposta restaurada (ADR-003, decisão D-3), semântica SNS/SQS.
- `trace2local-jdbc` — semântica SQL sobre atributos estáveis `db.*` e delta `inferred` por parse leve do comando (padrão `off`).
- `trace2local-lambda` — `Trace2LocalLambdaHandler` com flush síncrono no fim da invocação (ADR-002).
- `trace2local-station` — modo Companion: OTLP/HTTP em `/v1/traces` + `/t2lingest/v1/mutations`, árvore multi-serviço.
- `trace2local-testing` — extensão JUnit 5 e asserções sobre o TVEM.
- `trace2local-architecture` — regras ArchUnit da SPEC §4.3 e do ADR-008.
- `examples/order-service` — jornadas JC-1/JC-2/JC-3 contra LocalStack, `docker-compose.yml` e perfil de Native Image (M5).

### Decisões

- GATE 1 aprovado (ver `docs/adr/GATE-1-DECISOES.md`): D-1 Java 21 baseline; D-2 Station na v0.1; D-3 `capture-before` ligado em dev com aviso de boot; D-4 `tech.neural7.trace2local`; D-5 grafia Trace2Local.
