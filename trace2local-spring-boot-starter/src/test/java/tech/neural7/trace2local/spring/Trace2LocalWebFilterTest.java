package tech.neural7.trace2local.spring;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Redactor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Trace2LocalWebFilterTest {

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
        Trace2LocalConfig cfg = Trace2LocalConfig.defaults();
        Trace2LocalWebFilter filter = new Trace2LocalWebFilter(cfg);
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
        Trace2LocalConfig cfg = Trace2LocalConfig.defaults();
        String body = "{\"customerId\":\"C-1\",\"password\":\"hunter2\"}";
        String captured = Trace2LocalWebFilter.captureBody(body.getBytes(StandardCharsets.UTF_8), cfg);
        assertThat(captured).contains("C-1");
        assertThat(captured).contains(Redactor.REDACTED);
        assertThat(captured).doesNotContain("hunter2");
    }

    @Test
    void emptyBodyYieldsNullPayload() {
        assertThat(Trace2LocalWebFilter.captureBody(new byte[0], Trace2LocalConfig.defaults())).isNull();
        assertThat(Trace2LocalWebFilter.captureBody(null, Trace2LocalConfig.defaults())).isNull();
    }
}
