# Servidor MCP do Trace2Local — o app do dev visível para agentes de IA

> Decisão: [ADR-017](adr/ADR-017-servidor-mcp.md) · módulo `trace2local-mcp` · E2E na stack alvo:
> [qa/MCP-E2E.md](qa/MCP-E2E.md) (**14/14**).

O `trace2local-mcp` expõe a observabilidade local do Trace2Local pelo **Model Context Protocol**. Qualquer assistente
de código ou harness agêntico com suporte a MCP (Claude Code, Cursor, VS Code/Copilot, Codex, Windsurf, Zed,
orquestradores próprios) passa a **ver a execução real** do app — árvore, dados alterados, logs, laudo, insights,
topologia e mocks — em vez de adivinhar a partir do código.

```
 agente (Claude Code, Cursor, squad…) ──MCP (stdio | HTTP loopback)──► trace2local-mcp ──HTTP local──► Trace2Local
                                                                        (cliente fino)                   (app embedded ou Station)
```

É um **cliente fino da API local** (sem dependência do core): roda com JRE 21+, aponta para o app (modo embedded,
`:9876`) ou para o Station (`:19877` no compose de exemplo) e não abre nada além de stdio — ou, opcionalmente, um
endpoint HTTP em `127.0.0.1`.

## Instalação

```bash
mvn -pl trace2local-mcp -am package -DskipTests
# → trace2local-mcp/target/trace2local-mcp-<versão>-all.jar   (≈ 2,4 MB, só Jackson)
```

**Claude Code** (projeto — `.mcp.json` na raiz do repositório do app):

```json
{
  "mcpServers": {
    "trace2local": {
      "command": "java",
      "args": ["-jar", "/caminho/trace2local-mcp-0.1.0-SNAPSHOT-all.jar"],
      "env": { "TRACE2LOCAL_URL": "http://127.0.0.1:19877/trace2local" }
    }
  }
}
```

ou `claude mcp add trace2local -e TRACE2LOCAL_URL=http://127.0.0.1:19877/trace2local -- java -jar /caminho/trace2local-mcp-all.jar`.

**Cursor / VS Code / Windsurf**: o mesmo bloco `command` + `args` + `env` no arquivo de MCP do editor
(`.cursor/mcp.json`, `.vscode/mcp.json` com chave `servers`).

**Harness por URL** (Streamable HTTP): `java -jar trace2local-mcp-all.jar --http 7341` → `http://127.0.0.1:7341/mcp`
(defina `TRACE2LOCAL_MCP_HTTP_TOKEN` para exigir Bearer).

## Configuração

| variável / opção | padrão | efeito |
|---|---|---|
| `TRACE2LOCAL_URL` / `--url` | `http://127.0.0.1:9876/trace2local` | base da UI/API (Station do compose: `:19877`) |
| `TRACE2LOCAL_UI_TOKEN` / `--token` | — | token de UI do perfil `corporate` (vai como Bearer) |
| `TRACE2LOCAL_MCP_ALLOW_MUTATIONS` / `--allow-mutations` | `false` | expõe `dispatch_endpoint` e as ferramentas que alteram mocks |
| `TRACE2LOCAL_MCP_DATA` / `--data` | `structural` | `structural`: sem corpos de payload nem valores de dados; `full`: inclui (já redigidos na origem) |
| `TRACE2LOCAL_MCP_ALLOW_REMOTE` / `--allow-remote` | `false` | aceita URL fora do loopback |
| `TRACE2LOCAL_MCP_HTTP_PORT` / `--http` | — | Streamable HTTP em `127.0.0.1:PORTA/mcp` em vez de stdio |
| `TRACE2LOCAL_MCP_HTTP_TOKEN` | — | Bearer exigido no transporte HTTP |
| `TRACE2LOCAL_MCP_TOOLS` / `--tools` | `all` | `core` = 7 essenciais (`status`, `list_executions`, `get_execution`, `get_step`, `diagnose_failure`, `explain_execution`, `list_mock_suggestions`) — schema residente cai de ~3,0 k para ~1,3 k tokens; ou lista `a,b,c` |

## Ferramentas

