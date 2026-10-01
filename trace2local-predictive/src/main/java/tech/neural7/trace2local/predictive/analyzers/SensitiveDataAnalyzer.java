package tech.neural7.trace2local.predictive.analyzers;

import com.fasterxml.jackson.databind.JsonNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.model.LogEntry;
import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.correlation.FlowView;
import tech.neural7.trace2local.predictive.decision.Answer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dados sensíveis vazando para observabilidade:
 * <ul>
 *   <li><b>SEC-LOG-001</b> — o LOG DA APLICAÇÃO carregava dado sensível: a linha
 *       chegou ao Trace2Local com a marca de redação, o que prova que o valor
 *       bruto existia no log real (que NÃO é mascarado) — FATO;</li>
 *   <li><b>SEC-PII-001</b> — campos com nome de dado pessoal (nome, telefone,
 *       endereço, nascimento, documento…) passaram SEM redação em payloads/deltas —
 *       micro-decisão Jev por campo ("isso é dado pessoal?") com léxico como regra;</li>
 *   <li><b>SEC-URL-001</b> — segredo em query string de URL observada.</li>
 * </ul>
 */
public final class SensitiveDataAnalyzer implements PredictiveAnalyzer {

    public static final String LOG_ID = "SEC-LOG-001";
    public static final String PII_ID = "SEC-PII-001";
    public static final String URL_ID = "SEC-URL-001";
    private static final String MARK = "[TRACE2LOCAL_REDACTED]";
    private static final List<String> PII_NAMES = List.of("nome", "name", "telefone", "phone", "celular", "mobile",
            "endereco", "address", "logradouro", "cep", "zipcode", "nascimento", "birth", "dob", "rg", "documento",
            "document", "passaporte", "passport", "renda", "income", "salario", "salary", "mae", "mother", "genero", "gender");
    private static final Set<String> NOT_PII = Set.of("tablename", "queuename", "topicname", "functionname", "filename",
            "hostname", "classname", "typename", "servicename", "operationname", "username_hash", "name_space", "namespace");

    @Override
    public String name() {
        return "SensitiveDataAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.EXECUTION;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        List<Insight> out = new ArrayList<>();
        logsWithSecrets(ctx).ifPresent(out::add);
        if (ctx.flow() != null) {
            piiFields(ctx, ctx.flow()).ifPresent(out::add);
            urlSecrets(ctx, ctx.flow()).ifPresent(out::add);
        }
        return out;
    }

    private java.util.Optional<Insight> logsWithSecrets(AnalysisContext ctx) {
        if (ctx.logs() == null) {
            return java.util.Optional.empty();
        }
        List<Evidence> ev = new ArrayList<>();
        Map<String, Integer> byLogger = new LinkedHashMap<>();
        for (LogEntry l : ctx.logs()) {
            if (l.message() != null && l.message().contains(MARK) && l.source() != LogEntry.LogSource.PLATFORM) {
                String who = l.logger() != null ? l.logger() : (l.logGroup() != null ? l.logGroup() : "app");
                byLogger.merge(who, 1, Integer::sum);
                if (ev.size() < 4) {
                    String preview = l.message().length() > 140 ? l.message().substring(0, 140) + "…" : l.message();
                    ev.add(new Evidence(Evidence.Kind.LOG, l.level() + " " + who, preview,
                            new Evidence.Ref(ctx.executionId(), l.spanId(), null, 0, l.logGroup())));
                }
            }
        }
        if (ev.isEmpty()) {
            return java.util.Optional.empty();
        }
        int total = byLogger.values().stream().mapToInt(Integer::intValue).sum();
        InsightBuilder b = InsightBuilder.of(LOG_ID, name())
                .subject(String.join(",", byLogger.keySet()))
                .category(Insight.Category.SECURITY)
                .severity(Insight.Severity.HIGH)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Dado sensível presente nos logs da aplicação")
                .observation(total + " linha(s) de log desta execução continham valor sensível (token, e-mail, documento ou chave).")
                .evidence(ev)
                .correlation("O Trace2Local mascarou na captura — o arquivo/stream de log REAL da aplicação não é mascarado.")
                .hypothesis("Objetos de domínio ou headers são logados inteiros (toString/MDC) sem sanitização.")
                .recommend("Remover o dado do log ou aplicar mascaramento no encoder (ex.: logstash masking / pattern replace).")
                .recommend("Logar identificadores, não valores (ex.: últimos 4 dígitos, hash).")
                .execution(ctx.executionId());
        byLogger.keySet().forEach(b::component);
        return java.util.Optional.of(b.build());
    }

