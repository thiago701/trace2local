# Trace2Local Skills Index

Catálogo de skills reutilizáveis para Squad AI.

## Skills Disponíveis

### 1. instalo-pipeline-completo
**Descrição:** Guia de instalação e configuração completa de Trace2Local com CI/CD, Docker, Maven e GitHub Actions

**Usar quando:**
- Fazer setup inicial de Trace2Local em novo projeto
- Configurar pipeline de build e deploy
- Troubleshoot problemas de instalação
- Documentar processo de onboarding

**Estrutura:** 
- Pré-requisitos (Java 21+, Maven 3.9+, Docker)
- Step-by-step de clonagem, build, configuração
- Docker Compose + Terraform
- GitHub Actions CI/CD
- Troubleshooting table

---

### 2. observabilidade-lambda
**Descrição:** Instrumentação e observabilidade de AWS Lambda functions com Trace2Local, OpenTelemetry e CloudWatch

**Usar quando:**
- Instrumentar Lambda functions com OTEL
- Coletar traces em ambiente serverless
- Integrar com CloudWatch Logs Insights
- Monitorar performance e erros em produção

**Estrutura:**
- Arquitetura Lambda + OTEL + Trace2Local
- Java 25 handler example
- Maven build para Lambda (shade plugin)
- Terraform IaC para Lambda + networking
- CloudWatch Logs Insights queries
- Performance optimization tips

---

### 3. trace2local-dev-only-security
**Descrição:** Estratégia de 5 camadas para garantir que Trace2Local seja usado APENAS em desenvolvimento local — nunca suba para staging/produção

**Usar quando:**
- Configurar segurança e isolamento de Trace2Local
- Garantir que nunca chegue em produção
- Implementar Maven profiles dev/prod
- Setup CI/CD gates
- Validações em runtime

**Estrutura:**
- 5 camadas de proteção (Maven, Spring, Build, CI/CD, Runtime)
- Exemplos de pom.xml seguro
- Configurações YAML dev vs prod
- GitHub Actions workflows
- Guard validations

---

## Como Usar um Skill

1. **Importar dependency:**
   ```xml
   <dependency>
     <groupId>tech.neural7.trace2local</groupId>
     <artifactId>trace2local-skills-distribution</artifactId>
     <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```

2. **Carregar skill em Java:**
   ```java
   InputStream is = getClass().getResourceAsStream("/squad-ai-skills/instalo-pipeline-completo/SKILL.md");
   String content = new String(is.readAllBytes());
   ```

3. **Usar em prompts Claude:**
   ```
   [Coloque conteúdo do SKILL.md aqui]
   
   Agora, faça isso: [sua pergunta específica]
   ```

## Adicionar Novo Skill

1. Criar diretório: `src/main/resources/squad-ai-skills/{seu-skill-name}/`
2. Adicionar `SKILL.md` com frontmatter:
   ```yaml
   ---
   name: seu-skill-name
   description: Descrição breve do skill
   ---
   ```
3. Atualizar este `INDEX.md`
4. Commit + Push (Maven build automaticamente incluirá)

## Metadata Frontmatter

Todo SKILL.md deve ter:

```yaml
---
name: skill-identifier          # snake-case, único
description: Breve descrição    # 1 linha, < 100 chars
keywords: [otel, lambda, aws]   # opcional, para busca
audience: [developer, ops]      # opcional
complexity: intermediate        # basic, intermediate, advanced
updated: 2026-10-01             # opcional, ISO date
---
```

## Versionamento

Skills são versionados junto com o JAR:
- SNAPSHOT: 0.1.0-SNAPSHOT (desenvolvimento)
- RELEASE: 0.1.0 (estável)

Mudanças retrocompatíveis: sem breaking changes esperadas.

