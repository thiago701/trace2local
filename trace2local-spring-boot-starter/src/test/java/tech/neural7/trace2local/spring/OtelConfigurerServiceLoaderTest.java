package tech.neural7.trace2local.spring;

import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressão: o arquivo {@code META-INF/services} apontava para uma classe do nome anterior do projeto,
 * que não existe mais — apps com o autoconfigure do OTel
 * recebiam {@code ServiceConfigurationError} e o processor do Trace2Local nunca era anexado.
 */
class OtelConfigurerServiceLoaderTest {

    @Test
    void serviceLoaderFindsTheTrace2LocalConfigurer() {
        List<SdkTracerProviderConfigurer> found = new ArrayList<>();
        // iterar dispara ServiceConfigurationError se alguma entrada não existir
        ServiceLoader.load(SdkTracerProviderConfigurer.class).forEach(found::add);
        assertThat(found).hasAtLeastOneElementOfType(Trace2LocalOtelConfigurer.class);
    }
}
