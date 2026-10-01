# Segurança e adoção corporativa — revisão de código e guia

> Escopo: revisão de segurança da lib (UI/API, Station, ingest, logs, inteligência Jev/LLM, demos) para adoção em empresa.
> **"Produção corporativa" aqui = uso corporativo em dev, homologação e CI.** O Trace2Local continua **bloqueado em produção** (ADR-007): ele expõe payloads e deltas de dados por desenho.
> Decisões: [ADR-007](adr/ADR-007-local-first-sem-autenticacao.md) · [ADR-011](adr/ADR-011-motores-de-micro-decisao-jev.md) · [ADR-015](adr/ADR-015-endurecimento-corporativo-ui-api.md) · política: [SECURITY.md](../SECURITY.md).

## 1. Achados da revisão

| id | sev. | achado | correção | trava |
|---|---|---|---|---|
| S1 | **Alta** | **CSRF**: qualquer página aberta no navegador do dev fazia `POST /api/execute` (dispara endpoints da app) ou `DELETE /api/executions` — requisições "simples", sem preflight | `RequestGuard`: mutações exigem `X-Trace2Local: 1` + `Origin` igual ao `Host` → 403 | `guardBlocksCsrfAndDnsRebinding`, loop UX |
| S2 | **Alta** | **DNS rebinding**: `evil.example → 127.0.0.1` permitia a terceiros **ler** a API (payloads, deltas) | allowlist de `Host` (loopback + `TRACE2LOCAL_ALLOWED_HOSTS`) → 421 | `hostAllowlistDefeatsDnsRebinding` |
| S3 | **Alta** | **XSS** na UI antiga: o comparador montava HTML com chaves de mutação vindas dos dados (`innerHTML`) | UI v3 só com DOM/`textContent`; `innerHTML` restrito ao tooltip com escape | `UiCspComplianceTest.innerHtmlIsConfinedToTheEscapedTooltip` |
| S4 | Média | GET com efeito externo: explicação por LLM (egress + custo) acionável por `<img src>` de terceiros | LLM só por `POST` (passa pelo guard); GET = template local | código + ADR-015 |
| S5 | Média | Mensagens de exceção internas em respostas 500/502 | resposta genérica, detalhe só no log da app | testes do servidor |
| S6 | Média | Assinantes SSE ilimitados (exaustão de threads/memória) | máx. 32 (503 acima) | `SseHub.MAX_SUBSCRIBERS` |
| S7 | Média | `InfraIndexer` exibia credenciais em URL (`user:pass@`) e seguia symlinks para fora do projeto | redaction de URL + symlinks ignorados | `InfraIndexerTest` |
| S8 | Média | stdout da Lambda (agora capturado como log) pode conter segredos | redaction na origem (`LambdaLogCapture`) **e** na entrada do `LogStore` (`TextRedactor`) | `LogStoreTest`, `TextRedactor` |
| S9 | Média | Demo publicava Station (sem auth de UI) e LocalStack em `0.0.0.0` | portas só em `127.0.0.1`; Lambda → Station pela rede interna (`LAMBDA_DOCKER_NETWORK`) | `docker-compose.yml` |
| S10 | Média | CSP sem `object-src`/`base-uri`/`form-action` | CSP endurecida + COOP/CORP/Permissions-Policy | `UiCspComplianceTest`, loop UX (0 violação) |
| S11 | Baixa | `?limit=abc` gerava 500 (NumberFormatException) | parsing defensivo com faixa | testes do servidor |
| S12 | Baixa | Imagem do Station como root, Maven + código-fonte no contexto do build | runtime não-root a partir do jar pronto, contexto mínimo (`Dockerfile.station.dockerignore`) | Dockerfile |
| S13 | Baixa | Build incremental produzia fat jar com classes antigas (integridade do que se executa) | `maven-jar-plugin forceCreation` | inspeção do jar |
| S14 | Baixa | Cookie de sessão sem `Secure` atrás de TLS | `TRACE2LOCAL_COOKIE_SECURE=true` ou `X-Forwarded-Proto: https` | `RequestGuard` |
| S15 | Info | `examples/payment-service/.env` versionado com senha *placeholder* | mantido de propósito (demo do mascaramento da aba Infra); scanners de segredo podem acusar — é demonstrativo | — |
| S16 | Info | Chave do Jev compartilhada em conversa durante a avaliação | **rotacionar a chave**; a lib só lê de variável de ambiente, nunca grava/loga (`toString` mascarado) | `IntelligenceConfig` |

Sem achados críticos remanescentes. Riscos residuais declarados na seção 5.

## 2. Topologias suportadas

| uso | como rodar | proteção |
|---|---|---|
| **Máquina do dev** (padrão) | starter (Embedded) ou Station local | loopback, Host allowlist, anti-CSRF, CSP |
| **CI** | `trace2local-testing` + Testcontainers | sem UI exposta; Jev desligado (determinístico) |
| **Ambiente compartilhado de dev/hml** (VDI, devcontainer remoto, Station de equipe) | `TRACE2LOCAL_SECURITY_PROFILE=corporate` + bind fora do loopback | token de UI (gerado se ausente), `TRACE2LOCAL_ALLOWED_HOSTS`, **TLS no proxy**, `TRACE2LOCAL_STATION_TOKEN` no ingest, cookie `Secure` |
| Produção | **não suportado** | bloqueio de boot fora de perfil de dev (ADR-007) |

