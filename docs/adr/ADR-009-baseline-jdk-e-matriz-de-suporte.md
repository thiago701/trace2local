# ADR-009 — Baseline de bytecode e matriz de versões

- **Status:** **Aceita** — decisão **D-1** aprovada no GATE 1 (ver `GATE-1-DECISOES.md`)
- **Relacionada:** §7.2 da SPEC

## Contexto

O alvo declarado do produto é **Java 25 + GraalVM Native + AWS**. Mas "alvo de desenvolvimento" e "baseline de bytecode de uma biblioteca que outros embutem" são decisões diferentes, e a segunda define quem pode adotar o TraceVanta.

Dados verificados em 2026-09-18:

- **Java 25 é LTS** (GA em 16/09/2025). `ScopedValue` é **final** (JEP 506). `StructuredTaskScope` continua **preview** (JEP 505) mesmo na LTS. O *pinning* de virtual threads em `synchronized` foi resolvido no **JDK 24** (JEP 491), herdado pela 25.
- **Spring Boot 4.1.1** é o estável atual, com baseline **JDK 17** e suporte de primeira classe a Java 25. (A especificação anterior citava "Spring Boot 3.4+" — **defasado**.)
- **AWS Lambda** oferece os runtimes gerenciados `java25` e `java21`, além de `provided.al2023` para binário nativo.
- `ScopedValue` **não propaga** para pools de threads de plataforma clássicos (`ForkJoinPool`, `ExecutorService`); a herança automática existe dentro de `StructuredTaskScope` — que é preview.

Duas restrições se cruzam: exigir Java 25 simplifica o build e alinha ao discurso do produto, mas exclui todo aplicativo Spring Boot 4 rodando em 17 ou 21 — a maioria da base instalada. E uma biblioteca **não deve** exigir `--enable-preview` de quem a embute: isso contamina o build inteiro do consumidor.

## Decisão (aprovada no GATE 1)

**Baseline de bytecode Java 21** para `tracevanta-core` e os adapters; **build com JDK 25**; recursos exclusivos do 25 isolados em módulo opcional.

| Módulo | Baseline | Justificativa |
| :--- | :--- | :--- |
| `tracevanta-core`, `-otel`, `-aws`, `-jdbc`, `-server` | **21** | Cobre Spring Boot 4 em 17/21/25 e os runtimes `java21`/`java25` da Lambda |
| `tracevanta-spring-boot-starter` | **21** | Segue o consumidor |
| `tracevanta-java25` (opcional) | **25** | `ScopedValue` e o que mais vier; ausente ⇒ degrada sem erro |
| Build e CI | **JDK 25** | Compila com `--release`, testa nas três versões |

Consequência direta: **`ScopedValue` não entra no núcleo.** O carregamento de contexto usa o `Context` do OpenTelemetry (que é `ThreadLocal`), com `TraceVantaThreadFactory` para virtual threads — porque o `Context` do OTel, sendo `ThreadLocal`, **não é herdado** por `Thread.startVirtualThread` (issue oficial, fechada como *not planned*).

**Nenhum módulo DEVE exigir `--enable-preview`.** `StructuredTaskScope` fica fora até virar final.

## Alternativas

| Alternativa | Prós | Contras |
| :--- | :--- | :--- |
| **Java 25 puro em tudo** | Build mais simples; coerente com o discurso "Java 25 first"; `ScopedValue` disponível em qualquer módulo | Exclui a maior parte da base instalada de uma lib que quer adoção; contradiz o objetivo de open source amplo (ADR-010) |
| **Java 17** | Alcance máximo | Renuncia a virtual threads no núcleo e amarra o projeto ao passado por três anos |
| **Multi-Release JAR (21 + 25)** | Alcance e recursos novos no mesmo artefato | MR-JAR é fonte conhecida de dor em GraalVM Native e em ferramentas de build; complexidade alta para ganho marginal |

## Consequências

**Se aprovada (21).** Base instalada ampla; sem `--enable-preview`; matriz de teste de três versões (21, 25, nativo); custo: sem `ScopedValue` no núcleo — perda pequena, porque o contexto de trace já é resolvido pelo OTel.

**Se recusada em favor de 25 puro.** Build e matriz mais simples; produto coerente com o posicionamento "Java 25 + AOT"; custo: adoção restrita a quem já migrou para a LTS mais recente — aceitável se o público-alvo declarado forem squads que já estão em Java 25 (o que é o caso do autor).

> Esta é a decisão **D-1** do GATE 1. Ela muda o `pom.xml` de todos os módulos e a matriz de CI, então precisa ser tomada **antes do M0**, não depois.
