package tech.neural7.trace2local.examples.pix.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import tech.neural7.trace2local.examples.pix.Functions;
import tech.neural7.trace2local.examples.pix.infra.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Invoca uma função FORA da Lambda (mesmo código, sem Runtime API): depuração local
 * e treino do {@code native-image-agent} — que registra a reflexão/recursos/proxies
 * realmente usados pelos caminhos de código das jornadas.
 *
 * <pre>
 * java -cp finance-pix.jar tech.neural7.trace2local.examples.pix.runtime.LocalInvoke pix-api events/create.json [repetições]
 * </pre>
 */
public final class LocalInvoke {

    private LocalInvoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: LocalInvoke <pix-api|pix-settlement|pix-notifier> <evento.json> [repetições]");
            System.exit(1);
        }
        var handler = Functions.resolve(args[0]);
        String text = Files.readString(Path.of(args[1]));
        int times = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        for (int i = 0; i < times; i++) {
            // placeholders para chaves únicas por repetição
            String json = text.replace("${uuid}", UUID.randomUUID().toString());
            Map<String, Object> event = Json.MAPPER.readValue(json, new TypeReference<>() {});
            Object result = handler.handleRequest(event, new RuntimeContext(UUID.randomUUID().toString(),
                    System.currentTimeMillis() + 30_000, "arn:aws:lambda:local:000000000000:function:" + args[0]));
            System.out.println(Json.write(result));
        }
        System.exit(0);
    }
}
