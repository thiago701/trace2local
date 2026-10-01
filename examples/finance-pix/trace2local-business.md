# Glossário de negócio (demo finance-pix) — montado no Station pelo compose
# Formato: "- termo: descrição". Frases com "deve", "só", "nunca" ou "regra" viram
# REGRAS homologáveis: a Investigação cruza cada uma com os passos da árvore.
- pix-api: API de iniciação de Pix (API Gateway → Lambda). Regra: toda transferência aceita deve reservar o saldo antes de consultar o antifraude e deve ser enviada para liquidação na fila pix-settlement.
- Garantir idempotência: Regra: a mesma Idempotency-Key só pode criar uma transferência; reenvio com o mesmo payload deve devolver a mesma resposta, e payload diferente deve ser recusado com 409.
- Resolver chave Pix: Consulta a chave no DICT (BACEN) para descobrir a conta do recebedor. Regra: chave inexistente deve ser recusada sem reservar saldo.
- Verificar KYC e limites: Regra: pagador bloqueado ou valor acima do limite do dia deve ser recusado sem reservar saldo.
- Reservar saldo: Regra: o saldo disponível nunca pode ficar negativo; a reserva (HOLD) só é liberada por recusa ou falha técnica.
- Avaliar risco: Antifraude do parceiro. APPROVED segue para liquidação, REVIEW vai para análise humana e DENIED recusa. Regra: decisão desconhecida nunca pode aprovar — deve ir para revisão.
- Recusar transferência: Regra: recusa do antifraude deve liberar o saldo reservado e publicar PIX_REJECTED.
- Liberar saldo reservado: Compensação — devolve ao disponível o valor retido.
- pix-settlement: Liquidação no SPI (consome a fila). Regra: só debita o ledger depois do SPI confirmar SETTLED, e uma reentrega nunca pode debitar duas vezes.
- Liquidar no SPI: Chamada ao SPI (BACEN). SPI fora do ar faz a mensagem voltar para a fila (reentrega); após 3 tentativas vai para a DLQ.
- pix-notifier: Notificação ao cliente a partir dos eventos de domínio (SNS). Regra: falha no provedor de notificação nunca desfaz o Pix — deve ficar registrada como FAILED.
- pix-transfers: Tabela de estado das transferências (ACCEPTED, IN_REVIEW, REJECTED, SETTLED, FAILED).
- pix-idempotency: Tabela da guarda de idempotência (TTL de 24 h).
- pix-settlement: Fila de liquidação — o consumidor continua o mesmo trace da requisição.
- pix-events: Tópico de eventos de domínio (PIX_SETTLED, PIX_REJECTED, PIX_REVIEW_REQUIRED).
- ledger_entries: Lançamentos do ledger (HOLD → SETTLED ou RELEASED).
- accounts: Contas com saldo (balance) e valor retido (held).
