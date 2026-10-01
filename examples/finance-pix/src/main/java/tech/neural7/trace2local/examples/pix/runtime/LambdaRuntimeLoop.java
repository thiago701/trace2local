package tech.neural7.trace2local.examples.pix.runtime;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente mínimo da <a href="https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html">Lambda Runtime API</a>
 * — o que torna possível rodar Java 25 (JVM via {@code jlink} ou binário nativo GraalVM)
 * no runtime {@code provided.al2023}, inclusive no LocalStack (que ainda não oferece
 * a imagem gerenciada {@code java25}). Na AWS real, o Terraform pode trocar para o
 * runtime gerenciado sem mudar uma linha de handler.
 */
public final class LambdaRuntimeLoop {

    private static final String VERSION = "2018-06-01";
    private static final TypeReference<Map<String, Object>> EVENT = new TypeReference<>() {};

    private final String api;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public LambdaRuntimeLoop(String runtimeApi) {
        this.api = "http://" + runtimeApi + "/" + VERSION + "/runtime";
    }

    /** Laço infinito: next → handler → response/error. */
    public void run(RequestHandler<Map<String, Object>, Object> handler) {
        while (!Thread.currentThread().isInterrupted()) {
            String requestId = null;
            try {
                HttpResponse<String> next = http.send(HttpRequest.newBuilder(URI.create(api + "/invocation/next")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                requestId = next.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElseThrow();
                long deadline = next.headers().firstValue("Lambda-Runtime-Deadline-Ms").map(Long::parseLong)
                        .orElse(System.currentTimeMillis() + 30_000);
                String arn = next.headers().firstValue("Lambda-Runtime-Invoked-Function-Arn").orElse("");
                next.headers().firstValue("Lambda-Runtime-Trace-Id")
                        .ifPresent(t -> System.setProperty("com.amazonaws.xray.traceHeader", t));
                Map<String, Object> event = next.body() == null || next.body().isBlank()
                        ? Map.of() : Json.MAPPER.readValue(next.body(), EVENT);
                Object result = handler.handleRequest(event, new RuntimeContext(requestId, deadline, arn));
                post("/invocation/" + requestId + "/response", Json.MAPPER.writeValueAsString(result), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                if (requestId == null) {
                    // falha falando com a Runtime API: espera e tenta de novo
                    sleep(200);
                    continue;
                }
                post("/invocation/" + requestId + "/error", errorJson(t), "Unhandled");
            }
        }
    }

    /** Falha de inicialização (handler não instanciou): reporta e encerra o processo. */
    public void initError(Throwable t) {
        post("/init/error", errorJson(t), "Runtime.InitError");
    }

    private void post(String path, String body, String errorType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "null" : body));
            if (errorType != null) {
                b.header("Lambda-Runtime-Function-Error-Type", errorType);
            }
            http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("runtime: falha ao reportar " + path + ": " + e);
        }
    }

    private static String errorJson(Throwable t) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("errorMessage", String.valueOf(t.getMessage()));
        err.put("errorType", t.getClass().getName());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        err.put("stackTrace", List.of(sw.toString().split("\n")).subList(0, Math.min(20, sw.toString().split("\n").length)));
        try {
            return Json.MAPPER.writeValueAsString(err);
        } catch (Exception e) {
            return "{\"errorMessage\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
