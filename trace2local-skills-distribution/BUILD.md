# Build Instructions - trace2local-skills-distribution

## Pré-requisitos

- Maven 3.9+ (ou use Maven wrapper `mvnw` / `mvnw.cmd`)
- Java 21+ JDK

## Build Local

### Opção 1: Build apenas este módulo

```bash
cd trace2local-skills-distribution
mvn clean package -DskipTests
```

Resultado: `target/trace2local-skills-distribution-0.1.0-SNAPSHOT.jar`

### Opção 2: Build do projeto todo (recomendado)

```bash
cd .. # volta para raiz
mvn clean install -DskipTests
```

Isto constrói todos os módulos e instala no repositório local (`~/.m2/repository`).

### Opção 3: Build com testes

```bash
mvn clean verify
```

## Inspecionar JAR

Após build, verificar conteúdo:

```bash
jar tf target/trace2local-skills-distribution-0.1.0-SNAPSHOT.jar | grep squad-ai-skills/
```

Deve listar:
```
squad-ai-skills/INDEX.md
squad-ai-skills/instalo-pipeline-completo/SKILL.md
squad-ai-skills/observabilidade-lambda/SKILL.md
```

## Usar localmente (sem publicar no Maven Central)

Após `mvn clean install`:

1. Adicione ao seu projeto `pom.xml`:
   ```xml
   <dependency>
     <groupId>tech.neural7.trace2local</groupId>
     <artifactId>trace2local-skills-distribution</artifactId>
     <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```

2. Seu projeto será capaz de carregar skills via classpath:
   ```java
   InputStream is = getClass().getResourceAsStream(
     "/squad-ai-skills/instalo-pipeline-completo/SKILL.md"
   );
   ```

## Publicar no Maven Central

(Requer credenciais OSSRH configuradas em `~/.m2/settings.xml`)

```bash
# Deploy SNAPSHOT
mvn clean deploy -DskipTests

# Release (require tag no git)
# Usar maven-release-plugin ou fazer manualmente:
# 1. Alterar version em pom.xml: 0.1.0-SNAPSHOT → 0.1.0
# 2. git commit + git tag v0.1.0
# 3. mvn clean deploy -DskipTests
# 4. Alterar version: 0.1.0 → 0.2.0-SNAPSHOT
# 5. git commit + git push
```

## Troubleshooting

| Problema | Solução |
|----------|---------|
| `[ERROR] Unknown packaging: jar` | Atualizar Maven para 3.9+ |
| `Build success but JAR vazio` | Verificar que resources estão em `src/main/resources/` |
| `Dependency not found` | Rodar `mvn clean install` em toda a árvore antes |
| `Maven wrapper ausente` | Usar `mvn` direto do PATH, ou baixar de maven.apache.org |

