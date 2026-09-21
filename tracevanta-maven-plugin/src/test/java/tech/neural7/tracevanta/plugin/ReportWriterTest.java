package tech.neural7.tracevanta.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    @Test
    void writesCanvasMapAndReportWithLogSuggestions(@TempDir Path dir) throws IOException {
        Findings f = new Findings();
        f.endpoints.add(new Findings.Endpoint("POST", "/pix", "SampleController#create"));
        f.businessMethods.add("SampleService#process (@ProcessarPagamento)");
        f.awsServices.add("dynamodb");
        f.systemOutSites.add("SampleService#process");
        f.slf4jUsages = 1;
        f.usesJdbc = true;
        f.scannedClasses = 12;

        ReportWriter.write(f, dir);

        String map = Files.readString(dir.resolve("canvas-map.md"));
        assertThat(map).contains("POST").contains("/pix").contains("SampleController#create");
        assertThat(map).contains("@ProcessarPagamento");
        assertThat(map).contains("dynamodb").contains("nó SQL");

        String report = Files.readString(dir.resolve("report.md"));
        assertThat(report).contains("Substitua `System.out` por SLF4J");
        assertThat(report).contains("tracevanta:configure");
        assertThat(report).contains("dd.trace_id").contains("trace_id").contains("Datadog");
    }
}
