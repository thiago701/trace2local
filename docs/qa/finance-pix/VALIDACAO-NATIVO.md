# Validação do Trace2Local na stack alvo — finance-pix

> Gerado por `examples/finance-pix/scripts/journeys.py` em 2026-10-01 06:16 UTC.
> Stack: API Gateway (OpenAPI) → Lambda Java 25 (runtime provided.al2023) → DynamoDB · Postgres 16 · SQS · SNS · 5 APIs externas.

**Resultado: 61/61 verificações OK.**

| nível | OK | total |
|---|---|---|
| L1 | 13 | 13 |
| L2 | 17 | 17 |
| L3 | 13 | 13 |
| L4 | 3 | 3 |
| L5 | 7 | 7 |
| L6 | 8 | 8 |

## Verificações

| jornada | nível | verificação | resultado |
|---|---|---|---|
| J1 KYC indisponível | L1 | 502 PARTNER_UNAVAILABLE com o parceiro identificado | ✅ |
| J1 KYC indisponível | L2 | execução registrada (raiz = gatilho do API Gateway) | ✅ |
| J1 KYC indisponível | L2 | nó KYC vermelho com a exceção de rede | ✅ |
| J1 KYC indisponível | L2 | raiz não é falsamente ÓRFÃ (chamador não instrumentado = API Gateway) | ✅ |
| J1 KYC indisponível | L2 | sem reserva de saldo nem antifraude após a falha | ✅ |
| J1 KYC indisponível | L3 | Δ do DynamoDB: CREATE e depois DELETE da chave (libera nova tentativa) | ✅ |
| J1 KYC indisponível | L3 | saldo/retido inalterados no Postgres | ✅ |
| J1 KYC indisponível | L5 | conselheiro sugere plugar mock (HIGH) a partir do contrato kyc.yaml | ✅ |
| J2 Plugar mock do KYC | L5 | binding criado e RUNNING | ✅ |
| J2 Plugar mock do KYC | L5 | rota publicada para kyc.bureau.local:8080 | ✅ |
| J3 Pix aprovado e liquidado | L1 | 202 ACCEPTED com transferId e endToEndId de 32 posições | ✅ |
| J3 Pix aprovado e liquidado | L2 | KYC atendido pelo mock e marcado como SIMULADO | ✅ |
| J3 Pix aprovado e liquidado | L2 | ordem de negócio: idempotência → DICT → KYC → reserva → risco → aceite | ✅ |
| J3 Pix aprovado e liquidado | L4 | liquidação continua a MESMA árvore, como filha do nó SQS | ✅ |
| J3 Pix aprovado e liquidado | L4 | notificação continua a árvore como filha do nó SNS (consumidor do tópico) | ✅ |
| J3 Pix aprovado e liquidado | L2 | nenhum nó ÓRFÃO e execução COMPLETED | ✅ |
| J3 Pix aprovado e liquidado | L2 | SQL rotulado com operação + tabela | ✅ |
| J3 Pix aprovado e liquidado | L3 | DynamoDB: transferência SETTLED (estado final real) | ✅ |
| J3 Pix aprovado e liquidado | L3 | Δ UPDATE da árvore = estado no banco (status SETTLED) | ✅ |
| J3 Pix aprovado e liquidado | L3 | ledger: HOLD liquidado (SETTLED) de 150,00 | ✅ |
| J3 Pix aprovado e liquidado | L3 | saldo debitado exatamente uma vez; retido volta ao anterior | ✅ |
| J3 Pix aprovado e liquidado | L3 | notificação registrada como SENT | ✅ |
| J3 Pix aprovado e liquidado | L6 | logs CloudWatch correlacionados às 3 funções | ✅ |
| J4 Reenvio idempotente | L1 | mesma resposta (mesmo transferId) | ✅ |
| J4 Reenvio idempotente | L2 | nenhuma reserva, antifraude ou envio para liquidação no reenvio | ✅ |
| J4 Reenvio idempotente | L2 | a guarda condicional aparece (Conditional check) e a leitura da resposta gravada | ✅ |
| J4 Reenvio idempotente | L3 | saldo intacto | ✅ |
| J5 Conflito de idempotência | L1 | 409 IDEMPOTENCY_CONFLICT | ✅ |
| J6 Valor atípico → revisão | L1 | 202 IN_REVIEW | ✅ |
| J6 Valor atípico → revisão | L2 | sem liquidação (nada na fila) e evento de revisão no tópico | ✅ |
| J6 Valor atípico → revisão | L3 | saldo retido aumenta 7.000 (reserva mantida até a análise) | ✅ |
| J6 Valor atípico → revisão | L3 | DynamoDB: IN_REVIEW | ✅ |
| J7 Conta sinalizada → recusa | L1 | 422 REJECTED_BY_FRAUD | ✅ |
| J7 Conta sinalizada → recusa | L2 | recusa libera o saldo (passo de compensação presente) | ✅ |
| J7 Conta sinalizada → recusa | L3 | saldo e retido intactos no Postgres | ✅ |
| J7 Conta sinalizada → recusa | L2 | recusa de negócio não é ERRO na raiz (4xx ≠ 5xx) | ✅ |
| J8 Saldo insuficiente | L1 | 422 INSUFFICIENT_FUNDS | ✅ |
| J8 Saldo insuficiente | L2 | antifraude nunca consultado | ✅ |
| J8 Saldo insuficiente | L3 | nenhum lançamento para acc-002 | ✅ |
| J9 Chave inexistente | L1 | 422 INVALID_KEY | ✅ |
| J9 Chave inexistente | L2 | DICT 404 visível; KYC e reserva não acontecem | ✅ |
| J10 Consulta | L1 | 200 SETTLED com valor 150 | ✅ |
| J10 Consulta | L1 | 404 NOT_FOUND | ✅ |
| J11 Variação sob demanda (antifraude) | L5 | conselheiro detecta que /decision do antifraude decide o fluxo | ✅ |
| J11 Variação sob demanda (antifraude) | L5 | variação aplicada em modo sob demanda (repasse + baggage) | ✅ |
| J11 Variação sob demanda (antifraude) | L1 | decisão AUSENTE → regra de negócio manda para revisão (nunca aprova o desconhecido) | ✅ |
| J11 Variação sob demanda (antifraude) | L2 | nó do antifraude marcado com a variação aplicada | ✅ |
| J11 Variação sob demanda (antifraude) | L1 | sem baggage, a API real responde (APPROVED → ACCEPTED) | ✅ |
| J11 Variação sob demanda (antifraude) | L2 | repasse sem variação NÃO é marcado como resposta simulada | ✅ |
| J12 SPI com falha transitória | L5 | conselheiro aponta que só o caminho feliz do SPI foi exercitado | ✅ |
| J12 SPI com falha transitória | L5 | variação '503 só na 1ª chamada' aplicada | ✅ |
| J12 SPI com falha transitória | L1 | 202 ACCEPTED | ✅ |
| J12 SPI com falha transitória | L4 | duas tentativas de liquidação na MESMA árvore: 1ª vermelha, 2ª verde | ✅ |
| J12 SPI com falha transitória | L3 | liquidação concluída e débito ÚNICO apesar da reentrega | ✅ |
| L6 Visões da ferramenta | L6 | narrativa de negócio gerada | ✅ |
| L6 Visões da ferramenta | L6 | laudo/insights gerados para a execução | ✅ |
| L6 Visões da ferramenta | L6 | homologação de um Pix perfeito: apta, nenhuma regra violada (siglas e caminho de falha não viram violação) | ✅ |
| L6 Visões da ferramenta | L6 | sem falso positivo das regras preditivas na stack alvo (IDEM-002 por SQL parametrizado, PII já mascarado) | ✅ |
| L6 Visões da ferramenta | L6 | topologia mostra os 5 parceiros externos | ✅ |
| L6 Visões da ferramenta | L6 | anatomia: só a DLQ fica como declarada (nome real do recurso no IaC, sem duplicatas) | ✅ |
| L6 Visões da ferramenta | L6 | IaC (Terraform) indexado: tabelas, fila, tópico | ✅ |

## Latência observada (cliente → API Gateway → Lambda)

| requisição | s |
|---|---|
| J1 (cold) | 0.666 |
| J3 | 0.203 |
| J4 | 0.035 |
| J5 | 0.052 |
| J6 | 0.248 |
| J7 | 0.217 |
| J8 | 0.102 |
| J9 | 0.047 |
| J11 variação | 0.242 |
| J11 real | 0.221 |
| J12 | 0.203 |
