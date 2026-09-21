# ADR-002 — Dois modos de execução: Embedded e Companion

- **Status:** Aceita (2026-09-18)
- **Decisão de escopo associada:** D-2 confirmada no GATE 1 — o Station entra na v0.1, mas no marco M7

## Contexto

O escopo da v0.1 inclui **AWS Lambda em Java**, e não apenas aplicações web de longa duração. Isso colide de frente com o desenho original ("adicione a dependência e abra `localhost:9876`"):

- O ambiente de execução de uma Lambda clássica segue o ciclo **Init → Invoke → Shutdown** e **congela entre invocações**; processos e callbacks pendentes pausam junto e retomam apenas se o ambiente for reaproveitado (documentação da AWS).
- Não existe, portanto, um processo de longa duração dentro do container da função capaz de hospedar uma UI ou de manter um stream SSE aberto com o navegador.
- O padrão já consagrado no ecossistema (ADOT, extensions, Telemetry API) resolve isso colocando o coletor **fora** do ambiente de execução.

Ao mesmo tempo, para uma aplicação Spring Boot local, subir um container extra só para ver a árvore seria burocracia gratuita — e mataria o "zero-configuration" que é o coração do produto.

## Decisão

Suportar **dois modos**, com o mesmo núcleo e o mesmo TVEM:

**Modo A — Embedded.** Trace2Local dentro do processo da aplicação: coleta, montagem, servidor HTTP e UI no mesmo JVM. É o modo padrão e a experiência de referência.

**Modo B — Companion (Station).** A aplicação carrega apenas a ponte e um exporter; um processo separado (`trace2local-station`, um container ao lado do LocalStack) recebe a telemetria, monta a árvore e serve a UI.

Regras que acompanham a decisão:

1. O Station aceita **OTLP/HTTP padrão** em `/v1/traces` — qualquer serviço já instrumentado com OTel aparece nele sem código do Trace2Local —, mais um endpoint proprietário para o canal de mutação de dados (ADR-003), que não tem equivalente em OTLP.
2. Em Lambda, o flush **DEVE** ser síncrono no fim do handler, com teto de 200 ms. `BatchSpanProcessor` padrão perde telemetria: o ambiente congela antes do worker acordar.
3. O modo é **detectado**, não configurado: presença de `AWS_LAMBDA_FUNCTION_NAME` ⇒ Companion; `trace2local.station.endpoint` definido ⇒ Companion; caso contrário ⇒ Embedded.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **Só Embedded, Lambda fora da v0.1** | Contraria o escopo escolhido pelo product owner; e Lambda é o ambiente onde o rastro se perde com mais frequência — é onde a ferramenta mais vale |
| **Lambda Extension externa** (processo dentro do ambiente de execução) | Roda dentro do mesmo ambiente efêmero; não resolve a UI, e prende o desenho ao runtime da AWS. A Telemetry API vira uma opção futura de ingestão para o Station, não a arquitetura |
| **Só Station, sempre** (um container para todos os casos) | Mata o zero-configuration e transforma o Trace2Local em "mais um Jaeger local", perdendo o diferencial (§2 da SPEC) |
| **Ler logs do CloudWatch/LocalStack** | Telemetria por log é lossy, sem estrutura e sem correlação confiável |

## Consequências

**Boas.** O modo Companion é o que habilita a visão **multi-serviço**: dois microsserviços e uma Lambda apontando para o mesmo Station produzem **uma árvore**, não três — capacidade que o modo Embedded nunca teria. E o Station serve de plano B se o Embedded encontrar barreira intransponível em algum framework.

**Ruins, e assumidas.**

1. **Duas topologias para manter e testar.** Mitigação: núcleo e TVEM idênticos; só a borda muda. ArchUnit garante que a lógica não se duplique.
2. **~2 semanas de esforço no M7** (Risco R-06). Mitigação: entra depois do valor central provado; se o cronograma apertar, é o primeiro candidato a escorregar para a v0.2 — o Embedded funciona sozinho.
3. **Flush síncrono adiciona latência à invocação da Lambda.** Assumido: é ferramenta de dev-time, e o teto é configurável.
4. **Lambda Managed Instances** (GA nov/2025) mantém a JVM quente entre invocações — nesse modelo o Embedded voltaria a funcionar em Lambda. Reavaliar na v0.3 (Risco R-10).
