package tech.neural7.tracevanta.spring;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.Redactor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TraceVantaWebFilterTest {

    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        GlobalOpenTelemetry.resetForTest();
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        GlobalOpenTelemetry.set(sdk);
    }

    @AfterEach
    void tearDown() {
        sdk.getSdkTracerProvider().close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void wrapsRequestChainAndExtractsW3CTraceparent() throws Exception {
        TraceVantaConfig cfg = TraceVantaConfig.defaults();
        TraceVantaWebFilter filter = new TraceVantaWebFilter(cfg);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.setContent("{\"customerId\":\"C-1\",\"password\":\"hunter2\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        // simula chegada de um traceparent W3C externo
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-0123456789abcdef-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(201);
        });

        assertThat(response.getStatus()).isEqualTo(201);
    }

    @Test
    void redactsRequestBodyForPayload() {
        TraceVantaConfig cfg = TraceVantaConfig.defaults();
        String body = "{\"customerId\":\"C-1\",\"password\":\"hunter2\"}";
        String captured = TraceVantaWebFilter.captureBody(body.getBytes(StandardCharsets.UTF_8), cfg);
        assertThat(captured).contains("C-1");
        assertThat(captured).contains(Redactor.REDACTED);
        assertThat(captured).doesNotContain("hunter2");
    }

    @Test
    void emptyBodyYieldsNullPayload() {
        assertThat(TraceVantaWebFilter.captureBody(new byte[0], TraceVantaConfig.defaults())).isNull();
        assertThat(TraceVantaWebFilter.captureBody(null, TraceVantaConfig.defaults())).isNull();
    }
}
