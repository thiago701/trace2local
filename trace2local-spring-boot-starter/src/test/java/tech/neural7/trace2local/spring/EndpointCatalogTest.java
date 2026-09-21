package tech.neural7.trace2local.spring;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointCatalogTest {

    @RestController
    static class OrdersController {
        @PostMapping("/orders")
        public String create(@RequestBody SchemaInferrerTest.OrderRequest body) {
            return "ok";
        }

        @PostMapping("/health")
        public String health() {
            return "ok";
        }
    }

    @Test
    void discoversMethodsAndInfersSchemas() throws Exception {
        Method create = OrdersController.class.getMethod("create", SchemaInferrerTest.OrderRequest.class);
        Method health = OrdersController.class.getMethod("health");
        HandlerMethod createHandler = new HandlerMethod(new OrdersController(), create);
        HandlerMethod healthHandler = new HandlerMethod(new OrdersController(), health);

        Map<RequestMappingInfo, HandlerMethod> methods = new LinkedHashMap<>();
        methods.put(RequestMappingInfo.paths("/orders")
                        .methods(org.springframework.web.bind.annotation.RequestMethod.POST).build(), createHandler);
        methods.put(RequestMappingInfo.paths("/health")
                        .methods(org.springframework.web.bind.annotation.RequestMethod.POST).build(), healthHandler);

        EndpointCatalog catalog = new EndpointCatalog(() -> methods);
        List<tech.neural7.trace2local.spi.EndpointDescriptor> endpoints = catalog.discover();

        assertThat(endpoints).hasSize(2);
        var orders = endpoints.stream().filter(e -> e.path().equals("/orders")).findFirst().orElseThrow();
        assertThat(orders.endpointId()).isEqualTo("post:/orders");
        assertThat(orders.method()).isEqualTo("POST");
        assertThat(orders.handler()).contains("create");
        // schema do record OrderRequest
        assertThat(orders.requestSchema().path("properties").has("customerId")).isTrue();
        assertThat(orders.sampleBody()).contains("customerId");
        // sem @RequestBody ⇒ sem schema inventado
        var healthEp = endpoints.stream().filter(e -> e.path().equals("/health")).findFirst().orElseThrow();
        assertThat(healthEp.requestSchema()).isNull();
        assertThat(healthEp.sampleBody()).isNull();
    }
}
