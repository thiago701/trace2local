# MCP do Trace2Local — E2E na stack alvo (finance-pix)

> Gerado por um cliente MCP real (stdio, JSON-RPC 2.0) contra o Station do compose, com `TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true`.
> Roteiro de agente: investigar a falha → comparar → laudo → validar variação de parceiro sob demanda → limpar.

**14/14 verificações OK**

| verificação | resultado | evidência |
|---|---|---|
| initialize negocia 2025-06-18 e se apresenta | ✅ | {'name': 'trace2local', 'title': 'Trace2Local', 'version': '0.1.0-SNAPSHOT'} |
| 21 ferramentas com mutações ligadas | ✅ | status, list_executions, get_execution, get_step, get_logs, diagnose_failure, explain_execution, list_insights, get_topo |
| 3 prompts | ✅ |  |
| status: Station acessível, capacidade mocks | ✅ |  |
| diagnose_failure acha a causa raiz (SPI 503) com caminho e laudo | ✅ | Execução 77cf7f2f-0cf0-4800-9906-08c0b21e1834 · FAILED · 26.64 s · raiz: pix-api · POST /pix/transfers |
| compare_executions: o que mudou entre a que passou e a que falhou | ✅ |  |
| explain_execution: Pix perfeito apto, sem violação falsa | ✅ | Sucesso em pix-api · POST /pix/transfers — homologação: apta |
| conselheiro sugere variações para o antifraude | ✅ | happy-d4bc378c → http-503 |
| apply_mock_suggestion sob demanda devolve o baggage | ✅ | Binding mock-antifraude: RUNNING · 1 stub(s) |
| list_endpoints traz o contrato | ✅ |  |
| dispatch_endpoint com baggage: execução chega e o antifraude aparece SIM com a variação | ✅ | Disparado:  trace f8b88d24de956feb81f90fdeecad8b23 |
| sem baggage: repasse da API real (↪), sem variação | ✅ |  |
| journal mostra a variação aplicada | ✅ |  |
| remove o binding criado (limpeza) | ✅ |  |

## Transcrição (saída das ferramentas, resumida)

### `status` {} — 165 ms

```text
Trace2Local acessível em http://127.0.0.1:19877/trace2local
modo companion · app station · versão 0.1.0 · runtime 21.0.12.1
capacidades: endpoints, stream, export, execute, mocks
buffer 0 · ao vivo 0 · linhas de log 633 · descartados 0
motor de decisão: deterministic (egress structural) · análises 57
MCP: mutações LIGADAS · dados structural
```

### `diagnose_failure` {} — 56 ms

```text
Execução 77cf7f2f-0cf0-4800-9906-08c0b21e1834 · FAILED · 26.64 s · raiz: pix-api · POST /pix/transfers

CAUSA(S) RAIZ — FATO OBSERVADO
- [3c5fe631cddb7829] HTTP_CLIENT POST SPI (BACEN) /spi/v1/settlements
  caminho: pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Liquidar no SPI › POST SPI (BACEN) /spi/v1/settlements
  erro: status do span: HTTP 503
  resposta do Mock Connect: [SIM binding=mock-spi-bacen; stub=repasse; passthrough=true; variation=v-falha-transitoria]
(+2 passo(s) ancestral(is) marcados com erro por propagação)

LOGS (erro/aviso e do passo)
  +401 ms ERROR [/aws/lambda/pix-settlement] Invocation failed: tech.neural7.trace2local.examples.pix.partners.PartnerUnavailableException: SPI (BACEN) indisponível: HTTP 503

LAUDO
  desfecho: Falha técnica (93%, jev-deterministic) — erro com assinatura técnica (PartnerUnavailableException)
  risco: alto (75%, jev-deterministic) — 3 passo(s) com erro; execução FAILED; duração alta (26641 ms); escrita persistida antes da falha (efeito parcial)
  anomalia (alta): Erro em pix-settlement · SQS pix-settlement — PartnerUnavailableException: SPI (BACEN) indisponível: HTTP 503
  anomalia (alta): Erro em Liquidar no SPI — PartnerUnavailableException: SPI (BACEN) indisponível: HTTP 503
  anomalia (alta): Erro em POST SPI (BACEN) /spi/v1/settlements — status do span: HTTP 503
  insight PERF-ASYNC-001 (correlation, 90%): Latência elevada no processamento assíncrono

PRÓXIMOS PASSOS
- get_step(77cf7f2f-0cf0-4800-9906-08c0b21e1834, 3c5fe631cddb7829) para at
```

