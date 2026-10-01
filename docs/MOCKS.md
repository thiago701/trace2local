# Mock Connect — mocks de API plugáveis, sugeridos pelo próprio trace

> Decisão: [ADR-016](adr/ADR-016-mock-connect.md) · módulo `trace2local-mocks` · validado na stack alvo
> ([finance-pix](../examples/finance-pix/README.md), jornadas J1, J2, J11 e J12).

O Mock Connect resolve dois problemas do dia a dia de quem desenvolve um microsserviço que depende de parceiros:

1. **A API não existe no meu ambiente local** (bureau de crédito, SPI, parceiro em homologação fechada).
2. **Preciso validar variações da resposta JSON** — campo ausente, valor que muda o fluxo, 503 transitório,
   latência, contrato novo — sem editar mock à mão e sem pedir nada ao time parceiro.

A diferença para um mock server comum: **o Trace2Local já vê as chamadas nas execuções**. Um *conselheiro* lê
os traces e **indica quando plugar um mock e quais variações testar**, com evidência navegável até o passo.
O modelo de extensão copia o que funciona no **Kafka Connect**: *source → transforms (+ predicates) → sink*,
configuração plana validada chave a chave, REST API de gestão e plugins isolados por JAR.

```
            ┌───────────── binding (config plana, como um conector) ─────────────┐
 contrato   │  SOURCE            TRANSFORMS (SMT)           PREDICATES     SINK   │   cliente
 OpenAPI ──►│  openapi       ─►  set-field /decision   ◄── header-matches  ─► embedded ├──► Trace2LocalHttp
 traces  ──►│  observed          set-status 503         ◄── call-count 1     wiremock │     (roteamento)
 à mão   ──►│  inline            latency · fault · …        path · body …     file    │   ou URL base
 API real──►│  proxy (repasse)                                                     │
            └──────────────────────────────────────────────────────────────────────┘
                     ▲ conselheiro: sugestões a partir das execuções (evidência → passo)
```

## 1. Em 3 minutos (pela UI)

1. Rode a aplicação contra o Station (`TRACE2LOCAL_MOCKS_DIR` apontando para os contratos dos parceiros).
2. Abra **Mocks** (tecla **9**). As sugestões aparecem ranqueadas, cada uma com **POR QUÊ**, **EVIDÊNCIA**
   (abre o passo exato na árvore) e **VARIAÇÕES PARA VALIDAR** agrupadas (fluxo, contrato, bordas do JSON, resiliência).
3. Marque as variações, escolha **Sempre** ou **Sob demanda** e clique **Plugar mock**.
4. Repita a chamada. O passo do parceiro aparece na árvore com o selo **SIM** (resposta simulada) ou **↪**
   (repasse da API real sem variação); o inspetor mostra binding, stub e variação aplicada.

**Sob demanda** é o modo para validar variações sem atrapalhar ninguém: a variação só vale quando a requisição
ao **seu** serviço traz `baggage: t2l.mock=<id-da-variação>` — o baggage W3C atravessa os serviços
instrumentados até o parceiro simulado. A UI entrega o cabeçalho pronto para copiar.

```bash
curl -X POST "$API/pix/transfers" -H 'Content-Type: application/json' \
     -H 'baggage: t2l.mock=decision-ausente' -d @transfer.json
```

## 2. O que o conselheiro indica

| regra | quando dispara | o que propõe |
|---|---|---|
| **API indisponível aqui** (`UNAVAILABLE_DEPENDENCY`, ALTA) | chamadas ao host falham por rede (ConnectException, DNS, timeout) | plugar mock a partir do contrato (`source=openapi`) ou do que já foi observado |
| **Resposta decide o fluxo** (`RESPONSE_DRIVES_FLOW`) | um campo da resposta (ex.: `/decision`) muda a sequência de passos de negócio | variações com os valores vistos, os previstos no contrato e o **ausente**, em modo sob demanda e `source=proxy` (a API real responde, só o campo muda) |
| **Só o caminho feliz** (`HAPPY_PATH_ONLY`) | o parceiro só respondeu 2xx em todas as execuções | 503, 500, 429 + Retry-After, falha na 1ª chamada (retry), conexão derrubada, resposta vazia, campos ausentes, campo extra |
| **Dependência lenta** (`SLOW_DEPENDENCY`) | p95 do parceiro acima do limiar | latência e timeout para validar SLA/circuit breaker |
| **Fora do contrato** (`CONTRACT_DRIFT`) | resposta observada diverge do OpenAPI do parceiro | stubs do contrato para alinhar o serviço |

