package tech.neural7.trace2local.predictive.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Construtor fluente de {@link Insight} que FORÇA o princípio Evidence First:
 * {@link #build()} recusa insight sem evidência ou sem observação.
 */
public final class InsightBuilder {

    private final String id;
    private final String analyzer;
    private String subject = "";
    private Insight.Category category = Insight.Category.PERFORMANCE;
    private Insight.Severity severity = Insight.Severity.MEDIUM;
    private double confidence = 0.5;
    private Insight.Nature nature = Insight.Nature.CORRELATION;
    private String title;
    private String observation;
    private final List<Evidence> evidence = new ArrayList<>();
    private String correlation;
    private String hypothesis;
    private final List<String> recommendations = new ArrayList<>();
    private final LinkedHashSet<String> components = new LinkedHashSet<>();
    private final LinkedHashSet<String> executions = new LinkedHashSet<>();
    private String decidedBy = "rules";

    private InsightBuilder(String id, String analyzer) {
        this.id = id;
        this.analyzer = analyzer;
    }

    public static InsightBuilder of(String id, String analyzer) {
        return new InsightBuilder(id, analyzer);
    }

    /** Sujeito do achado (componente/fluxo/chave) — compõe o fingerprint de dedupe. */
    public InsightBuilder subject(String s) {
        this.subject = s == null ? "" : s;
        return this;
    }

    public InsightBuilder category(Insight.Category c) {
        this.category = c;
        return this;
    }

    public InsightBuilder severity(Insight.Severity s) {
        this.severity = s;
        return this;
    }

    public InsightBuilder confidence(double c) {
        this.confidence = Math.max(0, Math.min(1, c));
        return this;
    }

    public InsightBuilder nature(Insight.Nature n) {
        this.nature = n;
        return this;
    }

    public InsightBuilder title(String t) {
        this.title = t;
        return this;
    }

    public InsightBuilder observation(String o) {
        this.observation = o;
        return this;
    }

    public InsightBuilder evidence(Evidence e) {
        if (e != null) {
            this.evidence.add(e);
        }
        return this;
    }

    public InsightBuilder evidence(List<Evidence> list) {
        list.forEach(this::evidence);
        return this;
    }

    public InsightBuilder correlation(String c) {
        this.correlation = c;
        return this;
    }

    public InsightBuilder hypothesis(String h) {
        this.hypothesis = h;
        return this;
    }

    public InsightBuilder recommend(String r) {
        this.recommendations.add(r);
        return this;
    }

    public InsightBuilder component(String c) {
        if (c != null && !c.isBlank()) {
            this.components.add(c);
        }
        return this;
    }

    public InsightBuilder execution(String executionId) {
        if (executionId != null) {
            this.executions.add(executionId);
        }
        return this;
    }

    public InsightBuilder decidedBy(String engine) {
        this.decidedBy = engine;
        return this;
    }

    public Insight build() {
        if (observation == null || observation.isBlank()) {
            throw new IllegalStateException(id + ": insight sem observação");
        }
        if (evidence.isEmpty()) {
            throw new IllegalStateException(id + ": Evidence First — insight sem evidência");
        }
        if (nature == Insight.Nature.FACT) {
            confidence = Math.max(confidence, 0.999);
        } else if (confidence >= 0.999) {
            confidence = 0.98; // só fato observado tem confiança 1
        }
        String fingerprint = (id + "|" + subject).toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        return new Insight(id, fingerprint, category, severity, round(confidence), nature,
                title != null ? title : id, observation, List.copyOf(evidence), correlation, hypothesis,
                List.copyOf(recommendations), List.copyOf(components), List.copyOf(executions), analyzer, decidedBy,
                0, 1, now, now);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
