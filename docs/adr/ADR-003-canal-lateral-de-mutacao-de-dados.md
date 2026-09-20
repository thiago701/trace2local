# ADR-003 — Delta de dados em canal lateral, fora do span

- **Status:** **Aceita** — ressalva D-3 resolvida no GATE 1: `capture-before` **ligado por padrão em perfil de desenvolvimento**, com aviso de boot (ver `GATE-1-DECISOES.md`)
- **Relacionada:** ADR-001 (por que o span não basta), Risco R-01

## Contexto

O diferencial do TraceVanta frente a Jaeger, Glowroot e Postman é mostrar **o que mudou** — `before` e `after` de cada mutação de estado, como no protótipo (`Before: null (New Item)` / `After: {...}`). Duas restrições técnicas moldam a solução:

1. **O ciclo de vida do span não permite enriquecimento pós-fato.** No OpenTelemetry, `SpanProcessor.onStart` recebe um `ReadWriteSpan` (mutável), mas `onEnd` recebe um `ReadableSpan` — **somente leitura**. O resultado da operação (o que o DynamoDB devolveu) só é conhecido quando a chamada termina; nesse instante, o span já não aceita atributos. Além disso, o javadoc de ambos os métodos é explícito: são chamados **na thread de execução e não devem bloquear**.
2. **Payload não pertence a um span.** Corpos de requisição e itens de banco são grandes, sensíveis e de vida curta. Enfiá-los como atributos infla a telemetria, vaza para qualquer exporter encadeado e viola o local-first quando o dev também exporta para um backend remoto.

## Decisão

**Separar as duas correntes.** O span carrega estrutura, tempo e semântica; um **Data Mutation Channel** paralelo carrega payload e delta, correlacionado por `spanId`/`traceId` e fundido pelo Assembler na montagem do TVEM.

```java
record MutationEvent(String spanId, String traceId, DataMutation mutation, Instant at) {}
```

Consequências diretas do desenho: dados sensíveis **nunca** entram no pipeline OTel do desenvolvedor (não vazam para o Jaeger dele); a redaction acontece na origem, antes do buffer; e o canal pode ser desligado por inteiro sem afetar a árvore.

**Fidelidade é declarada, nunca presumida.** Todo `DataMutation` carrega `fidelity ∈ {EXACT, INFERRED, UNAVAILABLE}` e a UI mostra a diferença. Um delta derivado de parse de SQL não pode ter a mesma aparência de um item devolvido pelo DynamoDB.

### A parte arriscada: `ReturnValues` no DynamoDB

Para obter o `before` de um `PutItem`/`UpdateItem`/`DeleteItem`, o `ExecutionInterceptor` do TraceVanta **eleva** `ReturnValues` de `NONE` para `ALL_OLD` na requisição do desenvolvedor. Isso é modificar o comportamento do código alheio, e é a decisão mais arriscada da especificação. Ela só é aceitável com as quatro travas:

1. Desligável: `tracevanta.aws.dynamodb.capture-before=false`.
2. A resposta devolvida ao código da aplicação é **restaurada** ao formato original — os atributos que o TraceVanta pediu são removidos antes de o SDK entregar ao chamador. Existe teste dedicado que prova isso.
3. Ativa apenas em perfil de desenvolvimento (§8.4 da SPEC).
4. Documentada em letra grande no README, não em nota de rodapé.

> **Nota de implementação (registrada no M4, 2026-09):** a tabela da SPEC §4.10 afirma que `UpdateItem` devolve `before=ALL_OLD` e `after=ALL_NEW` "sem 2ª chamada". A API real do DynamoDB devolve **UM** conjunto de valores por chamada (campo único `Attributes`): com `ALL_NEW` sai o `after` exato e o `before` fica vazio; com `ALL_OLD` o inverso. Implementado o `after` exato (`ALL_NEW`) com `before=null`, exibido como ausência **declarada** na UI (invariante I3) e documentado no README. Obter ambos exigiria uma segunda chamada (pré-leitura, com janela de corrida — descartada nesta ADR) ou reconstrução do item a partir da expressão de update (complexidade desproporcional para o MVP).

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **Atributos de span com o payload** | `onEnd` é imutável; e vazaria dado sensível para exporters de terceiros |
| **Span Events** | Mesmo problema de mutabilidade e de vazamento; e o limite de atributos do OTel trunca sem aviso |
| **Leitura prévia do item (`GetItem` antes do `PutItem`)** | Dobra chamadas, custa latência e capacidade, e cria janela de corrida entre a leitura e a escrita |
| **DynamoDB Streams** | Só existe se a tabela tiver stream ligado, é assíncrono e chega tarde demais para a UI ao vivo |
| **Não ter delta de dados** | É o diferencial do produto (§2 da SPEC). Sem ele, o TraceVanta é um Jaeger local com catálogo |

## Consequências

**Boas.** O diferencial vira viável sem contaminar o pipeline OTel; sensível nunca sai do processo de origem; o canal é removível e testável isoladamente.

**Ruins, e assumidas.**

1. **Efeito colateral em código de terceiro** (Risco R-01) — travas acima; decisão D-3 no GATE 1.
2. **SQL não tem equivalente.** Sem `ReturnValues` em JDBC, restam `INFERRED` (parse do comando, sem tocar no banco) ou `before-image` opt-in (um `SELECT` na mesma transação, que muda custo e contenção). Padrão: `off`.
3. **Mais um caminho de dados para manter**, com sua própria correlação e seus próprios modos de falha. Mitigação: o Assembler trata mutação órfã (evento sem span) descartando-a após uma janela, sem quebrar a árvore.
4. **`TransactWriteItems` fica sem delta na v0.1** — declarado como `UNAVAILABLE`, não silenciado.
