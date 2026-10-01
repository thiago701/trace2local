---
name: instalo-pipeline-completo
description: Guia de instalação e configuração completa de Trace2Local com CI/CD, Docker, Maven e GitHub Actions
---

# Instalação & Pipeline Trace2Local

## Pré-requisitos

- **Java 21+** (JDK 25 recomendado)
- **Maven 3.9+**
- **Docker Desktop** (para exemplos com containers)
- **Git**

## Step 1: Clonar Repositório

```bash
git clone https://github.com/thiago701/trace2local.git
cd trace2local
```

## Step 2: Build Local

### Build Completo (todos os módulos)

```bash
mvn clean install -DskipTests
```

### Build com Testes

```bash
mvn clean verify
```

### Build Específico (módulo único)

```bash
mvn clean install -pl trace2local-spring-boot-starter
```

## Step 3: Configuração Spring Boot

### Para ambiente de desenvolvimento

```yaml
# application-dev.yaml
spring:
  profiles:
    active: dev
  
trace2local:
  enabled: true
  ui:
    port: 19877
  collector:
    batch-size: 100
    flush-interval: 5000
```

### Para ambiente de produção

```yaml
# application-prod.yaml
spring:
  profiles:
    active: prod
  
trace2local:
  enabled: false  # Desabilitado em produção
```

## Step 4: Docker & LocalStack

### Iniciar ambiente local com Docker Compose

```bash
cd examples/finance-pix
docker compose up -d
```

### Provisionar infraestrutura LocalStack

```bash
cd examples/finance-pix/infra/terraform
terraform init -backend-config="localstack.tfvars"
terraform apply -var-file="localstack.tfvars"
```

## Step 5: CI/CD com GitHub Actions

O repositório inclui workflows automáticos em `.github/workflows/`:

### Build & Test (push para qualquer branch)

```yaml
- Rodas testes Maven
- Publica SNAPSHOT no Maven Central (se main ou develop)
- Publica Docker image no ghcr.io
```

### Release (tag)

```bash
git tag -a v0.2.0 -m "Release 0.2.0"
git push origin v0.2.0
```

Isto automaticamente:
- Publica versão RELEASE no Maven Central
- Cria GitHub Release com changelog
- Publica Docker image com tag versão

## Step 6: Usar como Dependency

### Adicionar ao seu `pom.xml`

```xml
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

<dependencies>
  <!-- Auto-configuração Spring Boot -->
  <dependency>
    <groupId>tech.neural7.trace2local</groupId>
    <artifactId>trace2local-spring-boot-starter</artifactId>
  </dependency>
  
  <!-- Instrumentação JDBC (opcional) -->
  <dependency>
    <groupId>tech.neural7.trace2local</groupId>
    <artifactId>trace2local-jdbc</artifactId>
  </dependency>
  
  <!-- Suporte AWS Lambda (opcional) -->
  <dependency>
    <groupId>tech.neural7.trace2local</groupId>
    <artifactId>trace2local-aws</artifactId>
  </dependency>
</dependencies>
```

### Configuração mínima (application.yml)

```yaml
spring:
  application:
    name: meu-servico
  profiles:
    active: dev

trace2local:
  enabled: true
```

## Step 7: Validação

### Verificar se está funcionando

```bash
# 1. Iniciar aplicação
java -jar target/meu-servico.jar

# 2. Acessar UI em browser
open http://localhost:19877/trace2local

# 3. Fazer uma requisição para gerar traces
curl -X POST http://localhost:8080/api/dados

# 4. Verificar traces na UI
# Deve aparecer a árvore de execução, logs, e timeline
```

## Troubleshooting

| Problema | Solução |
|----------|---------|
| `ServiceLoader config error` | Verificar `META-INF/services/` em trace2local-spring-boot-starter JAR |
| `Port 19877 já em uso` | `lsof -i :19877` e matar processo, ou alterar `trace2local.ui.port` |
| `Docker network error` | Reiniciar Docker Desktop ou verificar `docker ps` |
| `Maven build falha` | `mvn clean` + deletar `.m2/repository/tech/neural7` + `mvn install` |

## Referências

- [ADR-009: Baseline bytecode Java 21](../../docs/adr/ADR-009-java-baseline.md)
- [Finance-PIX Demo](../examples/finance-pix/README.md)
- [Spring Boot Auto-Config](../../trace2local-spring-boot-starter/README.md)

