package tech.neural7.trace2local.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A UI roda sob CSP estrita ({@code default-src 'self'}, sem {@code 'unsafe-inline'}
 * em script nem em style). Este guard-rail impede regressões que o navegador só
 * acusaria em runtime: atributos {@code style=}, handlers {@code on*=} e scripts
 * inline no HTML, e montagem de estilo/HTML por string nos módulos JS.
 */
public class UiCspComplianceTest {

    private static final Path UI_ROOT = Path.of("..", "trace2local-ui", "src", "main", "resources")
            .toAbsolutePath().normalize();

    private static final Pattern INLINE_STYLE_ATTR = Pattern.compile("\\sstyle\\s*=\\s*[\"']");
    private static final Pattern INLINE_HANDLER = Pattern.compile("<[^>]+\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\ssrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_STYLE_ATTR = Pattern.compile("setAttribute\\(\\s*[\"']style[\"']|style\\s*=\\s*\\\\?[\"']|\\.cssText\\s*=");
    private static final Pattern JS_EVAL = Pattern.compile("\\beval\\s*\\(|new\\s+Function\\s*\\(|document\\.write\\s*\\(");

    @Test
    void htmlHasNoInlineStyleScriptOrHandlers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : files(".html")) {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            find(f, s, INLINE_STYLE_ATTR, violations);
            find(f, s, INLINE_HANDLER, violations);
            find(f, s, INLINE_SCRIPT, violations);
        }
        assertThat(violations).as("HTML incompatível com a CSP estrita").isEmpty();
    }

    @Test
    void javascriptNeverBuildsInlineStylesNorEvaluatesStrings() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : files(".js")) {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            find(f, s, JS_STYLE_ATTR, violations);
            find(f, s, JS_EVAL, violations);
        }
        assertThat(violations).as("JS incompatível com a CSP estrita (use CSSOM el.style.x e textContent)").isEmpty();
    }

    @Test
    void innerHtmlIsConfinedToTheEscapedTooltip() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : files(".js")) {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            long count = Pattern.compile("\\.innerHTML\\s*=").matcher(s).results().count();
            if (count > 0 && !(f.getFileName().toString().equals("core.js") && count == 1)) {
                offenders.add(f.getFileName() + " (" + count + "×)");
            }
        }
        assertThat(offenders).as("innerHTML só no tooltip (conteúdo escapado) — o resto usa textContent").isEmpty();
    }

    private static List<Path> files(String ext) throws IOException {
        try (var stream = Files.walk(UI_ROOT)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(ext)).toList();
        }
    }

    private static void find(Path file, String content, Pattern p, List<String> out) {
        var m = p.matcher(content);
        while (m.find()) {
            int line = content.substring(0, m.start()).split("\n", -1).length;
            out.add(file.getFileName() + ":" + line + " → " + m.group().trim());
        }
    }
}
