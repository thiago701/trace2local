---
name: observabilidade-lambda
description: Instrumentação e observabilidade de AWS Lambda functions com Trace2Local, OpenTelemetry e CloudWatch
---

# Observabilidade em AWS Lambda com Trace2Local

## Contexto

AWS Lambda é serverless, mas precisa de observabilidade. Este skill mostra como:
- Instrumentar Lambda com OpenTelemetry
- Coletar traces, métricas e logs
- Visualizar em Trace2Local Station + CloudWatch

## Arquitetura

```
Lambda Function (Java 25 + Trace2Local)
    ↓
OpenTelemetry Collector (Lambda Extension)
    ↓
Trace2Local Station (Local / CloudWatch)
    ↓
UI em http://localhost:19877/trace2local
```

## Setup

### 1. Criar Lambda Function com Java 25

```java
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

public class PaymentHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
  private static final Tracer tracer = GlobalOpenTelemetry.getTracer("payment-service");
  
  @Override
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    var span = tracer.spanBuilder("processPayment")
      .startSpan();
    
    try (var scope = span.makeCurrent()) {
      // Seu código de processamento
      return Map.of("statusCode", 200, "body", "OK");
    } finally {
      span.end();
    }
  }
}
```

### 2. Maven Build para Lambda

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals>
        <goal>shade</goal>
      </goals>
      <configuration>
        <finalName>lambda-payload</finalName>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### 3. Terraform para Lambda + Trace2Local

```hcl
# terraform/lambda.tf

resource "aws_lambda_function" "payment_processor" {
  filename      = "lambda-payload.jar"
  function_name = "payment-processor"
  role          = aws_iam_role.lambda_role.arn
  handler       = "com.example.PaymentHandler"
  runtime       = "java25"
  
  environment {
    variables = {
      OTEL_ENABLED = "true"
      OTEL_EXPORTER_OTLP_ENDPOINT = "http://trace2local-station:4317"
    }
  }
  
  vpc_config {
    subnet_ids         = [aws_subnet.private.id]
    security_group_ids = [aws_security_group.lambda.id]
  }
  
  layers = [
    aws_lambda_layer_version.otel_extension.arn
  ]
}

# OpenTelemetry Extension layer
resource "aws_lambda_layer_version" "otel_extension" {
  # ... configuração do layer compartilhado
}
```

### 4. CloudWatch Integration

```java
// Exportar para CloudWatch Logs
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

public class LambdaConfiguration {
  static {
    // Configurar exportador de logs para CloudWatch
    var spanExporter = new LoggingSpanExporter();
    var processor = BatchSpanProcessor.builder(spanExporter)
      .setScheduleDelay(1000, TimeUnit.MILLISECONDS)
      .build();
    
    // ... adicionar processor ao tracer provider
  }
}
```

## Monitoramento

### CloudWatch Logs Insights - Queries

```sql
-- Ver todos os spans de uma execução
fields @timestamp, @message, spanName, duration
| filter @message like /span/
| stats avg(duration) by spanName

-- Erros nos últimos 5 minutos
fields @timestamp, errorMessage, stackTrace
| filter ispresent(errorMessage)
| stats count() by errorMessage

-- Latência por operação
fields duration, operationName
| stats pct(duration, 50) as p50, pct(duration, 99) as p99 by operationName
```

### Trace2Local Station - Visualização

```bash
# Executar Trace2Local Station localmente (que conecta à Lambda em CloudWatch)
docker run -p 19877:19877 \
  -e LAMBDA_CLOUDWATCH_ENABLED=true \
  trace2local-station:latest

# Acessar em http://localhost:19877/trace2local
```

## Performance Tips

1. **Async Span Exportation**: Use BatchSpanProcessor, não síncrono
2. **Sampling**: Não envie todo trace para economizar custos

```java
new JaegerRemoteSampler.Builder()
  .setInitialSampler(new ProbabilitySampler(0.1)) // 10% sampling
  .build()
```

3. **Baggage para correlação**: Propagar IDs entre serviços

```java
Baggage.current()
  .toBuilder()
  .put("request-id", requestId)
  .build()
  .makeCurrent()
```

## Troubleshooting

| Erro | Solução |
|------|---------|
| `OTel Extension not found` | Verificar que layer está anexado à function |
| `Connection refused to trace2local` | Verificar VPC config e security groups |
| `No spans appearing` | Validar que `OTEL_ENABLED=true` está em Environment Variables |

## Referências

- [AWS Lambda Observability](https://docs.aws.amazon.com/lambda/latest/dg/monitoring-insights.html)
- [OpenTelemetry Java Auto-Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
- [Trace2Local AWS Support](../../trace2local-aws/README.md)

