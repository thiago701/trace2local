# Contribuindo com o Trace2Local

Obrigado pelo interesse! O Trace2Local é Apache-2.0 e aberto a contribuições de
código, documentação, testes e relato de bugs.

## Começando

```bash
# não precisa de Maven instalado — o wrapper baixa o 3.9.x
./mvnw install                      # Windows: mvnw.cmd install
./mvnw -Pit -pl examples/order-service verify   # E2E LocalStack (requer Docker)
./mvnw -Pit -pl examples/lambda-sqs verify      # E2E Lambda+SQS (requer Docker)
```

Requisitos: **JDK 21+** (CI roda 21 e 25) e **Docker** apenas para os E2E.

## Convenções

- **Linguagem**: código em inglês, comentários/javadoc em português, mensagens
  de usuário (UI, erros) em português — coerência com o público atual.
- **Formato**: 4 espaços, sem imports `*`, javadoc em toda classe pública.
- **Commits**: mensagem curta no imperativo, em português
  (`corrige redaction de chaves jwt`). Uma mudança lógica por commit.
- **Nenhum literal de atributo OTel fora de `OtelAttributeNames`** (ADR-008) —
  existe teste de arquitetura que falha o build.
- **Nenhuma referência externa nos assets da UI** (ADR-005) — `UiOfflineTest`.

## Definição de pronto (uma PR está pronta quando)

1. `./mvnw install` verde (unidade + propriedade + contrato + ArchUnit).
2. Mudanças em telemetria/redaction têm teste de corpus ou de invariante.
3. Mudanças de UI passam no loop de usabilidade (`scripts/ux-loop/persona-loop-pix.mjs`,
   inclui auditoria de contraste WCAG) e atualizam as telas em `docs/qa/screenshots/v4-resonance/`.
4. Mudanças em coleta, árvore, Lambda, Mock Connect ou MCP rodam as jornadas da stack alvo
   (`examples/finance-pix/scripts/journeys.py`) — em JVM **e** nativo quando tocam o caminho do Lambda
   ([docs/qa/ACEITE.md](docs/qa/ACEITE.md)). Achado vira correção + trava de regressão.
5. CHANGELOG atualizado na seção `0.1.0-SNAPSHOT`.
6. Nada de dependência nova sem versão pinada no parent/BOM.

## Estrutura de pastas

```
trace2local-*/        módulos da lib (um por responsabilidade — docs/ARQUITETURA.md)
examples/            apps de demonstração (NÃO são a lib); stack alvo: examples/finance-pix
docs/                SPEC, ADRs, pesquisa, arquitetura, evidências de QA
scripts/ux-loop/     loops de usabilidade por persona (Playwright) — UI Resonance
scripts/real-traces/ captura de traces reais para o dataset preditivo
scripts/screenshots/ capturas da UI v2 (legado — ver LEGACY.md)
.github/             CI e templates de issues/PRs
AGENTS.md            guia para agentes/contribuidores + evolução das regras preditivas
```

## UI, inteligência e segurança — regras rápidas

- **UI**: ES modules sem dependência externa; nada de `style=`, `on*=`, script
  inline ou `innerHTML` com dados (o `UiCspComplianceTest` reprova); rode
  `scripts/ux-loop` contra um Station vivo antes de abrir PR de UI.
- **Regras preditivas / micro-decisões**: siga o processo de promoção e os
  deveres do [AGENTS.md](AGENTS.md#predictive-async-rules--continuous-evolution) —
  regra nova exige cenário + controle e benchmark atualizado em `docs/qa/`.
- **API**: clientes de `POST/DELETE` enviam `X-Trace2Local: 1` (ADR-015).
- **Segredos**: chaves (Jev, LLM, tokens) só por variável de ambiente — nunca
  em código, teste, cassete ou commit.

## Reportando bugs e pedindo features

Use os templates de issue. Bug report deve trazer: versão, JDK/OS, passos
mínimos, comportamento esperado vs. observado. Para vulnerabilidades, siga o
[SECURITY.md](SECURITY.md) (não abra issue pública).

## Release

`mvn -Prelease -DskipTests deploy` publica fontes + javadoc + assinatura GPG no
OSSRH (credenciais no settings.xml do maintainer). Versões seguem SemVer a
partir do 1.0.0; em 0.x, quebras entre minors são permitidas e registradas no
CHANGELOG (ADR-010).
