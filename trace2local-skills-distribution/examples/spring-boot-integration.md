# Integração com Spring Boot

Exemplo de como integrar `trace2local-skills-distribution` em uma aplicação Spring Boot.

## Setup

### 1. Dependency no pom.xml

```xml
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-skills-distribution</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Opcional: Spring Boot Web para exemplo com REST -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

### 2. Service para carregar Skills

```java
package com.example.skills;

import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class SkillService {
  
  private static final Map<String, String> skillCache = new HashMap<>();
  
  public String loadSkill(String skillName) throws Exception {
    if (skillCache.containsKey(skillName)) {
      return skillCache.get(skillName);
    }
    
    String path = "/squad-ai-skills/" + skillName + "/SKILL.md";
    InputStream is = getClass().getResourceAsStream(path);
    
    if (is == null) {
      throw new IllegalArgumentException("Skill not found: " + skillName);
    }
    
    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    skillCache.put(skillName, content);
    
    return content;
  }
  
  public String getSkillIndex() throws Exception {
    return loadSkill("../INDEX");  // Carrega INDEX.md
  }
}
```

### 3. REST Controller para acessar Skills

```java
package com.example.skills;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
  
  @Autowired
  private SkillService skillService;
  
  @GetMapping("/{skillName}")
  public ResponseEntity<String> getSkill(@PathVariable String skillName) {
    try {
      String skill = skillService.loadSkill(skillName);
      return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .body(skill);
    } catch (Exception e) {
      return ResponseEntity.notFound().build();
    }
  }
  
  @GetMapping
  public ResponseEntity<String> listSkills() {
    try {
      String index = skillService.getSkillIndex();
      return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .body(index);
    } catch (Exception e) {
      return ResponseEntity.internalServerError().build();
    }
  }
}
```

### 4. Configuração (application.yml)

```yaml
spring:
  application:
    name: skill-server
  mvc:
    throw-exception-if-no-handler-found: true

server:
  port: 8080
  servlet:
    context-path: /

logging:
  level:
    com.example.skills: DEBUG
```

## Uso

### Via HTTP

```bash
# Listar todos os skills
curl http://localhost:8080/api/skills

# Carregar um skill específico
curl http://localhost:8080/api/skills/instalo-pipeline-completo

# Salvar em arquivo
curl http://localhost:8080/api/skills/instalo-pipeline-completo > instalo.md
```

### Programaticamente

```java
@Service
public class MyService {
  
  @Autowired
  private SkillService skillService;
  
  public void executarComSkill() throws Exception {
    String skill = skillService.loadSkill("observabilidade-lambda");
    
    // Usar skill em sua lógica
    System.out.println("Skill carregado:");
    System.out.println(skill.substring(0, 200)); // primeiros 200 chars
  }
}
```

## Advanced: Custom Skill Loader com Reflection

```java
package com.example.skills;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SkillRegistry {
  
  private Map<String, String> registry = new HashMap<>();
  
  public SkillRegistry() throws Exception {
    loadAllSkills();
  }
  
  private void loadAllSkills() throws Exception {
    PathMatchingResourcePatternResolver resolver = 
      new PathMatchingResourcePatternResolver();
    
    Resource[] resources = resolver.getResources(
      "classpath*:squad-ai-skills/**/SKILL.md"
    );
    
    for (Resource resource : resources) {
      String path = resource.getURL().getPath();
      String skillName = extractSkillName(path);
      String content = new String(resource.getInputStream().readAllBytes());
      
      registry.put(skillName, content);
    }
    
    System.out.println("Carregados " + registry.size() + " skills");
  }
  
  private String extractSkillName(String path) {
    // /squad-ai-skills/skill-name/SKILL.md → skill-name
    String[] parts = path.split("/squad-ai-skills/");
    if (parts.length > 1) {
      return parts[1].split("/")[0];
    }
    return "unknown";
  }
  
  public Map<String, String> getAll() {
    return new HashMap<>(registry);
  }
  
  public String get(String skillName) {
    return registry.get(skillName);
  }
}
```

Usar com controller:

```java
@Autowired
private SkillRegistry skillRegistry;

@GetMapping
public ResponseEntity<Map<String, String>> listSkills() {
  return ResponseEntity.ok(skillRegistry.getAll());
}
```

## Docker

### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk-jammy as builder

WORKDIR /app
COPY . .
RUN apt-get update && apt-get install -y maven \
    && mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-jammy

WORKDIR /app
COPY --from=builder /app/target/skill-server.jar .

EXPOSE 8080
CMD ["java", "-jar", "skill-server.jar"]
```

Build:

```bash
mvn clean package -DskipTests -f pom.xml
docker build -t skill-server:latest .
docker run -p 8080:8080 skill-server:latest
```

## Testes Unitários

```java
package com.example.skills;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SkillControllerTest {
  
  @Autowired
  private MockMvc mockMvc;
  
  @Test
  void testGetSkillInstalo() throws Exception {
    mockMvc.perform(get("/api/skills/instalo-pipeline-completo"))
      .andExpect(status().isOk())
      .andExpect(content().contentType("text/plain;charset=UTF-8"))
      .andExpect(content().string(containsString("Instalação")));
  }
  
  @Test
  void testGetSkillNotFound() throws Exception {
    mockMvc.perform(get("/api/skills/skill-inexistente"))
      .andExpect(status().isNotFound());
  }
  
  @Test
  void testListSkills() throws Exception {
    mockMvc.perform(get("/api/skills"))
      .andExpect(status().isOk())
      .andExpect(content().contentType("text/plain;charset=UTF-8"));
  }
}
```

---

**Exemplo completo:** `examples/spring-boot-skill-server/` no repositório trace2local