Uma sugestão com mock já plugado fica marcada **RESOLVIDA** e vai para o fim da lista; o contador do botão
**Mocks** mostra só as pendentes (vermelho quando há ALTA).

## 3. Configuração do binding (formato Connect)

```properties
name=antifraude-variacoes
target=antifraude.partner.local:8080        # host:porta da API real que o mock substitui
api.name=Antifraude
source=proxy                                # openapi · observed · inline · proxy
transforms=negado,lento
transforms.negado.type=set-field
transforms.negado.pointer=/decision
transforms.negado.value=DENIED
transforms.negado.predicate=pedido          # só quando o predicado casar
transforms.lento.type=latency
transforms.lento.ms=3000
transforms.lento.predicate=pedido
transforms.lento.negate=true                # … ou quando NÃO casar
predicates=pedido
predicates.pedido.type=header-matches
predicates.pedido.name=baggage
predicates.pedido.regex=.*t2l\.mock=negado.*
sink=embedded                               # embedded · wiremock · file
unmatched=not-found                         # not-found (com near-misses) · proxy · error
```

- Toda chave é declarada pelo plugin (`ConfigDef`): **Validar** na UI (ou `PUT /api/mocks/validate`) devolve
  erro **por chave**, com a documentação — antes de salvar.
- Segredos: `${env:NOME}` (config provider). Valores do tipo `PASSWORD` nunca são persistidos nem exibidos.
- Bindings declarativos na subida: `TRACE2LOCAL_MOCKS_CONFIG=/caminho/bindings.json` (`{"bindings":[{"name":…, "config":{…}, "paused":false}]}`).

### Plugins embutidos (21)

| tipo | plugins |
|---|---|
| **SOURCE** | `openapi` (exemplos nomeados ou schema do contrato) · `observed` (replay do que a API real respondeu nos traces) · `inline` (stubs à mão ou mappings WireMock) · `proxy` (repasse para a API real; as variações alteram a resposta real) |
| **TRANSFORM** | `set-field` · `remove-field` · `rename-field` · `set-status` · `set-header` · `latency` · `fault` (connection-reset, empty-response, timeout) · `template` (corpo a partir da requisição) |
| **PREDICATE** | `path-matches` · `method-is` · `header-matches` · `body-matches` · `call-count` (1, 1-2, 3+, every:3) · `probability` (com seed) |
| **SINK** | `embedded` (servidor do Station: dinâmico, journal, near-misses, roteamento) · `wiremock` (publica num WireMock existente; remove só o que publicou) · `file` (exporta mappings WireMock versionáveis para CI) |

O catálogo completo, com cada chave, está na aba **Plugins** e em `GET /api/mocks/plugins`.

### Plugin próprio

Implemente `tech.neural7.trace2local.mocks.spi.StubSource`, `ResponseTransform`, `RequestPredicate` ou `StubSink`,
declare as chaves em `config()` e registre em `META-INF/services/tech.neural7.trace2local.mocks.spi.MockPlugin`.
Coloque o JAR em `TRACE2LOCAL_MOCKS_PLUGIN_PATH` — cada JAR ganha um classloader isolado (como o `plugin.path`
do Connect). Regras: plugin **sem estado**, nunca registrar `PASSWORD`, nunca abrir rede além do que a config declara.

## 4. Como a chamada chega ao mock

