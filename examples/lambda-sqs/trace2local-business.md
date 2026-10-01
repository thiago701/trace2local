# Glossário de negócio (demo lambda-sqs) — montado no Station pelo compose
# Formato: "- termo: descrição". Frases com "deve", "só", "nunca" ou "regra" viram
# REGRAS homologáveis: a Investigação cruza cada uma com os passos da árvore.
- order-processor: Regra: todo pedido aceito deve ser gravado em orders e publicado em orders-queue; pedido acima do limite de crédito deve ser recusado sem publicar evento.
- order-billing: Regra: só cobra pedido já gravado e o marca como BILLED uma única vez.
- IdempotencyGuard: Regra: a mesma chave só pode gravar uma vez; duplicados devem ser recusados sem efeito colateral.
- orders-queue: Fila de pedidos — o consumidor de cobrança continua o mesmo trace.
- orders: Tabela de pedidos da aplicação.
- idempotency: Tabela da guarda de idempotência (uma entrada por chave).