### `list_executions` {"status": "COMPLETED", "query": "POST /pix/transfers", "limit": 50} — 35 ms

```text
5 execução(ões):
- f770bc08-f168-4951-a0ca-e5987a2f67fd · pix-api · POST /pix/transfers · COMPLETED · 4.39 s · 34 passos · LAMBDA_EVENT · 2026-10-01T06:12:24.537812250Z
- c284c997-560a-450e-8e5a-e0f025959974 · pix-api · POST /pix/transfers · COMPLETED · 257 ms · 24 passos · LAMBDA_EVENT · 2026-10-01T06:12:18.263893235Z
- 5b7e9d1f-6c5e-4ff8-b3e0-8470a9794f06 · pix-api · POST /pix/transfers · COMPLETED · 258 ms · 27 passos · LAMBDA_EVENT · 2026-10-01T06:11:50.868483581Z
- 86bae547-f949-4ca9-ae55-53d89dfa8e92 · pix-api · POST /pix/transfers · COMPLETED · 243 ms · 24 passos · LAMBDA_EVENT · 2026-10-01T06:11:44.364769916Z
- 21905d46-3bcc-45ff-bb35-0e67225b6c9f · pix-api · POST /pix/transfers · COMPLETED · 2.91 s · 34 passos · LAMBDA_EVENT · 2026-10-01T06:11:28.624400205Z
```

### `compare_executions` {"a": "f770bc08-f168-4951-a0ca-e5987a2f67fd", "b": "77cf7f2f-0cf0-4800-9906-08c0b21e1834"} — 31 ms

```text
A f770bc08-f168-4951-a0ca-e5987a2f67fd · COMPLETED · 4.39 s · 34 passos
B 77cf7f2f-0cf0-4800-9906-08c0b21e1834 · FAILED · 26.64 s · 37 passos

SÓ EM A (não aconteceu em B)
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Debitar no ledger
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Debitar no ledger › SQL: UPDATE ledger_entries
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Debitar no ledger › SQL: UPDATE accounts
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Concluir transferência
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Concluir transferência › DynamoDB: pix-transfers
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Publicar liquidação
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS pix-settlement › Publicar liquidação › SNS: pix-events
- pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement
```

### `explain_execution` {"executionId": "f770bc08-f168-4951-a0ca-e5987a2f67fd", "audience": "executive"} — 64 ms

```text
EXECUTIVO
Sucesso em pix-api · POST /pix/transfers — homologação: apta
A execução observada percorreu 34 passo(s) em 4392 ms, alterando dados em 9 ponto(s). Desfecho: sucesso; risco baixo. Regras: 4 respeitada(s), 0 violada(s), 1 não exercitada(s), 6 inconclusiva(s).
desfecho: Sucesso (100%, fact) — status COMPLETED, nenhum passo com erro
prontidão para homologar: apta (70%, jev-deterministic) — fluxo íntegro e regras atendidas
risco: baixo (75%, jev-deterministic) — duração alta (4392 ms)
alterou dados: sim · efeitos assíncronos consumidos: sim
REGRAS × FLUXO
- R1 RESPEITADA · pix-api: API de iniciação de Pix (API Gateway → Lambda). Regra: toda transferência aceita deve reservar o saldo ant
```

### `list_mock_suggestions` {"host": "antifraude.partner.local"} — 10 ms

