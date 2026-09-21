package tech.neural7.trace2local.server;

import tech.neural7.trace2local.internal.Redactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catálogo de infra/DevOps por engenharia reversa dos ARQUIVOS do projeto:
 * varre {@code *.tf} (recursos AWS + URLs/ARNs), {@code docker-compose*.yml},
 * {@code .env*} e {@code application*.yml/properties} e extrai TODAS as URLs,
 * ARNs, variáveis de ambiente e recursos Terraform com a LOCALIZAÇÃO exata
 * (arquivo:linha) — o detalhamento de onde cada endereço/segredo vive na infra.
 *
 * <p>Segurança: valores de variáveis com chave sensível (senha/token/chave…)
 * são mascarados com {@code [OCULTO]} usando o mesmo critério do Redactor
 * (ADR-007) — o catálogo mostra o NOME e o LOCAL, nunca o segredo.
 */
public final class InfraIndexer {

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern ARN = Pattern.compile("arn:aws:[^\\s\"'<>]+");
    private static final Pattern TF_RESOURCE = Pattern.compile("resource\\s+\"([a-z0-9_]+)\"\\s+\"([^\"]+)\"");
    private static final Pattern ENV_ASSIGN = Pattern.compile("^\\s*-?\\s*([A-Za-z_][A-Za-z0-9_]*)=(.+)$");

    private static final int MAX_FILES = 3000;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_DEPTH = 6;

    /** Uma localização de fonte (arquivo:linha + tipo de arquivo). */
    public record Source(String file, int line, String kind) {}

    /** Uma entrada do catálogo: tipo, nome, valor (mascarado se sensível) e fontes. */
    public record Entry(String type, String name, String value, List<Source> sources) {}

    public List<Entry> scan(List<Path> roots) {
        Map<String, Entry> byKey = new LinkedHashMap<>(); // key = type|name|value
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            walk(root, 0, byKey);
        }
        return new ArrayList<>(byKey.values());
    }

    private void walk(Path dir, int depth, Map<String, Entry> out) {
        if (depth > MAX_DEPTH || out.size() > MAX_FILES) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (Files.isDirectory(path)) {
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules")
                            || name.equals(".idea") || name.equals(".mvn")) {
                        continue;
                    }
                    walk(path, depth + 1, out);
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (Files.size(path) > MAX_FILE_BYTES) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                String kind = kindOf(name);
                if (kind != null) {
                    parse(path, kind, out);
                }
            }
        } catch (IOException ignored) {
            // diretório ilegível é pulado — catálogo best-effort
        }
    }

    private static String kindOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".tf")) {
            return "terraform";
        }
        if (lower.contains("docker-compose") && (lower.endsWith(".yml") || lower.endsWith(".yaml"))) {
            return "compose";
        }
        if (lower.startsWith(".env") || lower.endsWith(".env")) {
            return "env";
        }
        if (lower.startsWith("application") && (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))) {
            return "config";
        }
        return null;
    }

    private void parse(Path file, String kind, Map<String, Entry> out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            String rel = relative(file);

            // recursos terraform: resource "aws_dynamodb_table" "payments"
            if ("terraform".equals(kind)) {
                Matcher tf = TF_RESOURCE.matcher(line);
                while (tf.find()) {
                    String type = tf.group(1).replace("aws_", "");
                    String name = tf.group(2);
                    add(out, "RECURSO_TERRAFORM", type, name, name,
                            new Source(rel, lineNo, kind));
                }
            }

            // URLs e ARNs
            Matcher urls = URL.matcher(line);
            while (urls.find()) {
                String value = urls.group();
                add(out, "URL", "url", value, value, new Source(rel, lineNo, kind));
            }
            Matcher arns = ARN.matcher(line);
            while (arns.find()) {
                String value = arns.group();
                add(out, "ARN", "arn", value, value, new Source(rel, lineNo, kind));
            }

            // variáveis de ambiente (compose - KEY=value, .env KEY=value)
            if ("compose".equals(kind) || "env".equals(kind)) {
                Matcher env = ENV_ASSIGN.matcher(line);
                if (env.find()) {
                    String varName = env.group(1);
                    String rawValue = env.group(2).trim().replaceAll("^[\"']|[\"']$", "");
                    String value = Redactor.isSensitiveKey(varName) ? "[OCULTO]" : rawValue;
                    add(out, "ENV", varName, varName, value, new Source(rel, lineNo, kind));
                }
            }
        }
    }

    private static String relative(Path file) {
        Path cwd = Path.of("").toAbsolutePath();
        try {
            Path abs = file.toAbsolutePath();
            if (abs.startsWith(cwd)) {
                return cwd.relativize(abs).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // fallback abaixo
        }
        return file.toString().replace('\\', '/');
    }

    private static void add(Map<String, Entry> out, String type, String name, String value,
                            String displayValue, Source source) {
        String key = type + "|" + name + "|" + displayValue;
        Entry existing = out.get(key);
        if (existing == null) {
            List<Source> sources = new ArrayList<>();
            sources.add(source);
            out.put(key, new Entry(type, name, displayValue, sources));
        } else if (existing.sources().stream().noneMatch(s -> s.file().equals(source.file()) && s.line() == source.line())) {
            existing.sources().add(source);
        }
    }
}
