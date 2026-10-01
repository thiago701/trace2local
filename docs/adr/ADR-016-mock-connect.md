# ADR-016 — Mock Connect: mocks de API plugáveis no modelo do Kafka Connect, sugeridos pelos traces

- **Status:** Aceita (2026-10-01)
- **Relacionadas:** ADR-002 (modos), ADR-007/015 (local-first e RequestGuard), ADR-008 (ACL de atributos OTel), [docs/MOCKS.md](../MOCKS.md)

## Contexto

A validação na stack alvo ([finance-pix](../../examples/finance-pix/README.md)) mostrou dois bloqueios recorrentes
de quem desenvolve um microsserviço que depende de parceiros:

1. **Parceiro indisponível no ambiente local** (bureau de KYC, SPI): a jornada para no primeiro `ConnectException`.
2. **Variações da resposta não são exercitadas**: o serviço só vê o caminho feliz; `decision` ausente, 503
   transitório, latência ou campo renomeado só aparecem em produção.

Ferramentas de mock (WireMock, Hoverfly, Prism, Mockoon) resolvem a *simulação*, mas o dev precisa saber
**quando** plugar e **o que** variar. O Trace2Local já observa cada chamada externa — tem a evidência para indicar.

## Decisão

1. **Módulo `trace2local-mocks`** (sem dependência de servidor HTTP externo; Jackson + core + OTel bridge) com o
   pipeline do Kafka Connect: **SOURCE → TRANSFORM (+ PREDICATE, `negate`) → SINK**.
   - Config **plana** por binding (`target`, `source.*`, `transforms=a,b`, `transforms.a.type`, `predicates.p.type`…),
     `ConfigDef`/`ConfigValue` com validação **por chave** e documentação saindo da definição; `${env:NOME}` como
     config provider; valores `PASSWORD` mascarados e nunca persistidos.
   - Plugins via `ServiceLoader`; terceiros em `TRACE2LOCAL_MOCKS_PLUGIN_PATH` com **classloader isolado por JAR**.
   - 21 plugins embutidos: 4 sources (`openapi`, `observed`, `inline`, `proxy`), 8 transforms, 6 predicates,
     3 sinks (`embedded`, `wiremock`, `file`).
2. **REST API espelhando a do Connect** em `/api/mocks` (bindings, config, status, pause/resume/restart, validate,
   plugins) + o que é próprio de mock (stubs efetivos, export WireMock, journal, rotas, contratos, sugestões),
   atrás do mesmo `RequestGuard` (ADR-015).
3. **Conselheiro (`MockAdvisor`)** lendo execuções concluídas: `UNAVAILABLE_DEPENDENCY`, `RESPONSE_DRIVES_FLOW`
   (assinatura de execução por valor do campo), `HAPPY_PATH_ONLY`, `SLOW_DEPENDENCY`, `CONTRACT_DRIFT`.
   Cada sugestão traz evidência navegável, variações agrupadas e a config pronta. Nada é aplicado sem clique.
4. **Variação sob demanda por W3C baggage** (`t2l.mock=<id>`): a variação só vale para a requisição marcada e
   atravessa os serviços instrumentados — o mock pode ficar plugado sem afetar o resto do time.
5. **Chegada ao mock** por roteamento **no cliente** (`Trace2LocalHttp` + `TRACE2LOCAL_MOCKS_ROUTING=on`, rotas
   publicadas pelo Station com Bearer) ou por URL base; o nó mantém o host lógico. Toda resposta carrega
   `X-Trace2Local-Mock` → atributo `t2l.mock` → selo **SIM**/**↪** na UI e na narrativa.
6. **Interoperabilidade WireMock** (import de mappings, export com variações "assadas", publicação em WireMock
   existente com metadado `trace2local.binding` e remoção só do que publicou).

## Alternativas descartadas

- **Embutir o WireMock**: ~10 MB de dependências (Jetty) no Station, ciclo de vida próprio e nenhuma ligação com a
  evidência dos traces. Mantido como *sink* opcional.
- **Proxy de rede transparente** (Hoverfly-style no nível de socket): exige CA/iptables na máquina do dev e
  quebra o local-first sem fricção. O repasse com variações (`source=proxy`) cobre o caso "a API real responde, só
  o campo muda".
- **Variação por cabeçalho próprio** (`X-Mock-Variation`): não atravessa serviços; o baggage W3C já é propagado
  pelo OTel (o predicado `header-matches` continua aceitando qualquer cabeçalho).

## Consequências

- (+) O dev descobre **quando** plugar e **o que** variar a partir do que o sistema fez — validado em J1/J2 (KYC
  indisponível → mock), J11 (decision ausente → revisão, sob demanda) e J12 (503 na 1ª chamada → retry sem débito duplo).
- (+) Bindings exportáveis para CI (mappings WireMock) — o mock vira teste de regressão.
- (−) Superfície nova: um servidor de mocks na porta do Station. Mitigação: bind herdado do Station, destinos
  públicos bloqueados por padrão (`TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS`), mutações sob `X-Trace2Local: 1`.
- (−) O roteamento no cliente só cobre `Trace2LocalHttp`; outros clientes usam a URL base.
- (−) O conselheiro depende de execuções observadas: sem tráfego, só a sugestão a partir do contrato.
