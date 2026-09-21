package tech.neural7.trace2local.plugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Engenharia reversa: endpoints, negócio, integrações e higiene de logs. */
class ClassScannerTest {

    @Test
    void discoversEndpointsBusinessMethodsAndIntegrations() throws IOException {
        Path classes = Path.of("target", "test-classes");
        Findings f = new ClassScanner().scan(classes);

        assertThat(f.scannedClasses).isGreaterThan(5);
        assertThat(f.endpoints).anySatisfy(e -> {
            assertThat(e.method()).isEqualTo("POST");
            assertThat(e.path()).isEqualTo("/pix/create");
            assertThat(e.handler()).contains("SampleController#create");
        });
        assertThat(f.endpoints).anySatisfy(e -> assertThat(e.path()).isEqualTo("/pix/{id}"));
        assertThat(f.businessMethods).anyMatch(b -> b.contains("SampleService#process")
                && b.contains("ProcessarPagamento"));
        assertThat(f.awsServices).contains("dynamodb");
        assertThat(f.usesJdbc).isTrue();
        assertThat(f.slf4jUsages).isGreaterThan(0);
        assertThat(f.systemOutSites).anyMatch(s -> s.contains("SampleService"));
        assertThat(f.printStackTraceSites).isGreaterThan(0);
    }
}
