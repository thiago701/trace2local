<!-- gerado por RealTraceJevLiveCheckTest (requer chave) — não editar à mão; rode o teste para atualizar -->
# Jev ao vivo sobre traces REAIS (Lambda + LocalStack)

> Traces de `trace2local-predictive/src/test/resources/real-traces/lambda-sqs/` (capturados de `examples/lambda-sqs` com `scripts/real-traces/capture.py`). Egress **estrutural** (sem payload/valores). Reproduzir: `JEV_API_KEY=… mvn -pl trace2local-predictive test -Dtest=RealTraceJevLiveCheckTest`.
>
> Leitura: *laudo final = só regras* conta decisões em que o laudo com Jev ficou igual ao só-determinístico. Em **prontidão** o Jev (meia voz na média ponderada) puxa "apta" para "apta com ressalvas" quando há espera longa na fila — divergência de julgamento, aceita e visível na UI como motor "fusão".

12 execuções reais · 3077 ms · modelo jev-1.13.0 · chamadas 12 · falhas 0 · latência média 243 ms · tokens de entrada 23267 · custo estimado US$ 9.77E-4

| família | decisões | laudo final = só regras | decididas pelo Jev | Jev divergiu, regra mantida |
|---|---|---|---|---|
| desfecho | 12 | 12 | 0 | 0 |
| prontidão | 12 | 3 | 12 | 0 |
| risco | 12 | 10 | 12 | 0 |
| papel do passo | 46 | 46 | 4 | 0 |
| classe de log | 114 | 114 | 57 | 0 |

## Divergências (amostra)

- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- risco em order-processor/COMPLETED: Jev decidiu 'No operational risk observed' (75%) onde a regra diria 'Low risk: minor latency or warnings only'
- prontidão em idempotent-processor/FAILED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- risco em order-processor/COMPLETED: Jev decidiu 'No operational risk observed' (75%) onde a regra diria 'Low risk: minor latency or warnings only'
- prontidão em idempotent-processor/FAILED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em idempotent-processor/FAILED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
- prontidão em order-processor/COMPLETED: Jev decidiu 'Approvable with caveats' (70%) onde a regra diria 'Ready for business approval'
