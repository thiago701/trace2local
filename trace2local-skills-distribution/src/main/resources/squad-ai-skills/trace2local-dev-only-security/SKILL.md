---
name: trace2local-dev-only-security
description: Estratégia de 5 camadas para garantir que Trace2Local seja usado APENAS em desenvolvimento local — nunca suba para staging/produção
keywords: [security, dev-only, maven, profiles, spring-boot, ci-cd, production-safety]
audience: [architect, senior-developer, devops, tech-lead]
complexity: intermediate
updated: 2026-10-01
---

# Trace2Local: Development-Only Security Strategy

## Contexto

Trace2Local é poderosa, mas é **APENAS para desenvolvimento local**. Deve ser impossível que suba para staging ou produção, por:

- **Segurança**: Expõe detalhes internos da aplicação (traces, logs, timeline)
- **Performance**: Coleta traces tem overhead
- **Compliance**: Ambiente de produção não deve ter ferramentas de debug

**Este skill garante que Trace2Local NUNCA chegue em produção** através de 5 camadas de proteção independentes.

---

## 🔒 Camada 1: Maven Profiles + Scopes

### Regra: `provided` scope + profile `dev`

**Por que funciona:** 
- `provided` = disponível em compile-time mas NÃO é incluído no JAR final
- Profile `dev` = ativa APENAS localmente por padrão
- Profile `prod` = ativa em CI/CD, não inclui trace2local

### Implementação

```xml
<!-- pom.xml -->

<!-- Gerenciamento de dependências -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>tech.neural7.trace2local</groupId>
      <artifactId>trace2local-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- Dependências principais: SEM trace2local aqui -->
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <!-- ... outras deps ... -->
</dependencies>

<!-- PROFILES: Separação strict -->
<profiles>
  <!-- ✅ DEV: Inclui Trace2Local -->
  <profile>
    <id>dev</id>
    <activation>
      <activeByDefault>true</activeByDefault>  <!-- ← Padrão local -->
    </activation>
    <dependencies>
      <dependency>
        <groupId>tech.neural7.trace2local</groupId>
        <artifactId>trace2local-spring-boot-starter</artifactId>
        <scope>provided</scope>  <!-- 🔒 Não entra em JAR -->
      </dependency>
    </dependencies>
  </profile>

  <!-- 🚫 PROD: NÃO inclui Trace2Local -->
  <profile>
    <id>prod</id>
    <activation>
      <property>
        <name>env.CI</name>  <!-- ← Ativa em GitHub Actions -->
      </property>
    </activation>
    <!-- Nenhuma dependência de trace2local aqui -->
  </profile>
</profiles>
```

### Validação

```bash
# Build local (ativa dev por padrão)
mvn clean install
jar tf target/*.jar | grep trace2local
# ✅ Deve listar arquivos de trace2local

# Build produção (força prod)
mvn clean verify -P prod
jar tf target/*.jar | grep trace2local
# ✅ Deve retornar VAZIO
```

---

## 🔐 Camada 2: Spring Boot Conditional Activation

### Regra: `@ConditionalOnProperty` com `matchIfMissing=false`

**Por que funciona:**
- Property `trace2local.enabled` padrão: `false`
- Mesmo se classe está no classpath, não ativa sem flag
- YAML pode sobrescrever, mas CI/CD controla YAML

### Implementação

```java
// Autoconfiguração de Trace2Local
@Configuration
@ConditionalOnProperty(
  name = "trace2local.enabled",
  havingValue = "true",
  matchIfMissing = false  // 🔒 Padrão: DESABILITADO
)
public class Trace2LocalAutoConfiguration {
  
  @Bean
  public Trace2LocalCollector trace2LocalCollector() {
    System.out.println("[Trace2Local] ✓ ATIVADO (apenas desenvolvimento)");
    return new Trace2LocalCollector();
  }
}
```

### Configuração YAML

