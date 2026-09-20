# ADR-006 — Fila limitada com descarte na borda (e não LMAX Disruptor)

- **Status:** Aceita (2026-09-18)

## Contexto

O contrato do `SpanProcessor` do OpenTelemetry é explícito no javadoc de `onStart` e `onEnd`: são chamados **sincronamente, na thread de execução, e não devem lançar exceção nem bloquear**. Essa é a thread que atende a requisição do desenvolvedor — a mesma cuja latência o TraceVanta promete não degradar em mais de 5% (NFR-1).

Há, portanto, exatamente um requisito duro: **entre a instrumentação e a montagem da árvore precisa existir um desacoplamento que jamais espere.**

A especificação original propunha um Ring Buffer baseado em LMAX Disruptor.

## Decisão

**`ArrayBlockingQueue` limitada (padrão 4096 eventos) com política `offer()`** — nunca `put()`. Fila cheia significa **evento descartado**, contador `tracevanta.dropped` incrementado e aviso visível na UI ("N eventos descartados; a árvore pode estar incompleta"). Consumo por **uma thread virtual dedicada** que monta o TVEM e alimenta o hub SSE.

Corolários normativos:

- Nenhuma operação bloqueante no caminho de ingest: sem I/O, sem `synchronized`, sem alocação além do evento imutável. Verificado por ArchUnit e por benchmark JMH (NFR-2: < 50 µs p99).
- Erro interno do TraceVanta **nunca** propaga para a aplicação: tudo encapsulado, log em `debug`, contador na UI.
- Perda é **declarada**, não escondida. É a invariante I3 do TVEM aplicada ao buffer: melhor uma árvore incompleta e honesta do que uma completa e inventada.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **LMAX Disruptor** | Resolve throughput de milhões de eventos/s com *mechanical sympathy*. Uma ferramenta de debug local, com um disparo por vez, não tem esse problema. Custo: mais uma dependência no núcleo que se propõe a ser POJO + JDK. **P1-Simplicidade vence** — e se a medição de NFR-2 mostrar contenção, a troca é local e barata, feita com número na mão |
| **Fila ilimitada** | Troca latência por OOM: o pico vira memória e a JVM do dev morre. Inaceitável numa ferramenta de dev |
| **Bloquear quando cheio (`put()`)** | Viola o contrato do OTel e degrada a aplicação instrumentada — exatamente o que a ferramenta promete não fazer |
| **Processar sincronamente em `onEnd`** | Monta a árvore na thread da requisição. Mata o NFR-1 |
| **Descartar silenciosamente** | Produz árvore errada com cara de certa. Pior que não mostrar nada |

## Consequências

**Boas.** Zero dependência nova; comportamento previsível sob pressão; caminho quente trivial de medir e de auditar.

**Ruins, e assumidas.**

1. **Sob rajada, a árvore fica incompleta.** Aceito e exibido. Mitigações se virar incômodo real: aumentar a capacidade, amostrar por execução (descartar execuções inteiras em vez de nós soltos — árvore parcial é pior que execução ausente), ou então reavaliar o Disruptor.
2. **Uma thread virtual dedicada por processo** é ponto único de falha do pipeline. Mitigação: supervisão com reinício e contador exposto em `/api/health`.
3. **Ordem de chegada não é garantida** entre spans e eventos de mutação. O Assembler já é escrito para eventos fora de ordem — coberto por teste de propriedade (invariante I1).
