# ADR-004 — Server-Sent Events com um único stream por aba

- **Status:** Aceita (2026-09-18)

## Contexto

A UI precisa receber a árvore **enquanto ela acontece**: nós aparecendo, latências fechando, mutações chegando. Isso é tráfego unidirecional servidor→navegador, com rajadas curtas e intensas (uma execução inteira em dezenas de milissegundos) e longos períodos de silêncio.

A comunicação cliente→servidor já é resolvida por REST (`POST /api/execute`): não há necessidade de canal bidirecional persistente.

Fatos relevantes:

- `EventSource` **reconecta sozinho** por padrão, com intervalo configurável via `retry:` e retomada por `Last-Event-ID`. WebSocket não tem nada disso nativamente — reconexão vira código da aplicação.
- Navegadores limitam **6 conexões TCP por origem em HTTP/1.1**, e um stream aberto ocupa uma delas por toda a sua vida. Há casos documentados de UI que satura o limite com múltiplas abas/painéis SSE — e o efeito colateral, aqui, seria travar a aplicação do próprio desenvolvedor, que compartilha a origem.
- Em GraalVM Native Image, SSE sobre WebFlux/MVC roda nas stacks oficialmente suportadas; WebSocket com STOMP teve *gap* específico de metadados de reflexão para handlers `@MessageMapping` — historicamente mais espinhoso.

## Decisão

**SSE** como transporte de push, com três regras normativas:

1. **Um único `EventSource` por aba**, multiplexando todos os tipos de evento (`execution.started`, `node.upserted`, `node.mutation`, `execution.completed`, `system.warning`). Nunca um stream por painel.
2. **`id:` monotônico por execução**, `retry: 2000` e heartbeat de comentário a cada 15 s.
3. **Coalescência no servidor**: no máximo 20 frames/s por execução, agregando mutações no intervalo. Uma árvore com 300 nós não vira 300 frames.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **WebSocket** | Ganha bidirecionalidade que não precisamos e perde reconexão automática; superfície maior em Native Image; handshake e, com STOMP, um broker |
| **WebSocket + STOMP** | Tudo acima, mais uma biblioteca de cliente no bundle da UI |
| **Long polling** | Latência e desperdício; pior nas rajadas, que é justamente o nosso caso |
| **Polling simples** | Mataria a sensação de "ver a requisição viajar", que é a proposta do produto |

> Nota de honestidade: a pesquisa **não** encontrou um censo formal de qual transporte as ferramentas de dev-tooling escolhem. A decisão se apoia nas propriedades técnicas acima — reconexão nativa, menor superfície AOT, menor complexidade — e não em "todo mundo faz assim".

## Consequências

**Boas.** Reconexão grátis; um `GET` com `text/event-stream` é trivial de depurar (`curl` mostra o stream); menor risco em Native Image; nada novo no bundle da UI.

**Ruins, e assumidas.**

1. **Só servidor→cliente.** Qualquer interação futura que exija push do cliente (cancelar execução em andamento, por exemplo) precisa de um `POST`. Aceitável: é um clique, não um fluxo contínuo.
2. **Consome uma das 6 conexões HTTP/1.1 da origem.** Mitigado pela regra do stream único; se a aplicação servir a UI na sua própria porta e já usar muitas conexões, o efeito aparece — documentar e recomendar porta própria (padrão 9876).
3. **Sem binário.** Payloads vão em JSON, texto. Custo aceitável para volumes de debug local; se virar problema, comprimir por `Content-Encoding`, não trocar de transporte.