```yaml
# application.yml (padrão, produção-like)
trace2local:
  enabled: false  # 🔒 Padrão seguro

# application-dev.yml (desenvolvimento)
trace2local:
  enabled: true   # ✅ Liberar em dev
  ui:
    port: 19877

# application-prod.yml (produção)
trace2local:
  enabled: false  # 🔒 NUNCA ativar em prod
```

### Validação

```bash
# Dev: deve estar ativado
java -jar app.jar --spring.profiles.active=dev
# Log: "[Trace2Local] ✓ ATIVADO (apenas desenvolvimento)"

# Prod: deve estar desabilitado
java -jar app.jar --spring.profiles.active=prod
# Log: "[Trace2Local] Desabilitado (perfil: prod)"
```

---

## 🛡️ Camada 3: Maven Build Guards

### Regra: Enforcer Plugin bloqueia JAR com trace2local

**Por que funciona:**
- Executa durante fase `package`
- Verifica conteúdo do JAR gerado
- Rejeita build se trace2local detectado em produção

### Implementação

```xml
<!-- Maven Enforcer Plugin -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.4.1</version>
  <executions>
    <execution>
      <id>verify-no-trace2local-in-jar</id>
      <phase>verify</phase>
      <goals>
        <goal>enforce</goal>
      </goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <excludes>
              <exclude>tech.neural7.trace2local:*</exclude>
            </excludes>
            <message>
              ❌ ERRO CRÍTICO: Trace2Local detectado em build de PRODUÇÃO!
              Trace2Local é apenas para desenvolvimento local.
              Se você precisa de observabilidade em produção, use OpenTelemetry diretamente.
            </message>
          </bannedDependencies>
        </rules>
        <fail>true</fail>
      </configuration>
    </execution>
  </executions>
</plugin>

<!-- Verificação adicional: JAR final -->
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.1.0</version>
  <executions>
    <execution>
      <id>verify-jar-clean</id>
      <phase>verify</phase>
      <goals>
        <goal>exec</goal>
      </goals>
      <configuration>
        <executable>bash</executable>
        <arguments>
          <argument>-c</argument>
          <argument>
            JAR=$(find target -name "*.jar" | head -1)
            if [ -n "$JAR" ] &amp;&amp; jar tf "$JAR" | grep -iq trace2local; then
              echo "❌ FATAL: Trace2Local em JAR de produção!"
              exit 1
            fi
            echo "✅ JAR limpo: Trace2Local não encontrado"
          </argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Validação

```bash
# Tentar build com prod (deve falhar se trace2local está em dependencies)
mvn clean verify -P prod
# ✅ Deve passar (enforcer OK)
# ✅ Deve imprimir: "✅ JAR limpo: Trace2Local não encontrado"
```

---

## 🚀 Camada 4: CI/CD Gates (GitHub Actions)

### Regra: Workflows separados (dev vs prod), prod força env var

**Por que funciona:**
- Dev workflow usa `-P dev` (inclui trace2local)
- Prod workflow usa `-P prod` (exclui trace2local)
- GitHub Actions não pode "burlar" profiles com env vars
- Qualquer pessoa que tente fazer push com trace2local para main é bloqueada

### Implementação

```yaml
# .github/workflows/build-prod.yml
name: Build & Deploy Production

on:
  push:
    branches: [main]

env:
  CI: true  # ← Ativa profile prod automaticamente

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 25
        uses: actions/setup-java@v3
        with:
          java-version: '25'
      
      # ❌ NUNCA ativa profile dev em produção
      - name: Build with Maven (PROD profile)
        run: mvn clean verify -P prod -DskipTests=false
      
      # Verificação extra: JAR NÃO deve conter trace2local
      - name: Verify Trace2Local NOT in JAR
        run: |
          JAR=$(find target -name "*.jar" | head -1)
          if jar tf "$JAR" | grep -iq trace2local; then
            echo "❌ ERRO: Trace2Local em JAR de produção!"
            exit 1
          fi
          echo "✅ Verificado: Trace2Local não está em produção JAR"
      
      # Deploy para Maven Central / Docker Registry / etc
      - name: Deploy to Maven Central
        run: mvn deploy -P prod -DskipTests
        env:
          MAVEN_CENTRAL_TOKEN: ${{ secrets.MAVEN_CENTRAL_TOKEN }}
