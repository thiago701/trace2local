package tech.neural7.trace2local.examples.pix.runtime;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import tech.neural7.trace2local.examples.pix.Functions;

import java.util.Map;

/**
 * Ponto de entrada do runtime custom ({@code provided.al2023}): o mesmo binário
 * (JAR + JRE 25 enxuto, ou executável nativo) atende as 3 funções — {@code _HANDLER}
 * escolhe qual ({@code pix-api}, {@code pix-settlement}, {@code pix-notifier}).
 */
public final class Bootstrap {

    private Bootstrap() {}

    public static void main(String[] args) {
        String api = System.getenv("AWS_LAMBDA_RUNTIME_API");
        if (api == null || api.isBlank()) {
            System.err.println("AWS_LAMBDA_RUNTIME_API ausente — este binário roda dentro de uma Lambda (provided.al2023)");
            System.exit(1);
        }
        LambdaRuntimeLoop loop = new LambdaRuntimeLoop(api);
        RequestHandler<Map<String, Object>, Object> handler;
        try {
            handler = Functions.resolve(System.getenv("_HANDLER"));
        } catch (Throwable t) {
            loop.initError(t);
            System.exit(2);
            return;
        }
        loop.run(handler);
    }
}
