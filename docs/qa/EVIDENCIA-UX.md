# EVIDÊNCIAS UX — GUI v2 do TraceVanta (estudo, plano e implementação)

> Data: 2026-09-20 · Escopo: `tracevanta-ui` (index.html, app.css, app.js)
> Restrições respeitadas: **ADR-005** (zero referência externa — `UiOfflineTest` verde)
> e **CSP `default-src 'self'; style-src 'self'; script-src 'self'`** (zero estilo/script inline).

## Estado "antes" (documentado no diagnóstico)

O plano [`UX-PLANO-MELHORIA.md`](UX-PLANO-MELHORIA.md) registra 12 achados do estudo
profundo: lista de execuções com só UUID, sem waterfall, sem auto-fit, inspector
monolítico, `alert()` bloqueante, sem busca, sem legenda, sem resumo no canvas,
sem copiar, sem estado vazio/skeleton, sem focus-ring/reduced-motion. As telas
anteriores foram apresentadas na rodada anterior (tamanhos 755/636/640/656/604/569 KB).

## Implementado (GUI v2)

| # | Melhoria | Evidência |
|---|---|---|
| 1 | **Cards de execução ricos**: rótulo da raiz em destaque, badge de status com texto (OK/FALHOU/PARCIAL/EM CURSO), chip de trigger colorido, duração · nº de nós · tempo relativo, barra de duração proporcional | `.exec-card` × 3, `.root-label` × 3, `.chip` × 3, `.status-badge` × 3 (checagem DOM) |
| 2 | **Waterfall no canvas**: cada nó ganha barra de tempo (total + self) proporcional à duração da execução | `g.tv-node .timebar-total` × 5 (checagem DOM) |
| 3 | **Auto-fit**: primeira renderização de cada execução encaixa a árvore na viewport | verificado visualmente em 08/10/12 |
| 4 | **Resumo da execução no header**: status + trigger + duração + nº de nós + avisos | `#exec-summary` visível: "OK · LAMBDA · duração 1.85s · nós 5" (checagem DOM) |
| 5 | **Busca/filtro** na lista de execuções (rótulo/status/trigger) | filtro com termo inexistente mostra estado vazio (checagem DOM) |
| 6 | **Inspector moderno**: seções colapsáveis (ATRIBUTOS colapsa > 6 com contador), filtro de atributos, botões COPIAR (erro/before/after/payload) | 3 seções, 2 cabeçalhos colapsáveis, 2 botões de cópia (checagem DOM) |
| 7 | **Toasts** no lugar de `alert()` (não bloqueante, auto-dismiss) | código + tela 07 |
| 8 | **Legenda de tipos de nó** (popover com 9 kinds) | 9 itens na legenda (checagem DOM) |
| 9 | **Atalhos** documentados em popover (F, Esc, setas, zoom) | `#shortcuts-pop` |
| 10 | **Skeleton de carregamento** ao trocar de execução | `#canvas-loading` |
| 11 | **Acessibilidade/estética**: focus-ring `:focus-visible`, `prefers-reduced-motion`, scrollbars estilizadas, estados vazios, design tokens consolidados | app.css v2 |

## Validação (script `capture-lambda-station.mjs` estendido)

```
consistência c5b5a5a1…: UI=3 nós, API=3 nós → OK (labels idênticos)
consistência req-demo-2: UI=2 nós, API=2 nós → OK (labels idênticos)
consistência req-demo-1: UI=5 nós, API=5 nós → OK (labels idênticos)
UX v2: waterfallBars=5 · summaryVisible=true · richCards=3 · triggerChips=3
       statusBadges=3 · filterEmptyShown=true · legendItems=9
       inspectorSections=3 · inspectorCollapsibleHeaders=2 · inspectorCopyButtons=2
UX v2: todas as checagens passaram ✓
```

Gates: `mvn install` verde (inclui `UiOfflineTest` — nenhuma referência externa nos
assets) + captura com **consistência UI↔API preservada** (os labels do canvas
continuam idênticos aos da API, agora com waterfall e cards ricos).

## Telas (depois)

| Tela | O que mostra |
|---|---|
| [`screenshots/07-lambda-station-overview.png`](screenshots/07-lambda-station-overview.png) | visão geral v2: sidebar com cards ricos + busca |
| [`screenshots/08-lambda-tree-dynamo-sqs.png`](screenshots/08-lambda-tree-dynamo-sqs.png) | árvore J1 com waterfall + resumo no header |
| [`screenshots/09-lambda-inspector-delta.png`](screenshots/09-lambda-inspector-delta.png) | inspector v2 do delta EXACT (colapsável + copiar) |
| [`screenshots/10-lambda-tree-failure.png`](screenshots/10-lambda-tree-failure.png) | árvore vermelha com waterfall |
| [`screenshots/11-lambda-inspector-error.png`](screenshots/11-lambda-inspector-error.png) | inspector do erro com botão COPIAR |
| [`screenshots/12-lambda-tree-consumer.png`](screenshots/12-lambda-tree-consumer.png) | árvore fundida de 5 nós com waterfall |
