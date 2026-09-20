# TRACEVANTA — ESPECIFICAÇÃO TÉCNICA E ARQUITETURAL COMPLETA
> **Versão:** 1.0.0-PROPOSAL  
> **Target Runtime:** Java 25 (LTS) | Spring Boot 3.4+ / Quarkus 3.x | GraalVM Native Image (AOT)  
> **Ecossistema Alvo:** AWS SDK v2 | Docker Compose | LocalStack  
> **Tagline:** *TraceVanta — See your request travel through the system.*

---

## 1. VISÃO GERAL DO PRODUTO

### 1.1 O Problema
No desenvolvimento local de arquiteturas distribuídas e microsserviços (usando Docker Compose e LocalStack), depurar o fluxo de ponta a ponta exige alternar entre múltiplos terminais de logs, ferramentas de banco de dados, consoles do LocalStack e dashboards pesados de APM (Jaeger, Zipkin, X-Ray). Falta uma ferramenta embutida, visual e executável que una o disparo de requisições à inspeção instantânea de runtime, mutações de estado e propagação assíncrona.

### 1.2 A Solução
O **TraceVanta** é uma biblioteca Java plugável via Maven/Gradle que atua como uma runtime canvas interativa para desenvolvedores. Inspirada na ergonomia do **Swagger UI**, mas expandida para cobrir **Distributed Tracing, Jornada de Dados e Infraestrutura Local**, a biblioteca permite descobrir endpoints, disparar requisições diretamente do navegador e visualizar em tempo real o caminho percorrido através do código, bancos de dados, filas e tópicos AWS.

### 1.3 Princípios Norteadores
1. **Zero-Configuration:** Adicione a dependência, execute a aplicação e acesse `http://localhost:9876/tracevanta`.
2. **Local-First & Privacy by Design:** Nenhum dado sai da máquina do desenvolvedor; bind exclusivo em `localhost`.
3. **GraalVM & AOT First:** Sem instrumentação dinâmica de bytecode em runtime ou geração opaca de proxies CGLIB. Compatibilidade nativa estrita.
4. **Sem Sobrecarga Crítica:** Coleta assíncrona desacoplada via Ring Buffer (baseado em LMAX Disruptor/Bounded Queues) e uso de Java 25 `ScopedValue` para Virtual Threads.
5. **Visibilidade Semântica:** A observabilidade não deve ser apenas métricas e spans desconexos, mas sim uma árvore viva que correlaciona regras de negócio a mutações de infraestrutura.

---

## 2. BENCHMARK CONCEITUAL

| Critério | Swagger UI / OpenAPI | Jaeger / Zipkin / X-Ray | Postman | n8n / Workflow Engines | **TraceVanta** |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Ponto de Partida** | Contrato estático da API | Coleta passiva de telemetria | Disparador de requisições isolado | Automação e orquestração de nós | **Disparo ativo com tracing visual imediato** |
| **Visão de Infraestrutura**| Nula | Spans isolados com tags genéricas | Nenhuma | Nós de conectores | **Árvore viva (DynamoDB, SQS, SNS, S3, RDS)** |
| **Mutações de Estado** | Nenhuma | Nenhuma | Nenhuma | Dados de etapa | **Delta de dados (Before vs After em tempo real)** |
| **Complexidade de Setup**| Inclusão de dependência | Depende de daemons, collectors e portas | Desktop Client separado | Container dedicado | **Embutido no próprio JAR da aplicação** |
| **Foco de Persona** | Desenvolvedor/Consumidor API | SRE / Operações em Produção | QA / Desenvolvedor | Desenvolvedor / Integrador | **Desenvolvedor Java em ciclo de codificação/debug local** |

---

## 3. CONCEITO VISUAL: A ÁRVORE DE EXECUÇÃO E DAG DINÂMICO

A interface adota a metáfora da **Árvore Viva**, permitindo alternar para **DAG Direcionado, Waterfall Timeline, Sequence Diagram e Business Journey**.