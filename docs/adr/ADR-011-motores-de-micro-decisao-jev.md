# ADR-011 — Micro-decisões por modelos pequenos (Jev) com piso determinístico local

- **Status:** Aceita (2026-09-30)
- **Relacionadas:** ADR-007 (local-first), ADR-013 (Regras Preditivas), ADR-015 (endurecimento corporativo)

## Contexto

A UI Resonance precisa responder, a cada execução, dezenas de perguntas pequenas: *qual o papel deste passo?*, *esta linha de log é erro de negócio ou técnico?*, *a regra do glossário foi respeitada?*, *o desfecho foi sucesso, recusa protegida ou falha?*, *qual o risco para homologar?*. São **micro-decisões** — classificação, escolha entre poucas opções, nota numérica — e não pedem um LLM generalista.

O usuário pediu "adotar massivamente o Jev" (TypeSafe AI System One: primitivas `noul`, `choice` e `score`, perguntas em lote avaliadas em paralelo) **e** que a lib funcione sem a chave. Restrições inegociáveis: local-first (ADR-007), nenhum segredo/payload sai da máquina sem opt-in, custo previsível, e o laudo de homologação não pode piorar por causa de um modelo confiante e errado.

## Decisão

**1. Uma SPI de decisão (`DecisionModel`) e um motor em cascata (`DecisionEngine`).** Ordem: **cassete** (replay determinístico, offline) → **Jev ao vivo** (só com chave) → **determinístico local** (`DeterministicJevModel`, sempre disponível). Fatos observáveis (status, erro presente, consumidor observado) são respondidos como **FATO** antes de qualquer modelo.

**2. Determinístico é o padrão e o piso.** Sem chave (`TRACE2LOCAL_JEV_API_KEY`/`JEV_API_KEY`) o modo `auto` vira `deterministic`. As mesmas perguntas, o mesmo contrato de resposta (`Answer`: escolha, valor, confiança, motor, justificativa) — a UI não muda.

**3. Política de fusão por família, calibrada por benchmark (`FusionPolicy`).** O benchmark rotulado (106 itens) mostrou que o Jev acerta muito em classificação de log e papel arquitetural, mas erra **com confiança 1,0** em desfecho e veredito de regra. Então:

| família | política | limiar |
|---|---|---|
| classe de log | `JEV_FIRST` | 0,70 |
| papel do passo | `JEV_FIRST` | 0,90 |
| desfecho da execução | `RULE_FIRST` | 0,95 |
| veredito de regra | `RULE_FIRST` | 0,90 |
| PII / IaC (`pii_`, `iac_`) | `RULE_FIRST` | 0,95 |
| idempotência/performance (`idem_`, `perf_`) | `RULE_FIRST` | 0,90 |
| notas numéricas (`score`) | média ponderada pela confiança, Jev com **meia voz** | — |

Divergência forte com regra fraca ⇒ `needsReview` (revisão humana), visível na UI.

**4. Egress mínimo e auditável (`EgressSanitizer`).** Padrão `structural`: só a forma da execução (tipos, rótulos, contagens, durações) — sem payload, sem valores; texto livre passa pelo `TextRedactor`; hosts viram `<host-N>`, IPs privados são mascarados. `values` é opt-in explícito. Endpoint só HTTPS (ou loopback para testes); sem redirect.

**5. Orçamento e disjuntor.** Limite de requisições/min, teto diário de tokens de entrada, timeout, estimativa de custo, **circuit breaker** (3 falhas → 60 s aberto; erro de autenticação → 10 min). Qualquer falha cai no determinístico, sem quebrar a UI.

**6. Cassete reprodutível.** `record` grava `sha256(modelo|estado|pergunta) → resposta`; `replay` responde offline. O benchmark do repositório roda em CI sem chave.

## Alternativas descartadas

| Alternativa | Por que não |
|---|---|
| LLM generalista para tudo | Custo/latência por execução, não determinístico, difícil de auditar, envia contexto demais |
| Jev obrigatório | Quebra local-first e a adoção em empresas sem aprovação de fornecedor |
| Confiar na confiança do Jev | Medimos confiança 1,0 em respostas erradas (desfecho/veredito) |
| Só heurística | Perde ganho real medido (papel arquitetural: 0,75 → 1,00 com a política) |

## Consequências

**Boas.** Política do produto 0,96 de acurácia no gabarito (determinístico 0,92; Jev puro 0,78); sobre traces REAIS (Lambda + LocalStack, 12 execuções) o Jev ao vivo custou ~US$ 0,001, 0 falhas, ~250 ms por lote, e não alterou nenhum desfecho (política RULE_FIRST). Funciona offline, idêntico, sem chave.

**Ruins, e assumidas.** O determinístico foi ajustado com o mesmo gabarito (risco de sobreajuste — declarado nos relatórios); a confiança do Jev não é calibrada; manter a política exige re-medir a cada versão do modelo (dever do AGENTS.md).
