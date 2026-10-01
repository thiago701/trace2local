# Trace2Local Resonance — desenho de experiência (UI v4)

> O dev deve sentir que está usando um **scanner de ressonância** do próprio sistema: ver a anatomia, fazer cortes
> no tempo, receber um laudo — e, quando um parceiro falta ou precisa variar, plugar um mock ali mesmo.
> Telas: [qa/screenshots/v4-resonance](qa/screenshots/v4-resonance/) · aceite: [qa/ACEITE.md](qa/ACEITE.md) ·
> loop por persona na stack alvo: [qa/finance-pix/UX-LOOP-PERSONAS.md](qa/finance-pix/UX-LOOP-PERSONAS.md) (**69/69**).

## 1. Identidade visual v4 (agnóstica a tema)

Uma interface noturna, técnica e silenciosa, em que **só o que pede ação acende**. Sem personagem, marca de
terceiros, fotografia ou ilustração: a essência é a ressonância — varredura, contorno, sinal.

| elemento | regra | tokens (`app.css :root`) |
|---|---|---|
| **Fundo profundo** | preto profundo → ônix; textura só com gradientes (sem imagem, sob CSP) | `--bg #050608` · `--bg-2 #0a0c10` |
| **Cards elevados** | cinza-carvão, cantos suaves, bordas quase invisíveis; *hover* acende o contorno ciano | `--panel #0e1116` · `--panel-2 #13171e` · `--panel-3 #191e27` · `--hover #1d2430` · `--line*` (branco 5,5–14 %) · `--elev` |
| **Ponto focal** | ciano neon **só** em CTAs, navegação ativa, contornos de foco e varredura | `--scan #38dfff` · `--glow-sm/md` |
| **CTA** | botão primário sólido ciano com **texto escuro** (11,8:1) | `--scan-ink #03141a` |
| **Neutros tipográficos** | títulos em branco puro; corpo em cinza-frio | `--text #fff` · `--text-2 #c2cad6` · `--muted #919bad` · `--faint #838da0` |
| **Títulos industriais** | geométrica condensada em **caixa alta**, tracking aberto | `--font-display` (Bahnschrift/DIN → fallbacks do sistema) · `--track-display .14em` |
| **Microcópia** | entrelinha 1,5, tracking levemente aberto em rótulos | `--track .08em` |
| **Tech-glow** | brilho sutil em ícones lineares, badges e contornos finos (HUD) | `drop-shadow` 3–4 px em `svg.i`; nunca em texto corrido |
| **Ícones** | traço linear 24×24 desenhado em SVG; **nenhum emoji** (categorias de insight também) | `core.js › ICONS`, `CATEGORY[*].icon` |

Zero fonte, imagem ou script externo (ADR-005): a UI continua 100 % offline e sob CSP estrita.

### Contraste medido (WCAG 2.x)

| texto \ superfície | `--bg` | `--panel` | `--panel-2` | `--panel-3` | `--hover` |
|---|---|---|---|---|---|
| `--text` | 20,3 | 18,9 | 18,0 | 16,7 | 15,6 |
| `--text-2` | 12,3 | 11,4 | 10,9 | 10,1 | 9,4 |
| `--muted` | 7,2 | 6,8 | 6,4 | 6,0 | 5,6 |
| `--faint` | 6,1 | 5,7 | 5,4 | 5,0 | **4,7** |
| `--scan` | 12,7 | 11,9 | 11,3 | 10,5 | 9,8 |
| `--err` | 7,4 | 6,9 | 6,6 | 6,1 | 5,7 |

Todos ≥ 4,5:1 (AA para texto normal) em todas as superfícies. Além da tabela, o loop de usabilidade **audita o
texto visível de cada tela percorrida** (cor × fundo efetivo com mistura alfa): **0 falhas**.

## 2. Metáfora → visões (teclas 1–9)

