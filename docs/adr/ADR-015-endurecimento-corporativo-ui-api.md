# ADR-015 — Endurecimento da UI/API para adoção corporativa (complementa a ADR-007)

- **Status:** Aceita (2026-09-30) — **complementa** a [ADR-007](ADR-007-local-first-sem-autenticacao.md) (não a substitui)
- **Relacionadas:** ADR-011 (egress de modelos), [docs/SEGURANCA-CORPORATIVA.md](../SEGURANCA-CORPORATIVA.md)

## Contexto

A ADR-007 assumiu "loopback = seguro o bastante". A revisão de segurança para adoção corporativa mostrou que loopback protege da rede, **não do navegador do próprio dev**:

- **CSRF**: qualquer página aberta podia fazer `POST http://127.0.0.1:9876/trace2local/api/execute` (requisição "simples", sem preflight) e disparar endpoints da aplicação, ou `DELETE` no acervo.
- **DNS rebinding**: `evil.example → 127.0.0.1` permite a uma página de terceiros **ler** a API (payloads, deltas de dados).
- Em ambientes compartilhados (VDI, devcontainer remoto, Station em rede de equipe) "sem autenticação" deixa de ser aceitável.
- A UI antiga montava HTML com dados (`innerHTML`), com um XSS no comparador.

## Decisão

1. **`RequestGuard` em todas as rotas da UI/API** (o ingest tem seu próprio Bearer):
   - **Allowlist de `Host`** — com bind em loopback só `localhost`, `127.0.0.1`, `[::1]` (+ `TRACE2LOCAL_ALLOWED_HOSTS`): rebinding recebe **421**.
   - **Mutação exige prova de mesma origem** — `POST/PUT/DELETE` precisam do cabeçalho `X-Trace2Local: 1` (força preflight CORS, que nunca é aprovado) e, havendo `Origin`, ele deve casar com o `Host`: **403**.
   - **Token de UI opcional** (`TRACE2LOCAL_UI_TOKEN`), **gerado automaticamente** no perfil `TRACE2LOCAL_SECURITY_PROFILE=corporate` quando o bind sai do loopback; trocado por cookie `HttpOnly; SameSite=Strict` (derivado SHA-256 — o token nunca volta ao navegador; `Secure` com `TRACE2LOCAL_COOKIE_SECURE=true` ou `X-Forwarded-Proto: https`).
2. **CSP endurecida** (`default-src 'self'`, sem `unsafe-inline`, `object-src 'none'`, `base-uri 'none'`, `form-action 'none'`, `frame-ancestors 'none'`) + `COOP`, `CORP`, `Permissions-Policy`; guarda-rail `UiCspComplianceTest` (sem `style=`/`on*=`/script inline no HTML, sem `eval`/`cssText`, `innerHTML` só no tooltip escapado).
3. **Respostas sem detalhe interno** (500 genérico; 502 do launcher sem stack), `Content-Type` verificado (415), limite de 32 conexões SSE (503), parsing numérico defensivo.
4. **Ações com egress/custo só por POST** (explicação por LLM), para que um `<img src>` de terceiros não as dispare.
5. **Demo compose**: portas publicadas só em `127.0.0.1`; funções Lambda falam com o Station pela rede interna (`LAMBDA_DOCKER_NETWORK`); imagem do Station não-root, a partir do jar pronto.

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| Autenticação obrigatória sempre | Fricção desproporcional no loopback de um dev (a ADR-007 continua válida aí) |
| Só CORS restritivo | Não protege requisições "simples" (CSRF) nem rebinding |
| Token em query string permanente | Vaza em histórico/logs; por isso a troca imediata por cookie e o redirect sem o token |

## Consequências

Clientes programáticos de `POST/DELETE` precisam enviar `X-Trace2Local: 1` (o IT do order-service foi ajustado). Testes: `guardBlocksCsrfAndDnsRebinding`, `hostAllowlistDefeatsDnsRebinding`, `UiCspComplianceTest`, e o loop de usabilidade verifica CSP servida, 0 violação e 403 sem o cabeçalho. **Continua declarado:** sem TLS próprio (use proxy), redaction é mitigação, e a ferramenta não deve rodar em produção (bloqueio da ADR-007 mantido).
