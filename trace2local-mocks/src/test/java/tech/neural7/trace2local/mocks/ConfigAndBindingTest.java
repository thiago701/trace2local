package tech.neural7.trace2local.mocks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.neural7.trace2local.mocks.config.ConfigDef;
import tech.neural7.trace2local.mocks.config.ConfigException;
import tech.neural7.trace2local.mocks.config.ConfigValue;
import tech.neural7.trace2local.mocks.config.MockConfig;
import tech.neural7.trace2local.mocks.config.Password;
import tech.neural7.trace2local.mocks.config.Validators;
import tech.neural7.trace2local.mocks.runtime.BindingConfig;
import tech.neural7.trace2local.mocks.runtime.MockConnectWorker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contrato de configuração (ConfigDef) e da config plana de binding no formato do Kafka Connect. */
class ConfigAndBindingTest {

    private MockConnectWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MockConnectWorker(MockConnectWorker.Settings.loopback(0)
                .withEnv(k -> k.equals("WM_TOKEN") ? "s3cr3t" : null), List::of);
        worker.start();
    }

    @AfterEach
    void tearDown() {
        worker.close();
    }

    @Test
    void configDefParsesTypesAggregatesErrorsAndSuggestsTypos() {
        ConfigDef def = new ConfigDef()
                .define("ms", ConfigDef.Type.LONG, ConfigDef.NO_DEFAULT, Validators.range(0, 100), ConfigDef.Importance.HIGH, "atraso")
                .define("value", ConfigDef.Type.JSON, ConfigDef.NO_DEFAULT, null, ConfigDef.Importance.HIGH, "valor")
                .define("token", ConfigDef.Type.PASSWORD, null, null, ConfigDef.Importance.LOW, "segredo");

        MockConfig ok = def.parse(Map.of("ms", "42", "value", "DENIED", "token", "abc"));
        assertThat(ok.getLong("ms")).isEqualTo(42);
        assertThat(ok.getJson("value").asText()).isEqualTo("DENIED");      // texto simples vira string JSON
        assertThat(def.parse(Map.of("ms", "1", "value", "0")).getJson("value").isNumber()).isTrue();
        assertThat(def.parse(Map.of("ms", "1", "value", "null")).getJson("value").isNull()).isTrue();
        assertThat(ok.getPassword("token").value()).isEqualTo("abc");
        assertThat(String.valueOf(ok.getPassword("token"))).isEqualTo(Password.HIDDEN);

        List<ConfigValue> report = def.validate(Map.of("ms", "500", "valeu", "x", "token", "abc"));
        assertThat(report).filteredOn(v -> v.name().equals("ms")).first()
                .satisfies(v -> assertThat(v.errors()).anyMatch(e -> e.contains("[0..100]")));
        assertThat(report).filteredOn(v -> v.name().equals("value")).first()
                .satisfies(v -> assertThat(v.errors()).anyMatch(e -> e.startsWith("obrigatório")));
        assertThat(report).filteredOn(v -> v.name().equals("valeu")).first()
                .satisfies(v -> assertThat(v.errors()).anyMatch(e -> e.contains("você quis dizer 'value'")));
        assertThat(report).filteredOn(v -> v.name().equals("token")).first()
                .satisfies(v -> assertThat(v.value()).isEqualTo(Password.HIDDEN)); // nunca ecoa segredo

        assertThatThrownBy(() -> def.parse(Map.of("ms", "x")))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> assertThat(((ConfigException) e).errors()).containsKeys("ms", "value"));
    }

    @Test
    void kafkaStyleBindingConfigValidatesEndToEnd() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "antifraude.parceiro:8080");
        cfg.put("source", "inline");
        cfg.put("source.stubs", "[{\"method\":\"POST\",\"path\":\"/v1/score\",\"body\":{\"decision\":\"APPROVED\"}}]");
        cfg.put("transforms", "negado,lento");
        cfg.put("transforms.negado.type", "set-field");
        cfg.put("transforms.negado.pointer", "/decision");
        cfg.put("transforms.negado.value", "DENIED");
        cfg.put("transforms.negado.predicate", "score");
        cfg.put("transforms.lento.type", "latency");
        cfg.put("transforms.lento.ms", "10");
        cfg.put("predicates", "score");
        cfg.put("predicates.score.type", "path-matches");
        cfg.put("predicates.score.pattern", "/v1/score");

        BindingConfig.Report report = worker.validate("antifraude", cfg);
        assertThat(report.errorCount()).as(report.errors().toString()).isZero();
        assertThat(report.groups()).contains("Binding", "Pipeline", "Origem: inline", "Transformação: negado (set-field)");
        assertThat(report.configs()).anyMatch(e -> e.definition().name().equals("transforms.negado.pointer"));
    }

    @Test
    void reportsEveryMistakeAtOnceWithActionableMessages() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "http://antifraude/v1");             // esquema/caminho no target
        cfg.put("source", "openapi");                           // falta source.spec
        cfg.put("sink", "wiremock");
        cfg.put("sink.url", "https://wiremock.empresa.com.br"); // host público recusado (anti-SSRF)
        cfg.put("transforms", "tpl,x");
        cfg.put("transforms.tpl.type", "template");             // dinâmico num destino estático
        cfg.put("transforms.tpl.body", "{\"id\":\"{{uuid}}\"}");
        cfg.put("transforms.x.type", "set-feild");              // tipo desconhecido
        cfg.put("transforms.esquecido.type", "latency");        // alias fora da lista
        cfg.put("transforms.tpl.predicate", "nope");            // predicado não declarado
        cfg.put("unmatchd", "proxy");                           // typo de chave

        BindingConfig.Report r = worker.validate("Nome Inválido", cfg);
        Map<String, List<String>> e = r.errors();
        assertThat(e).containsKeys("name", "target", "source.spec", "sink.url", "transforms.tpl.type",
                "transforms.x.type", "transforms.esquecido", "transforms.tpl.predicate", "unmatchd");
        assertThat(e.get("unmatchd")).anyMatch(m -> m.contains("'unmatched'"));
        assertThat(e.get("sink.url")).anyMatch(m -> m.contains("público"));
        assertThat(e.get("transforms.tpl.type")).anyMatch(m -> m.contains("sink=embedded"));
        assertThat(e.get("transforms.x.type")).anyMatch(m -> m.contains("set-field"));
    }

    @Test
    void envProviderKeepsSecretsOutOfConfigAndReportsMissingVariables() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("target", "dict:8080");
        cfg.put("source", "inline");
        cfg.put("source.stubs", "[{\"path\":\"/x\"}]");
        cfg.put("sink", "wiremock");
        cfg.put("sink.url", "http://wiremock:8080");
        cfg.put("sink.auth.token", "${env:WM_TOKEN}");
        assertThat(worker.validate("dict", cfg).errorCount()).isZero();

        cfg.put("sink.auth.token", "${env:NAO_EXISTE}");
        assertThat(worker.validate("dict", cfg).errors().get("sink.auth.token"))
                .anyMatch(m -> m.contains("NAO_EXISTE"));
    }

    @Test
    void privateHostDetectionForSinks() {
        assertThat(Validators.isPrivateHost("wiremock")).isTrue();          // nome de serviço do compose
        assertThat(Validators.isPrivateHost("localhost")).isTrue();
        assertThat(Validators.isPrivateHost("10.1.2.3")).isTrue();
        assertThat(Validators.isPrivateHost("192.168.0.10")).isTrue();
        assertThat(Validators.isPrivateHost("api.parceiro.com.br")).isFalse();
        assertThat(Validators.isPrivateHost("8.8.8.8")).isFalse();
    }
}
