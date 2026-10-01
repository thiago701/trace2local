package tech.neural7.trace2local.mocks.plugins.transform;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigDef.Importance;
import tech.neural7.trace2local.mocks.config.ConfigDef.Type;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.json.JsonPointers;
import tech.neural7.trace2local.mocks.model.Fault;
import tech.neural7.trace2local.mocks.model.MockResponse;
import tech.neural7.trace2local.mocks.spi.ResponseTransform;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transformações embutidas (SMTs de resposta). Cada classe é um plugin público
 * registrado em {@code META-INF/services} — o mesmo caminho que um plugin de terceiro usa.
 */
public final class Transforms {

    private Transforms() {}

    /** {@code set-field}: define um campo do corpo JSON (cria intermediários). */
    public static final class SetField implements ResponseTransform {
        @Override public String name() { return "set-field"; }
        @Override public String description() {
            return "Define um campo do JSON de resposta (JSON Pointer). Ex.: /decision = DENIED, /limits/daily = 0.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("pointer", Type.STRING, ConfigDef.NO_DEFAULT, Validators.jsonPointer(), Importance.HIGH,
                            "campo a alterar, em JSON Pointer (ex.: /decision)")
                    .define("value", Type.JSON, ConfigDef.NO_DEFAULT, null, Importance.HIGH,
                            "novo valor em JSON; texto simples vira string (DENIED), null vira null, 0 vira número");
        }
        @Override public Transformation configure(MockConfig c) {
            String pointer = c.getString("pointer");
            JsonNode value = c.getJson("value");
            return (req, res) -> res.withJsonBody(JsonPointers.set(res.bodyJson(), pointer, value));
        }
    }

    /** {@code remove-field}: remove um campo — simula contrato que "esqueceu" o campo. */
    public static final class RemoveField implements ResponseTransform {
        @Override public String name() { return "remove-field"; }
        @Override public String description() {
            return "Remove um campo do JSON de resposta — valida se o microsserviço tolera campo ausente.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef().define("pointer", Type.STRING, ConfigDef.NO_DEFAULT, Validators.jsonPointer(),
                    Importance.HIGH, "campo a remover (JSON Pointer)");
        }
        @Override public Transformation configure(MockConfig c) {
            String pointer = c.getString("pointer");
            return (req, res) -> {
                JsonNode body = res.bodyJson();
                return body == null ? res : res.withJsonBody(JsonPointers.remove(body, pointer));
            };
        }
    }

    /** {@code rename-field}: troca o nome de um campo — simula quebra de contrato do parceiro. */
    public static final class RenameField implements ResponseTransform {
        @Override public String name() { return "rename-field"; }
        @Override public String description() {
            return "Renomeia um campo (ex.: /score → /riskScore) — simula versão nova do contrato do parceiro.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("from", Type.STRING, ConfigDef.NO_DEFAULT, Validators.jsonPointer(), Importance.HIGH, "campo de origem")
                    .define("to", Type.STRING, ConfigDef.NO_DEFAULT, Validators.jsonPointer(), Importance.HIGH, "campo de destino");
        }
        @Override public Transformation configure(MockConfig c) {
            String from = c.getString("from");
            String to = c.getString("to");
            return (req, res) -> {
                JsonNode body = res.bodyJson();
                JsonNode value = JsonPointers.get(body, from);
                if (value == null) {
                    return res;
                }
                return res.withJsonBody(JsonPointers.set(JsonPointers.remove(body, from), to, value));
            };
        }
    }

    /** {@code set-status}: troca o status (e opcionalmente o corpo de erro). */
    public static final class SetStatus implements ResponseTransform {
        @Override public String name() { return "set-status"; }
        @Override public String description() {
            return "Troca o status HTTP (ex.: 422, 503) e, opcionalmente, o corpo — valida o tratamento de erro do parceiro.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("code", Type.INT, ConfigDef.NO_DEFAULT, Validators.range(100, 599), Importance.HIGH,
                            "status HTTP", "400", "404", "409", "422", "429", "500", "502", "503", "504")
                    .define("body", Type.JSON, null, null, Importance.MEDIUM,
                            "corpo de erro (JSON); vazio mantém o corpo atual");
        }
        @Override public Transformation configure(MockConfig c) {
            int code = c.getInt("code");
            JsonNode body = c.getJson("body");
            return (req, res) -> {
                MockResponse r = res.withStatus(code);
                return body == null ? r : r.withJsonBody(body);
            };
        }
    }

    /** {@code set-header}: define um cabeçalho de resposta (Retry-After, ETag...). */
    public static final class SetHeader implements ResponseTransform {
        @Override public String name() { return "set-header"; }
        @Override public String description() { return "Define um cabeçalho de resposta (ex.: Retry-After: 2)."; }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("name", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(), Importance.HIGH, "nome do cabeçalho")
                    .define("value", Type.STRING, "", null, Importance.HIGH, "valor");
        }
        @Override public Transformation configure(MockConfig c) {
            String n = c.getString("name");
            String v = c.getString("value");
            return (req, res) -> res.withHeader(n, v);
        }
    }

    /** {@code latency}: atraso fixo (+ jitter) — reproduz parceiro lento e timeouts de cliente. */
    public static final class Latency implements ResponseTransform {
        @Override public String name() { return "latency"; }
        @Override public String description() {
            return "Atrasa a resposta (ms + jitter) — reproduz parceiro lento, timeout de cliente e SLA estourado.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("ms", Type.LONG, ConfigDef.NO_DEFAULT, Validators.range(0, 120_000), Importance.HIGH,
                            "atraso fixo em milissegundos", "300", "1500", "5000", "15000")
                    .define("jitter.ms", Type.LONG, 0L, Validators.range(0, 60_000), Importance.LOW,
                            "variação aleatória somada ao atraso (destinos estáticos usam só o valor fixo)");
        }
        @Override public Transformation configure(MockConfig c) {
            long ms = c.getLong("ms");
            long jitter = c.getLong("jitter.ms");
            return (req, res) -> res.withDelay(ms + (jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0));
        }
    }

    /** {@code fault}: falha de rede — o que nenhuma API real faz sob encomenda. */
    public static final class InjectFault implements ResponseTransform {
        @Override public String name() { return "fault"; }
        @Override public String description() {
            return "Falha de rede: connection-reset, empty-response ou timeout — valida retry, circuit breaker e idempotência.";
        }
        @Override public ConfigDef config() {
            return new ConfigDef()
                    .define("kind", Type.STRING, ConfigDef.NO_DEFAULT,
                            Validators.in("connection-reset", "empty-response", "timeout"), Importance.HIGH,
                            "tipo de falha", "connection-reset", "empty-response", "timeout")
                    .define("timeout.ms", Type.LONG, 30_000L, Validators.range(100, 120_000), Importance.LOW,
                            "quanto tempo segurar a conexão no modo timeout");
        }
        @Override public Transformation configure(MockConfig c) {
            Fault fault = switch (c.getString("kind").toLowerCase(Locale.ROOT)) {
                case "connection-reset" -> Fault.CONNECTION_RESET;
                case "empty-response" -> Fault.EMPTY_RESPONSE;
                default -> Fault.TIMEOUT;
            };
            long timeout = c.getLong("timeout.ms");
            return (req, res) -> {
                MockResponse r = res.withFault(fault);
                return fault == Fault.TIMEOUT ? r.withDelay(timeout) : r;
            };
        }
    }

    /**
     * {@code template}: corpo gerado a partir da requisição — {@code {{request.method}}},
     * {@code {{request.path}}}, {@code {{request.path.N}}} (segmento), {@code {{request.query.X}}},
     * {@code {{request.header.X}}}, {@code {{request.body/ponteiro}}}, {@code {{response/ponteiro}}},
     * {@code {{uuid}}}, {@code {{now}}}. Dentro de strings JSON o valor é escapado.
     */
    public static final class Template implements ResponseTransform {
        @Override public String name() { return "template"; }
        @Override public String description() {
            return "Gera o corpo a partir da requisição (eco de ids, endToEndId, timestamps) — {{request.path.2}}, {{request.body/amount}}, {{uuid}}, {{now}}.";
        }
        @Override public boolean isStatic() { return false; }
        @Override public ConfigDef config() {
            return new ConfigDef().define("body", Type.STRING, ConfigDef.NO_DEFAULT, Validators.nonEmpty(), Importance.HIGH,
                    "corpo com marcadores {{...}}");
        }
        @Override public Transformation configure(MockConfig c) {
            String template = c.getString("body");
            return (req, res) -> res.withBody(ResponseTemplating.render(template, req, res));
        }
    }
}
