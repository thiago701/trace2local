# Plano de melhoria de UX/Usabilidade — UI Trace2Local

> Estudo profundo da UI atual (`trace2local-ui`: index.html + app.css + app.js, 613 linhas JS)
> feito em 2026-09-20. Restrições imutáveis: **ADR-005** (zero asset/referência externa),
> **CSP `default-src 'self'; style-src 'self'; script-src 'self'`** (nada de estilo/script inline).

## Diagnóstico (achados)

| # | Problema | Impacto |
|---|---|---|
| 1 | **Lista de execuções mostra só o UUID bruto** — nada diz O QUE a execução é (rótulo da raiz, trigger, nº de nós) | usuário não identifica a execução sem abri-la |
| 2 | **Sem waterfall**: nós mostram durações só em texto — a essência de um canvas de tracing (ver proporções de tempo) não existe | leitura lenta, nenhuma visão de gargalo |
| 3 | **Sem auto-fit**: a árvore abre com scale 1 fixo e frequentemente cortada | primeiro contato ruim; usuário precisa caçar a árvore |
| 4 | **Inspector é um bloco monolítico**: todos os atributos (podem ser dezenas) sempre expandidos; sem filtro; seções não colapsáveis | rolagem infinita num painel de 240px |
| 5 | **alert() para erros** (JSON inválido / falha de disparo) — modal bloqueante, fora do design system | quebra de fluxo, visual datado |
| 6 | **Sem busca/filtro** na lista de execuções (nem por rótulo, nem por status) | inútil com acervo cheio |
| 7 | **Sem legenda** das cores/ícones de kind de nó (DDB, SQS, λ…) | símbolos opacos para quem não decorou |
| 8 | **Sem resumo da execução no topo do canvas** (status/trigger/duração/aviso) — é preciso olhar a lista + o inspector | contexto fragmentado |
| 9 | Erros: **stack/atributos gigantes sem copiar**; sem botão de copiar payload/erro | operação comum (levar o erro pro editor) é manual |
| 10 | Sem estado vazio na lista de recentes; sem feedback de carregamento (skeleton) | telas mortas em cold start |
| 11 | Acessibilidade/estética: sem focus-ring visível, sem `prefers-reduced-motion`, sem scrollbar estilizada, hint de atalhos escondido | padrão abaixo do esperado |
| 12 | `statusCodeOf` mostra código HTTP solto sem contexto; trigger não aparece em lugar nenhum da UI | dado presente mas não comunicado |

## Plano de melhoria (implementação)

1. **Design tokens refinados** — paleta, raios, sombras, espaçamentos, focus-visible, scrollbars, `prefers-reduced-motion`.
2. **Cards de execução ricos** — rótulo da raiz em destaque, chip de trigger colorido, badge de status com texto, duração + nº de nós, **barra de duração proporcional**, horário relativo ("há 2 min"), UUID em linha secundária; estado vazio.
3. **Busca/filtro** na lista de recentes (rótulo/status/trigger).
4. **Waterfall no canvas** — cada nó ganha barra de tempo proporcional (total × self) relativa à duração da execução; card maior com nome do kind por extenso + chip colorido.
5. **Resumo da execução no header do canvas** — badge de status, chip de trigger, duração, nº de nós, contador de avisos.
6. **Auto-fit inteligente** — primeira renderização de cada execução encaixa a árvore na viewport (mantém zoom/pan do usuário depois).
7. **Inspector moderno** — seções colapsáveis; ATRIBUTOS colapsado quando > 6 com botão "mostrar todos" + filtro de atributo; botões **copiar** para erro, before/after e payload; erro como bloco vermelho destacado.
8. **Toasts** no lugar de `alert()` (auto-dismiss, não bloqueante).
9. **Legenda de kinds** (popover) + **hint de atalhos** (popover) no header do canvas.
10. **Skeleton de carregamento** no canvas durante o fetch da execução.

## Validação

- Captura antes/depois via `capture-lambda-station.mjs` (consistência UI↔API preservada — labels do canvas idênticos à API).
- Novas asserções DOM de evidência: presença de waterfall bars, chips de trigger, resumo no header, busca filtrando, inspector colapsável.
- `UiOfflineTest` (nenhuma referência externa) e `mvn install` verdes ao final.
