## O que esta PR faz
<!-- Resumo curto da mudança -->

## Tipo de mudança
- [ ] Correção de bug
- [ ] Nova funcionalidade
- [ ] Melhoria de UI/UX
- [ ] Documentação
- [ ] Infra/CI/build

## Definição de pronto (marque o que se aplica)
- [ ] `./mvnw install` verde (unidade + propriedade + contrato + ArchUnit)
- [ ] Testes novos para a mudança (corpus de redaction, invariantes, UI)
- [ ] E2E revalidado quando mexe em telemetria/instrumentação (`-Pit`)
- [ ] Jornadas da stack alvo (`examples/finance-pix/scripts/journeys.py`) — JVM e nativo se tocar o Lambda
- [ ] Loop de usabilidade (`scripts/ux-loop/persona-loop-pix.mjs`) verde quando muda a UI
- [ ] Mock Connect/MCP: nada de mutação sem opt-in; simulado sempre marcado (SIM/↪)
- [ ] CHANGELOG atualizado em `0.1.0-SNAPSHOT`
- [ ] Sem referência externa nos assets da UI (ADR-005)
- [ ] Sem literal de atributo OTel fora de `OtelAttributeNames` (ADR-008)

## Notas para o revisor
<!-- Contexto, riscos, decisões tomadas -->
