# Loop de usabilidade por persona — 2026-10-01T02:48:40.778Z

Base: `http://127.0.0.1:19877/trace2local` (examples/lambda-sqs: Lambda java21 + DynamoDB + SQS + ESM + CloudWatch no LocalStack 4.2, duas rodadas) · duração 18 s

**42/42 checks passaram**

| persona | check | resultado | evidência |
|---|---|---|---|
| dev-1º contato | UI pronta em < 3 s | ✅ | 256 ms |
| dev-1º contato | Anatomia mostra os órgãos do ecossistema | ✅ | 7 componente(s) |
| dev-1º contato | Zonas legíveis (núcleo/fronteira) | ✅ | Núcleo, Fronteira local |
| dev-1º contato | Legenda declara a fronteira externa (pronta para expansão) | ✅ | Núcleo · 4Fronteira local · 3Fronteira externa · 012 execuções · 4 fluxosfronteira externa: nenhuma chamada observada |
| dev-1º contato | Laudo do componente mostra conexões e espera da fila | ✅ | LAUDO DO COMPONENTEorders-queueFila SQSFronteira localidsqs:orders-queuechamadas6erros0 (0%)latência p5012 mslatência p9533 msfluxos1vizinhos2CONEXÕES←order-pro |
| dev-1º contato | Status do motor de decisão visível | ✅ | motor: regras locais |
| dev-async | Consumidor real (event source mapping) fundido na árvore do produtor | ✅ | d1881cb8-773d-4d57-8ecb-7411e0c58680 · 5 nós · 7643 ms |
| dev-async | Árvore desenha os 5 passos (deep link ?execution=) | ✅ | 5 nós |
| dev-async | Aresta assíncrona rotulada com a espera na fila | ✅ | ⧗ fila 6,07 s |
| dev-async | Faixa da execução com status, duração e manchete | ✅ | concluídaorder-processorgatilho evento Lambdaduração 7,64 spassos 5Sucesso em order-processor — homologação: aptalink |
| dev-async | Inspector do consumidor explica a espera na fila | ✅ | Função Lambdaorder-billing600 ms · self 140 ms · início +7,04 s · fila 6,07 sResumoDadosPayloadLogs 5InsightsInfraLEITURA DO ASSISTENTEpapel: Consumo assíncrono |
| dev-async | Teclado: → desce do consumidor ao passo filho | ✅ | DynamoDB: orders, DynamoDB, 459 ms |
| dev-async | Passo selecionado não fica escondido sob o inspector | ✅ |  |
| dev-async | Delta de dados antes → depois (BILLED) | ✅ | DynamoDBΔ updateDynamoDB: orders459 ms · self 459 ms · início +7,16 sResumoDadosPayloadLogsInsightsInfraOPERAÇÃOtipoUPDATEalvoorderschaveORDER-C1fidelidadeEXACT |
| dev-async | Troca de visão < 1 s | ✅ | 419 ms |
| dev-async | Logs CloudWatch correlacionados (START/END/REPORT + app) | ✅ | 11 linha(s) |
| dev-async | Narrativa em ordem: START antes do primeiro log da função | ✅ | START#0 app#1 |
| dev-async | Capítulos didáticos na linha do tempo | ✅ | 7 capítulo(s) |
| dev-async | Espera em fila hachurada entre produtor e consumidor | ✅ | 2 trecho(s) |
| dev-async | Reprodução narra 'AGORA' o que acontece | ✅ | Cap. 3 · Mensageria — mensagem aguardando na fila há 1,50 s até order-billing — log: INFO evento OrderCreated de ORDER-C1 publicado em orders-queue |
| dev-async | Ao final a narrativa chega ao desfecho | ✅ | Cap. 6 · Desfecho — Função Lambda: order-billing — plataforma: relatório — 809,43 ms · 512 MB de memória |
| qa-po | Manchete executiva em linguagem de negócio | ✅ | Falha de negócio em order-processor — homologação: bloqueada (1 regra(s) violada(s)) |
| qa-po | Regras do glossário cruzadas com o fluxo (veredito por regra) | ✅ | Violada, Não exercitada, Não exercitada |
| qa-po | Cada decisão mostra o motor que decidiu (fato/regra/Jev) | ✅ | regra local, fato |
| qa-po | Checklist de homologação auditável | ✅ | 6 itens |
| qa-po | Reentrega bloqueada lida como 'recusa protegida' (não falha técnica) | ✅ | Recusa protegida pela regra |
| tech-lead | Insights ranqueados no painel lateral | ✅ | 4 · 🗄Criação sobrescreveu um registro existentealto100% · fato observado×3IDEM-001Em orders, a chave OR |
| tech-lead | Fato · correlação · hipótese · recomendação separados | ✅ | 🗄Criação sobrescreveu um registro existentealtoDadosFATO100% · fato observado×3IDEM-001 · IdempotencyAnalyzer · jev-deterministicFATO OBSERVADOEm orders, a cha |
| tech-lead | Evidence first: evidências navegáveis | ✅ | 2 evidência(s) |
| tech-lead | Explicação local (sem LLM) disponível | ✅ | O QUE FOI OBSERVADO (fato) Em orders, a chave ORDER-C4 já existia e foi regravada sem condição — com valores diferentes. |
| tech-lead | [Ver trace] leva à árvore da execução | ✅ |  |
| tech-lead | Inspector do passo não cobre o Painel | ✅ |  |
| tech-lead | Painel de inteligência: motor, pipeline, baseline | ✅ | INTELIGÊNCIA · motores de micro-decisão e regras preditivasmodoauto → efetivo deterministicJevsem chave — motor determinístico local (t2l-deterministic-1.0)circ |
| tech-lead | Histórico/baseline por fluxo com sparkline | ✅ | 4 fluxo(s) |
| tech-lead | Ctrl+K encontra componente/execução por nome | ✅ | COMPONENTEorder-billingFunção Lambda · core \| INSIGHT🔁 Mesma entidade processada mais de uma vez por order-billingIDEM-002 · 85% |
| a11y | Todo botão visível tem nome acessível | ✅ | 0 sem nome |
| segurança | Zero violação de CSP (style/script inline) | ✅ |  |
| segurança | CSP estrita servida (default-src 'self', sem unsafe-inline) | ✅ | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-an |
| segurança | Mutação sem X-Trace2Local é recusada (CSRF) | ✅ | DELETE sem cabeçalho → 403 |
| a11y | Sem rolagem horizontal em 390 px | ✅ |  |
| a11y | Topo sem sobreposição em 390 px | ✅ |  |
| geral | Nenhum erro de console | ✅ |  |


