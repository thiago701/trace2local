# Application Configuration Examples

## 📋 application.yml (Padrão / Produção-like)

```yaml
# application.yml - Configuração padrão
# Ativada quando nenhum perfil específico é selecionado
# ⚠️ Trace2Local DESABILITADO por segurança

spring:
  application:
    name: payment-service
    version: 1.0.0
  
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQL10Dialect
  
  datasource:
    url: jdbc:postgresql://localhost:5432/payment_db
    username: payment_user
    password: ${DB_PASSWORD}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  metrics:
    export:
      prometheus:
        enabled: true

# 🔒 TRACE2LOCAL: Desabilitado por padrão
trace2local:
  enabled: false
  
logging:
  level:
    root: INFO
    com.example.payment: INFO
```

## 🔧 application-dev.yml (Desenvolvimento Local)

```yaml
# application-dev.yml - Configuração local de desenvolvimento
# Ativada com: java -jar app.jar --spring.profiles.active=dev
# ✅ Trace2Local HABILITADO

spring:
  application:
    name: payment-service
    version: 1.0.0-DEV
  
  jpa:
    hibernate:
      ddl-auto: create-drop  # Recria BD a cada execução
    show-sql: true
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQL10Dialect
      hibernate.format_sql: true
  
  datasource:
    url: jdbc:postgresql://localhost:5432/payment_db_dev
    username: payment_dev
    password: password  # OK em dev local
    hikari:
      maximum-pool-size: 5

# 🚀 TRACE2LOCAL: HABILITADO em desenvolvimento
trace2local:
  enabled: true
  ui:
    port: 19877
    context-path: /trace2local
  collector:
    batch-size: 10  # Menor batch para testes interativos
    flush-interval: 2000  # 2 segundos
  storage:
    max-traces: 500
    retention-minutes: 30

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,trace2local  # Expor endpoints de debug
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  level:
    root: DEBUG
    com.example.payment: DEBUG
    tech.neural7.trace2local: DEBUG  # Logs detalhados de trace2local
  pattern:
    console: "%d{HH:mm:ss.SSS} [%-15thread] %-5level %-40logger{39} : %msg%n"
```

## 📦 application-prod.yml (Produção)

```yaml
# application-prod.yml - Configuração de produção
# Ativada com: java -jar app.jar --spring.profiles.active=prod
# 🔒 Trace2Local SEMPRE DESABILITADO

spring:
  application:
    name: payment-service
    version: 1.0.0
  
  jpa:
    hibernate:
      ddl-auto: validate  # Apenas valida, não modifica BD
    show-sql: false
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQL10Dialect
  
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      connection-timeout: 5000
      idle-timeout: 600000
      max-lifetime: 1800000

# 🔒 TRACE2LOCAL: SEMPRE desabilitado em produção
trace2local:
  enabled: false

# Observabilidade real (não trace2local)
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
      cloudwatch:
        enabled: true  # Se em AWS
  tracing:
    sampling:
      probability: 0.1  # 10% sampling para produção

logging:
  level:
    root: WARN
    com.example.payment: INFO
  pattern:
    json: true  # JSON logging para ELK
  file:
    name: /var/log/payment-service/app.log
    max-size: 100MB
    max-history: 30
```

## 🧪 Validações em Startup

### Application.java com Guards