```

```yaml
# .github/workflows/build-dev.yml
name: Build Development

on:
  push:
    branches: [develop, "feat/**"]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 25
        uses: actions/setup-java@v3
        with:
          java-version: '25'
      
      # ✅ DEV profile: inclui trace2local
      - name: Build with Maven (DEV profile)
        run: mvn clean verify -P dev -DskipTests=false
      
      # Deploy SNAPSHOT local
      - name: Deploy SNAPSHOT
        run: mvn deploy -P dev
        env:
          MAVEN_CENTRAL_TOKEN: ${{ secrets.MAVEN_CENTRAL_TOKEN }}
```

### Validação

```bash
# Verificar que workflows estão em .github/workflows/
ls -la .github/workflows/build-*.yml

# Revisar: dev workflow deve usar "-P dev"
grep -- "-P dev" .github/workflows/build-dev.yml

# Revisar: prod workflow deve usar "-P prod"
grep -- "-P prod" .github/workflows/build-prod.yml
```

---

## 🔑 Camada 5: Runtime Validation

### Regra: Guard no `main()` + `@PostConstruct` valida profiles

**Por que funciona:**
- Mesmo que alguém acidentalmente ative trace2local em prod
- Application startup valida e LANÇA EXCEÇÃO
- Faz com que seja impossível rodar em produção

### Implementação

```java
@SpringBootApplication
public class PaymentServiceApplication {
  
  @Value("${trace2local.enabled:false}")
  private boolean trace2localEnabled;
  
  private final Environment env;
  
  public PaymentServiceApplication(Environment env) {
    this.env = env;
  }
  