| ferramenta | para quê | leitura? |
|---|---|---|
| `status` | Trace2Local acessível? modo, app, capacidades, motor de decisão | ✓ |
| `list_executions` | execuções recentes, filtro por status/texto | ✓ |
| `get_execution` | árvore como *outline* com `[nodeId]`, ✕ erro, Δ dado, SIM/↪ mock, ⧗ fila | ✓ |
| `get_step` | um passo: caminho, atributos, erro + stack, Δ, payload*, logs do passo | ✓ |
| `get_logs` | logs correlacionados (app + START/END/REPORT), filtro por nível/texto | ✓ |
| `diagnose_failure` | **por que falhou?** causa raiz, caminho, exceção, logs, laudo, insights, mock pronto | ✓ |
| `explain_execution` | laudo executivo (regras × fluxo, checklist) e técnico (hotspots, caminho crítico) + narrativa | ✓ |
| `list_insights` | Regras Assíncronas Preditivas ranqueadas | ✓ |
| `get_topology` | anatomia por zona, chamadas, erros, p50/p95, ligações assíncronas | ✓ |
| `compare_executions` | o que mudou entre a que passou e a que falhou | ✓ |
| `list_endpoints` · `wait_for_execution` | contrato disparável; esperar uma execução por id/traceId | ✓ |
| `list_mock_suggestions` · `list_mock_bindings` · `get_mock_journal` · `list_mock_plugins` · `validate_mock_binding` | Mock Connect (quando/qual mock, variações, journal, catálogo, validação por chave) | ✓ |
| `dispatch_endpoint` | dispara o endpoint (corpo, cabeçalhos — ex.: `baggage: t2l.mock=<id>`) e devolve a árvore | opt-in |
| `apply_mock_suggestion` · `put_mock_binding` · `control_mock_binding` | pluga mock/variação, cria binding, pausa/retoma/reinicia/remove | opt-in |

\* payload só com `TRACE2LOCAL_MCP_DATA=full`.

**Prompts** (o harness oferece como comandos): `investigar-falha`, `validar-variacoes-de-parceiro`,
`homologar-execucao` — cada um encadeia as ferramentas e impõe a disciplina *evidência primeiro, fato ≠ hipótese*.

## Como um agente usa (exemplo real, resumido de [qa/MCP-E2E.md](qa/MCP-E2E.md))

```text
> diagnose_failure
Execução 77cf7f2f… · FAILED · 26.64 s · raiz: pix-api · POST /pix/transfers
CAUSA(S) RAIZ — FATO OBSERVADO
- [3c5fe631cddb7829] HTTP_CLIENT POST SPI (BACEN) /spi/v1/settlements
  caminho: pix-api · POST /pix/transfers › Aceitar e enviar para liquidação › SQS: pix-settlement › pix-settlement · SQS … › Liquidar no SPI › POST SPI …
  erro: status do span: HTTP 503
  resposta do Mock Connect: [SIM binding=mock-spi-bacen; …; variation=v-falha-transitoria]
LAUDO  desfecho: Falha técnica (93%, jev-deterministic) …
PRÓXIMOS PASSOS  get_step(…) · compare_executions … · list_mock_suggestions host=spi.bacen.local
```

## Segurança e privacidade

- **Somente leitura por padrão.** Disparar o app ou mudar mocks exige opt-in explícito; ferramenta bloqueada responde
  com `isError` explicando como ligar (o agente propõe o passo ao dev em vez de falhar calado).
- **Local-first.** A base precisa ser loopback (senão, `--allow-remote`); o transporte HTTP só escuta em `127.0.0.1`,
  valida `Origin` (anti DNS rebinding) e aceita Bearer opcional.
- **O que sai para o modelo do agente.** Registrar o servidor no harness é a autorização explícita para o agente
  ler estrutura, tempos, erros e logs (já redigidos na origem pelo Trace2Local). Corpos de payload e valores de dados
  só com `TRACE2LOCAL_MCP_DATA=full`. Nada é persistido pelo MCP.
- **Respeita o RequestGuard** do Trace2Local (ADR-015): mutações levam `X-Trace2Local: 1`; token de UI quando exigido.
- **Saídas limitadas** (60 000 caracteres, com aviso de truncamento) e erros acionáveis — nunca stack interno.
