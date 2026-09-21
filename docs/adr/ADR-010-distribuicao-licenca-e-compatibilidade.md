# ADR-010 — Apache-2.0, Central Portal e superfície pública mínima

- **Status:** **Aceita** — decisão **D-4** aprovada no GATE 1 (ver `GATE-1-DECISOES.md`)

## Contexto

O Trace2Local será publicado como open source no Maven Central, sob a Neural7 Tech. Três escolhas precisam estar definidas antes do primeiro `deploy`, porque todas são caras de mudar depois: **licença**, **coordenadas** e **o que conta como API pública**.

Fatos verificados em 2026-09-18:

- O **OSSRH** (`oss.sonatype.org`, `s01.oss.sonatype.org`) foi **encerrado em 30/06/2025**. A publicação hoje é pelo **Central Publisher Portal** — qualquer tutorial que mencione o Nexus antigo está obsoleto.
- Namespace: domínio próprio exige registro **TXT no DNS**; sem domínio, `io.github.<usuário>` é auto-verificado por login no GitHub.
- Requisitos por release: `jar` + `-sources.jar` + `-javadoc.jar`, checksums, **assinatura GPG de cada arquivo**, POM com licença, desenvolvedor e SCM.
- Apache-2.0 é o padrão de facto do ecossistema Java de observabilidade — OpenTelemetry, Jaeger, Zipkin, Glowroot e springdoc estão todos sob ela — e é a única entre as permissivas com **concessão explícita de patente**; a CNCF a recomenda desde 2017.

## Decisão

**Licença: Apache-2.0.** MIT não traz concessão de patente, o que trava aprovação em jurídico corporativo; AGPL inviabiliza adoção numa biblioteca de dev-tooling (a obrigação de abrir o fonte de quem roda versão modificada como serviço é um não imediato em qualquer empresa).

**Coordenadas: `tech.neural7.trace2local`**, verificando `neural7.tech` por registro TXT — o domínio já pertence ao autor e a marca compõe com o produto. Alternativa imediata, se a verificação emperrar: `io.github.<usuário>`.

**Publicação:** Central Publisher Portal, com `central-publishing-maven-plugin`, assinatura GPG e SBOM (CycloneDX) por release.

**Compatibilidade.** SemVer a partir do 1.0.0. Em 0.x, a API pode quebrar entre *minors* — e isso **DEVE** estar no README, não subentendido. A superfície pública é **apenas**:

1. `Trace2LocalExtension` e os tipos do TVEM que ela expõe;
2. as propriedades de configuração `trace2local.*`;
3. os contratos REST/SSE de `/trace2local/api`.

Todo o resto vive em pacotes `internal` e muda livremente — regra verificada por ArchUnit, não por convenção.

**Pendência de nome:** ~~o repositório atual grafa "traceventa" nos arquivos~~ — resolvida no GATE 1 (D-5): arquivos renomeados para `trace2local-*`. Resta verificar disponibilidade do nome no GitHub, no npm (caso a UI vire pacote) e como marca.

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **MIT** | Sem concessão de patente; fora do padrão do nicho de observabilidade |
| **AGPL-3.0** | Inviabiliza adoção corporativa — o oposto do objetivo |
| **Dual license / open core** | Complexidade de governança desproporcional a um projeto de um mantenedor |
| **Repositório privado primeiro** | Adia o feedback que mais importa: se alguém de fora entende a proposta em cinco minutos |
| **API pública ampla desde o 0.1** | Toda classe exposta vira compromisso. Superfície mínima é o que permite evoluir (Risco R-09) |

## Consequências

**Boas.** Zero atrito jurídico para adoção; alinhamento com o ecossistema; liberdade de refatorar o interior sem quebrar ninguém; SBOM e assinatura desde o primeiro release.

**Ruins, e assumidas.**

1. **Cerimônia de release** (GPG, javadoc, sources, checksums) em toda publicação. Mitigação: automatizar no CI já no M0 — release manual apodrece.
2. **Verificação de domínio depende de DNS**, com latência de propagação. Mitigação: iniciar no M0, não no M9.
3. **Apache-2.0 permite uso comercial fechado** do trabalho. É o preço da adoção, e é o trade-off certo aqui.
4. **0.x com API instável** exige disciplina de CHANGELOG desde o primeiro commit.
