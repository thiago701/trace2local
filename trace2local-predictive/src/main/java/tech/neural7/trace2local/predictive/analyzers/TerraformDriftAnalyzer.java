package tech.neural7.trace2local.predictive.analyzers;

import tech.neural7.trace2local.predictive.api.AnalysisContext;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.api.PredictiveAnalyzer;
import tech.neural7.trace2local.predictive.decision.Answer;
import tech.neural7.trace2local.predictive.project.ProjectSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Infraestrutura como código entre ambientes (dev × hml × prod):
 * <ul>
 *   <li><b>IAC-DRIFT-001</b> — divergências RELEVANTES: chave presente em um
 *       ambiente e ausente em outro, ou valor diferente em URL/escopo/timeout/
 *       memória/concorrência/flag. Diferenças que só trocam o nome do ambiente
 *       ({@code orders-dev} × {@code orders-prod}) são esperadas e descartadas;
 *       o resto passa por micro-decisão "intencional?" (Jev → regra);</li>
 *   <li><b>IAC-SEC-001</b> — segredo LITERAL em arquivo Terraform/tfvars — FATO.</li>
 * </ul>
 * Um insight agregado por análise (top divergências), nunca um por chave.
 */
public final class TerraformDriftAnalyzer implements PredictiveAnalyzer {

    public static final String DRIFT_ID = "IAC-DRIFT-001";
    public static final String SECRET_ID = "IAC-SEC-001";
    private static final Pattern ENV_TOKEN = Pattern.compile("(?i)(dev|develop|development|hml|homolog|staging|stg|qa|uat|prod|prd|production)");
    private static final List<String> RELEVANT = List.of("url", "endpoint", "host", "scope", "audience", "issuer", "timeout",
            "memory", "concurren", "batch", "retention", "visibility", "runtime", "handler", "role", "policy", "secret",
            "token", "key", "password", "feature", "flag", "enabled", "replica", "min_", "max_", "capacity", "billing");

    @Override
    public String name() {
        return "TerraformDriftAnalyzer";
    }

    @Override
    public Scope scope() {
        return Scope.PROJECT;
    }

    @Override
    public List<Insight> analyze(AnalysisContext ctx) {
        ProjectSnapshot p = ctx.project();
        if (p == null || p.terraform().isEmpty()) {
            return List.of();
        }
        List<Insight> out = new ArrayList<>();
        secrets(p).ifPresent(out::add);
        if (p.terraform().size() >= 2) {
            drift(ctx, p).ifPresent(out::add);
        }
        return out;
    }

