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

## Limites declarados (não são bugs)

- A **UI/API de inspeção não têm autenticação**: quem alcança a porta vê o
  acervo. Por isso o loopback é obrigatório por padrão e a exposição é uma
  decisão explícita. Se você precisa de acesso remoto à UI, coloque um proxy
  autenticado na frente (SSH tunnel ou reverse proxy com auth).
- O **token protege apenas o ingest** (`/v1/traces`, `/t2lingest/v1/mutations`);
  as rotas da UI continuam locais.
- Redaction é mitigação de vazamento acidental, não proteção contra atacante
  com acesso ao processo.
- O Station não faz TLS: use em rede local ou atrás de um proxy TLS.

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
