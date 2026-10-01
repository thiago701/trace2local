package tech.neural7.trace2local.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nome do projeto: <b>Trace2Local</b>. Trava duas regressões da renomeação do projeto (ADR-010, GATE 1 D-5):
 * <ol>
 *   <li>nenhum resto do nome antigo em código, recursos, build ou scripts (o histórico em
 *       {@code docs/} e no {@code CHANGELOG.md} é preservado de propósito);</li>
 *   <li>toda entrada de {@code META-INF/services} aponta para uma classe que existe no repositório —
 *       um provider inexistente vira {@code ServiceConfigurationError} na app do usuário.</li>
 * </ol>
 */
public class ProjectNamingTest {

    private static final Pattern LEGACY_NAME = Pattern.compile("(?i)trace[\\s_-]?v[ae]nta");
    private static final Set<String> SKIPPED_DIRS = Set.of("target", "node_modules", ".git", ".terraform", "docs", ".idea");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".xml", ".yml", ".yaml", ".properties", ".json", ".js", ".mjs", ".css", ".html",
            ".md", ".sh", ".ps1", ".cmd", ".py", ".tf", ".sql", ".toml", ".imports", ".factories");

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void noLegacyProjectNameOutsideTheHistory() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = walk()) {
            for (Path file : files.toList()) {
                Path rel = REPO.relativize(file);
                if (rel.toString().equals("CHANGELOG.md") || !isText(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (LEGACY_NAME.matcher(lines.get(i)).find()) {
                        violations.add(rel + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(violations).as("nome antigo do projeto (use Trace2Local / trace2local)").isEmpty();
    }

    @Test
    void everyServiceProviderEntryPointsToAnExistingClass() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = walk()) {
            for (Path file : files.filter(p -> p.getParent() != null
                    && p.getParent().endsWith(Path.of("META-INF", "services"))).toList()) {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String fqcn = raw.replaceAll("#.*", "").trim();
                    if (fqcn.isEmpty() || !fqcn.startsWith("tech.neural7.")) {
                        continue;
                    }
                    if (!sourceExists(fqcn)) {
                        missing.add(REPO.relativize(file) + " → " + fqcn);
                    }
                }
            }
        }
        assertThat(missing).as("providers de ServiceLoader sem classe correspondente").isEmpty();
    }

    private static boolean sourceExists(String fqcn) throws IOException {
        String outer = fqcn.contains("$") ? fqcn.substring(0, fqcn.indexOf('$')) : fqcn;   // classe aninhada
        String suffix = outer.replace('.', '/') + ".java";
        try (Stream<Path> files = walk()) {
            return files.anyMatch(p -> p.toString().replace('\\', '/').endsWith("/src/main/java/" + suffix));
        }
    }

    private static Stream<Path> walk() throws IOException {
        return Files.walk(REPO)
                .filter(p -> REPO.relativize(p).toString().split("[/\\\\]").length > 0)
                .filter(p -> {
                    for (Path part : REPO.relativize(p)) {
                        if (SKIPPED_DIRS.contains(part.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .filter(Files::isRegularFile);
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        if (name.equals("Dockerfile") || name.startsWith("Dockerfile.") || name.startsWith("bootstrap-")
                || name.equals("mvnw") || file.getParent() != null && file.getParent().endsWith(Path.of("META-INF", "services"))) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot));
    }
}
