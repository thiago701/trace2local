# Trace2Local Skills Distribution

Distribuição oficial de **Skills reutilizáveis** da Squad AI. Este JAR contém templates de `SKILL.md` e recursos compartilhados para integração fácil em qualquer projeto Java/Maven.

## O que é um Skill?

Um **Skill** é um arquivo `SKILL.md` que encapsula instruções reusáveis para um agente IA (Claude). Inclui:
- Instruções passo-a-passo
- Padrões de decisão
- Exemplos de uso
- Validações e checkpoints

## Como usar

### 1. Adicionar Dependency ao seu `pom.xml`

```xml
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-skills-distribution</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Acessar Skills em seu código

Os skills são distribuídos como **resources classpath**:

```java
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SkillLoader {
  public static String loadSkill(String skillName) throws IOException {
    InputStream is = SkillLoader.class.getResourceAsStream(
      "/squad-ai-skills/" + skillName + "/SKILL.md"
    );
    if (is == null) {
      throw new IllegalArgumentException("Skill not found: " + skillName);
    }
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
  }
  
  public static void main(String[] args) throws IOException {
    String skill = loadSkill("instalo-pipeline-completo");
    System.out.println(skill);
  }
}
```

### 3. Integração com Claude (via prompt context)

```java
// 1. Carregar skill
String skill = SkillLoader.loadSkill("seu-skill-aqui");

// 2. Incorporar no prompt
String prompt = skill + "\n\nSeu contexto e pergunta específica aqui...";

// 3. Enviar para Claude API (anthropic-sdk-java)
// ... chamada à API com prompt customizado
```

## Estrutura de Diretórios

```
trace2local-skills-distribution/
├── pom.xml
├── README.md
└── src/main/resources/
    └── squad-ai-skills/
        ├── instalo-pipeline-completo/
        │   └── SKILL.md
        ├── observabilidade-lambda/
        │   └── SKILL.md
        ├── trace2local-dev-only-security/
        │   └── SKILL.md
        └── (mais skills...)
```

## Disponíveis Skills

| Skill | Descrição |
|-------|-----------|
| `instalo-pipeline-completo` | Guia completo de instalação e setup do Trace2Local com CI/CD |
| `observabilidade-lambda` | Observabilidade de Lambda functions com Trace2Local e OpenTelemetry |
| `trace2local-dev-only-security` | Estratégia de 5 camadas para garantir Trace2Local apenas em desenvolvimento |

## Maven Central

Este JAR é publicado automaticamente no **Maven Central** via GitHub Actions.

**Versão estável (release):**
```xml
<version>0.1.0</version>
```

**Versão snapshot (desenvolvimento):**
```xml
<version>0.1.0-SNAPSHOT</version>
<repositories>
  <repository>
    <id>ossrh-snapshots</id>
    <url>https://s01.oss.sonatype.org/content/repositories/snapshots</url>
  </repository>
</repositories>
```

## Contribuindo novo Skill

1. Crie um diretório sob `src/main/resources/squad-ai-skills/{skill-name}/`
2. Adicione `SKILL.md` e recursos relacionados
3. Atualize este `README.md` com entrada na tabela de skills
4. Commitar e fazer push: a build Maven automaticamente incluirá

## Exemplo: Carregar todos os skills

```java
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class SkillRegistry {
  public Map<String, String> loadAllSkills() throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Resource[] resources = resolver.getResources("classpath*:squad-ai-skills/**/SKILL.md");
    
    Map<String, String> skills = new HashMap<>();
    for (Resource resource : resources) {
      String path = resource.getURL().getPath();
      String skillName = path.split("/squad-ai-skills/")[1].split("/")[0];
      String content = new String(resource.getInputStream().readAllBytes());
      skills.put(skillName, content);
    }
    return skills;
  }
}
```

## License

Apache License 2.0 — veja [LICENSE](../../LICENSE) no repositório raiz.

## Suporte

- 📝 [GitHub Issues](https://github.com/thiago701/trace2local/issues)
- 💬 [Discussões](https://github.com/thiago701/trace2local/discussions)

---

**Autor:** Thiago Gonçalo Gomes  
**Mantido por:** Thiago Gonçalo Gomes