```java
package com.example.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class PaymentServiceApplication {
  
  @Value("${trace2local.enabled:false}")
  private boolean trace2localEnabled;
  
  private final Environment env;
  
  public PaymentServiceApplication(Environment env) {
    this.env = env;
  }
  
  @PostConstruct
  public void validateEnvironment() {
    String[] activeProfiles = env.getActiveProfiles();
    String activeProfileStr = String.join(",", activeProfiles).isEmpty() ? "default" : String.join(",", activeProfiles);
    
    System.out.println("╔════════════════════════════════════════════════════════════╗");
    System.out.println("║          Payment Service - Startup Validation             ║");
    System.out.println("╠════════════════════════════════════════════════════════════╣");
    System.out.println("║ Active Profile(s): " + String.format("%-40s", activeProfileStr) + "║");
    System.out.println("║ Trace2Local Enabled: " + (trace2localEnabled ? "✅ YES" : "❌ NO") + String.format("%39s", "") + "║");
    
    // 🚨 Segurança crítica: bloquear se trace2local está em produção
    if (isProd() && trace2localEnabled) {
      System.err.println("╠════════════════════════════════════════════════════════════╣");
      System.err.println("║ 🔴 FATAL ERROR: Trace2Local HABILITADO EM PRODUÇÃO!       ║");
      System.err.println("║ Trace2Local é APENAS para desenvolvimento local.          ║");
      System.err.println("╚════════════════════════════════════════════════════════════╝");
      throw new IllegalStateException(
        "Trace2Local não pode estar habilitado em produção. " +
        "Profile: " + activeProfileStr + ", Trace2Local: " + trace2localEnabled
      );
    }
    
    // ⚠️ Avisar se em desenvolvimento
    if (isDev() && trace2localEnabled) {
      System.out.println("║ 🟡 TRACE2LOCAL: Ativado (DEV apenas) em http://localhost:19877 ║");
    }
    
    System.out.println("║ Database: " + env.getProperty("spring.datasource.url") + String.format("%30s", "") + "║");
    System.out.println("╚════════════════════════════════════════════════════════════╝");
  }
  
  private boolean isProd() {
    return env.acceptsProfiles("prod");
  }
  
  private boolean isDev() {
    return env.acceptsProfiles("dev");
  }
  
  public static void main(String[] args) {
    // 🔒 Se em CI/CD (GitHub Actions), forçar perfil prod
    if (System.getenv("CI") != null && 
        System.getProperty("spring.profiles.active") == null) {
      System.setProperty("spring.profiles.active", "prod");
    }
    
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
```

## 🚀 Como rodar em cada ambiente

### Desenvolvimento Local

```bash
# Padrão: ativa "dev" profile automaticamente
java -jar payment-service-1.0.0.jar

# Ou explícito:
java -jar payment-service-1.0.0.jar --spring.profiles.active=dev

# ✅ Resultado:
# - Trace2Local ativado
# - UI em http://localhost:19877/trace2local
# - BD em-memory ou local
# - Logs DEBUG
```

### Staging / QA

```bash
# Teste SEM trace2local
java -jar payment-service-1.0.0.jar --spring.profiles.active=staging

# Se staging-profile.yml não existir, usa padrão (trace2local=false)
```

### Produção (CI/CD)

```bash
# GitHub Actions (env var CI=true ativa prod automaticamente)
java -jar payment-service-1.0.0.jar --spring.profiles.active=prod

# Docker Compose
docker run -e SPRING_PROFILES_ACTIVE=prod payment-service:latest

# Kubernetes
kubectl set env deployment/payment-service SPRING_PROFILES_ACTIVE=prod

# 🔒 Resultado:
# - Trace2Local DESABILITADO (mesmo se JAR contém)
# - Observabilidade real (Prometheus, CloudWatch)
# - Logs estruturados
# - BD remoto com credenciais
```

## 📊 Verificação de Deploy

### Antes de fazer deploy para prod:

```bash
# 1. Verificar que JAR não contém trace2local
jar tf target/payment-service-1.0.0.jar | grep -i trace2local
# ✅ Deve retornar vazio

# 2. Verificar que config prod desabilita trace2local
grep "trace2local" application-prod.yml
# ✅ Deve listar: trace2local: enabled: false

# 3. Simular produção localmente
java -jar target/payment-service-1.0.0.jar --spring.profiles.active=prod

# ✅ Startup logs devem mostrar:
# "Trace2Local Enabled: ❌ NO"
# "Active Profile(s): prod"
```

---

**Segurança garantida:** Trace2Local nunca chegará em produção com essa configuração.

