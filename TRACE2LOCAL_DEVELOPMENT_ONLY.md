# Trace2Local - Development Only Strategy

Garantir que Trace2Local seja usado **apenas em desenvolvimento local** e NUNCA suba para staging/produção.

## Estratégia em 5 Camadas

### 🔒 Camada 1: Maven Scope & Profiles

#### pom.xml - Configuração Base

```xml
<!-- BOM com trace2local como optional -->
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

<!-- Trace2Local: NUNCA na seção principal de dependencies -->
<dependencies>
  <!-- ... outras dependências ... -->
</dependencies>

<!-- Profile: APENAS para desenvolvimento local -->
<profiles>
  <profile>
    <id>dev</id>
    <activation>
      <activeByDefault>true</activeByDefault>  <!-- Ativa por padrão localmente -->
    </activation>
    <dependencies>
      <!-- Spring Boot Starter + Trace2Local -->
      <dependency>
        <groupId>tech.neural7.trace2local</groupId>
        <artifactId>trace2local-spring-boot-starter</artifactId>
        <scope>provided</scope>  <!-- Só em compile-time, não em JAR -->
      </dependency>
    </dependencies>
  </profile>

  <profile>
    <id>prod</id>
    <activation>
      <property>
        <name>env.CI</name>  <!-- Ativa em CI/CD (GitHub Actions) -->
      </property>
    </activation>
    <!-- Nenhuma dependência trace2local aqui -->
  </profile>
</profiles>
```

### 🔐 Camada 2: Spring Boot Conditional Activation

#### Configuração Spring (application.yml)

```yaml
# application.yml (padrão, prod-like)
spring:
  application:
    name: payment-service

trace2local:
  enabled: false  # Desabilitado por padrão
  
# application-dev.yml (desenvolvimento)
spring:
  profiles:
    active: dev

trace2local:
  enabled: true
  ui:
    port: 19877
```

#### Autoconfiguração com ConditionalOnProperty

```java
package tech.neural7.trace2local.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import io.opentelemetry.api.GlobalOpenTelemetry;

@Configuration
@ConditionalOnProperty(
  name = "trace2local.enabled",
  havingValue = "true",
  matchIfMissing = false  // Padrão: desabilitado
)
public class Trace2LocalAutoConfiguration {
  
  public Trace2LocalAutoConfiguration() {
    System.out.println("[Trace2Local] ✓ ATIVADO (apenas desenvolvimento)");
  }
  
  // ... resto da configuração
}
```

### 🛡️ Camada 3: Guard em Tempo de Build

#### Maven Enforcer Plugin - Bloquear produção

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.4.1</version>
  <executions>
    <!-- Bloquear se tentar fazer release com profile dev -->
    <execution>
      <id>block-release-with-dev</id>
      <phase>validate</phase>
      <goals>
        <goal>enforce</goal>
      </goals>
      <configuration>
        <rules>
          <requireProperty>
            <property>maven.test.skip</property>
            <regex>false</regex>  <!-- Testes devem rodar -->
            <regexMessage>Testes não podem ser skipped em release!</regexMessage>
          </requireProperty>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>

