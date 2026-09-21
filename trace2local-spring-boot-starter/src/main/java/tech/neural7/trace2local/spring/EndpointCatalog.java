package tech.neural7.trace2local.spring;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import tech.neural7.trace2local.spi.EndpointDescriptor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Descoberta de endpoints (SPEC §4.8): {@code RequestMappingHandlerMapping.getHandlerMethods()}
 * — a mesma API que o {@code /actuator/mappings} usa internamente — que funciona
 * em Native Image porque o AOT do Spring materializa as definições em build time.
 */
public final class EndpointCatalog {

    private final RequestMappingInfoHandlerMappingProvider provider;

    public interface RequestMappingInfoHandlerMappingProvider {
        java.util.Map<RequestMappingInfo, HandlerMethod> handlerMethods();
    }

    public EndpointCatalog(RequestMappingInfoHandlerMappingProvider provider) {
        this.provider = provider;
    }

    public List<EndpointDescriptor> discover() {
        List<EndpointDescriptor> out = new ArrayList<>();
        try {
            for (var entry : provider.handlerMethods().entrySet()) {
                RequestMappingInfo info = entry.getKey();
                HandlerMethod handler = entry.getValue();
                // getPatternValues: inclui templates com {pathVariable} (E3 — "100% dos @RequestMapping")
                for (String path : info.getPatternValues()) {
                    for (var method : info.getMethodsCondition().getMethods()) {
                        Method javaMethod = handler.getMethod();
                        Type bodyType = requestBodyType(javaMethod);
                        var schema = bodyType != null ? SchemaInferrer.schemaOf(bodyType) : null;
                        String sample = bodyType != null ? SchemaInferrer.sampleBodyOf(bodyType) : null;
                        out.add(new EndpointDescriptor(
                                endpointId(method.name(), path),
                                method.name(),
                                path,
                                handler.getBeanType().getSimpleName() + "." + javaMethod.getName(),
                                schema,
                                sample));
                    }
                }
            }
        } catch (Throwable ignored) {
            // catálogo quebrado não pode derrubar o boot (SPEC §7.3)
        }
        out.sort(java.util.Comparator.comparing(EndpointDescriptor::path));
        return List.copyOf(out);
    }

    static String endpointId(String method, String path) {
        return method.toLowerCase(java.util.Locale.ROOT) + ":" + path;
    }

    private static Type requestBodyType(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return parameter.getParameterizedType();
            }
        }
        return null;
    }
}
