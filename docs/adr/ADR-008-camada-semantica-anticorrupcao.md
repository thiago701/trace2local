# ADR-008 — Nenhum nome de atributo do OpenTelemetry fora do módulo de ponte

- **Status:** Aceita (2026-09-18)
- **Relacionada:** ADR-001, Risco R-03

## Contexto

O TraceVanta depende das convenções semânticas do OpenTelemetry para saber que um span **é** uma escrita no DynamoDB, uma publicação no SNS ou um `UPDATE` em SQL — é disso que sai o ícone, o rótulo "DynamoDB: orders" e os campos do inspector.

O problema é que essas convenções estão em estágios diferentes de maturidade. Verificado em 2026-09-18:

| Domínio | Status | Exemplos |
| :--- | :--- | :--- |
| Banco de dados | **Estável** | `db.system.name`, `db.namespace`, `db.collection.name`, `db.operation.name`, `db.query.text` |
| Mensageria | **Development** (a página inteira) | `messaging.system`, `messaging.destination.name`, `messaging.operation.type` |
| AWS | **Development** | `aws.dynamodb.*`, `aws.sqs.queue.url`, `aws.sns.topic.arn` |

Ou seja: **a metade do modelo mental do produto que trata de filas e AWS repousa sobre nomes que podem mudar em release menor do OTel.** Espalhar essas strings pelo código é garantir uma caça a literais a cada bump de versão.

## Decisão

Uma **camada anti-corrupção** com fronteira normativa: nenhum nome de atributo do OTel **DEVE** aparecer fora do módulo `tracevanta-otel`. Existe uma única classe de tradução:

```java
public interface SemanticMapper {
    Optional<NodeKind> kindOf(SpanData span);      // DYNAMODB, SQS, SNS, SQL, HTTP_SERVER...
    NodeLabel labelOf(SpanData span);              // "DynamoDB: orders"
    Map<String, String> inspectorFieldsOf(SpanData span);
}
```

Três regras que a acompanham:

1. **Degradação graciosa obrigatória.** Span não reconhecido vira `NodeKind.UNKNOWN`, rotulado a partir de `span.name`. O mapper **nunca** lança exceção e **nunca** faz um nó desaparecer — um nó genérico é infinitamente melhor que um buraco na árvore.
2. **Teste de contrato por versão de semconv suportada**, com fixtures de `SpanData` reais gravados por versão. Bump do OTel que quebre o mapeamento falha o CI, não a experiência do usuário.
3. **A fronteira é verificada por ArchUnit**: qualquer literal `db.`, `messaging.` ou `aws.` fora do módulo de ponte reprova o build.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **Usar os atributos do OTel direto no TVEM e na UI** | Acopla o modelo público do produto a um vocabulário instável; cada bump vira caça a string espalhada |
| **Esperar as convenções estabilizarem** | Mensageria está em *development* há anos; esperar é não construir |
| **Fork das convenções** | Custo de manutenção e divergência do ecossistema — perde a interoperabilidade que motivou o ADR-001 |
| **Depender só de `span.name`** | Frágil e inconsistente entre instrumentações |

## Consequências

**Boas.** Bump do OTel afeta uma classe testada; o TVEM (modelo público) fica estável mesmo com o vocabulário abaixo se mexendo; o mapeamento vira ponto natural para enriquecimento próprio (o que transforma telemetria em narrativa legível).

**Ruins, e assumidas.**

1. **Uma indireção a mais** entre span e nó. Paga-se em legibilidade do código, ganha-se em estabilidade — e a fronteira é simples de explicar.
2. **Fixtures precisam ser mantidos** por versão suportada. Custo real, mas é o único jeito de detectar quebra no CI em vez de no usuário.
3. **Cobertura sempre atrasada** em relação a convenções novas. Mitigação: `UNKNOWN` funcional e a SPI (§4.7) para quem quiser mapear antes de nós.
