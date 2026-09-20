package tech.neural7.tracevanta.station;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.NodeKind;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpTraceReceiverTest {

    @Test
    void ingestsOtlpSpansIntoSemanticTree() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.builder().quiescenceMs(2_000).build();
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg)) {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            http.createContext("/v1/traces", new OtlpTraceReceiver(pipeline, cfg));
            http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            try {
                String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
                String rootId = "aaaaaaaaaaaaaaaa";
                String snsId = "bbbbbbbbbbbbbbbb";

                Span root = span(rootId, "", "POST /orders", Span.SpanKind.SPAN_KIND_SERVER, 0, 30,
                        kv(tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_METHOD, "POST"), kv(tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_ROUTE, "/orders"));
                Span sns = span(snsId, rootId, "Sns.Publish", Span.SpanKind.SPAN_KIND_PRODUCER, 5, 15,
                        kv(tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_SYSTEM, "aws-api"), kv(tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_SERVICE, "Sns"), kv(tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_METHOD, "Publish"),
                        kv(tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_SNS_TOPIC, "arn:aws:sns:us-east-1:000000000000:order-events"));

                ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                        .addResourceSpans(ResourceSpans.newBuilder()
                                .addScopeSpans(ScopeSpans.newBuilder().addSpans(root).addSpans(sns)))
                        .build();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1/traces"))
                                .header("Content-Type", "application/x-protobuf")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);

                Execution execution = awaitExecution(pipeline, traceId, Duration.ofSeconds(5));
                assertThat(execution.roots()).hasSize(1);
                assertThat(execution.roots().get(0).kind()).isEqualTo(NodeKind.HTTP_SERVER);
                assertThat(execution.roots().get(0).label()).isEqualTo("POST /orders");
                assertThat(execution.roots().get(0).children()).hasSize(1);
                assertThat(execution.roots().get(0).children().get(0).kind()).isEqualTo(NodeKind.SNS);
                assertThat(execution.roots().get(0).children().get(0).label()).isEqualTo("SNS: order-events");
            } finally {
                http.stop(0);
            }
        }
    }

    @Test
    void rejectsMalformedProtobuf() throws Exception {
        try (TraceVantaPipeline pipeline = TraceVantaPipeline.start(TraceVantaConfig.defaults())) {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            http.createContext("/v1/traces", new OtlpTraceReceiver(pipeline, TraceVantaConfig.defaults()));
            http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            try {
                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1/traces"))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(400);
            } finally {
                http.stop(0);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static Execution awaitExecution(TraceVantaPipeline pipeline, String traceId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var summary : pipeline.store().recent(50)) {
                if (summary.traceId().equals(traceId)) {
                    Optional<Execution> execution = pipeline.store().get(summary.executionId());
                    if (execution.isPresent()) {
                        return execution.get();
                    }
                }
            }
            sleep(20);
        }
        throw new AssertionError("execução do trace " + traceId + " não completou em " + timeout);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Span span(String spanId, String parentSpanId, String name, Span.SpanKind kind,
                             long startMs, long endMs, KeyValue... attributes) {
        Span.Builder builder = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(hex("4bf92f3577b34da6a3ce929d0e0e4736")))
                .setSpanId(ByteString.copyFrom(hex(spanId)))
                .setName(name)
                .setKind(kind)
                .setStartTimeUnixNano(startMs * 1_000_000L)
                .setEndTimeUnixNano(endMs * 1_000_000L);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            builder.setParentSpanId(ByteString.copyFrom(hex(parentSpanId)));
        }
        for (KeyValue attribute : attributes) {
            builder.addAttributes(attribute);
        }
        return builder.build();
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
