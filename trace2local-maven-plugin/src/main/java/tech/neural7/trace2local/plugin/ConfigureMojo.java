package tech.neural7.trace2local.plugin;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code mvn trace2local:configure} — instalador automático: adiciona o BOM e o
 * starter ao {@code pom.xml} (quando ausentes) e cria os arquivos de apoio —
 * {@code trace2local-business.md} (glossário de negócio), {@code application-trace2local.yml}
 * (config) e {@code logback-spring.xml} (padrão de log com correlação
 * Datadog + OpenTelemetry). Nenhum arquivo existente é sobrescrito.
 */
@Mojo(name = "configure", requiresProject = true, threadSafe = true)
public class ConfigureMojo extends AbstractMojo {

    private static final String GROUP = "tech.neural7.trace2local";
    private static final String BOM = "trace2local-bom";
    private static final String STARTER = "trace2local-spring-boot-starter";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Versão do Trace2Local a adicionar (padrão: a do próprio plugin). */
    @Parameter(defaultValue = "${project.version}", property = "trace2local.version")
    private String trace2localVersion;

    @Override
    public void execute() throws MojoExecutionException {
        Path base = project.getBasedir().toPath();
        try {
            Path pom = base.resolve("pom.xml");
            boolean pomChanged = ensurePomDependencies(pom);

            Path resources = base.resolve("src/main/resources");
            Files.createDirectories(resources);

            boolean glossaryCreated = createIfAbsent(resources.resolve("trace2local-business.md"),
                    "# Glossário de negócio (Trace2Local)\n"
                            + "# Descreva os termos do SEU domínio — a aba STORY usa estas notas.\n"
                            + "# - MeuFluxo: O que este passo de negócio faz, em linguagem de time.\n");

            boolean configCreated = createIfAbsent(resources.resolve("application-trace2local.yml"),
                    "# Gerado pelo instalador do Trace2Local (mvn trace2local:configure)\n"
                            + "trace2local:\n"
                            + "  port: 9876            # UI do Trace2Local\n"
                            + "  redaction:\n"
                            + "    mode: strict        # strict | keys | off\n"
                            + "  retention:\n"
                            + "    max-executions: 100\n"
                            + "  aws:\n"
                            + "    dynamodb:\n"
                            + "      capture-before: true\n");

            boolean logbackCreated = createIfAbsent(resources.resolve("logback-spring.xml"),
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<configuration>\n"
                            + "  <appender name=\"CONSOLE\" class=\"ch.qos.logback.core.ConsoleAppender\">\n"
                            + "    <encoder>\n"
                            + "      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - "
                            + "trace_id=%X{trace_id:-} span_id=%X{span_id:-} "
                            + "dd.trace_id=%X{dd.trace_id:-} dd.span_id=%X{dd.span_id:-} - %msg%n</pattern>\n"
                            + "    </encoder>\n"
                            + "  </appender>\n"
                            + "  <root level=\"INFO\">\n"
                            + "    <appender-ref ref=\"CONSOLE\"/>\n"
                            + "  </root>\n"
                            + "</configuration>\n");

            getLog().info("");
            getLog().info("== Trace2Local instalado ==");
            getLog().info("  pom.xml                      : " + (pomChanged ? "BOM + starter adicionados" : "já configurado"));
            getLog().info("  trace2local-business.md       : " + (glossaryCreated ? "criado (edite com os termos do domínio)" : "já existia"));
            getLog().info("  application-trace2local.yml   : " + (configCreated ? "criado" : "já existia"));
            getLog().info("  logback-spring.xml           : " + (logbackCreated
                    ? "criado com correlação Datadog+OTel" : "já existia (não sobrescrito)"));
            getLog().info("");
            getLog().info("Próximos passos:");
            getLog().info("  1. rode a aplicação em perfil dev e abra http://localhost:9876/trace2local");
            getLog().info("  2. dispare uma requisição — os logs agora carregam trace_id/span_id (OTel) e dd.trace_id/dd.span_id (Datadog)");
            getLog().info("  3. rode 'mvn trace2local:analyze' para ver o mapa completo do canvas");
            getLog().info("");
        } catch (Exception e) {
            throw new MojoExecutionException("Falha ao configurar o projeto", e);
        }
    }

    private boolean ensurePomDependencies(Path pom) throws Exception {
        Model model;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            model = new MavenXpp3Reader().read(reader);
        }
        boolean changed = false;

        boolean hasStarter = model.getDependencies().stream()
                .anyMatch(d -> STARTER.equals(d.getArtifactId()) && GROUP.equals(d.getGroupId()));
        if (!hasStarter) {
            Dependency starter = new Dependency();
            starter.setGroupId(GROUP);
            starter.setArtifactId(STARTER);
            starter.setVersion(trace2localVersion);
            model.addDependency(starter);
            changed = true;
        }

        DependencyManagement dm = model.getDependencyManagement();
        if (dm == null) {
            dm = new DependencyManagement();
            model.setDependencyManagement(dm);
        }
        boolean hasBom = dm.getDependencies().stream()
                .anyMatch(d -> BOM.equals(d.getArtifactId()) && GROUP.equals(d.getGroupId())
                        && "pom".equals(d.getType()) && "import".equals(d.getScope()));
        if (!hasBom) {
            Dependency bom = new Dependency();
            bom.setGroupId(GROUP);
            bom.setArtifactId(BOM);
            bom.setVersion(trace2localVersion);
            bom.setType("pom");
            bom.setScope("import");
            dm.addDependency(bom);
            changed = true;
        }

        if (changed) {
            Path backup = pom.resolveSibling("pom.xml.trace2local.bak");
            if (!Files.exists(backup)) {
                Files.copy(pom, backup, StandardCopyOption.REPLACE_EXISTING);
                getLog().info("  backup do pom: " + backup.getFileName());
            }
            try (Writer writer = Files.newBufferedWriter(pom, StandardCharsets.UTF_8)) {
                new MavenXpp3Writer().write(writer, model);
            }
        }
        return changed;
    }

    private boolean createIfAbsent(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }
}
