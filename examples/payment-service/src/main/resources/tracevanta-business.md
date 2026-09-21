# Glossário de negócio (demo payment-service — storytelling)
- ProcessarPagamento: Processa o Pix — grava o pagamento na tabela e dispara a notificação do pagador.
- ConfirmarPagamento: Confirma o Pix (regra: só um pagamento PENDING pode ser confirmado).
- NotificarPagador: Avisa o pagador de que o Pix foi recebido (integração externa simulada).
- BuscarPagamento: Consulta o estado atual do Pix pelo pagador.
- payments: Tabela de pagamentos Pix do sistema.