## 3. Governança de dados

- **O que nunca sai da máquina sem opt-in:** payloads, deltas, logs, código-fonte, configuração. Sem chave/endpoint configurados, **zero egress**.
- **Jev (opt-in por chave):** egress `structural` por padrão — forma da execução (tipos, rótulos, contagens, durações), texto livre redigido, hosts como `<host-N>`, IPs privados mascarados; `values` só com `TRACE2LOCAL_JEV_EGRESS=values`. Kill switch: `TRACE2LOCAL_JEV_ENABLED=false`. Orçamento: `TRACE2LOCAL_JEV_MAX_REQUESTS_PER_MINUTE`, `TRACE2LOCAL_JEV_MAX_INPUT_TOKENS_PER_DAY`; disjuntor automático.
- **LLM de explicação (opt-in):** `TRACE2LOCAL_LLM_ENDPOINT` (prefira local, ex.: Ollama); endpoint externo exige `TRACE2LOCAL_LLM_ALLOW_EXTERNAL=true` + HTTPS; só por POST.
- **Arquivos locais** (`.trace2local/`, no `.gitignore`): `history/flows.json` (baseline: ids, durações, componentes — sem payload), `history/feedback.json` (feedback de insights), `jev-cassette.jsonl` (perguntas já sanitizadas + respostas, só em modo `record`). `TRACE2LOCAL_HISTORY=off` desliga a persistência.

## 4. Gestão de segredos

- Chaves (Jev, LLM, token de UI, token de ingest) **apenas por variável de ambiente / cofre** (Vault, AWS Secrets Manager, GitHub Actions secrets). Nunca em `application.yml` versionado.
- Rotação: troque a chave e reinicie o processo; nenhum segredo é persistido pela lib.
- O token de UI vira cookie derivado (SHA-256) e é removido da URL por redirect — não aparece em histórico.

## 5. Riscos residuais (declarados)

- Sem TLS próprio: use proxy reverso com TLS fora do loopback.
- Redaction é **mitigação**: campo de negócio com nome inocente pode passar.
- Quem alcança a UI autenticada vê o acervo (por desenho) — restrinja o acesso ao time do serviço.
- O tail de CloudWatch não assina requisições (só LocalStack).
- Análise de projeto por regex pode produzir falso positivo/negativo (heurística declarada nos insights).

## 6. Checklist para aprovação interna

- [ ] Dependência com escopo de desenvolvimento (`provided`/perfil dev) — nunca no artefato de produção.
- [ ] Bind em loopback, **ou** perfil `corporate` + `TRACE2LOCAL_ALLOWED_HOSTS` + TLS no proxy + token de ingest.
- [ ] Jev/LLM: decisão explícita (desligado por padrão); se ligado, egress `structural`, orçamento e chave no cofre.
- [ ] `.trace2local/` ignorado no Git (já no `.gitignore` do projeto-modelo).
- [ ] Glossário de negócio (`trace2local-business.md`) sem dados reais de clientes.
- [ ] Pipeline de CI rodando `trace2local-architecture` (guarda-rails de CSP/offline/ACL).

## 7. Referência de variáveis

| variável | padrão | efeito |
|---|---|---|
| `TRACE2LOCAL_BIND_ADDRESS` / `TRACE2LOCAL_ALLOW_NON_LOOPBACK` | `127.0.0.1` / `false` | exposição da UI/API |
| `TRACE2LOCAL_SECURITY_PROFILE` | — | `corporate` gera token de UI quando fora do loopback |
| `TRACE2LOCAL_UI_TOKEN` | — | token de UI explícito (`off` desliga) |
| `TRACE2LOCAL_ALLOWED_HOSTS` | loopback | hosts aceitos no cabeçalho `Host` |
| `TRACE2LOCAL_COOKIE_SECURE` | `false` | cookie de sessão com `Secure` |
| `TRACE2LOCAL_STATION_TOKEN` | — | Bearer obrigatório no ingest (OTLP, mutações, logs) |
| `TRACE2LOCAL_JEV_API_KEY` / `JEV_API_KEY` | — | habilita o Jev (opt-in) |
| `TRACE2LOCAL_JEV_MODE` | `auto` | `auto`·`live`·`record`·`replay`·`deterministic`·`off` |
| `TRACE2LOCAL_JEV_EGRESS` | `structural` | `values` envia valores (opt-in) |
| `TRACE2LOCAL_JEV_ENABLED` | `true` | `false` = kill switch |
| `TRACE2LOCAL_LLM_ENDPOINT` / `_MODEL` / `_API_KEY` / `_ALLOW_EXTERNAL` | — | explicação por LLM (opt-in) |
| `TRACE2LOCAL_PREDICTIVE_ENABLED` | `true` | liga/desliga as Regras Preditivas |
| `TRACE2LOCAL_HISTORY` / `TRACE2LOCAL_DATA_DIR` | ligado / `.trace2local` | baseline persistido |
| `TRACE2LOCAL_CLOUDWATCH_ENDPOINT` | — | tail do CloudWatch (LocalStack) no Station |
| `TRACE2LOCAL_LATE_CONTINUATION_MS` | `600000` | janela da continuação tardia (0 desliga) |
