# Loop de usabilidade por persona — finance-pix (stack alvo) — 2026-10-01T06:42:48.152Z

Base: `http://127.0.0.1:19877/trace2local` · duração 24 s

**69/69 checks passaram** · 1 erro(s) de console esperados (sondas) descontados

| persona | check | resultado | evidência |
|---|---|---|---|
| dev-1º contato | UI pronta em < 3 s | ✅ | 335 ms |
| identidade | Marca RESONANCE no topo e no título da aba | ✅ | RESONANCE · Trace2Local — Resonance · station |
| identidade | Nenhum resquício do nome antigo (Ressonância) na interface | ✅ |  |
| identidade | Títulos industriais: caixa alta, tracking aberto, branco puro | ✅ | {"h1Upper":true,"h1Track":0.2,"h1Color":"rgb(255, 255, 255)","bg":"rgba(0, 0, 0, 0)","bodyLine":1.5} |
| identidade | Microcópia com entrelinha espaçosa (≥ 1,45) | ✅ | 1.50 |
| dev-1º contato | Anatomia mostra o ecossistema (3 Lambdas, tabelas, fila, tópico, Postgres) | ✅ | 33 componente(s) |
| dev-1º contato | Os 5 parceiros externos aparecem na fronteira externa | ✅ | ext:dict.bacen.local, ext:kyc.bureau.local, ext:antifraude.partner.local, ext:notify.partner.local, ext:spi.bacen.local |
| dev-1º contato | Sem componente fantasma do IaC (rótulo local do Terraform) | ✅ | nenhum |
| dev-1º contato | Visão Mocks descobrível no topo (com contagem de sugestões pendentes) | ✅ | badge=2 |
| dev-1º contato | Status do motor de decisão visível | ✅ | motor: regras locais |
| dev-investiga | Há transferência liquidada de ponta a ponta no acervo | ✅ | 644ea7b5-62e8-4eef-97e3-3113588b293b · 34 passos · 1454 ms |
| dev-investiga | Árvore desenha todos os passos (deep link ?execution=) | ✅ | 34 de 34 |
| dev-investiga | Arestas assíncronas (SQS e SNS) rotuladas com a espera | ✅ | ⧗ fila 924 ms · ⧗ fila 17 ms |
| dev-investiga | Faixa da execução: status, gatilho, duração, passos e manchete | ✅ | concluídapix-api · POST /pix/transfersgatilho evento Lambdaduração 1,45 spassos 34Sucesso em pix-api · POST /pix/transfers — homologação: aptalink |
| dev-investiga | Passo atendido pelo mock marcado SIM e anunciado ao leitor de tela | ✅ | GET KYC & Limites /v2/customers/{id}/limits, Chamada externa, 5 ms, resposta simulada pelo Mock Connect |
| dev-investiga | Inspetor diz que a resposta veio do Mock Connect (binding + stub) | ✅ | Chamada externaSIMGET KYC & Limites /v2/customers/{id}/limits5 ms · self 5 ms · início +58 msResumoDadosPayloadLogsInsights 1InfraNA LINGUAGEM DO NEGÓCIOChamada externa GET a KYC & |
| dev-investiga | Nota de negócio da chamada externa nomeia o parceiro (sem 'para ao serviço') | ✅ | Chamada externaSIMGET KYC & Limites /v2/customers/{id}/limits5 ms · self 5 ms · início +58 msResumoDadosPayloadLogsInsights 1InfraNA LINGUAGEM DO NEGÓCIOChamada externa GET a KYC & |
| dev-investiga | Passo selecionado não fica escondido sob o inspetor | ✅ |  |
| dev-investiga | Minimapa não cobre a legenda com o inspetor aberto | ✅ |  |
| dev-investiga | Δ do Postgres honesto: operação + tabela + chave, e o que NÃO foi capturado explicado | ✅ | Banco SQLΔ updateSQL: UPDATE accounts1 ms · self 1 ms · início +87 msResumoDadosPayloadLogsInsightsInfraOPERAÇÃOtipoUPDATEalvoaccountschaveid = ?fidelidadeinferida do SQL (sem valo |
| dev-investiga | Troca de visão < 1 s | ✅ | 85 ms |
| dev-investiga | Logs CloudWatch das 3 funções correlacionados na mesma linha do tempo | ✅ | 3 START · 12 linha(s) |
| dev-investiga | Capítulos didáticos na linha do tempo | ✅ | 32 capítulo(s) |
| dev-mocks | Tecla 9 abre o Mock Connect em < 1 s | ✅ | 40 ms |
| dev-mocks | Sugestões com POR QUÊ + evidência navegável | ✅ | Só o caminho feliz de Antifraude foi exercitado (5 ev) \| Só o caminho feliz de Notificações foi exercitado (5 ev) \| KYC & Limites indisponível no ambiente local — plu (1 ev) |
| dev-mocks | Pendentes primeiro; plugadas marcadas RESOLVIDA no fim | ✅ | ••✓ |
| dev-mocks | [abrir] da evidência leva ao passo exato na árvore | ✅ | POST Antifraude /v1/score |
| dev-mocks | Selecionar variação atualiza a ação (quantas serão aplicadas) | ✅ | Plugar mock + 1 variação(ões) |
| dev-mocks | Modo 'Sob demanda' é um radio acessível e não pula a rolagem | ✅ | aria-checked=true · Δy=0 |
| a11y | Botão de ação principal com alvo ≥ 32 px | ✅ | {"h":34,"w":290.40625} |
| dev-mocks | Plugar: feedback imediato (toast) com o binding ativo | ✅ | Mock mock-antifraude ativo — 1 stub(s), 1 variação(ões) |
| dev-mocks | 'Como usar agora' entrega o cabeçalho baggage pronto para copiar | ✅ | COMO USAR AGORAClientes instrumentados com Trace2LocalHttp e TRACE2LOCAL_MOCKS_ROUTING=on já chamam o mock — o passo aparece na árvore marcado como SIMULADO.ou aponte a URL base pa |
| dev-mocks | Binding aparece ativo na aba Bindings (estado + fonte → destino) | ✅ | mock-kyc-limites:ativo, mock-antifraude:ativo |
| dev-mocks | Stubs efetivos inspecionáveis (requisição → resposta/variação) | ✅ | 1 stub(s) |
| dev-mocks | Exportar para WireMock a um clique | ✅ | /trace2local/api/mocks/bindings/mock-antifraude/export |
| dev-mocks | Journal: cada chamada atendida (binding, stub, variação, resultado) | ✅ | 7 chamada(s) |
| dev-mocks | Catálogo de plugins por tipo (fonte · transformação · predicado · destino) | ✅ | 21 plugins · Fontes de stubs / Transformações (variações) / Predicados (quando aplicar) / Destinos |
| dev-mocks | Editor valida chave a chave (estilo Connect) e aponta o que falta | ✅ | 4 problema(s)target — obrigatório: host[:porta] da API real que o mock substitui (ex.: antifraude.parceiro:8080)source.spec — obrigatório: arquivo OpenAPI (YAML/JSON), relativo a T |
| dev-mocks | Remover exige confirmação em dois passos e limpa o binding | ✅ | confirmar remoção |
| dev-dispara | Aba API lista os endpoints do contrato | ✅ | /pix/transfers · /pix/transfers/{transferId} |
| dev-dispara | Parâmetro de path do contrato vira campo (obrigatório marcado) | ✅ | pathtransferId* |
| dev-dispara | Sem o obrigatório: aviso claro e foco no campo (nada é disparado) | ✅ | Informe transferId (path) — obrigatório no contrato |
| dev-dispara | Disparo pelo Station informa que segue pelo traceId | ✅ | Disparado (trace 14c132…aadc5) — a árvore abre assim que a execução chegar |
| dev-dispara | A execução disparada abre sozinha quando chega da Lambda | ✅ | ee3d2249-bad2-47f6-97c1-4201757d7dac |
| dev-dispara | Painel sem execução fantasma 'em curso' (id provisório trocado pelo da raiz) | ✅ | nenhuma |
| dev-dispara | Idempotency-Key explicado (gerado a cada disparo) — não bloqueia | ✅ | (gerado a cada disparo) |
| dev-dispara | Corpo de exemplo vem do contrato | ✅ | { "payerAccountId" : "acc-001", "pixKey" : "joao@pix.example", "amount" : 150.0, "description" : "al |
| qa-po | Manchete executiva em linguagem de negócio | ✅ | Sucesso em pix-api · POST /pix/transfers — homologação: apta |
| qa-po | Regras do glossário (trace2local-business.md) com veredito | ✅ | Respeitada, Inconclusiva, Inconclusiva, Inconclusiva, Inconclusiva, Respeitada, Não exercitada, Inconclusiva, Respeitada |
| qa-po | Checklist de homologação auditável | ✅ | 6 itens |
| qa-po | Narrativa de negócio sem frase quebrada | ✅ | Jornada do evento Lambda — pix-api · POST /pix/transfers294 ms · concluída copiar (Markdown)A execução começou quando o evento chegou à função Lambda (pix-api · |
| tech-lead | Insights ranqueados no painel lateral | ✅ | 5 · Latência elevada no processamento assíncronoalto90% · forte evidênciaPERF-ASYNC-001O fluxo |
| identidade | Categorias com ícone linear (sem emoji colorido) | ✅ |  |
| identidade | Nenhum emoji pictográfico na interface | ✅ |  |
| tech-lead | Painel: motor, pipeline preditivo e baseline | ✅ | INTELIGÊNCIA · motores de micro-decisão e regras preditivasmodoauto → efetivo deterministicJevsem chave — motor determinístico local (t2l-de |
| tech-lead | Ctrl+K encontra o parceiro/etapa por nome | ✅ | COMPONENTEAvaliar risco (antifraude)Negócio · core \| COMPONENTEantifraude.partner.localChamada externa · external \| COMPONENTEEnviar para revisão antifraudeNegócio · core |
| tech-lead | Ctrl+K leva ao Mock Connect | ✅ | VISÃOMock Connect — simular APIs e variaçõestecla 9 \| COMANDOPlugar mock no lugar de uma API (sugestões)Mocks |
| a11y | Todo botão visível tem nome acessível | ✅ | 0 sem nome |
| a11y | Teclas 1–9 alcançam as 9 visões | ✅ | anatomy,tree,timeline,investigate,story,dashboard,compare,infra,mocks |
| a11y | Foco de teclado visível (contorno ciano) | ✅ | {"tag":"BUTTON","outline":"solid","w":"2px","shadow":true} |
| a11y | prefers-reduced-motion respeitado | ✅ |  |
| segurança | Zero violação de CSP (style/script inline) | ✅ |  |
| segurança | CSP estrita servida (default-src 'self', sem unsafe-inline) | ✅ | default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-an |
| segurança | Mutação do Mock Connect sem X-Trace2Local é recusada (CSRF) | ✅ | DELETE sem cabeçalho → 403 |
| a11y | Contraste WCAG AA em todo texto visível auditado | ✅ | 0 falhas |
| a11y | Sem rolagem horizontal em 390 px (visão Mocks) | ✅ |  |
| a11y | Topo sem sobreposição em 390 px | ✅ |  |
| a11y | Em 390 px a visão ativa fica visível na barra rolável | ✅ |  |
| geral | Nenhum erro de console | ✅ |  |


