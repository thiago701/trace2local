package tech.neural7.trace2local.examples.pix.infra;

/** Configuração por variável de ambiente (Terraform injeta; nada de segredo no código). */
public final class Env {

    private Env() {}

    public static String get(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    public static String required(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("variável de ambiente obrigatória ausente: " + key);
        }
        return v.trim();
    }
}
