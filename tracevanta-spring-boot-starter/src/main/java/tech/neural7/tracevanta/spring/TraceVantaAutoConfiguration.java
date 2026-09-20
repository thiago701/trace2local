package tech.neural7.tracevanta.spring;

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
import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;
import tech.neural7.tracevanta.otel.TraceVantaOtel;
import tech.neural7.tracevanta.server.TraceVantaHttpServer;
import tech.neural7.tracevanta.server.TraceVantaMeta;
import tech.neural7.tracevanta.spi.EndpointDescriptor;
import tech.neural7.tracevanta.spi.TraceVantaExtension;

import java.util.List;

/**
 * Autoconfiguração do modo Embedded (ADR-002): uma dependência, zero
 * configuração para o caso comum — {@code http://localhost:9876/tracevanta}.
 * Wire-up: pipeline (ring buffer → assembler) → SDK OTel com o SpanProcessor do
 * TraceVanta acrescentado → servidor REST+SSE com catálogo e launcher.
 */
@AutoConfiguration
@EnableConfigurationProperties(TraceVantaProperties.class)
@Conditional(TraceVantaEnabledCondition.class)
@ConditionalOnProperty(prefix = "tracevanta", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TraceVantaAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TraceVantaAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TraceVantaConfig traceVantaConfig(TraceVantaProperties properties, Environment environment) {
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
                    TraceVanta é uma ferramenta de DEV-TIME e NÃO DEVE subir em produção (SPEC §8.4).
                    tracevanta.enabled=true foi forçado fora de um perfil de desenvolvimento.
                    Se você entende o risco, defina tracevanta.i-know-what-im-doing=true —
                    ou remova a dependência/use <scope>provided</scope> no artefato de produção.
                    """);
        }
        return properties.toConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceVantaPipeline traceVantaPipeline(TraceVantaConfig cfg) {
        TraceVantaPipeline pipeline = TraceVantaPipeline.start(cfg);
        // sink compartilhado com o configurer SPI (quando o SDK é do autoconfigure do OTel)
        TraceVantaBridge.set(pipeline.buffer()::offer, cfg);
        return pipeline;
    }

    /**
     * SDK OTel próprio — criado apenas quando a app NÃO traz o autoconfigure do
     * OTel; quando traz, o processor é anexado pelo {@link TraceVantaOtelConfigurer}
     * (ServiceLoader) e o sink chega via {@link TraceVantaBridge}. Em modo
     * Companion (station.endpoint ou Lambda), acrescenta o exportador OTLP/HTTP
     * para o Station (ADR-002).
     */
    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    @ConditionalOnMissingClass("io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration")
    public OpenTelemetrySdk traceVantaOpenTelemetry(TraceVantaPipeline pipeline, TraceVantaConfig cfg) {
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
            LOG.info("modo Companion detectado (tracevanta.station.endpoint): exportando OTLP para {}", base + "v1/traces");
        }
        return TraceVantaOtel.buildAndRegister(cfg, pipeline.buffer()::offer, exporters);
    }

    /**
     * Span raiz HTTP sem agente — registrado só quando a instrumentação Spring
     * do OTel não está presente (evita span raiz duplicado).
     */
    @Bean
    @ConditionalOnMissingClass("io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry")
    public OncePerRequestFilter traceVantaWebFilter(TraceVantaConfig cfg) {
        return new TraceVantaWebFilter(cfg);
    }

    /** Nós de negócio via @TraceVanta (AOP declarativo — sem bytecode em runtime). */
    @Bean
    public TraceVantaAspect traceVantaAspect(TraceVantaConfig cfg) {
        return new TraceVantaAspect(cfg);
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
    @Conditional(TraceVantaEmbeddedModeCondition.class)
    public TraceVantaHttpServer traceVantaHttpServer(
            TraceVantaConfig cfg,
            TraceVantaPipeline pipeline,
            TraceVantaProperties properties,
            Environment environment,
            ObjectProvider<EndpointCatalog> catalog,
            ObjectProvider<RequestLauncher> launcher,
            @org.springframework.beans.factory.annotation.Value("${spring.application.name:tracevanta-app}") String appName) {
        TraceVantaHttpServer server = TraceVantaHttpServer.builder(cfg, pipeline)
                .meta(() -> TraceVantaMeta.embedded(appName))
                .endpoints(() -> {
                    List<EndpointDescriptor> discovered = catalog.getIfAvailable() != null
                            ? catalog.getObject().discover() : List.of();
                    // SPI: extensões também podem contribuir endpoints (SPEC §4.7/§4.8)
                    java.util.ArrayList<EndpointDescriptor> all = new java.util.ArrayList<>(discovered);
                    for (TraceVantaExtension extension : tech.neural7.tracevanta.internal.Extensions.all()) {
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

    private void bootBanner(TraceVantaConfig cfg, TraceVantaProperties properties,
                            Environment environment, TraceVantaHttpServer server) {
        LOG.info("TraceVanta em http://{}:{}{} — modo Embedded | redaction={} | capture-before={}",
                cfg.bindAddress(), server.port(), cfg.basePath(),
                cfg.redactionMode(), cfg.dynamoDbCaptureBefore());
        if (!cfg.allowNonLoopback() && !isLoopback(cfg.bindAddress())) {
            LOG.warn("bind {} fora do loopback SEM tracevanta.allow-non-loopback=true — quem alcança a porta, alcança a UI (SPEC §8.1)",
                    cfg.bindAddress());
        }
        if (cfg.allowNonLoopback()) {
            LOG.warn("tracevanta.allow-non-loopback=true: a UI está exposta além do loopback, SEM autenticação — consequência declarada no README (ADR-007)");
        }
        if (cfg.dynamoDbCaptureBefore()) {
            LOG.warn("captura de delta do DynamoDB ligada: o interceptor ELEVA ReturnValues=ALL_OLD em escritas e restaura a resposta (ADR-003/R-01). Desligue com tracevanta.aws.dynamodb.capture-before=false");
        }
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address) || "::1".equals(address);
    }
}
