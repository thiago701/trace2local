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
 * Offline absoluto da UI (ADR-005 / SPEC §6.2): nenhuma referência externa nos
 * assets — sem CDN, sem fonte remota, sem source map. A única exceção é o
 * namespace XML do SVG, que é um identificador, não uma requisição de rede.
 */
public class UiOfflineTest {

    private static final Pattern URL_REFERENCE = Pattern.compile("https?://[^\\s\"')]+");
    private static final Pattern SVG_NAMESPACE = Pattern.compile("http://www\\.w3\\.org/2000/svg");

    @Test
    void uiAssetsHaveNoExternalReferences() throws IOException {
        Path uiRoot = Path.of("..", "trace2local-ui", "src", "main", "resources").toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();

        try (var stream = Files.walk(uiRoot)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                // remove o namespace SVG (identificador XML, não tráfego de rede)
                String cleaned = SVG_NAMESPACE.matcher(content).replaceAll("");
                var matcher = URL_REFERENCE.matcher(cleaned);
                while (matcher.find()) {
                    violations.add(file.getFileName() + " → " + matcher.group());
                }
            }
        }
        assertThat(violations).as("referências externas nos assets da UI (SPEC §6.2)").isEmpty();
    }
}
