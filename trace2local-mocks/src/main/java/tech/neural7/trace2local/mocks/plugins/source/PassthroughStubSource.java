package tech.neural7.trace2local.mocks.plugins.source;

import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.model.RequestMatcher;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.SourceContext;
import tech.neural7.trace2local.mocks.spi.StubSource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code proxy}: REPASSE — a API real continua respondendo e as transformações agem
 * sobre a resposta real (estilo "modify" do Hoverfly/mitmproxy). É a fonte certa para
 * validar VARIAÇÕES do JSON no microsserviço sem perder o comportamento real nas
 * requisições que não pedem variação (modo sob demanda por {@code baggage}).
 */
public final class PassthroughStubSource implements StubSource {

    @Override
    public String name() {
        return "proxy";
    }

    @Override
    public String description() {
        return "Repasse para a API real; as variações (transforms) alteram a resposta real — ideal para testar ajustes no JSON.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef().define("operations", Type.LIST, List.of(), null, Importance.LOW,
                "restringe o repasse a operações (ex.: POST /v1/score); vazio = tudo");
    }

    @Override
    public List<Stub> load(MockConfig config, SourceContext context) {
        List<String> ops = config.getList("operations");
        List<Stub> out = new ArrayList<>();
        if (ops.isEmpty()) {
            out.add(new Stub("repasse", "* /**", RequestMatcher.of(null, "/**"), MockResponse.json(502, "{}"), 9,
                    "proxy:" + context.target().hostPort(), List.of(), true));
        } else {
            int i = 0;
            for (String op : ops) {
                int sp = op.indexOf(' ');
                String method = sp > 0 ? op.substring(0, sp) : null;
                String path = sp > 0 ? op.substring(sp + 1) : op;
                out.add(new Stub("repasse-" + i++, op, RequestMatcher.of(method, path), MockResponse.json(502, "{}"), 9,
                        "proxy:" + context.target().hostPort(), List.of(), true));
            }
        }
        return out;
    }
}
