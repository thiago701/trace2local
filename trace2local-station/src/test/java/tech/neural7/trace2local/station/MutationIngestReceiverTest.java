package tech.neural7.trace2local.station;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.SpanEndEvent;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.MutationFidelity;
import tech.neural7.trace2local.model.MutationKind;
import tech.neural7.trace2local.model.NodeKind;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MutationIngestReceiverTest {

    @Test
    void fusesIncomingMutationsWithSpans() throws Exception {
        Trace2LocalConfig cfg = Trace2LocalConfig.builder().quiescenceMs(2_000).build();
        try (Trace2LocalPipeline pipeline = Trace2LocalPipeline.start(cfg)) {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            http.createContext("/t2lingest/v1/mutations", new MutationIngestReceiver(pipeline));
            http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            try {
                String traceId = "trace-mutation-1";
                Instant t0 = Instant.now();
                pipeline.buffer().offer(new SpanEndEvent(traceId, "dyn-node", null, null, null,
                        NodeKind.DYNAMODB, "DynamoDB: orders", Map.of(),
                        t0, t0.plusMillis(10), false, null, null, null, List.of(), false));

                String body = """
                        {"mutations":[{"spanId":"dyn-node","traceId":"trace-mutation-1","at":"%s",
                          "mutation":{"kind":"CREATE","target":"orders","key":"ORDER#1",
                          "before":null,"after":{"orderId":"88291"},"deltas":[],"fidelity":"EXACT"}}]}
                        """.formatted(t0.plusMillis(5));
                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/t2lingest/v1/mutations"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"accepted\":1");

                Execution execution = OtlpTraceReceiverTest.awaitExecution(pipeline, traceId, Duration.ofSeconds(5));
                DataMutation mutation = execution.roots().get(0).mutation();
                assertThat(mutation).isNotNull();
                assertThat(mutation.kind()).isEqualTo(MutationKind.CREATE);
                assertThat(mutation.fidelity()).isEqualTo(MutationFidelity.EXACT);
                assertThat(mutation.key()).isEqualTo("ORDER#1");
            } finally {
                http.stop(0);
            }
        }
    }

    @Test
    void skipsMalformedItemsWithoutDroppingTheBatch() throws Exception {
        try (Trace2LocalPipeline pipeline = Trace2LocalPipeline.start(Trace2LocalConfig.defaults())) {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            http.createContext("/t2lingest/v1/mutations", new MutationIngestReceiver(pipeline));
            http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            try {
                String body = "{\"mutations\":[{\"spanId\":\"x\",\"traceId\":\"t\",\"mutation\":{\"kind\":\"BOGUS\"}},"
                        + "{\"spanId\":\"y\",\"traceId\":\"t2\",\"mutation\":{\"kind\":\"READ_ONLY\",\"target\":\"t\",\"key\":null,\"before\":null,\"after\":null,\"deltas\":[],\"fidelity\":\"UNAVAILABLE\"}}]}";
                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/t2lingest/v1/mutations"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"accepted\":1");
            } finally {
                http.stop(0);
            }
        }
    }
}