    private java.util.Optional<Insight> piiFields(AnalysisContext ctx, FlowView flow) {
        Map<String, String[]> found = new LinkedHashMap<>(); // campo → [nodeId, label, onde]
        for (FlowView.Step s : flow.steps()) {
            var n = s.node();
            if (n.payload() != null) {
                scanJson(n.payload().request(), "request", s, found);
                scanJson(n.payload().response(), "response", s, found);
            }
            if (n.mutation() != null) {
                scanNode(n.mutation().after(), "", "delta.after", s, found);
            }
        }
        if (found.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<Evidence> ev = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        String engine = Answer.ENGINE_DETERMINISTIC;
        for (var e : found.entrySet()) {
            String field = e.getKey();
            Map<String, String> state = Map.of("field", field, "where", e.getValue()[2], "component", e.getValue()[1]);
            boolean lexical = isPiiKey(leaf(field).replace("[]", ""));
            Answer a = ctx.decisions().noul("pii_" + Math.abs(field.hashCode()), state,
                    "The JSON field '" + field + "' most likely holds personal data about a person (PII).",
                    lexical ? 0.8 : 0.3, lexical ? "nome do campo está no léxico de dado pessoal" : "fora do léxico");
            if (a.yes()) {
                confirmed.add(field);
                engine = a.engine();
                if (ev.size() < 5) {
                    ev.add(new Evidence(Evidence.Kind.DATA, "campo " + field + " sem redação", e.getValue()[2] + " em " + e.getValue()[1],
                            Evidence.Ref.node(ctx.executionId(), e.getValue()[0])));
                }
            }
        }
        if (confirmed.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(InsightBuilder.of(PII_ID, name())
                .subject(String.join(",", confirmed.stream().map(SensitiveDataAnalyzer::leaf).sorted().toList()))
                .category(Insight.Category.SECURITY)
                .severity(Insight.Severity.MEDIUM)
                .confidence(0.7)
                .nature(Insight.Nature.HYPOTHESIS)
                .title("Possível dado pessoal sem mascaramento em payloads")
                .observation("Campos com cara de dado pessoal trafegaram sem redação: " + String.join(", ", confirmed) + ".")
                .evidence(ev)
                .correlation("A redação por chave do Trace2Local não cobre esses nomes — o mesmo vale para logs/APM da empresa.")
                .hypothesis("Dado pessoal com nome inocente passa pelos filtros por chave (limite declarado da SPEC §8.3).")
                .recommend("Acrescentar os campos à política de redação (RedactionPolicy SPI) e ao mascaramento de logs.")
                .recommend("Avaliar minimização: o fluxo precisa mesmo carregar esse dado?")
                .execution(ctx.executionId())
                .decidedBy(engine)
                .build());
    }

    private java.util.Optional<Insight> urlSecrets(AnalysisContext ctx, FlowView flow) {
        for (FlowView.Step s : flow.steps()) {
            Map<String, String> a = s.node().attributes() != null ? s.node().attributes() : Map.of();
            for (var e : a.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
                if ((e.getKey().startsWith("url") || e.getKey().startsWith("http")) && v.contains("?")
                        && (v.contains("token=") || v.contains("apikey=") || v.contains("api_key=") || v.contains("secret=")
                        || v.contains("password=") || v.contains("senha=") || v.contains("access_token="))) {
                    return java.util.Optional.of(InsightBuilder.of(URL_ID, name())
                            .subject(s.component())
                            .category(Insight.Category.SECURITY)
                            .severity(Insight.Severity.HIGH)
                            .confidence(0.999)
                            .nature(Insight.Nature.FACT)
                            .title("Segredo em query string de URL")
                            .observation("A URL chamada por " + s.node().label() + " carrega credencial na query string.")
                            .evidence(List.of(Evidence.span("atributo " + e.getKey(), "parâmetro sensível na URL",
                                    ctx.executionId(), s.node().nodeId())))
                            .correlation("Query strings vão para logs de proxy, APM e histórico — o segredo se espalha.")
                            .hypothesis("Credencial passada por URL por conveniência.")
                            .recommend("Mover a credencial para header Authorization e rotacionar a chave exposta.")
                            .component(s.component())
                            .execution(ctx.executionId())
                            .build());
                }
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Valor já protegido: marca do Trace2Local, marcas usuais de redação ou máscara com
     * asteriscos ("J*** S***", "***.456.789-**") — o parceiro/serviço mascarou, não é vazamento.
     */
    static boolean masked(String v) {
        if (v == null) {
            return true;
        }
        String t = v.trim();
        return MARK.equals(t) || t.contains("***") || t.matches("(?i)\\[(redacted|oculto|masked|mascarado)[^\\]]*\\]")
                || t.chars().filter(c -> c == '*' || c == 'x' || c == 'X').count() >= Math.max(3, t.length() / 2);
    }

    private static void scanJson(String json, String where, FlowView.Step s, Map<String, String[]> found) {
        if (json == null || json.isBlank() || !(json.trim().startsWith("{") || json.trim().startsWith("["))) {
            return;
        }
        try {
            scanNode(JsonSupport.MAPPER.readTree(json), "", where, s, found);
        } catch (Exception ignored) {
            // payload truncado/não-JSON
        }
    }

    private static void scanNode(JsonNode node, String path, String where, FlowView.Step s, Map<String, String[]> found) {
        if (node == null || found.size() > 30) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                JsonNode v = e.getValue();
                String key = e.getKey().toLowerCase(Locale.ROOT);
                if (v.isValueNode() && !v.isNull() && !masked(v.asText()) && v.asText().length() >= 3 && isPiiKey(e.getKey())) {
                    found.putIfAbsent(p, new String[] {s.node().nodeId(), s.node().label(), where});
                } else if (v.isObject() && v.size() == 1 && v.elements().next().isValueNode()) {
                    // atributo DynamoDB {"S": "..."}
                    JsonNode inner = v.elements().next();
                    if (!masked(inner.asText()) && inner.asText().length() >= 3 && isPiiKey(e.getKey())) {
                        found.putIfAbsent(p, new String[] {s.node().nodeId(), s.node().label(), where});
                    }
                } else {
                    scanNode(v, p, where, s, found);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                scanNode(child, path + "[]", where, s, found);
            }
        }
    }

    /**
     * Chave com cara de dado pessoal: algum TOKEN do nome (camelCase/snake) é do
     * léxico — tokens curtos (rg, cep, dob, mae) só por igualdade, longos por prefixo.
     * Evita "target"/"charge" casarem "rg" e "tableName" casar "name".
     */
    public static boolean isPiiKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (NOT_PII.contains(k)) {
            return false;
        }
        String[] tokens = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String t : tokens) {
            for (String p : PII_NAMES) {
                if (p.length() <= 3 ? t.equals(p) : t.startsWith(p)) {
                    // "name" só conta quando é nome de pessoa (customerName, nomeCompleto), não de recurso
                    if ((p.equals("name") || p.equals("nome")) && tokens.length > 1
                            && List.of("table", "queue", "topic", "function", "file", "host", "class", "type", "service",
                                    "operation", "bucket", "stream", "group", "app", "event").contains(tokens[0])) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static String leaf(String path) {
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
