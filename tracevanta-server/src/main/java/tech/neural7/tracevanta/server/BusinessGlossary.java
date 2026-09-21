package tech.neural7.tracevanta.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Glossário de negócio OPCIONAL — engenharia reversa assistida por documentação:
 * a equipe descreve os termos do domínio em {@code tracevanta-business.md} no
 * classpath (resources da app no Embedded, ou montado no container do Station no
 * modo Companion) e o {@link StoryService} usa as descrições como anotações de
 * storytelling dos nós.
 *
 * <pre>{@code
 * # Glossário de negócio (TraceVanta)
 * - CreateOrder: Cria o pedido — valida o cliente, grava no DynamoDB e publica o evento.
 * - billing: Cobrança após confirmação (condição: status = CONFIRMED).
 * - orders: Tabela de pedidos da aplicação.
 * }</pre>
 *
 * <p>Formato: linhas {@code - termo: descrição} ou {@code | termo | descrição |};
 * o casamento é por substring (case-insensitive) no rótulo do nó — sem arquivo,
 * o serviço usa apenas a engenharia reversa de nomes/semântica (zero config).
 */
public final class BusinessGlossary {

    private final Map<String, String> entries = new LinkedHashMap<>();

    public BusinessGlossary() {
        loadFromClasspath();
    }

    /** Descrição do termo que casa com o rótulo, ou vazio. */
    public String noteFor(String label) {
        if (label == null) {
            return "";
        }
        String lower = label.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private void loadFromClasspath() {
        try (InputStream in = BusinessGlossary.class.getClassLoader()
                .getResourceAsStream("tracevanta-business.md")) {
            if (in != null) {
                parse(in);
            }
        } catch (Throwable ignored) {
            // glossário é best-effort: sem ele, a storytelling usa só a semântica
        }
    }

    private void parse(InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("- ")) {
                    trimmed = trimmed.substring(2);
                }
                int sep = trimmed.indexOf(':');
                if (sep > 0) {
                    add(trimmed.substring(0, sep).trim(), trimmed.substring(sep + 1).trim());
                    continue;
                }
                if (trimmed.startsWith("|")) {
                    String[] parts = trimmed.split("\\|");
                    if (parts.length >= 3) {
                        add(parts[1].trim(), parts[2].trim());
                    }
                }
            }
        }
    }

    private void add(String term, String description) {
        if (!term.isBlank() && !description.isBlank()) {
            entries.put(term.toLowerCase(Locale.ROOT), description);
        }
    }
}
