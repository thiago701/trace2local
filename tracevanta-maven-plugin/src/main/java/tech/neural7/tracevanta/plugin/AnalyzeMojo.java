package tech.neural7.tracevanta.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * {@code mvn tracevanta:analyze} — engenharia reversa (somente leitura):
 * escaneia o bytecode compilado e identifica os recursos que serão mapeados no
 * canvas (endpoints Spring, {@code @TraceVanta}, AWS SDK v2, JDBC) e audita a
 * higiene dos logs com sugestões compatíveis com Datadog/OpenTelemetry.
 * Escreve {@code target/tracevanta/canvas-map.md} e {@code target/tracevanta/report.md}.
 */
@Mojo(name = "analyze", requiresProject = true, threadSafe = true,
        defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private String outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources", readonly = true)
    private String resourcesDirectory;

    @Parameter(defaultValue = "${project.build.directory}/tracevanta", readonly = true)
    private String reportDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        Findings findings;
        try {
            findings = new ClassScanner().scan(Path.of(outputDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException("Falha ao escanear classes em " + outputDirectory, e);
        }
        Path resources = Path.of(resourcesDirectory);
        findings.hasLogbackConfig = exists(resources, "logback-spring.xml") || exists(resources, "logback.xml");
        findings.hasLog4j2Config = exists(resources, "log4j2.xml") || exists(resources, "log4j2-spring.xml");
        findings.logPatternHasTraceIds = anyContentContains(resources, "trace_id");
        findings.hasBusinessGlossary = exists(resources, "tracevanta-business.md");

        try {
            ReportWriter.write(findings, Path.of(reportDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException("Falha ao escrever relatórios", e);
        }

        getLog().info("");
        getLog().info("== TraceVanta: engenharia reversa concluída ==");
        getLog().info("  classes escaneadas : " + findings.scannedClasses);
        getLog().info("  endpoints Spring   : " + findings.endpoints.size());
        for (Findings.Endpoint e : findings.endpoints) {
            getLog().info("    " + e.method() + " " + e.path() + "  <- " + e.handler());
        }
        getLog().info("  @TraceVanta        : " + findings.businessMethods.size());
        getLog().info("  serviços AWS v2    : " + String.join(", ", findings.awsServices));
        getLog().info("  JDBC               : " + (findings.usesJdbc ? "sim" : "não"));
        getLog().info("  System.out/err     : " + findings.systemOutSites.size() + " ocorrência(s)");
        getLog().info("  printStackTrace    : " + findings.printStackTraceSites + " ocorrência(s)");
        getLog().info("  relatórios         : " + reportDirectory + "/report.md e canvas-map.md");
        getLog().info("");
    }

    private static boolean exists(Path resources, String name) {
        return Files.isRegularFile(resources.resolve(name));
    }

    private static boolean anyContentContains(Path resources, String needle) {
        Path dir = resources;
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().matches("logback.*\\.xml|log4j2.*\\.xml"))
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p).contains(needle);
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }
}
