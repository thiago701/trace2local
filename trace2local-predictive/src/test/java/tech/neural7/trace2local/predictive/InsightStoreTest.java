package tech.neural7.trace2local.predictive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.neural7.trace2local.predictive.api.Evidence;
import tech.neural7.trace2local.predictive.api.Insight;
import tech.neural7.trace2local.predictive.api.InsightBuilder;
import tech.neural7.trace2local.predictive.ranking.InsightStore;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ranking, dedupe, supressões e aprendizado por feedback (ADR-013 §9). */
class InsightStoreTest {

    @TempDir
    Path tmp;

    private static Insight insight(String exec, Insight.Severity sev, String analyzer) {
        return InsightBuilder.of("PERF-ASYNC-001", analyzer).subject("flow|sqs:q").severity(sev).confidence(0.9)
                .observation("espera alta").evidence(Evidence.metric("espera", "4 s")).execution(exec).build();
    }

    @Test
    void dedupesByFingerprintAndAccumulatesOccurrences() {
        InsightStore store = InsightStore.inMemory();
        store.accept(List.of(insight("e1", Insight.Severity.MEDIUM, "A")));
        store.accept(List.of(insight("e2", Insight.Severity.MEDIUM, "A")));
        assertThat(store.all()).hasSize(1);
        Insight merged = store.all().get(0);
        assertThat(merged.occurrences()).isEqualTo(2);
        assertThat(merged.executionIds()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(merged.score()).isPositive();
    }

    @Test
    void dismissHidesUntilSeverityWorsensAndFeedbackPersistsAndLearns() {
        Path file = tmp.resolve("feedback.json");
        InsightStore store = new InsightStore(file);
        store.accept(List.of(insight("e1", Insight.Severity.MEDIUM, "Noisy")));
        String fp = store.all().get(0).fingerprint();
        double before = store.precision("Noisy");
        store.feedback(fp, InsightStore.Action.DISMISS);
        assertThat(store.top(10, 0)).isEmpty();
        assertThat(store.precision("Noisy")).isLessThan(before);
        // piorou ⇒ volta a aparecer apesar do dismiss
        store.accept(List.of(insight("e2", Insight.Severity.HIGH, "Noisy")));
        assertThat(store.top(10, 0)).hasSize(1);
        // persistido: um novo store lê o feedback
        InsightStore reloaded = new InsightStore(file);
        assertThat(reloaded.precision("Noisy")).isEqualTo(store.precision("Noisy"));
    }

    @Test
    void evidenceFirstIsEnforced() {
        assertThatThrownBy(() -> InsightBuilder.of("X-1", "A").observation("algo").build())
                .hasMessageContaining("Evidence First");
    }
}
