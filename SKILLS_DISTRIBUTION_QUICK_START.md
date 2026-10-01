# Trace2Local Skills Distribution - Quick Start

## Resumo

Você agora tem um **módulo Maven pronto para distribuir Skills da Squad AI** como um JAR reutilizável.

### O que foi criado?

```
trace2local-skills-distribution/
├── pom.xml                          # Configuração Maven (packaging: jar)
├── README.md                        # Documentação completa
├── BUILD.md                         # Instruções de build
└── src/main/resources/
    └── squad-ai-skills/
        ├── INDEX.md                 # Catálogo de skills
        ├── instalo-pipeline-completo/
        │   └── SKILL.md             # Skill: Instalação & CI/CD
        ├── observabilidade-lambda/
        │   └── SKILL.md             # Skill: Observabilidade em Lambda
        └── trace2local-dev-only-security/
            └── SKILL.md             # Skill: Segurança Development-Only
```

## Próximos passos

### 1. Fazer Build do JAR

```bash
cd trace2local-skills-distribution
mvn clean package -DskipTests
# Resultado: target/trace2local-skills-distribution-0.1.0-SNAPSHOT.jar
```

Ou build de todo o projeto:

```bash
cd ..
mvn clean install -DskipTests
```

### 2. Verificar conteúdo do JAR

```bash
jar tf trace2local-skills-distribution/target/*.jar | grep squad-ai-skills/
```

Deve listar os 3 skills:
- `squad-ai-skills/instalo-pipeline-completo/SKILL.md`
- `squad-ai-skills/observabilidade-lambda/SKILL.md`
- `squad-ai-skills/trace2local-dev-only-security/SKILL.md`
- `squad-ai-skills/INDEX.md`

### 3. Usar em outro projeto

#### No pom.xml do seu projeto:

```xml
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-skills-distribution</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

#### Em sua aplicação Java:

```java
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SkillLoader {
  public static String loadSkill(String skillName) throws IOException {
    String path = "/squad-ai-skills/" + skillName + "/SKILL.md";
    InputStream is = SkillLoader.class.getResourceAsStream(path);
    
    if (is == null) {
      throw new IllegalArgumentException("Skill not found: " + skillName);
    }
    
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
  }
}

// Uso:
String skill = SkillLoader.loadSkill("instalo-pipeline-completo");
System.out.println(skill);
```

### 4. Publicar no Maven Central (opcional)

Pré-requisitos:
- Conta Sonatype OSSRH
- Credenciais em `~/.m2/settings.xml`

```bash
# Deploy SNAPSHOT (desenvolvimento)
mvn clean deploy -DskipTests

# Release (tag + version sem SNAPSHOT)
# Ver BUILD.md para instruções completas
```

## Adicionar novos Skills

1. Crie diretório:
   ```bash
   mkdir -p trace2local-skills-distribution/src/main/resources/squad-ai-skills/seu-skill-name/
   ```

2. Crie `SKILL.md` com frontmatter:
   ```yaml
   ---
   name: seu-skill-name
   description: Descrição breve (< 100 chars)
   ---
   
   # Seu Skill
   
   Conteúdo do skill...
   ```

3. Atualize `INDEX.md`:
   ```bash
   vim trace2local-skills-distribution/src/main/resources/squad-ai-skills/INDEX.md
   ```

4. Commit e push:
   ```bash
   git add trace2local-skills-distribution/
   git commit -m "feat(skills): adicionar novo skill 'seu-skill-name'"
   git push
   ```

## Estrutura de um SKILL.md

```yaml
---
name: skill-identifier          # snake-case, único
description: Breve descrição    # max 100 chars
keywords: [tag1, tag2]          # opcional
audience: [developer, ops]      # opcional
complexity: intermediate        # basic | intermediate | advanced
updated: 2026-10-01             # ISO date
---

# Título do Skill

## Contexto
Quando usar este skill...

## Step 1: ...
Instruções...

## Troubleshooting
| Problema | Solução |
|----------|---------|

## Referências
- [Link](...)
```

## Integração com Claude

Para usar um skill em conversas com Claude:

1. Carregar o skill:
   ```bash
   mvn dependency:copy-dependencies -DoutputDirectory=./lib
   # (ou disponibilizar o JAR de outra forma)
   ```

2. Extrair SKILL.md e colocar em seu prompt Claude

3. Exemplo:
   ```
   [Conteúdo de instalo-pipeline-completo/SKILL.md aqui]
   
   Agora, configure o trace2local em meu projeto Java com Spring Boot.
   ```

## Documentação Completa

- **README.md** — Overview e uso como dependency
- **BUILD.md** — Build, deploy, e troubleshooting
- **INDEX.md** — Catálogo de skills disponíveis

---

**Criado em:** 2026-10-01  
**Versão:** 0.1.0-SNAPSHOT  
**Autor:** Thiago Gonçalo Gomes  
**License:** Apache 2.0

