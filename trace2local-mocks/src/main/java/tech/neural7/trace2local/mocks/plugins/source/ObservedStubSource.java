package tech.neural7.trace2local.mocks.plugins.source;

import tech.neural7.trace2local.internal.Redactor;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.ObservedExchange;
import tech.neural7.trace2local.mocks.spi.SourceContext;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code observed}: gravação/replay a partir dos traces — cada operação já vista
 * (método + rota) vira um stub com a resposta observada. Os payloads chegam
 * REDIGIDOS na origem; campos {@value Redactor#REDACTED} são sinalizados no stub
 * para o dev completar com {@code set-field} (honestidade, ADR-007).
 */
public final class ObservedStubSource implements StubSource {

    @Override
    public String name() {
        return "observed";
    }

    @Override
    public String description() {
        return "Replay do que a API real respondeu nos traces (gravação sem proxy): um stub por operação observada.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef()
                .define("operations", Type.LIST, List.of(), null, Importance.MEDIUM,
                        "filtra operações (ex.: POST /v1/score); vazio = todas")
                .define("strategy", Type.STRING, "latest", Validators.in("latest", "first"), Importance.LOW,
                        "qual observação usar quando houver várias", "latest", "first")
                .define("include.errors", Type.BOOLEAN, false, null, Importance.LOW,
                        "inclui respostas 4xx/5xx observadas (só quando não houver sucesso para a operação)");
    }

    @Override
    public List<Stub> load(MockConfig config, SourceContext context) {
        List<String> filter = config.getList("operations");
        boolean latest = "latest".equals(config.getString("strategy"));
        boolean includeErrors = config.getBoolean("include.errors");
        Map<String, ObservedExchange> chosen = new LinkedHashMap<>();
        Map<String, ObservedExchange> fallback = new LinkedHashMap<>();
        List<ObservedExchange> all = new ArrayList<>(context.observed()); // mais recentes primeiro
        if (!latest) {
            java.util.Collections.reverse(all);
        }
        for (ObservedExchange x : all) {
            if (x.mocked() || x.status() < 0 || (!filter.isEmpty() && !filter.contains(x.operation()))) {
                continue; // nunca gravar resposta de mock como "real"
            }
            if (x.successful()) {
                chosen.putIfAbsent(x.operation(), x);
            } else if (includeErrors) {
                fallback.putIfAbsent(x.operation(), x);
            }
        }
        fallback.forEach(chosen::putIfAbsent);
        if (chosen.isEmpty()) {
            throw new MockPluginException("nenhuma resposta real observada para " + context.target().hostPort()
                    + " — rode a jornada uma vez contra a API real (ou use source=openapi/inline)");
        }
        List<Stub> out = new ArrayList<>();
        for (ObservedExchange x : chosen.values()) {
            List<String> notes = new ArrayList<>();
            String body = x.responseBody();
            if (body != null && body.contains(Redactor.REDACTED)) {
                notes.add("corpo contém campos redigidos na origem — complete com set-field se o microsserviço os usar");
            }
            if (body != null && body.contains(Redactor.TRUNCATED)) {
                notes.add("corpo truncado na captura (trace2local.payload.max-bytes) — o JSON pode estar incompleto");
            }
            if (body == null) {
                notes.add("resposta observada sem corpo capturado (payload desligado ou não-texto)");
            }
            MockResponse response = MockResponse.json(x.status(), body);
            out.add(new Stub("obs-" + Integer.toHexString(x.operation().hashCode()), x.operation(),
                    RequestMatcher.of(x.method(), x.route()), response, 5,
                    "observed:" + x.executionId(), notes));
        }
        return out;
    }
}
