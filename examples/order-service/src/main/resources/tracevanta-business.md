# Glossário de negócio (demo order-service — storytelling)
- CreateOrder: Cria o pedido — grava no DynamoDB e publica o evento de confirmação.
- ConfirmOrder: Confirma o pedido (condição: status = PENDING); tentativa duplicada gera erro visível.
- BillOrder: Cobra o pedido confirmado e o marca como BILLED.
- order-events: Eventos do ciclo de vida do pedido.
- billing-queue: Fila de cobrança — consumida pelo BillingConsumer da aplicação.