```text
- [happy-d4bc378c] LOW · HAPPY_PATH_ONLY · antifraude.partner.local:8080
  Só o caminho feliz de Antifraude foi exercitado
  por quê: As 5 chamada(s) observadas a Antifraude (POST /v1/score) só exercitaram sucesso. O tratamento de erro, timeout, retry e campos ausentes do microsserviço nunca rodou — plugue o mock com variações para cobrir esses caminhos antes da homologação.
  evidência: POST /v1/score → HTTP 200 → execução 77cf7f2f-0cf0-4800-9906-08c0b21e1834 passo 47e1937eb6b76a33
  variações: http-503 http-500 http-429 falha-transitoria timeout lento conexao-resetada resposta-vazia decision-ausente score-ausente campo-extra
    http-503 — Indisponível (503): parceiro fora do ar: o serviço degrada com elegância?
    http-500 — Erro interno (500): erro inesperado do parceiro
    http-429 — Limite de taxa (429 + Retry-After): throttling: respeita Retry-After?
    falha-transitoria — 503 só na 1ª chamada: falha transitória: o retry recupera? (predicado próprio; não selecionável por baggage)
    timeout — Timeout (15 s sem resposta): o timeout do cliente está configurado?
    lento — Lentidão (3 s): latência alta: SLA e timeouts encadeados
    conexao-resetada — Conexão derrubada: fa
```

### `apply_mock_suggestion` {"suggestionId": "happy-d4bc378c", "variations": ["http-503"], "mode": "on-demand"} — 57 ms

```text
Binding mock-antifraude: RUNNING · 1 stub(s)
endpoint: http://trace2local-station:19878/mock-antifraude
ative por requisição com o cabeçalho → baggage: t2l.mock=http-503
Clientes com Trace2LocalHttp e TRACE2LOCAL_MOCKS_ROUTING=on já chamam o mock; o passo aparece como SIM.
```

### `list_endpoints` {} — 51 ms

```text
- createTransfer · POST /pix/transfers · Inicia uma transferência Pix (assíncrona — liquidação via SPI)
    header Idempotency-Key (obrigatório) ex.: (gerado a cada disparo)
    corpo de exemplo: { "payerAccountId" : "acc-001", "pixKey" : "joao@pix.example", "amount" : 150.0, "description" : "almoço" }
- getTransfer · GET /pix/transfers/{transferId} · Consulta o estado de uma transferência
    path transferId (obrigatório)
```

### `dispatch_endpoint` {"endpointId": "createTransfer", "body": {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 10.0, "description": "mcp-e2e"}, "headers": {"bag — 5622 ms