  // Guard 1: main() força profile prod em CI
  public static void main(String[] args) {
    if (System.getenv("CI") != null && 
        System.getProperty("spring.profiles.active") == null) {
      System.setProperty("spring.profiles.active", "prod");
    }
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
  
  // Guard 2: @PostConstruct valida setup
  @PostConstruct
  public void validateEnvironment() {
    String[] profiles = env.getActiveProfiles();
    boolean isProd = env.acceptsProfiles("prod");
    
    if (isProd && trace2localEnabled) {
      // 🔴 REJEITAR IMEDIATAMENTE
      System.err.println("╔════════════════════════════════════════════════════════════╗");
      System.err.println("║ 🔴 FATAL: Trace2Local ativado em PRODUÇÃO!                ║");
      System.err.println("║ Isso é um bug de segurança. Não pode continuar.            ║");
      System.err.println("╚════════════════════════════════════════════════════════════╝");
      throw new IllegalStateException(
        "Trace2Local não pode estar ativado em produção. " +
        "Perfil: " + String.join(",", profiles) + 
        ", Trace2Local: " + trace2localEnabled
      );
    }
    
    // ✅ Log confirmação
    System.out.println("╔════════════════════════════════════════════════════════════╗");
    System.out.println("║ Perfil(s): " + String.format("%-50s", String.join(",", profiles)) + "║");
    System.out.println("║ Trace2Local: " + (trace2localEnabled ? "✅ ATIVADO (DEV)" : "❌ DESABILITADO") + String.format("%40s", "") + "║");
    System.out.println("╚════════════════════════════════════════════════════════════╝");
  }
}
```

### Validação

```bash
# Dev: deve iniciar com sucesso
java -jar app.jar --spring.profiles.active=dev
# Log: "Trace2Local: ✅ ATIVADO (DEV)"

# Prod: deve REJEITAR se trace2local ativado (não deve conseguir)
java -jar app.jar --spring.profiles.active=prod --trace2local.enabled=true
# ❌ Exceção: "FATAL: Trace2Local ativado em PRODUÇÃO!"
# Aplicação NÃO inicia
```

---

## 📊 Teste Completo: Garantir que Funciona

### Cenário 1: Build Local (DEV)

```bash
cd seu-projeto
mvn clean install  # ativa dev automaticamente

# ✅ Esperado:
# - Build sucesso
# - JAR contém tech/neural7/trace2local/...
# - Pode iniciar com trace2local ativado
```

### Cenário 2: Build Produção (CI/CD)

```bash
# Simular GitHub Actions (env var CI=true)
export CI=true
mvn clean verify -P prod

# ✅ Esperado:
# - Build sucesso
# - JAR NÃO contém trace2local
# - Enforcer OK
# - Log: "✅ JAR limpo: Trace2Local não encontrado"
```

### Cenário 3: Tentar "Burlar" a Segurança

```bash
# Tentar colocar trace2local em produção
# (Tudo abaixo deve FALHAR)

# 1️⃣ Tentar forçar dependency em pom.xml prod profile
#    → Maven Enforcer rejeita
mvn clean verify -P prod
# ❌ ERRO: bannedDependencies

# 2️⃣ Tentar ativar trace2local via env var
java -jar app.jar --spring.profiles.active=prod --trace2local.enabled=true
# ❌ Startup guard lança IllegalStateException

# 3️⃣ Tentar fazer push sem testes
#    → GitHub Actions workflow obriga testes
git push origin main
# ❌ Actions falha se testes não passam
```

---

## ✅ Checklist de Implementação

- [ ] **Maven Profiles:** Criar profiles `dev` (default) e `prod` (CI env var)
- [ ] **Scopes:** Trace2Local com scope `provided` em dev, não em prod
- [ ] **Spring:** `@ConditionalOnProperty(...matchIfMissing=false)` em autoconfig
- [ ] **YAML:** `trace2local.enabled: false` em `application.yml`, `true` em `application-dev.yml`
- [ ] **Enforcer:** Maven Enforcer Plugin com bannedDependencies
- [ ] **Exec Plugin:** Verificar JAR final não contém trace2local
- [ ] **CI/CD:** Workflows separados (dev vs prod), prod força `-P prod`
- [ ] **Runtime:** Guard em `main()` força prod profile em CI, `@PostConstruct` valida
- [ ] **Tests:** Testes unitários validam que autoconfig não ativa sem flag
- [ ] **Documentation:** README documenta como usar em dev vs prod

---

## 🚫 Anti-patterns (NUNCA faça!)

```xml
<!-- ❌ ERRADO: Trace2Local em dependencies principal -->
<dependencies>
  <dependency>
    <groupId>tech.neural7.trace2local</groupId>
    <artifactId>trace2local-spring-boot-starter</artifactId>
  </dependency>
</dependencies>

<!-- ❌ ERRADO: Scope runtime (vai para JAR) -->
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-spring-boot-starter</artifactId>
  <scope>runtime</scope>
</dependency>
```

```yaml
# ❌ ERRADO: Trace2Local ativado por padrão
trace2local:
  enabled: true  # Vai ativar em QUALQUER lugar!
```

```bash
# ❌ ERRADO: Usar -Dspring.profiles.active em CI
mvn clean deploy -Dspring.profiles.active=dev  # CI vai usar dev!
# Correto: usar environment variable ou Maven profile
```

---

## 🎯 Resultado Final

Com essas 5 camadas:

✅ **Impossível** que trace2local chegue em produção  
✅ **Impossível** que suba para staging/QA sem detecção  
✅ **Impossível** ignorar warnings/validações  
✅ **Fácil** usar em desenvolvimento (padrão)  
✅ **Auditável** (logs + GitHub Actions)  

---

## Referências

- Maven Profiles: https://maven.apache.org/guides/introduction/introduction-to-profiles.html
- Maven Enforcer: https://maven.apache.org/enforcer/maven-enforcer-plugin/
- Spring @ConditionalOnProperty: https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/autoconfigure/condition/ConditionalOnProperty.html
- GitHub Actions Workflows: https://docs.github.com/en/actions/using-workflows