    private java.util.Optional<Insight> drift(AnalysisContext ctx, ProjectSnapshot p) {
        Set<String> envs = p.terraform().keySet();
        Set<String> keys = new LinkedHashSet<>();
        p.terraform().values().forEach(m -> keys.addAll(m.keySet()));
        record Divergence(String key, String kind, Map<String, ProjectSnapshot.Assignment> byEnv, Answer intentional) {}
        List<Divergence> divergences = new ArrayList<>();
        int expected = 0;
        for (String key : keys) {
            String lk = key.toLowerCase(Locale.ROOT);
            if (RELEVANT.stream().noneMatch(lk::contains)) {
                continue;
            }
            Map<String, ProjectSnapshot.Assignment> byEnv = new LinkedHashMap<>();
            for (String env : envs) {
                ProjectSnapshot.Assignment a = p.terraform().get(env).get(key);
                if (a != null) {
                    byEnv.put(env, a);
                }
            }
            if (byEnv.size() < envs.size()) {
                divergences.add(new Divergence(key, "ausente em " + missing(envs, byEnv.keySet()), byEnv, null));
                continue;
            }
            Set<String> normalized = new LinkedHashSet<>();
            Set<String> raw = new LinkedHashSet<>();
            byEnv.values().forEach(a -> {
                raw.add(a.value());
                normalized.add(ENV_TOKEN.matcher(a.value()).replaceAll("<env>"));
            });
            if (raw.size() <= 1) {
                continue;
            }
            if (normalized.size() <= 1) {
                expected++; // só o nome do ambiente muda — esperado
                continue;
            }
            Map<String, String> state = new LinkedHashMap<>();
            state.put("setting", key);
            byEnv.forEach((env, a) -> state.put(env, maskIfSecret(key, a.value())));
            boolean sizing = lk.contains("memory") || lk.contains("concurren") || lk.contains("capacity")
                    || lk.contains("replica") || lk.contains("min_") || lk.contains("max_") || lk.contains("retention");
            Answer intentional = ctx.decisions().noul("iac_" + Math.abs(key.hashCode()), state,
                    "This difference between environments is an intentional, environment-specific setting "
                            + "(for example production sizing) rather than a configuration mistake.",
                    sizing ? 0.75 : 0.3, sizing ? "dimensionamento costuma diferir por ambiente" : "URL/escopo/timeout divergente sem padrão de ambiente");
            if (intentional.yes()) {
                expected++;
                continue;
            }
            divergences.add(new Divergence(key, "valores diferentes", byEnv, intentional));
        }
        if (divergences.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<Evidence> ev = new ArrayList<>();
        String engine = "rules";
        for (Divergence d : divergences.subList(0, Math.min(8, divergences.size()))) {
            StringBuilder v = new StringBuilder();
            d.byEnv().forEach((env, a) -> v.append(env).append('=').append(maskIfSecret(d.key(), a.value())).append("  "));
            ProjectSnapshot.Assignment any = d.byEnv().values().stream().findFirst().orElse(null);
            ev.add(Evidence.file(Evidence.Kind.IAC, d.key() + " (" + d.kind() + ")", v.toString().trim(),
                    any != null ? any.file() : null, any != null ? any.line() : 0));
            if (d.intentional() != null) {
                engine = d.intentional().engine();
            }
        }
        return java.util.Optional.of(InsightBuilder.of(DRIFT_ID, name())
                .subject(String.join(",", envs))
                .category(Insight.Category.INFRASTRUCTURE)
                .severity(divergences.size() >= 5 ? Insight.Severity.MEDIUM : Insight.Severity.LOW)
                .confidence(0.8)
                .nature(Insight.Nature.CORRELATION)
                .title("Divergências relevantes entre ambientes (" + String.join(" × ", envs) + ")")
                .observation(divergences.size() + " configuração(ões) relevante(s) divergem entre os ambientes; "
                        + expected + " diferença(s) esperada(s) foram descartadas (só o nome do ambiente muda ou dimensionamento).")
                .evidence(ev)
                .correlation("Divergência em URL/escopo/timeout costuma aparecer só na homologação/produção — o dev local não reproduz.")
                .hypothesis("Configuração aplicada manualmente num ambiente e não propagada aos demais.")
                .recommend("Confirmar se cada diferença é intencional; mover o que é comum para módulos/variáveis compartilhadas.")
                .recommend("Adicionar validação no CI (terraform plan por ambiente + checagem de chaves obrigatórias).")
                .decidedBy(engine)
                .build());
    }

    private java.util.Optional<Insight> secrets(ProjectSnapshot p) {
        List<Evidence> ev = new ArrayList<>();
        for (var env : p.terraform().entrySet()) {
            for (ProjectSnapshot.Assignment a : env.getValue().values()) {
                String lk = a.key().toLowerCase(Locale.ROOT);
                boolean sensitive = lk.contains("password") || lk.contains("secret") || lk.contains("token")
                        || lk.endsWith("api_key") || lk.endsWith("apikey") || lk.contains("private_key");
                String v = a.value().trim();
                boolean literal = !v.isBlank() && !v.startsWith("var.") && !v.startsWith("data.") && !v.startsWith("local.")
                        && !v.startsWith("${") && !v.startsWith("aws_") && !v.startsWith("module.") && !v.equals("null")
                        && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false") && !v.startsWith("random_");
                if (sensitive && literal && !lk.contains("secret_arn") && !lk.contains("secret_name") && !lk.endsWith("_id")) {
                    ev.add(Evidence.file(Evidence.Kind.IAC, a.key() + " (" + env.getKey() + ")", "valor literal no arquivo",
                            a.file(), a.line()));
                }
            }
        }
        if (ev.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(InsightBuilder.of(SECRET_ID, name())
                .subject(String.join(",", ev.stream().map(Evidence::label).sorted().toList()))
                .category(Insight.Category.SECURITY)
                .severity(Insight.Severity.HIGH)
                .confidence(0.999)
                .nature(Insight.Nature.FACT)
                .title("Segredo literal em Terraform")
                .observation(ev.size() + " chave(s) sensível(is) têm valor literal versionado no IaC.")
                .evidence(ev)
                .correlation("Segredo em tfvars vai para o histórico do Git e para o state do Terraform.")
                .hypothesis("Valor colocado direto no arquivo durante o desenvolvimento e nunca migrado para cofre.")
                .recommend("Ler do AWS Secrets Manager/SSM (data source) e rotacionar o valor exposto.")
                .recommend("Ativar gitleaks/trufflehog no pre-commit e no CI.")
                .build());
    }

    private static String missing(Set<String> all, Set<String> present) {
        List<String> m = new ArrayList<>(all);
        m.removeAll(present);
        return String.join(", ", m);
    }

    private static String maskIfSecret(String key, String value) {
        String lk = key.toLowerCase(Locale.ROOT);
        if (lk.contains("password") || lk.contains("secret") || lk.contains("token") || lk.contains("key")) {
            return "[OCULTO]";
        }
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
