package tech.neural7.trace2local.predictive.decision;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utilidades léxicas determinísticas (PT-BR + EN) do modelo determinístico:
 * normalização sem acento, tokens com radical curto, stopwords e léxicos de
 * domínio (negócio × técnico, recusa × falha). Nada de aleatoriedade: a mesma
 * entrada produz SEMPRE a mesma saída.
 */
final class TextFeatures {

    private static final Set<String> STOP = Set.of(
            "a", "o", "e", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas", "um", "uma",
            "para", "por", "com", "que", "se", "ao", "aos", "as", "os", "ou", "the", "of", "to", "and",
            "in", "on", "for", "is", "are", "this", "that", "it", "be", "esta", "este", "isso", "foi",
            "ser", "pelo", "pela", "sobre", "mais", "como", "passo", "step", "execucao", "execution");

    static final Set<String> NEGATIONS = Set.of("nao", "sem", "nenhum", "nenhuma", "nunca", "not", "no", "without", "never", "none");

    /** Sinais de RECUSA/GUARDA de negócio (falha esperada que protege o invariante). */
    static final List<String> BUSINESS_GUARD = List.of(
            "conditionalcheckfailed", "conditional request failed", "idempot", "duplicad", "duplicate", "ja existe",
            "already exists");

    /**
     * Linguagem NORMATIVA de regra de guarda no glossário ("só uma vez", "não pode").
     * Separada dos sinais de ERRO de guarda: "pedido recusado: limite de crédito" é
     * falha de negócio, não guarda protetora — medido no benchmark Jev de 2026-09-30.
     */
    static final List<String> GUARD_RULE_WORDS = List.of(
            "uma vez", "so um", "so uma", "nao pode", "duplicad", "idempot", "recusad", "unico", "unica");

    /**
     * A guarda de idempotência AGIU (reentrega/duplicado ignorado sem efeito): é um
     * evento de negócio esperado, não um erro (achado em trace real Lambda + LocalStack).
     */
    static final List<String> IDEMPOTENT_SKIP = List.of(
            "ja processad", "already processed", "reentreg", "redeliver", "duplicad", "duplicate", "ignorad", "ignored",
            "sem efeito colateral", "no side effect", "idempot");

    /** Sinais de falha de NEGÓCIO (regra/validação). */
    static final List<String> BUSINESS_FAILURE = List.of(
            "illegalstate", "illegalargument", "validation", "validacao", "invalid", "invalido", "invalida",
            "limite", "limit", "excede", "exceeds", "insuficiente", "insufficient", "credito", "credit", "recusad", "refused",
            "rejeitad", "rejected", "nao pode", "cannot be", "expired", "expirad", "conflict",
            "nao permitido", "not allowed", "regra", "rule", "conflict", "conflito");

    /** Sinais de falha TÉCNICA (infra, rede, programação). */
    static final List<String> TECHNICAL_FAILURE = List.of(
            "timeout", "timed out", "connection", "conexao", "refused connection", "unknownhost", "ioexception",
            "sdkclientexception", "nullpointer", "classcast", "outofmemory", "serialization", "throttl",
            "resourcenotfound", "accessdenied", "credentials", "unavailable", "503", "500", "socket", "dns");

    /** Verbos/substantivos que marcam EVENTO de negócio num log. */
    static final List<String> BUSINESS_EVENT = List.of(
            "pedido", "order", "pagamento", "payment", "pix", "cobranc", "billing", "billed", "fatura", "invoice",
            "cliente", "customer", "confirm", "criad", "created", "gravad", "saved", "aprovad", "approved",
            "cancel", "estorn", "refund", "notific", "enviad", "sent", "recebid", "received");

    private TextFeatures() {}

    static String fold(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** Tokens normalizados com radical de até 6 letras (stemming grosseiro, porém estável). */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String raw : fold(s).split("[^a-z0-9]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) {
                continue;
            }
            out.add(raw.length() > 6 ? raw.substring(0, 6) : raw);
        }
        return out;
    }

    static Set<String> tokenSet(String s) {
        return new HashSet<>(tokens(s));
    }

    static boolean containsAny(String haystack, List<String> needles) {
        String h = fold(haystack);
        for (String n : needles) {
            if (h.contains(n)) {
                return true;
            }
        }
        return false;
    }

    static int countAny(String haystack, List<String> needles) {
        String h = fold(haystack);
        int c = 0;
        for (String n : needles) {
            if (h.contains(n)) {
                c++;
            }
        }
        return c;
    }

    /** Proporção dos tokens de {@code query} presentes em {@code doc} (0..1). */
    static double coverage(Set<String> query, Set<String> doc) {
        if (query.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String t : query) {
            if (doc.contains(t)) {
                hit++;
            }
        }
        return (double) hit / query.size();
    }

    /** Softmax determinístico com temperatura (ordem preservada). */
    static Map<String, Double> softmax(Map<String, Double> scores, double temperature) {
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<String, Double> exp = new LinkedHashMap<>();
        double sum = 0;
        for (var e : scores.entrySet()) {
            double v = Math.exp((e.getValue() - max) / Math.max(1e-6, temperature));
            exp.put(e.getKey(), v);
            sum += v;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : exp.entrySet()) {
            out.put(e.getKey(), round(e.getValue() / sum));
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Linha de frame de stack trace ({@code "\tat pkg.Classe.metodo(Arquivo.java:87)"}). */
    static boolean isStackFrame(String line) {
        if (line == null) {
            return false;
        }
        String t = line.stripLeading();
        return (t.startsWith("at ") && (t.contains(".java:") || t.contains("Unknown Source") || t.contains("Native Method")))
                || t.startsWith("... ") && t.endsWith(" more");
    }
}