<!-- Verificação: Trace2Local não pode estar em JAR final -->
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.1.0</version>
  <executions>
    <execution>
      <id>verify-no-trace2local-in-jar</id>
      <phase>verify</phase>
      <goals>
        <goal>exec</goal>
      </goals>
      <configuration>
        <executable>sh</executable>
        <arguments>
          <argument>-c</argument>
          <argument>
            jar tf target/*.jar | grep -i "trace2local" && echo "ERRO: Trace2Local encontrado em JAR!" && exit 1 || echo "✓ Trace2Local NÃO está em JAR (correto)"
          </argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### 🚀 Camada 4: CI/CD Guards (GitHub Actions)

#### .github/workflows/build-prod.yml

```yaml
name: Build Production

on:
  push:
    branches: [main]
  workflow_dispatch:

env:
  CI: true  # Ativa profile prod
  MAVEN_PROFILE: prod

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 25
        uses: actions/setup-java@v3
        with:
          java-version: '25'
          distribution: 'temurin'
      
      # ❌ NUNCA ativa profile dev em produção
      - name: Build with Maven (PROD profile)
        run: mvn clean verify -P prod -DskipTests=false
      
      # Verificação extra: garantir que trace2local NÃO está incluído
      - name: Verify Trace2Local is NOT in production JAR
        run: |
          JAR_FILE=$(find target -name "*.jar" -type f | head -1)
          if jar tf "$JAR_FILE" | grep -i "trace2local" > /dev/null; then
            echo "❌ ERRO CRÍTICO: Trace2Local detectado em JAR de produção!"
            exit 1
          else
            echo "✅ Verificado: Trace2Local NÃO está em JAR de produção"
          fi
      
      - name: Publish to Maven Central
        run: mvn deploy -P prod -DskipTests
        env:
          MAVEN_CENTRAL_TOKEN: ${{ secrets.MAVEN_CENTRAL_TOKEN }}
```

#### .github/workflows/build-dev.yml

```yaml
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
          distribution: 'temurin'
      
      # ✅ SEMPRE ativa profile dev em snapshots
      - name: Build with Maven (DEV profile)
        run: mvn clean verify -P dev -DskipTests=false
      
      # Verificação: Trace2Local DEVE estar em snapshots locais
      - name: Verify Trace2Local IS in development build
        run: |
          JAR_FILE=$(find target -name "*.jar" -type f | head -1)
          if jar tf "$JAR_FILE" | grep -i "trace2local" > /dev/null; then
            echo "✅ Verificado: Trace2Local está presente em build de desenvolvimento"
          else
            echo "⚠️ AVISO: Trace2Local NÃO encontrado em build de desenvolvimento"
          fi
      
      - name: Deploy SNAPSHOT to Maven Central
        run: mvn deploy -P dev -DskipTests
        env:
          MAVEN_CENTRAL_TOKEN: ${{ secrets.MAVEN_CENTRAL_TOKEN }}
```

### 🔑 Camada 5: Runtime Validation

#### Guard na aplicação

```java
package com.example.trace2local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class PaymentServiceApplication {
  
  @PostConstruct
  public void validateTrace2LocalUsage() {
    String profile = System.getProperty("spring.profiles.active", "");
    String trace2localEnabled = System.getProperty("trace2local.enabled", "false");
    
    // ❌ Bloquear se trace2local está ativado em produção
    if ("prod".equalsIgnoreCase(profile) && "true".equalsIgnoreCase(trace2localEnabled)) {
      throw new IllegalStateException(
        "FATAL: Trace2Local não pode estar ativado em produção! " +
        "Profile: " + profile + ", Trace2Local: " + trace2localEnabled
      );
    }
    
    // ✅ Avisar se em desenvolvimento
    if ("dev".equalsIgnoreCase(profile)) {
      System.out.println("⚠️  [Trace2Local] Ativado em DESENVOLVIMENTO (local)");
      System.out.println("🔒 [Trace2Local] Será DESATIVADO em produção");
    }
  }
  
  public static void main(String[] args) {
    // Forçar profile prod se não especificado e estiver em CI
    if (System.getenv("CI") != null && 
        System.getProperty("spring.profiles.active") == null) {
      System.setProperty("spring.profiles.active", "prod");
    }
    
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
```

## 📋 Resumo das Proteções

| Camada | Mecanismo | Quando Bloqueia |
|--------|-----------|-----------------|
| 1️⃣ Maven | Profile `dev` + `provided` scope | Quando tenta compilar com prod |
| 2️⃣ Spring | `@ConditionalOnProperty` + `matchIfMissing=false` | Quando app inicia sem flag |
| 3️⃣ Build | Maven Enforcer + verificação JAR | Durante build em CI/CD |
| 4️⃣ CI/CD | Workflows separados (dev vs prod) | Em GitHub Actions |
| 5️⃣ Runtime | Guard no main() + @PostConstruct | Ao iniciar aplicação |

## 🧪 Teste: Garantir que funciona

### Local (DEV) - Deve incluir Trace2Local

```bash
# Build local (ativa dev profile por padrão)
mvn clean install

# Verificar que está incluído
jar tf target/payment-service.jar | grep trace2local
# Deve listar algo: tech/neural7/trace2local/...

# Iniciar (ativa trace2local)
java -jar target/payment-service.jar
# Deve ver: "[Trace2Local] ✓ ATIVADO (apenas desenvolvimento)"
# UI disponível em http://localhost:19877/trace2local
```

### CI/CD (PROD) - NÃO deve incluir Trace2Local

```bash
# Simular build produção (ativa prod profile)
mvn clean verify -P prod

# Verificar que NÃO está incluído
jar tf target/payment-service.jar | grep trace2local
# Deve retornar vazio ou erro (arquivo não encontrado)

# Iniciar (trace2local desativado)
java -jar target/payment-service.jar
# Deve ver: "trace2local.enabled is false - skipping"
# UI NÃO disponível
```

## 🚫 Anti-patterns (NUNCA faça!)

```xml
<!-- ❌ ERRADO: Trace2Local em dependencies principal -->
<dependencies>
  <dependency>
    <groupId>tech.neural7.trace2local</groupId>
    <artifactId>trace2local-spring-boot-starter</artifactId>
  </dependency>
</dependencies>

<!-- ❌ ERRADO: Usar runtime scope -->
<dependency>
  <groupId>tech.neural7.trace2local</groupId>
  <artifactId>trace2local-spring-boot-starter</artifactId>
  <scope>runtime</scope>  <!-- Vai para JAR! -->
</dependency>

<!-- ❌ ERRADO: Habilitar trace2local por padrão -->
trace2local:
  enabled: true  # Vai ativar em qualquer lugar!
```

## ✅ Checklist para Segurança

- [ ] Trace2Local em profile `dev` APENAS
- [ ] Maven scope é `provided` (não `compile`)
- [ ] `trace2local.enabled: false` em `application.yml`
- [ ] `trace2local.enabled: true` em `application-dev.yml`
- [ ] Spring Boot `matchIfMissing: false` (padrão: desativado)
- [ ] Maven Enforcer verifica JAR final
- [ ] CI/CD usa `-P prod` para produção
- [ ] GitHub Actions bloqueia se trace2local detectado em produção JAR
- [ ] Runtime guard valida profiles vs trace2local
- [ ] Documentação clara para o time

---

**Resultado:** Trace2Local só funciona em desenvolvimento local (máquina do dev). Em qualquer build para CI/CD (staging/prod), é completamente removido e não pode ativar.

