# GATE 1 — Decisões aprovadas pelo product owner

> **Data da aprovação:** 2026-09 (product owner: Thiago Gonçalo — instrução direta de implementação do projeto)
> **Efeito:** destrava o M0 e toda a implementação da v0.1. As cinco decisões pendentes da §13 da SPEC foram resolvidas conforme as recomendações da squad, registradas abaixo.

| # | Decisão | Resolução | Impacto |
| :--- | :--- | :--- | :--- |
| **D-1** | Baseline de bytecode | **Java 21** para `core`/adapters/starter; build com JDK 25; nada de `ScopedValue` no núcleo | ADR-009 sai de Proposta → Aceita |
| **D-2** | Station na v0.1? | **Sim**, mantido no marco M7 — o modo Embedded funciona sozinho antes dele | ADR-002 permanece Aceita |
| **D-3** | `ReturnValues` elevado por padrão? | **Ligado por padrão em perfil de desenvolvimento**, com aviso no log de boot; desligável via `tracevanta.aws.dynamodb.capture-before=false`; desligado fora de dev | ADR-003 sai de "com ressalva" → Aceita |
| **D-4** | groupId | **`tech.neural7.tracevanta`** (verificação de domínio `neural7.tech` via TXT antes do primeiro deploy; fallback `io.github.*`) | ADR-010 sai de Proposta → Aceita |
| **D-5** | Grafia oficial | **TraceVanta**; arquivos `traceventa-*` renomeados para `tracevanta-*` neste commit | Pendência de §11.2 resolvida |

**Notas de fechamento:**

- Nenhuma linha de código foi escrita antes desta aprovação, conforme o aviso do rodapé da SPEC.
- As decisões D-1 e D-4 eram as únicas com status "Proposta" nos ADRs; a tabela do `docs/adr/README.md` e os cabeçalhos dos ADRs afetados foram atualizados nesta mesma revisão.
