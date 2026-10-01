package tech.neural7.trace2local.predictive.api;

import java.util.Locale;

/** Formatação PT-BR curta para observações e evidências. */
public final class Fmt {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Fmt() {}

    /** 120 ms · 4,1 s · 2,5 min */
    public static String ms(double ms) {
        if (Double.isNaN(ms)) {
            return "—";
        }
        if (ms < 1000) {
            return Math.round(ms) + " ms";
        }
        if (ms < 120_000) {
            return String.format(PT_BR, "%.1f s", ms / 1000.0);
        }
        return String.format(PT_BR, "%.1f min", ms / 60_000.0);
    }

    /** 78,8% */
    public static String pct(double ratio) {
        return String.format(PT_BR, "%.1f%%", ratio * 100);
    }

    /** +115% / −20% */
    public static String change(double ratio) {
        long p = Math.round(ratio * 100);
        return (p >= 0 ? "+" : "−") + Math.abs(p) + "%";
    }

    public static String num(double v) {
        return String.format(PT_BR, "%.2f", v);
    }

    /** Remove o prefixo técnico do componente ({@code sqs:orders-queue → orders-queue}). */
    public static String component(String c) {
        if (c == null) {
            return "?";
        }
        int i = c.indexOf(':');
        return i > 0 && i < 12 ? c.substring(i + 1) : c;
    }
}