| caminho | quando usar | como |
|---|---|---|
| **Roteamento no cliente** (recomendado) | serviço Java com `Trace2LocalHttp` | `TRACE2LOCAL_MOCKS_ROUTING=on` no serviço: o cliente busca as rotas no Station (`/t2lingest/v1/mock-routes`, Bearer token) e desvia **só** os hosts com binding ativo. O nó mantém o host lógico e ganha `t2l.mock.routed`. Sem a variável, nada muda (opt-in explícito). |
| **URL base** | qualquer linguagem/cliente | aponte a URL do parceiro para o endpoint do binding (`http://<station>:<porta-mocks>/<binding>`), exibido em Bindings e em "Como usar agora". |
| **WireMock existente** | o time já usa WireMock | `sink=wiremock` + `sink.url`; stubs publicados com prioridade alta e metadado `trace2local.binding`. |
| **CI** | testes de contrato/regressão | `sink=file` ou **Exportar WireMock** na UI: mappings com as variações já "assadas". |

Toda resposta do mock leva `X-Trace2Local-Mock: binding=…; stub=…; variation=…` — o cliente instrumentado grava
em `t2l.mock` e a UI mostra **SIM**/**↪**, o inspetor e a narrativa de negócio ("Resposta SIMULADA pelo Mock Connect").

## 5. REST API (`/trace2local/api/mocks`, espelha a do Kafka Connect)

| método e caminho | o quê |
|---|---|
| `GET /mocks` | visão geral: bindings, rotas, journal, contratos, avisos |
| `GET /mocks/plugins` · `PUT /mocks/plugins/{p}/config/validate` | catálogo e validação por plugin |
| `PUT /mocks/validate` | valida `{name, config}` inteiro (core + plugins), erro por chave |
| `GET /mocks/bindings` · `GET/PUT /mocks/bindings/{n}/config` · `DELETE /mocks/bindings/{n}` | CRUD |
| `GET /mocks/bindings/{n}/status` · `PUT …/pause` · `PUT …/resume` · `POST …/restart` | ciclo de vida |
| `GET /mocks/bindings/{n}/stubs` · `GET …/export` | stubs efetivos; export WireMock (variações assadas) |
| `GET /mocks/journal?binding=&limit=` · `GET /mocks/routes` · `GET /mocks/contracts` | observabilidade |
| `GET /mocks/suggestions` · `POST /mocks/suggestions/{id}/apply` `{variations, mode}` | conselheiro |

Mutações exigem `X-Trace2Local: 1` (ADR-015) e passam pelo mesmo `RequestGuard` da UI (Host allowlist, token).

## 6. Variáveis do Station

| variável | padrão | efeito |
|---|---|---|
| `TRACE2LOCAL_MOCKS` | `on` | `off` desliga o Mock Connect |
| `TRACE2LOCAL_MOCKS_DIR` | — | raiz dos contratos (`source.spec` relativo a ela) e do sink `file` |
| `TRACE2LOCAL_MOCKS_PORT` / `TRACE2LOCAL_MOCKS_BIND` | `9877` / bind do Station | servidor de mocks (`embedded`) |
| `TRACE2LOCAL_MOCKS_ADVERTISED_URL` | — | URL anunciada aos clientes (como `advertised.listeners`; ex.: `http://trace2local-station:19878` dentro da rede do compose) |
| `TRACE2LOCAL_MOCKS_STATE_FILE` | — | persiste bindings entre reinícios (sem valores `PASSWORD`) |
| `TRACE2LOCAL_MOCKS_CONFIG` | — | bindings declarativos carregados na subida |
| `TRACE2LOCAL_MOCKS_PLUGIN_PATH` | — | JARs de plugins de terceiros (classloader isolado por JAR) |
| `TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS` | `false` | libera `sink=wiremock` em host público (padrão: só loopback/rede privada) |

No serviço instrumentado: `TRACE2LOCAL_MOCKS_ROUTING=on` (roteamento no cliente; desligado por padrão).

## 7. Segurança e honestidade

- **Nunca ligado em produção por acidente**: o roteamento no cliente é opt-in por variável e o runtime Lambda
  vira *pass-through* sem endpoint do Station; destinos públicos bloqueados por padrão.
- **Simulado nunca se passa por real**: selo SIM, atributo `t2l.mock`, narrativa e journal dizem de onde veio a
  resposta; repasse sem variação é marcado como real (↪).
- **Segredos**: `${env:…}` e `PASSWORD` mascarado em validação, status, estado persistido e export.
- **Rede**: o sink `embedded` só atende o que foi configurado; `proxy`/`unmatched=proxy` só falam com o `target` declarado.
