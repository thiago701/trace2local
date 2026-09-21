package tech.neural7.trace2local.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras de dependência da SPEC §4.3, verificadas por ArchUnit em CI (SPEC §10).
 * Violação = build vermelho, não conselho de review.
 */
@AnalyzeClasses(packages = "tech.neural7.trace2local", importOptions = ImportOption.DoNotIncludeTests.class)
public class DependencyRulesTest {

    /** trace2local-core é POJO + JDK: o TVEM, a SPI e a config não conhecem framework. */
    @ArchTest
    public static final ArchRule CORE_HAS_NO_FRAMEWORK_DEPENDENCIES = noClasses()
            .that().resideInAnyPackage(
                    "tech.neural7.trace2local.model..",
                    "tech.neural7.trace2local.spi..",
                    "tech.neural7.trace2local.config..",
                    "tech.neural7.trace2local.internal..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "software.amazon.awssdk..", "io.opentelemetry..")
            .because("trace2local-core é POJO + JDK (SPEC §4.3 — a única biblioteca permitida é Jackson)");

    /** Nenhum módulo depende do starter, exceto o próprio starter. */
    @ArchTest
    public static final ArchRule NOTHING_DEPENDS_ON_THE_STARTER = noClasses()
            .that().resideInAPackage("tech.neural7.trace2local..")
            .and().resideOutsideOfPackage("tech.neural7.trace2local.spring..")
            .should().dependOnClassesThat().resideInAPackage("tech.neural7.trace2local.spring..")
            .because("nenhum módulo DEVE depender do starter, exceto o consumidor final (SPEC §4.3)");

    /** Pacotes `internal` são compartilhados apenas dentro do projeto. */
    @ArchTest
    public static final ArchRule INTERNAL_PACKAGES_STAY_INSIDE_THE_PROJECT = noClasses()
            .that().resideOutsideOfPackage("tech.neural7.trace2local..")
            .should().dependOnClassesThat().resideInAPackage("tech.neural7.trace2local.internal..")
            .allowEmptyShould(true)
            .because("tudo em `internal` muda sem aviso (SPEC §11.3) — no CI a regra também analisa as classes da app");

    /** Caminho de ingest sem I/O (NFR-3): o processor não toca em java.io. */
    @ArchTest
    public static final ArchRule NO_IO_IN_INGEST_PATH = noClasses()
            .that().haveSimpleName("Trace2LocalSpanProcessor")
            .should().dependOnClassesThat().resideInAPackage("java.io..")
            .because("onStart/onEnd não devem fazer I/O (ADR-006 / NFR-3)");

    /** O núcleo (model/spi/config/internal) não conhece o SDK do OTel (ADR-001/ADR-008). */
    @ArchTest
    public static final ArchRule ONLY_THE_BRIDGE_TALKS_TO_THE_OTEL_SDK = noClasses()
            .that().resideInAnyPackage(
                    "tech.neural7.trace2local.model..",
                    "tech.neural7.trace2local.spi..",
                    "tech.neural7.trace2local.config..",
                    "tech.neural7.trace2local.internal..")
            .should().dependOnClassesThat().resideInAPackage("io.opentelemetry.sdk..")
            .because("o núcleo não conhece o SDK do OTel — a ponte é o trace2local-otel (ADR-001/ADR-008)");

    /** ...e o core só enxerga Jackson além do JDK. */
    @org.junit.jupiter.api.Test
    void coreSeesOnlyJdkAndJackson() {
        JavaClasses classes = new ClassFileImporter().importPackages(
                "tech.neural7.trace2local.model",
                "tech.neural7.trace2local.spi",
                "tech.neural7.trace2local.config",
                "tech.neural7.trace2local.internal");
        noClasses()
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "tech.neural7.trace2local..", "java..", "com.fasterxml.jackson..")
                .because("core = POJO + JDK (+ Jackson pelo JsonNode do TVEM — SPEC §4.6)")
                .check(classes);
    }
}
