package tech.neural7.trace2local.predictive.project;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fotografia dos ARTEFATOS do projeto relevantes para correlação (ADR-013 §4):
 * Terraform por ambiente, cobertura de testes, quality gate, marcadores de
 * resiliência no código e configuração. Lida uma vez pelo worker de projeto
 * (com debounce por mtime) — os analisadores só consultam, nunca fazem I/O.
 *
 * @param scannedRoots    diretórios varridos
 * @param terraform       ambiente → (chave → atribuição)
 * @param coverage        cobertura JaCoCo (ou {@code null} se não houver relatório)
 * @param coverageGate    exigência mínima de cobertura de linha (0..1) e de onde veio, ou {@code null}
 * @param resilience      classe (nome simples) → marcadores de resiliência encontrados no código
 * @param timeouts        chaves de configuração de timeout encontradas (yml/properties/tf)
 * @param sources         classe (nome simples) → arquivo:linha da declaração
 * @param scannedAt       quando
 */
public record ProjectSnapshot(
        List<String> scannedRoots,
        Map<String, Map<String, Assignment>> terraform,
        Coverage coverage,
        Gate coverageGate,
        Map<String, List<Marker>> resilience,
        List<Assignment> timeouts,
        Map<String, Location> sources,
        Instant scannedAt) {

    public static final ProjectSnapshot EMPTY = new ProjectSnapshot(List.of(), Map.of(), null, null, Map.of(),
            List.of(), Map.of(), Instant.EPOCH);

    /** Uma atribuição {@code chave = valor} com localização. */
    public record Assignment(String key, String value, String file, int line) {}

    /** Um marcador de resiliência no código ({@code @Retry}, {@code @CircuitBreaker}, {@code .timeout(…)}). */
    public record Marker(String kind, String file, int line) {}

    public record Location(String file, int line) {}

    /** Cobertura de linhas (total e por pacote) do relatório JaCoCo. */
    public record Coverage(String report, long covered, long missed, Map<String, long[]> byPackage) {
        public double ratio() {
            long total = covered + missed;
            return total == 0 ? 0 : (double) covered / total;
        }
    }

    /** Exigência de cobertura e a fonte dela (pom jacoco:check, sonar, env). */
    public record Gate(double minimum, String source, String file, int line) {}
}
