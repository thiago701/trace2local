# ADR-005 — UI como WebJar e metadados de reflexão desde o dia 1

- **Status:** Aceita (2026-09-18)
- **Relacionada:** §9 da SPEC (compatibilidade AOT), Risco R-05

## Contexto

A UI precisa viajar dentro do JAR da biblioteca — "adicione a dependência e abra o navegador" — e funcionar **offline**, sem CDN. É exatamente o problema que o Swagger UI resolveu no ecossistema Spring, e vale copiar o que funciona: o springdoc embute o Swagger UI como **WebJar** (`org.webjars:swagger-ui`), cujos assets ficam em `classpath:/META-INF/resources/webjars/...`, servidos pelo mecanismo padrão de *resource handler* do Spring, com um transformer que reescreve o `index.html` em runtime.

O ponto delicado é o Native Image. O springdoc só funciona nativo porque publica um `RuntimeHintsRegistrar` (`SpringDocHints`) registrando **reflexão** (classes de modelo) e **recursos** (os assets da UI) — e, mesmo assim, tem issue aberta desde fevereiro/2026 por incompatibilidade do formato de metadados gerado com o GraalVM 25. O add-on separado `springdoc-openapi-native` está morto desde 2023; o suporte real vive dentro do core.

A lição é dupla: o mecanismo é o certo, e **a manutenção dele é o custo real**.

## Decisão

1. **Assets como WebJar** em `META-INF/resources/tracevanta/`, servidos por resource handler padrão — não por servlet próprio.
2. **Cada módulo publica seus próprios metadados** (`RuntimeHintsRegistrar` no mundo Spring, `reachability-metadata.json` no GraalVM) para: recursos da UI, tipos do TVEM serializados por Jackson, serviços da SPI (`ServiceLoader`) e tipos de request/response inspecionados pelo catálogo. Isso nasce junto com o código, **nunca como correção pós-falha**.
3. **CI compila a app de exemplo em Native Image a cada PR** e roda as jornadas JC-1/JC-2 **sobre o binário**. O critério não é "compilou": é "a jornada funciona no binário".
4. **A matriz de suporte declara a versão exata de GraalVM testada.** Sem promessa genérica de "funciona em nativo".
5. **Zero build step para o consumidor**: assets pré-compilados, orçamento de 400 KB gzip, nenhuma referência externa (verificado por teste que faz grep nos assets).

## Alternativas descartadas

| Alternativa | Por que não |
| :--- | :--- |
| **CDN para as libs da UI** | Quebra o offline-first e envia uma requisição para fora da máquina — viola o princípio §1.3 |
| **Servlet próprio servindo bytes do classpath** | Reinventa o resource handler e piora a integração com AOT |
| **UI como aplicação separada** (npm, processo próprio) | Mata o "uma dependência e pronto" |
| **Adiar os hints para quando o nativo quebrar** | É o erro clássico: o retrabalho aparece no fim, quando o desenho já está amarrado |
| **Prometer "zero reflexão"** | Falso. O catálogo precisa inspecionar tipos para inferir schema. A promessa honesta é "reflexão catalogada em build time" |

## Consequências

**Boas.** Mecanismo comprovado no ecossistema; funciona igual em JVM e nativo; sem toolchain JS no build do consumidor; a UI é auditável (é só arquivo estático no JAR).

**Ruins, e assumidas.**

1. **Custo de manutenção contínuo** a cada release do GraalVM e do Spring (Risco R-05). Mitigação: CI nativo por PR, para descobrir cedo.
2. **A inferência de schema depende de tipos registrados.** Tipo não catalogado ⇒ corpo vazio editável com aviso, nunca um schema inventado.
3. **Orçamento de bundle restringe escolhas de UI.** `d3-hierarchy` (ISC, ~6 KB gzip) para a árvore; `Cytoscape.js` (MIT, ~137 KB) só se a visão DAG da v0.2 exigir; ELK.js fica fora por peso (~433 KB) e por licença (EPL-2.0/GPL).
