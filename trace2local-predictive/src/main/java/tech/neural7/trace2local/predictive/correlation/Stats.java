package tech.neural7.trace2local.predictive.correlation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Estatística robusta (mediana, percentis, MAD, z-score robusto) — o que separa
 * um sinal real de ruído de laptop. Sem dependência externa.
 */
public final class Stats {

    private Stats() {}

    public static double median(Collection<? extends Number> values) {
        return percentile(values, 50);
    }

    /** Percentil por interpolação linear (p em 0..100); {@code NaN} se vazio. */
    public static double percentile(Collection<? extends Number> values, double p) {
        List<Double> sorted = sorted(values);
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        double frac = rank - lo;
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * frac;
    }

    /** Median Absolute Deviation (escala ~σ com fator 1.4826). */
    public static double mad(Collection<? extends Number> values) {
        double med = median(values);
        if (Double.isNaN(med)) {
            return Double.NaN;
        }
        List<Double> dev = new ArrayList<>();
        for (Number v : values) {
            dev.add(Math.abs(v.doubleValue() - med));
        }
        return 1.4826 * median(dev);
    }

    /**
     * z-score robusto de {@code x} contra a amostra; com MAD zero (amostra
     * constante) usa 5% da mediana como escala mínima para não explodir.
     */
    public static double robustZ(double x, Collection<? extends Number> sample) {
        double med = median(sample);
        if (Double.isNaN(med)) {
            return 0;
        }
        double scale = mad(sample);
        double floor = Math.max(1.0, Math.abs(med) * 0.05);
        return (x - med) / Math.max(scale, floor);
    }

    public static double mean(Collection<? extends Number> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double s = 0;
        for (Number v : values) {
            s += v.doubleValue();
        }
        return s / values.size();
    }

    /** Variação relativa (b − a) / a; {@code NaN} se a ≤ 0. */
    public static double relativeChange(double a, double b) {
        return a <= 0 ? Double.NaN : (b - a) / a;
    }

    private static List<Double> sorted(Collection<? extends Number> values) {
        List<Double> out = new ArrayList<>(values.size());
        for (Number v : values) {
            if (v != null && !Double.isNaN(v.doubleValue())) {
                out.add(v.doubleValue());
            }
        }
        out.sort(Double::compare);
        return out;
    }

    /** Confiança a partir de tamanho de amostra e tamanho de efeito (saturação suave). */
    public static double evidenceConfidence(int samples, double effect, int minSamples) {
        if (samples < minSamples) {
            return Math.min(0.5, 0.3 + 0.05 * samples);
        }
        double sampleTerm = 1 - Math.exp(-samples / (double) Math.max(1, minSamples * 2));
        double effectTerm = 1 - Math.exp(-Math.max(0, effect));
        return clamp(0.45 + 0.5 * sampleTerm * effectTerm + 0.05 * Math.min(1, effect / 4), 0, 0.99);
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
