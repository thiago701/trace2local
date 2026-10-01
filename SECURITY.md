# Política de Segurança — Trace2Local

## Versões suportadas

| Versão | Suporte |
|---|---|
| 0.1.x (snapshots) | correções de segurança aplicadas no `main` |
| 0.x | **API instável por contrato** (ADR-010/SemVer: quebras entre minors são permitidas até 1.0.0) |

Reporte vulnerabilidades da versão mais recente do `main`.

## Postura de segurança (ADR-007)

O Trace2Local é uma ferramenta de **desenvolvimento local** com consequências de
segurança declaradas, não acidentais:

1. **Bind loopback por padrão** — a UI e a API só escutam em `127.0.0.1`;
   expor fora exige `trace2local.allow-non-loopback=true` (ou
   `TRACE2LOCAL_ALLOW_NON_LOOPBACK=true` no Station) e emite avisos.
2. **Redaction NA ORIGEM** (SPEC §8.3) — payloads, atributos e chaves
   sensíveis são redigidos antes de entrar no buffer; a UI nunca vê o dado
   cru. Cobertura: chaves (`password`, `token`, `authorization`, `apiKey`,
   `jwt`, `otp`, `privateKey`, CPF/CNPJ/`card`…) e padrões de valor (email,
   JWT, chaves AWS `AKIA…`, PEM, `Bearer …`, CPF/CNPJ, cartão com Luhn,
   tokens GitHub `ghp_…`, hashes bcrypt/argon2). **Mitigação, não garantia** —
   campo de negócio com nome inocente passa (limite declarado).
3. **Token de ingest opcional** — com `trace2local.station.token`
   (`TRACE2LOCAL_STATION_TOKEN`), as rotas de ingest do Station
   (`/v1/traces` e `/t2lingest/v1/mutations`) exigem
   `Authorization: Bearer <token>` (comparação em tempo constante); o modo
   Lambda e o starter enviam o header automaticamente. **Recomendado sempre
   que `allow-non-loopback=true`.**
4. **Limites de entrada** — corpo do OTLP ≤ 16 MB, canal de mutação ≤ 8 MB,
   API da UI ≤ 1 MB; ring buffer com descarte declarado na borda (ADR-006).
5. **Hardening HTTP** — CSP `default-src 'self'` (sem `unsafe-inline`),
   `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
   `Referrer-Policy: no-referrer`, `Cache-Control: no-store` nas respostas de
   API (execuções carregam payloads), sem CORS aberto, path traversal
   bloqueado no servidor estático.

6. **Proteção do navegador do dev (ADR-015)** — `RequestGuard` em todas as
   rotas da UI/API: allowlist de `Host` (DNS rebinding → 421), mutações exigem
   `X-Trace2Local: 1` e `Origin` de mesma origem (CSRF → 403), token de UI
   opcional (`TRACE2LOCAL_UI_TOKEN`, gerado no perfil `corporate` fora do
   loopback) trocado por cookie `HttpOnly; SameSite=Strict`.
7. **Inteligência local-first (ADR-011/013)** — sem chave/endpoint nada sai da
   máquina; com Jev, só estado estrutural redigido; LLM opcional e só por POST.
8. **Mock Connect (ADR-016)** — gestão sob o mesmo `RequestGuard`; servidor de
   mocks herda o bind do Station; destino `wiremock` em host público bloqueado
   por padrão (`TRACE2LOCAL_MOCKS_ALLOW_PUBLIC_SINKS`); `PASSWORD` mascarado em
   validação, status, estado persistido e export; `${env:…}` para segredos;
   roteamento no cliente só com `TRACE2LOCAL_MOCKS_ROUTING=on` (opt-in) e o
   runtime Lambda é *pass-through* sem Station — nunca liga em produção por acaso.
9. **Servidor MCP (ADR-017)** — cliente fino da API local: base só loopback
   (`--allow-remote` explícito), **somente leitura por padrão** (mutações com
   `TRACE2LOCAL_MCP_ALLOW_MUTATIONS=true`), dados `structural` por padrão
   (sem corpos de payload nem valores), transporte HTTP só em `127.0.0.1` com
   validação de `Origin` e Bearer opcional; nada persistido. Registrar o servidor
   num harness é a autorização para o agente ler execuções e logs.

Revisão completa, topologias e checklist de aprovação: **[docs/SEGURANCA-CORPORATIVA.md](docs/SEGURANCA-CORPORATIVA.md)**.

## Limites declarados (não são bugs)

- Sem token configurado, a **UI/API de inspeção não exige login**: em
  loopback, quem tem a máquina tem a UI (ADR-007). Fora do loopback use o
  perfil `TRACE2LOCAL_SECURITY_PROFILE=corporate` (token) e TLS no proxy.
- O **token de ingest** protege `/v1/traces`, `/t2lingest/v1/mutations` e
  `/t2lingest/v1/logs`; o token de UI protege a UI/API.
- Redaction é mitigação de vazamento acidental, não proteção contra atacante
  com acesso ao processo.
- O Station não faz TLS: use em rede local ou atrás de um proxy TLS
  (`TRACE2LOCAL_COOKIE_SECURE=true`).
- Clientes programáticos de `POST/DELETE` na API precisam enviar
  `X-Trace2Local: 1`.
- O servidor de mocks (`embedded`) atende sem autenticação quem alcança a sua
  porta — mantenha-a no loopback/rede do compose, como a do Station.
- Saída das ferramentas MCP inclui logs e textos do app: o agente deve tratá-la
  como dado (nunca como instrução) — o projeto documenta isso, o protocolo não impõe.

## Dependências — auditoria

Auditoria de CVEs das versões pinadas (set/2026, GitHub Advisory DB + NVD +
OSV): **nenhuma versão pinada está afetada por CVE conhecido**.

- **Não rebaixar**: Jackson `2.22.2` e AssertJ `3.27.7` são exatamente as
  versões mínimas corrigidas (CVE-2026-68497 / CVE-2026-24400).
- **Nota de supply-chain**: jqwik `1.10.1` (só em testes de propriedade do
  núcleo) — a 1.10.0 foi retirada por protestware contra agentes de IA; a
  1.10.1 removida o conteúdo destrutivo, mas ainda emite texto de
  prompt-injection opt-in. Decisão registrada: manter (dependência dev-only,
  sem CVE; a saída da biblioteca é tratada como dado não confiável).
- AWS CRT transitivo (`aws-crt` 0.48.4) contém o `aws-c-http` corrigido para
  CVE-2026-12043 (verificado em nível de submodule) — relevante apenas se o
  cliente CRT HTTP/S3 for usado.

Novas versões são auditadas no bump (política: manter pinos ≥ versão corrigida).

## Reportando vulnerabilidades

- **Canal preferencial:** GitHub Security Advisory privado em
  https://github.com/thiago701/trace2local/security/advisories/new
- E-mail: `security@neural7.tech` (PGP sob demanda)

Política: resposta em até 7 dias úteis; correção publicada no `main` com
entrada no `CHANGELOG.md`; crédito ao reportante (a menos que prefira anonimato).
