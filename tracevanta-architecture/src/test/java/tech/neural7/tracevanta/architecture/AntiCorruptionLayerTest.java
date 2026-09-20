package tech.neural7.tracevanta.architecture;

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
 * Fronteira anti-corrupção do ADR-008, verificada no CÓDIGO-FONTE: nenhum nome
 * de atributo do OpenTelemetry ({@code db.}, {@code aws.}, {@code messaging.},
 * {@code rpc.}, {@code http.}) pode aparecer como literal fora do módulo
 * {@code tracevanta-otel}. Os produtores de span usam as constantes de
 * {@code OtelAttributeNames}.
 */
public class AntiCorruptionLayerTest {

    /**
     * Literal de atributo OTel, com aspa inicial (ex.: db.system.name, aws.sqs.queue.url,
     * code.namespace). O ramo aws exige um SERVIÇO conhecido (dynamodb|sqs|sns|s3|lambda|…)
     * para não flagrar propriedades do próprio SDK AWS (aws.region, aws.accessKeyId…).
     */
    private static final Pattern ATTRIBUTE_LITERAL = Pattern.compile(
            "\"(db|messaging|rpc|http|code)\\.[a-z0-9_.]+\""
            + "|\"aws\\.(dynamodb|sqs|sns|s3|lambda|log|eks|ecs|ec2|kinesis|bedrock|sagemaker|sdk)\\.[a-z0-9_.]+\"");

    @Test
    void noOtelAttributeLiteralsOutsideTheBridge() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        List<Path> violations = new ArrayList<>();

        try (var stream = Files.walk(repoRoot)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = repoRoot.relativize(path);
                // o módulo de ponte é o ÚNICO lugar onde os nomes existem
                if (relative.startsWith("tracevanta-otel")) {
                    continue;
                }
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (ATTRIBUTE_LITERAL.matcher(line).find()) {
                        violations.add(path);
                        break;
                    }
                }
            }
        }
        assertThat(violations)
                .as("nomes de atributo OTel fora de tracevanta-otel (ADR-008)")
                .isEmpty();
    }
}
