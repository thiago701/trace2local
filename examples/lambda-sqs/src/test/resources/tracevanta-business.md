# Glossário de negócio (demo lambda-sqs — storytelling)
- order-processor: Função que recebe o pedido, grava no DynamoDB e publica o evento na fila.
- IdempotencyGuard: Guarda de idempotência — a mesma chave só grava uma vez; duplicados são recusados sem efeito colateral.
- orders-queue: Fila de pedidos — o consumidor de cobrança continua o mesmo trace.
- order-billing: Consumidor que cobra o pedido e o marca como BILLED.
- orders: Tabela de pedidos da aplicação.
- idempotency: Tabela da guarda de idempotência (uma entrada por chave).
