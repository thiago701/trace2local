# EVIDÊNCIAS — Novo domínio (pagamentos Pix): análise do canvas e do storytelling

> Cenário: **`examples/payment-service`** (novo projeto demo, domínio DIFERENTE dos
> pedidos) — Spring Boot + Trace2Local Embedded (:9876) + DynamoDB no LocalStack,
> fluxos criar / duplicado / confirmar. Data: 2026-09-21.

## O que foi feito

1. Novo módulo `examples/payment-service` (registrado no reactor): Pix com
   **guarda de idempotência** (escrita condicional), confirmação com
   **read-back** (UpdateItem before+after EXACT) e **glossário de negócio**
   próprio (`trace2local-business.md`).
2. App rodada ao vivo contra o LocalStack; fluxos reais via HTTP:
   `created → duplicated:true (recusado) → CONFIRMED` (respostas verificadas).
3. Canvas analisado por script dedicado (`capture-payment-story.mjs`):
   consistência UI↔API, simplicidade e clareza da narrativa — com **loop de
   melhoria**: a primeira análise encontrou 3 problemas de clareza, corrigidos
   no `StoryService` e revalidados.

## 1. Consistência — ✅

| Verificação | Resultado |
|---|---|
| Labels do canvas × API | `5 nós = 5 nós, labels idênticos` |
| Árvore bate com o que aconteceu | duplicado: `POST /pix → ProcessarPagamento → DynamoDB ERROR (sem delta) + GetItem (somente leitura) → NotificarPagador` |
| Estado do banco | 3 chaves distintas, `Count` consistente; duplicado não reescreveu nada |
| Zero erros de JS no console | ✅ |

## 2. Simplicidade — ✅

- **Zero configuração de storytelling**: 3 endpoints descobertos automaticamente
  (`post:/pix`, `get:/pix/{key}`, `post:/pix/{key}/confirm`), 4 abas
  (CANVAS/STORY/DASHBOARD/COMPARAR), glossário opcional (sem ele, inferência).
- Fluxo de leitura: **2 cliques** (execução → STORY) ou **1 clique** (NOTAS).
- Nenhuma dependência nova; CSP/ADR-005 intactos.

## 3. Clareza — ✅ (após o loop de melhoria)

Narrativa real capturada do duplicado:

> **Jornada externa — POST /pix**
> *A execução começou quando o serviço recebeu uma chamada externa (POST /pix).*
> 1. Porta de entrada: requisição POST /pix (status 200).
> 2. **Processa o Pix — grava o pagamento na tabela e dispara a notificação do pagador.**
> 3. Tabela de pagamentos Pix do sistema. — **este passo FALHOU**: *The conditional request failed…* (guarda de idempotência)
> 4. Tabela de pagamentos Pix do sistema. **(somente leitura).**
> 5. **Avisa o pagador de que o Pix foi recebido** (integração externa simulada).
> *No fim, a jornada terminou com erro — 1 passo(s) vermelho(s) na árvore em 169 ms…*

Heurísticas automáticas: `allStepsReadable=true` (todos os passos entre 20–220
caracteres), verbo de negócio presente, conclusão com status/duração, botão
COPIAR COMO MARKDOWN, 15 linhas de nota no canvas com NOTAS.

### Melhorias de clareza aplicadas no loop (encontradas pela própria análise)

| Problema encontrado | Correção |
|---|---|
| Passo com ERRO não explicava a falha no texto (só no meta) | glossário + **"— este passo FALHOU: {mensagem}"** |
| Escrita recusada e leitura ficavam com a MESMA nota | sufixo **"(somente leitura)"** para READ_ONLY |
| Título/intro não citavam o endpoint | **"Jornada externa — POST /pix"** e intro com a rota |

## 4. Simulação cognitiva (loop de revisão da associação história↔nó)

**Métodos pesquisados e aplicados** — a literatura de avaliação de UX de
ferramentas de desenvolvedor recomenda *heuristic walkthroughs*
([ScienceDirect: heuristic walkthroughs para dev tools](https://www.sciencedirect.com/science/article/pii/S0950584923000745)),
*linking & brushing* para associação entre visões coordenadas
([VRVis: linking & brushing em CMV](https://www.vrvis.at/publications/pdfs/PB-VRVis-2023-026.pdf))
e métricas de eficiência estilo GOMS/Cognitive Task Analysis. Aplicados como
**simulações por persona automatizadas** no script de captura:

| Persona | Tarefa simulada | Resultado |
|---|---|---|
| **PO** (não técnico) | lê a história em 2 cliques; passos sem ruído técnico (UUIDs/Request IDs) | ✅ `poNoUuidNoise=true` |
| **DEV** (linking & brushing) | **1 clique** no passo 2 da STORY leva ao nó correspondente no canvas (selecionado + pulso) | ✅ `devOneClickLinksNode=true`, `devPulseOnClick=true` |
| **QA** | acha o passo com ⚠ FALHOU, abre o inspector com o erro, tag de erro presente | ✅ 3/3 |

### Encadeamento história↔nó implementado (a partir da simulação)

1. **Numeração compartilhada**: o balão de nota no canvas exibe o MESMO número
   do passo na aba STORY — o vínculo é visível sem ler o texto;
2. **Conector tracejado**: cada balão de nota é ligado ao seu cartão de nó por
   uma linha tracejada;
3. **Passos clicáveis**: clicar (ou Enter/Espaço) num passo muda para o canvas,
   seleciona o nó e aplica um **pulso visual** de 1,9 s — o usuário nunca
   perde o fio do trace;
4. **Brushing reverso**: selecionar um nó no canvas destaca o passo
   correspondente na narrativa;
5. **Swatch de cor do kind** em cada passo (mesmas cores da legenda da árvore).

### Correção de clareza achada pela persona PO no loop

A narrativa do passo com falha exibia o erro CRU do SDK
("…Request ID: 6d5ae8db-… (SDK Attempt Count: 1)") — ruído técnico indecifrável
para um PO. Corrigido no `StoryService.cleanErrorMessage`: agora o passo diz
apenas **"este passo FALHOU: The conditional request failed."** (com teste
unitário novo).

## Telas

| Tela | Conteúdo |
|---|---|
| [`19-payment-tree.png`](screenshots/19-payment-tree.png) | árvore do duplicado no domínio de pagamentos |
| [`20-payment-story.png`](screenshots/20-payment-story.png) | aba STORY com a narrativa de negócio |
| [`21-payment-notes.png`](screenshots/21-payment-notes.png) | canvas com NOTAS ao lado dos nós |

## Conclusão da análise

O storytelling se mostrou **domínio-agnóstico**: o mesmo pipeline que explicou
pedidos explicou pagamentos Pix sem mudança de código na lib — apenas o
glossário do projeto. A narrativa é consistente com a árvore (validação
automatizada), simples de acessar (2 cliques) e clara para um público não
técnico após as correções do loop (falhas explicadas, leituras distinguidas,
endpoint no título). Limitação declarada: textos de erro crus do SDK aparecem
na nota do passo com falha (truncados a 140 caracteres) — aceitável para um
monitor de dev-time; o glossário pode sobrescrever o nó para vocabulário da
empresa.
