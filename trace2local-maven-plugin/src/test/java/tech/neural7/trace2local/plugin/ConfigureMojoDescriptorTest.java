package tech.neural7.trace2local.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Trava a versão que o {@code configure} grava no {@code pom.xml} do app.
 *
 * <p>Dentro de um Mojo, {@code ${project.version}} é a versão do PROJETO que roda o goal (o app do
 * dev), não a do plugin: {@code configure} num app {@code 0.0.1-SNAPSHOT} adicionava
 * {@code trace2local-bom:0.0.1-SNAPSHOT}, que não existe. O padrão tem de ser {@code ${plugin.version}}.
 * Lê o descritor gerado pelo maven-plugin-plugin ({@code process-classes}, antes dos testes), porque
 * {@code @Parameter} não fica disponível por reflexão.
 */
class ConfigureMojoDescriptorTest {

    private static final Path DESCRIPTOR = Path.of("target/classes/META-INF/maven/plugin.xml");

    @Test
    void configureUsaAVersaoDoPluginEPermiteSobrescrever() throws Exception {
        assertThat(DESCRIPTOR).exists();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(DESCRIPTOR));
        Element versao = parametroDoGoal(doc, "configure", "trace2localVersion");

        assertThat(versao.getAttribute("default-value")).isEqualTo("${plugin.version}");
        assertThat(versao.getTextContent().trim()).isEqualTo("${trace2local.version}");
    }

    private static Element parametroDoGoal(Document doc, String goal, String parametro) {
        NodeList mojos = doc.getElementsByTagName("mojo");
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            if (!goal.equals(texto(mojo, "goal"))) {
                continue;
            }
            Element configuracao = (Element) mojo.getElementsByTagName("configuration").item(0);
            return (Element) configuracao.getElementsByTagName(parametro).item(0);
        }
        throw new AssertionError("goal '" + goal + "' ausente do plugin.xml");
    }

    private static String texto(Element pai, String tag) {
        NodeList nos = pai.getElementsByTagName(tag);
        return nos.getLength() == 0 ? null : nos.item(0).getTextContent().trim();
    }
}
