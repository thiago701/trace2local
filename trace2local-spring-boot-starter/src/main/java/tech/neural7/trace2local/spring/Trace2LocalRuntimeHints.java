package tech.neural7.trace2local.spring;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import tech.neural7.trace2local.model.DataMutation;
import tech.neural7.trace2local.model.ErrorInfo;
import tech.neural7.trace2local.model.Execution;
import tech.neural7.trace2local.model.ExecutionMetrics;
import tech.neural7.trace2local.model.ExecutionSummary;
import tech.neural7.trace2local.model.FieldDelta;
import tech.neural7.trace2local.model.Node;
import tech.neural7.trace2local.model.Payload;
import tech.neural7.trace2local.model.Warning;
import tech.neural7.trace2local.server.Trace2LocalMeta;
import tech.neural7.trace2local.spi.EndpointDescriptor;
import tech.neural7.trace2local.spi.MutationEvent;

import java.util.List;

/**
 * Metadados de reflexão catalogados em BUILD TIME (ADR-005 / SPEC §9.2): a
 * promessa honesta não é "zero reflexão", é "reflexão catalogada". Registra:
 * os tipos do TVEM serializados por Jackson, os tipos dos contratos REST/SSE,
 * os recursos da UI e os tipos inspecionados pelo catálogo de endpoints.
 */
public class Trace2LocalRuntimeHints implements RuntimeHintsRegistrar {

    private static final List<Class<?>> TVEM_TYPES = List.of(
            Execution.class, Node.class, DataMutation.class, FieldDelta.class,
            ErrorInfo.class, Payload.class, Warning.class, ExecutionMetrics.class,
            ExecutionSummary.class, Trace2LocalMeta.class, EndpointDescriptor.class,
            MutationEvent.class);

    private static final List<String> ENUM_TYPES = List.of(
            "tech.neural7.trace2local.model.NodeKind",
            "tech.neural7.trace2local.model.NodeStatus",
            "tech.neural7.trace2local.model.ExecutionStatus",
            "tech.neural7.trace2local.model.Trigger",
            "tech.neural7.trace2local.model.MutationKind",
            "tech.neural7.trace2local.model.MutationFidelity");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // assets da UI (ADR-005)
        hints.resources().registerPattern("META-INF/resources/trace2local/*");

        // TVEM + contratos, serializados por Jackson
        for (Class<?> type : TVEM_TYPES) {
            hints.reflection().registerType(type,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.PUBLIC_FIELDS);
        }
        for (String enumType : ENUM_TYPES) {
            hints.reflection().registerType(TypeReference.of(enumType), MemberCategory.values());
        }

        // tipos de request/response cujo schema o catálogo inspeciona (records)
        hints.reflection().registerType(TypeReference.of("com.fasterxml.jackson.databind.JsonNode"),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS);

        // proxy JDK do wrapper de DataSource (módulo jdbc) e interceptors
        hints.proxies().registerJdkProxy(javax.sql.DataSource.class);
    }
}
