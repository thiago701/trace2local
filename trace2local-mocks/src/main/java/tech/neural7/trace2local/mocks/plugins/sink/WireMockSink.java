package tech.neural7.trace2local.mocks.plugins.sink;

import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Password;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.json.WireMockFormat;
import tech.neural7.trace2local.mocks.model.Stub;
import tech.neural7.trace2local.mocks.spi.DeployRequest;
import tech.neural7.trace2local.mocks.spi.MockPluginException;
import tech.neural7.trace2local.mocks.spi.StubSink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code wiremock}: publica os stubs num WireMock que já existe (admin API
 * {@code /__admin/mappings}), com prioridade maior que os mappings do time e
 * {@code metadata.t2l.binding} para remover SÓ o que foi publicado aqui. É o jeito
 * de "plugar" uma variação no mock que os serviços já usam, sem mudar URL alguma.
 * Destino estático: transformações dinâmicas são recusadas na validação.
 */
public final class WireMockSink implements StubSink {

    private final boolean allowPublic;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WireMockSink(boolean allowPublic) {
        this.allowPublic = allowPublic;
    }

    @Override
    public String name() {
        return "wiremock";
    }

    @Override
    public String description() {
        return "Publica stubs/variações num WireMock existente (admin API) com prioridade alta; remove só o que publicou.";
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef()
                .define("url", Type.STRING, ConfigDef.NO_DEFAULT, Validators.httpUrl(allowPublic), Importance.HIGH,
                        "URL base do WireMock (ex.: http://wiremock:8080)")
                .define("priority", Type.INT, 1, Validators.range(1, 100), Importance.MEDIUM,
                        "prioridade dos stubs publicados (1 = mais alta; os do time costumam ser 5)")
                .define("auth.token", Type.PASSWORD, null, null, Importance.LOW,
                        "token Bearer da admin API (use ${env:NOME})")
                .define("public.url", Type.STRING, null, null, Importance.LOW,
                        "URL anunciada aos serviços, se diferente da URL de admin");
    }

    @Override
    public boolean dynamic() {
        return false;
    }

    @Override
    public boolean undeployOnShutdown() {
        return true;
    }

    @Override
    public Deployment deploy(DeployRequest request, MockConfig config) {
        String base = trim(config.getString("url"));
        undeploy(request.binding(), config);
        int priority = config.getInt("priority");
        List<String> warnings = new ArrayList<>();
        int i = 0;
        for (Stub stub : request.stubs()) {
            // variações por cabeçalho/corpo ("~") ficam um degrau acima da base
            int p = stub.id().contains("~") ? Math.max(1, priority - 1) : priority;
            ObjectNode mapping = WireMockFormat.write(stub, request.binding(), p);
            HttpResponse<String> r = call(config, HttpRequest.newBuilder(URI.create(base + "/__admin/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.write(mapping))));
            if (r.statusCode() >= 300) {
                throw new MockPluginException("WireMock recusou o stub " + stub.id() + " (HTTP " + r.statusCode() + ")");
            }
            if (!stub.notes().isEmpty()) {
                warnings.add(stub.id() + ": " + String.join("; ", stub.notes()));
            }
            i++;
        }
        String publicUrl = config.getString("public.url");
        return new Deployment(publicUrl != null && !publicUrl.isBlank() ? trim(publicUrl) : base,
                i + " stub(s) publicados no WireMock com prioridade " + priority, warnings);
    }

    @Override
    public void undeploy(String binding, MockConfig config) {
        String base = trim(config.getString("url"));
        ObjectNode filter = JsonSupport.MAPPER.createObjectNode();
        filter.putObject("matchesJsonPath").put("expression", "$.trace2local.binding").put("equalTo", binding);
        HttpResponse<String> r = call(config, HttpRequest.newBuilder(URI.create(base + "/__admin/mappings/remove-by-metadata"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.write(filter))));
        if (r.statusCode() >= 300 && r.statusCode() != 404) {
            throw new MockPluginException("WireMock recusou a remoção dos stubs de " + binding + " (HTTP " + r.statusCode() + ")");
        }
    }

    private HttpResponse<String> call(MockConfig config, HttpRequest.Builder b) {
        Password token = config.getPassword("auth.token");
        if (token != null && token.value() != null && !token.value().isBlank()) {
            b.header("Authorization", "Bearer " + token.value());
        }
        try {
            return http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MockPluginException("WireMock inacessível em " + config.getString("url") + ": " + e.getClass().getSimpleName()
                    + " — o container está de pé e a URL é alcançável a partir do Station?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MockPluginException("interrompido falando com o WireMock", e);
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