| exame | tecla | pergunta que responde |
|---|---|---|
| **Anatomia** | 1 | *Quais órgãos meu sistema tem e como se ligam?* Anéis por zona — núcleo (código), fronteira local (AWS/LocalStack), fronteira externa (parceiros), declarado no IaC e nunca visto (pelo **nome real** do recurso). |
| **Árvore** | 2 | *O que esta execução fez?* Esquerda→direita, mini-Gantt, papel arquitetural, Δ de dados, insights, caminho crítico, "⧗ fila Xs", selo **SIM**/**↪** do Mock Connect. |
| **Linha do tempo** | 3 | *Em que ordem e onde o tempo foi?* Capítulos, espera hachurada, logs CloudWatch das funções no mesmo eixo, narração "AGORA". |
| **Investigação** | 4 | *Posso homologar?* Executiva (desfecho, regras × fluxo, checklist) ao lado da técnica (hotspots, anomalias, regras preditivas). |
| Narrativa | 5 | a execução em linguagem de negócio (glossário; chamadas externas nomeiam o parceiro e dizem se a resposta foi simulada) |
| Painel | 6 | acervo, motores de inteligência, baseline por fluxo |
| Comparar | 7 | diff A/B de passos, durações e mutações |
| Infra | 8 | configuração/IaC × uso real |
| **Mocks** | 9 | *O que eu simulo e o que eu vario?* Sugestões do conselheiro com evidência, variações agrupadas, Sempre/Sob demanda, bindings, journal, plugins, editor com validação por chave ([MOCKS.md](MOCKS.md)). |

Transversais: painel lateral (Execuções · API do contrato com parâmetros · Insights), faixa da execução, inspetor
do passo (Resumo · Dados · Payload · Logs · Insights · Infra, com seção **Mock Connect** em chamadas externas),
paleta **Ctrl+K**, barra de status, deep link `?execution=&view=`.

## 3. Princípios

- **Só o que pede ação acende**: um CTA por cartão; contagem do botão Mocks mostra só pendências (vermelho se ALTA);
  sugestões resolvidas por um mock plugado descem e ficam verdes.
- **Coordenação entre visões**: evidência → passo exato na árvore (inspetor aberto); passo → sugestão de mock do parceiro.
- **Fato ≠ correlação ≠ hipótese ≠ recomendação**; **simulado ≠ real** (SIM, ↪, narrativa, journal).
- **Honestidade**: o que não foi capturado é dito (Δ SQL "inferida do SQL (sem valores)"); recurso com nome dinâmico
  no IaC não vira "nunca observado"; execução com id provisório é renomeada (`execution.renamed`), sem fantasmas.
- **Estabilidade de interação**: trocar modo/aba não rola nem rouba foco; atualização em segundo plano só repinta
  se o dado mudou; disparos pelo contrato abrem a execução quando ela chega (segue o `traceId`).
- **Acessibilidade**: nomes acessíveis em 100 % dos botões, radios com `aria-checked`, foco ciano de 2 px,
  teclado (1–9, setas na árvore, `[`, Esc, Ctrl+K), alvos ≥ 32 px nas ações, `prefers-reduced-motion`,
  390 px sem rolagem horizontal e com a visão ativa sempre à vista.
- **Segurança**: CSP estrita (sem `unsafe-inline`), DOM só por `textContent`, mutações com `X-Trace2Local: 1`.

## 4. Arquitetura do front-end

`index.html` + `app.css` + ES modules em `js/` (sem build step): `core` (estado, barramento, API anti-CSRF, ícones,
`mockOf`), `data` (carga + SSE: `execution.started/completed/merged/renamed`, `insights.updated`), `rail`,
`anatomy`, `tree`, `timeline`, `investigate`, `inspector`, `legacy`, `palette`, **`mocks`**, `app`.

## 5. Loops de usabilidade

| loop | cenário | resultado |
|---|---|---|
| `scripts/ux-loop/persona-loop-pix.mjs` | stack alvo finance-pix (6 personas + identidade, contraste, teclado, CSP, mobile) | **69/69** ([relatório](qa/finance-pix/UX-LOOP-PERSONAS.md)) |
| `scripts/ux-loop/persona-loop.mjs` | lambda-sqs (4 personas) | 42/42 na v3 ([histórico](qa/VALIDACAO-CASOS-REAIS.md)) |

Achados do loop que viraram correção nesta versão: execução fantasma "em curso" no modo Station (id provisório),
disparo pelo contrato que não abria a execução, nota "Chamada externa GET para ao serviço", componentes fantasmas
do Terraform na Anatomia, minimapa sobre a legenda com o inspetor aberto, rolagem/foco perdidos ao trocar o modo
de uma sugestão, visão ativa fora da vista em 390 px, `--faint` abaixo de 4,5:1 sobre `--hover`.
