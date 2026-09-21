package tech.neural7.trace2local.spring;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;
import tech.neural7.trace2local.otel.Trace2LocalOtel;
import tech.neural7.trace2local.server.Trace2LocalHttpServer;
import tech.neural7.trace2local.server.Trace2LocalMeta;
import tech.neural7.trace2local.spi.EndpointDescriptor;
import tech.neural7.trace2local.spi.Trace2LocalExtension;

import java.util.List;

/**
 * Autoconfiguração do modo Embedded (ADR-002): uma dependência, zero
 * configuração para o caso comum — {@code http://localhost:9876/trace2local}.
 * Wire-up: pipeline (ring buffer → assembler) → SDK OTel com o SpanProcessor do
 * Trace2Local acrescentado → servidor REST+SSE com catálogo e launcher.
 */
@AutoConfiguration
@EnableConfigurationProperties(Trace2LocalProperties.class)
@Conditional(Trace2LocalEnabledCondition.class)
@ConditionalOnProperty(prefix = "trace2local", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Trace2LocalAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(Trace2LocalAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public Trace2LocalConfig traceVantaConfig(Trace2LocalProperties properties, Environment environment) {
        boolean dev = ProductionGuard.isDevProfile(environment);
        if (!dev) {
            // D-3: a elevação de ReturnValues é de DEV — desligada em qualquer outro
            // contexto, mesmo com o escape i-know-what-im-doing (GATE-1-DECISOES)
            properties.setDynamoDbCaptureBefore(false);
        }
        // falha de boot explícita quando enabled=true é forçado fora de dev sem escape (SPEC §8.4)
        if (!dev && properties.isEnabled() && !properties.isIKnowWhatImDoing()) {
            throw new IllegalStateException(
                    """
                    Trace2Local é uma ferramenta de DEV-TIME e NÃO DEVE subir em produção (SPEC §8.4).
                    trace2local.enabled=true foi forçado fora de um perfil de desenvolvimento.
                    Se você entende o risco, defina trace2local.i-know-what-im-doing=true —
                    ou remova a dependência/use <scope>provided</scope> no artefato de produção.
                    """);
        }
        return properties.toConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public Trace2LocalPipeline traceVantaPipeline(Trace2LocalConfig cfg) {
        Trace2LocalPipeline pipeline = Trace2LocalPipeline.start(cfg);
        // sink compartilhado com o configurer SPI (quando o SDK é do autoconfigure do OTel)
        Trace2LocalBridge.set(pipeline.buffer()::offer, cfg);
        return pipeline;
    }

    /**
     * SDK OTel próprio — criado apenas quando a app NÃO traz o autoconfigure do
     * OTel; quando traz, o processor é anexado pelo {@link Trace2LocalOtelConfigurer}
     * (ServiceLoader) e o sink chega via {@link Trace2LocalBridge}. Em modo
     * Companion (station.endpoint ou Lambda), acrescenta o exportador OTLP/HTTP
     * para o Station (ADR-002).
     */
    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    @ConditionalOnMissingClass("io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration")
    public OpenTelemetrySdk traceVantaOpenTelemetry(Trace2LocalPipeline pipeline, Trace2LocalConfig cfg) {
        List<io.opentelemetry.sdk.trace.export.SpanExporter> exporters = new java.util.ArrayList<>();
        String station = cfg.stationEndpoint();
        if (station != null && !station.isBlank()) {
            String base = station.endsWith("/") ? station : station + "/";
            var exporterBuilder = io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.builder()
                    .setEndpoint(base + "v1/traces");
            if (cfg.stationToken() != null && !cfg.stationToken().isBlank()) {
                exporterBuilder.addHeader("Authorization", "Bearer " + cfg.stationToken());
            }
            exporters.add(exporterBuilder.build());
            LOG.info("modo Companion detectado (trace2local.station.endpoint): exportando OTLP para {}", base + "v1/traces");
        }
        return Trace2LocalOtel.buildAndRegister(cfg, pipeline.buffer()::offer, exporters);
    }

    /**
     * Span raiz HTTP sem agente — registrado só quando a instrumentação Spring
     * do OTel não está presente (evita span raiz duplicado).
     */
    @Bean
    @ConditionalOnMissingClass("io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry")
    public OncePerRequestFilter traceVantaWebFilter(Trace2LocalConfig cfg) {
        return new Trace2LocalWebFilter(cfg);
    }

    /** Nós de negócio via @Trace2Local (AOP declarativo — sem bytecode em runtime). */
    @Bean
    public Trace2LocalAspect traceVantaAspect(Trace2LocalConfig cfg) {
        return new Trace2LocalAspect(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public EndpointCatalog endpointCatalog(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return new EndpointCatalog(new EndpointCatalog.RequestMappingInfoHandlerMappingProvider() {
            @Override
            public java.util.Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> handlerMethods() {
                RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
                if (mapping == null) {
                    LOG.debug("RequestMappingHandlerMapping indisponível — catálogo vazio");
                    return java.util.Map.of();
                }
                return mapping.getHandlerMethods();
            }
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestLauncher traceVantaRequestLauncher(
            EndpointCatalog catalog,
            @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int serverPort) {
        return new RequestLauncher(catalog::discover, serverPort);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(Trace2LocalEmbeddedModeCondition.class)
    public Trace2LocalHttpServer traceVantaHttpServer(
            Trace2LocalConfig cfg,
            Trace2LocalPipeline pipeline,
            Trace2LocalProperties properties,
            Environment environment,
            ObjectProvider<EndpointCatalog> catalog,
            ObjectProvider<RequestLauncher> launcher,
            @org.springframework.beans.factory.annotation.Value("${spring.application.name:trace2local-app}") String appName) {
        Trace2LocalHttpServer server = Trace2LocalHttpServer.builder(cfg, pipeline)
                .meta(() -> Trace2LocalMeta.embedded(appName))
                .endpoints(() -> {
                    List<EndpointDescriptor> discovered = catalog.getIfAvailable() != null
                            ? catalog.getObject().discover() : List.of();
                    // SPI: extensões também podem contribuir endpoints (SPEC §4.7/§4.8)
                    java.util.ArrayList<EndpointDescriptor> all = new java.util.ArrayList<>(discovered);
                    for (Trace2LocalExtension extension : tech.neural7.trace2local.internal.Extensions.all()) {
                        try {
                            all.addAll(extension.discoverEndpoints());
                        } catch (Throwable ignored) {
                            // extensão quebrada não derruba o catálogo
                        }
                    }
                    return all;
                })
                .launcher(launcher.getIfAvailable())
                .build();
        server.start();
        bootBanner(cfg, properties, environment, server);
        return server;
    }

    private void bootBanner(Trace2LocalConfig cfg, Trace2LocalProperties properties,
                            Environment environment, Trace2LocalHttpServer server) {
        LOG.info("Trace2Local em http://{}:{}{} — modo Embedded | redaction={} | capture-before={}",
                cfg.bindAddress(), server.port(), cfg.basePath(),
                cfg.redactionMode(), cfg.dynamoDbCaptureBefore());
        if (!cfg.allowNonLoopback() && !isLoopback(cfg.bindAddress())) {
            LOG.warn("bind {} fora do loopback SEM trace2local.allow-non-loopback=true — quem alcança a porta, alcança a UI (SPEC §8.1)",
                    cfg.bindAddress());
        }
        if (cfg.allowNonLoopback()) {
            LOG.warn("trace2local.allow-non-loopback=true: a UI está exposta além do loopback, SEM autenticação — consequência declarada no README (ADR-007)");
        }
        if (cfg.dynamoDbCaptureBefore()) {
            LOG.warn("captura de delta do DynamoDB ligada: o interceptor ELEVA ReturnValues=ALL_OLD em escritas e restaura a resposta (ADR-003/R-01). Desligue com trace2local.aws.dynamodb.capture-before=false");
        }
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address) || "::1".equals(address);
    }
}
