# ADR-012 — Logs no estilo CloudWatch presos ao span, na linha do tempo

- **Status:** Aceita (2026-09-30)
- **Relacionadas:** ADR-002 (modos), ADR-007 (redaction na origem), ADR-014 (continuação tardia)

## Contexto

O pedido era "integrar uma nova linha do tempo para contar de forma clara e didática os logs com base no CloudWatch". Em Lambda, a história de uma invocação está no CloudWatch Logs: `START RequestId`, o stdout da função, `END`, `REPORT` (duração, memória, *cold start*). Antes, o Trace2Local não tinha modelo de log algum.

## Decisão

1. **Modelo `LogEntry`** (core): instante, nível, logger, mensagem, `traceId`/`spanId`, **`requestId`**, `logGroup`/`logStream` e **origem** (`APP` · `PLATFORM` sintética · `CLOUDWATCH` real).
2. **`LogStore`** (core): índices por trace e por RequestId (LRU 400 × 2000 linhas), deduplicação que **prefere a linha real** do CloudWatch à sintética, contadores de descarte, e **redaction de texto livre na entrada** (`TextRedactor`: credenciais em URL, `chave=valor` sensível, JWT, Bearer, chaves AWS, e-mail, CPF/CNPJ, cartão com Luhn).
3. **Três fontes, um contrato:**
   - *Lambda (Companion)*: `LambdaLogCapture` faz *tee* do stdout/stderr durante a invocação, sintetiza `INIT_START/START/END/REPORT` e publica em `/t2lingest/v1/logs` (Bearer do Station).
   - *Station*: `CloudWatchLogsTail` lê o CloudWatch **do LocalStack** (`TRACE2LOCAL_CLOUDWATCH_ENDPOINT`), atribui RequestId às linhas entre `START`/`END` e **dobra stack traces** (um evento por frame no LocalStack) numa única ocorrência `ERROR`.
   - *Embedded*: logs da app com `trace_id`/`span_id` no MDC (starter).
4. **Correlação**: linha com `spanId` → aquele passo; linha de plataforma → nó Lambda cujo `faas.invocation_id` = RequestId.
5. **Alinhamento honesto**: o LocalStack carimba as linhas **na ingestão** (depois da invocação). `START`/`END`/`REPORT` são presos às bordas do span da invocação e linhas do CloudWatch fora da janela são trazidas para dentro dela — o JSON traz `aligned` + `observedOffsetMs`, e a UI marca `≈` com o carimbo original no tooltip.

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| Assinar SigV4 e ler CloudWatch da AWS real | Lidar com credenciais reais contraria a ADR-007; o alvo é o ambiente local |
| Só logs sintéticos | Perde o que o dev vê de verdade (stack do runtime, REPORT real) |
| Ordenar pelo carimbo observado | Narrativa errada (START depois do log da função) — achado no caso real |

## Consequências

Na UI, cada linha tem nível, classe (erro de negócio/técnico, evento de negócio, diagnóstico, plataforma — micro-decisão da ADR-011), log group/stream e salto para o passo. **Limites declarados:** o tail do CloudWatch é para LocalStack (sem assinatura); linhas sem `spanId` nem RequestId ficam em "sem span"; o `LogStore` é limitado e declara descarte.