```text
Disparado:  trace f8b88d24de956feb81f90fdeecad8b23
Execução c02627c4-4430-4d8b-a52e-ad8a3211b2e9 · FAILED · 250 ms · gatilho LAMBDA_EVENT · trace f8b88d24de956feb81f90fdeecad8b23
passos 18 · profundidade 3 · spans perdidos 0 · eventos descartados 0
Legenda: [nodeId] TIPO rótulo — duração (self) · ✕ erro · Δ dado alterado · SIM resposta simulada · ↪ repasse · ⧗ espera em fila
[accde84c752dd3cf] LAMBDA pix-api · POST /pix/transfers — 250 ms (self 1 ms) ERROR ✕ status do span: HTTP 502
  [872cce32ed9558d3] BUSINESS Validar pedido — 0 ms (self 0 ms)
  [1217b4a3ea0eac5e] BUSINESS Garantir idempotência — 19 ms (self 2 ms)
    [45bd4b7e4146c439] DYNAMODB DynamoDB: pix-idempotency — 16 ms (self 16 ms) Δ CREATE pix-idempotency chave=ui-a735515982269f0b campos=state, payloadHash, idempotencyKey, expiresAt
  [b4c180743dc820bc] BUSINESS Resolver chave Pix (DICT) — 48 ms (self 0 ms)
    [5b4814a9956e150e] HTTP_CLIENT GET DICT (BACEN) /api/v2/entries/{id} — 47 ms (self 47 ms)
  [373fec39caedb0bf] BUSINESS Verificar KYC e limites — 7 ms (self 0 ms)
    [620e483aee67dc03] HTTP_CLIENT GET KYC & Limites /v2/customers/{id}/limits — 7 ms (self 7 ms) [SIM binding=mock-kyc-limites; stub=consultarLimites]
  [a5298c0bf3da8e15] BUSINESS Reservar saldo — 23 ms (self 17 ms)
    [4b6fcf026fa98e97] SQL SQL: SELECT accounts — 3 ms (self 3 ms)
    [0146c862cc1cd307] SQL SQL: INSERT ledger_entries — 1 ms (self 1 ms) Δ CREATE ledger_entries (inferred)
    [c403c10b84969968] SQL SQL: UPDATE accounts — 0 ms (self 0 ms) Δ UPDATE accounts chave=id = ? (inferred)
  [94b318e12505ccf2] BUSINESS Avaliar risco (antifraude) — 116 ms (self 0 ms) ERROR ✕ tech.neural7.trace2local.examples.pix.partners.PartnerUnavailableException: Antifraude indisponível: HTTP 503
    [6a1e8205905990c0] HTTP_CLIENT POST Antifraude /v1/score — 115 ms (self 115 ms) ERROR ✕ status do span: HTTP 503 [SIM binding=mock-antifraude; stub=repasse; passthrough=true; variation=v-http-503]
  [e388999dd6a25a72] BUSINESS Liberar saldo reservado — 24 ms (self 18 ms)
    [fd2355397d217753] SQL SQL: UPDATE ledger_entries — 3 ms (self 3 ms) Δ UPDATE ledger_entries chave=transfer_id = ? AND entry_type = 'HOLD' AND status = 'PENDI… (inferred)
    [0ad08e992e387394] SQL SQL: UPDATE accounts — 1 ms (self 1 ms) Δ UPDATE accounts chave=id = ? (inferred)
  [8cb88f833ea2c74c] DYNAMODB DynamoDB: pix-idempotency — 10 ms (self 10 ms) Δ DELETE pix-idempotency chave=ui-a735515982269f0b campos=payloadHash, state, idempotencyKey, expiresAt
```

### `dispatch_endpoint` {"endpointId": "createTransfer", "body": {"payerAccountId": "acc-001", "pixKey": "joao@pix.example", "amount": 10.0, "description": "mcp-e2e"}, "timeoutSeconds" — 5614 ms

```text
Disparado:  trace fab9b0615e3ef187818c1463258cc5ff
Execução 705ccb15-6e5a-4c15-81da-df357bc14473 · COMPLETED · 221 ms · gatilho LAMBDA_EVENT · trace fab9b0615e3ef187818c1463258cc5ff
passos 19 · profundidade 3 · spans perdidos 0 · eventos descartados 0
Legenda: [nodeId] TIPO rótulo — duração (self) · ✕ erro · Δ dado alterado · SIM resposta simulada · ↪ repasse · ⧗ espera em fila
[e1795014a32c1e65] LAMBDA pix-api · POST /pix/transfers — 221 ms (self 1 ms)
  [061aea370ea7cda3] BUSINESS Validar pedido — 0 ms (self 0 ms)
  [165c1e65688b5ff3] BUSINESS Garantir idempotência — 9 ms (self 0 ms)
    [8e
```

### `get_mock_journal` {"binding": "mock-antifraude", "limit": 5} — 48 ms

```text
- 2026-10-01T06:14:23.259005483Z · mock-antifraude · POST /v1/score → 200 · stub repasse · stub · 53 ms · trace fab9b0615e3ef187818c1463258cc5ff
- 2026-10-01T06:14:17.677059337Z · mock-antifraude · POST /v1/score → 503 · stub repasse · variação v-http-503 · stub · 61 ms · trace f8b88d24de956feb81f90fdeecad8b23
```

### `control_mock_binding` {"name": "mock-antifraude", "action": "delete"} — 44 ms

```text
Binding mock-antifraude: delete ok
```

