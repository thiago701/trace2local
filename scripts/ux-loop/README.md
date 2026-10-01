# Loops de usabilidade por persona (UI Resonance)

Playwright sobre um Station/app **vivo**: cada passo de cada persona vira um *check* com evidência e
screenshot, e o script falha se a experiência regredir. É o nível **L7** do critério de aceite
([docs/qa/ACEITE.md](../../docs/qa/ACEITE.md)).

| script | cenário | personas |
|---|---|---|
| `persona-loop-pix.mjs` | **stack alvo** `examples/finance-pix` (Lambda Java 25 + API Gateway + DynamoDB + Postgres + SQS + SNS + 5 parceiros) | dev 1º contato · dev investigando · dev validando variações (Mock Connect) · dev disparando pelo contrato · QA/PO · tech lead |
| `persona-loop.mjs` | `examples/lambda-sqs` (Lambda + SQS + event source mapping) | dev 1º contato · dev latência assíncrona · QA/PO · tech lead |

Os dois cobrem também: identidade visual (marca, títulos industriais, ícones lineares, zero emoji),
**contraste WCAG AA medido** em todo texto visível percorrido (`harness.mjs › contrastAudit`), foco de
teclado visível, teclas 1–9, nomes acessíveis, `prefers-reduced-motion`, CSP servida e 0 violação,
anti-CSRF (403), 390 px sem rolagem/sobreposição e 0 erro de console.

```bash
npm i                                                   # playwright

# stack alvo: primeiro as jornadas (populam o acervo), depois o loop
(cd ../../examples/finance-pix && python3 scripts/journeys.py)
node persona-loop-pix.mjs

# itere na UI sem rebuild: assets da árvore de trabalho com os cabeçalhos REAIS do servidor
T2L_UI_DIR=../../trace2local-ui/src/main/resources/META-INF/resources/trace2local node persona-loop-pix.mjs
```

Variáveis: `T2L_BASE` (padrão `http://127.0.0.1:19877/trace2local`), `T2L_UI_DIR`, `T2L_OUT`, `CHROME_PATH`.
Saída em `out/<timestamp>/` (`report.md`, `report.json`, telas). O loop do Mock Connect cria e **remove**
um binding (confirmação em dois passos) — o estado do Station volta ao que era.
