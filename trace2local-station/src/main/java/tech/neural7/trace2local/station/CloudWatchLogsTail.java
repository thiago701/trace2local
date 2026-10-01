package tech.neural7.trace2local.station;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.neural7.trace2local.internal.JsonSupport;
import tech.neural7.trace2local.internal.LogStore;
import tech.neural7.trace2local.model.LogEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitor contínuo do CloudWatch Logs do LocalStack (ADR-012): a LINHA DO TEMPO
 * da UI passa a mostrar o que a plataforma realmente registrou para cada
 * invocação Lambda — {@code START}/{@code END}/{@code REPORT} reais (com
 * {@code Init Duration}, memória e duração faturada) e o stdout da função.
 *
 * <p>Correlação: dentro de um log stream a Lambda processa UMA invocação por
 * vez, então toda linha entre {@code START RequestId: X} e {@code END RequestId: X}
 * pertence a X; a UI cruza X com o {@code faas.invocation_id} do nó LAMBDA.
 *
 * <p>Escopo honesto: cliente mínimo do protocolo {@code x-amz-json-1.1} SEM
 * assinatura SigV4 válida — serve ao LocalStack (que não valida assinatura) e é
 * desligado por padrão. Ler o CloudWatch da AWS real é fronteira EXTERNA
 * (roadmap), não deste componente.
 */
final class CloudWatchLogsTail implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsTail.class.getName());
    private static final Pattern REQUEST_ID = Pattern.compile("RequestId:\\s*([0-9a-fA-F-]{8,64})");
    private static final Pattern LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL)\\b");
    private static final Pattern EXCEPTION = Pattern.compile("(^|[\\s:])([a-z][\\w$]*\\.)+[A-Z][\\w$]*(Exception|Error)(:|$|\\s)");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int MAX_SEEN_EVENTS = 50_000;

    private final URI endpoint;
    private final String region;
    private final String groupPrefix;
    private final long pollMs;
    private final LogStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Long> cursorByGroup = new HashMap<>();
    private final Map<String, String> currentRequestByStream = new HashMap<>();
    private final Set<String> seenEvents = new HashSet<>();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private final long startedAtMillis = System.currentTimeMillis() - 60_000;
    private volatile boolean running = true;
    private volatile Thread worker;
    private volatile long lastGroupScan;
    private final List<String> groups = new ArrayList<>();
    private volatile long linesRead;
    private volatile String lastError;

    CloudWatchLogsTail(String endpoint, String region, String groupPrefix, long pollMs, LogStore store) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
        this.groupPrefix = groupPrefix == null || groupPrefix.isBlank() ? "/aws/lambda/" : groupPrefix;
        this.pollMs = Math.max(500, pollMs);
        this.store = store;
    }

    /** Liga o leitor se {@code TRACE2LOCAL_CLOUDWATCH_ENDPOINT} estiver definido; senão devolve {@code null}. */
    static CloudWatchLogsTail fromEnv(LogStore store) {
        String endpoint = System.getenv("TRACE2LOCAL_CLOUDWATCH_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String region = System.getenv().getOrDefault("TRACE2LOCAL_CLOUDWATCH_REGION", "us-east-1");
        String prefix = System.getenv().getOrDefault("TRACE2LOCAL_CLOUDWATCH_LOG_GROUP_PREFIX", "/aws/lambda/");
        long poll = parseLong(System.getenv("TRACE2LOCAL_CLOUDWATCH_POLL_MS"), 2000);
        CloudWatchLogsTail tail = new CloudWatchLogsTail(endpoint, region, prefix, poll, store);
        tail.start();
        LOG.info(() -> "CloudWatch Logs (LocalStack) em " + endpoint + " — grupos " + prefix + "* a cada " + poll + " ms");
        return tail;
    }

    void start() {
        worker = Thread.ofVirtual().name("trace2local-cloudwatch-tail").start(this::loop);
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
                lastError = null;
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Uma rodada: (re)descobre grupos e lê eventos novos de cada um. Visível para teste. */
    void pollOnce() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastGroupScan > 15_000 || groups.isEmpty()) {
            refreshGroups();
            lastGroupScan = now;
        }
        for (String group : List.copyOf(groups)) {
            readGroup(group);
        }
    }

    private void refreshGroups() throws Exception {
        ObjectNode body = JsonSupport.MAPPER.createObjectNode();
        body.put("logGroupNamePrefix", groupPrefix);
        body.put("limit", 50);
        JsonNode response = call("DescribeLogGroups", body);
        List<String> found = new ArrayList<>();
        for (JsonNode g : response.path("logGroups")) {
            String name = g.path("logGroupName").asText("");
            if (!name.isBlank()) {
                found.add(name);
            }
        }
        synchronized (groups) {
            groups.clear();
            groups.addAll(found);
        }
    }

    private void readGroup(String group) throws Exception {
        long since = cursorByGroup.getOrDefault(group, startedAtMillis);
        String nextToken = null;
        long maxTs = since;
        int pages = 0;
        do {
            ObjectNode body = JsonSupport.MAPPER.createObjectNode();
            body.put("logGroupName", group);
            body.put("startTime", since);
            body.put("limit", 1000);
            if (nextToken != null) {
                body.put("nextToken", nextToken);
            }
            JsonNode response = call("FilterLogEvents", body);
            List<String[]> page = new ArrayList<>();
            for (JsonNode ev : response.path("events")) {
                long ts = ev.path("timestamp").asLong(System.currentTimeMillis());
                maxTs = Math.max(maxTs, ts);
                String eventId = ev.path("eventId").asText(group + ts + ev.path("message").asText().hashCode());
                if (!remember(eventId)) {
                    continue;
                }
                page.add(new String[] {ev.path("logStreamName").asText(""), Long.toString(ts), ev.path("message").asText("")});
            }
            ingestFolded(group, page);
            nextToken = response.hasNonNull("nextToken") ? response.path("nextToken").asText() : null;
            pages++;
        } while (nextToken != null && pages < 10);
        // reler o último milissegundo é seguro: o eventId deduplica
        cursorByGroup.put(group, maxTs);
    }

    private boolean remember(String eventId) {
        if (!seenEvents.add(eventId)) {
            return false;
        }
        seenOrder.addLast(eventId);
        if (seenOrder.size() > MAX_SEEN_EVENTS) {
            seenEvents.remove(seenOrder.removeFirst());
        }
        return true;
    }

    /**
     * Junta as linhas de continuação (frames {@code "\tat …"}, {@code "Caused by:"},
     * {@code "\t... N more"}) ao evento anterior do MESMO stream: o runtime/LocalStack
     * quebra a stack trace em um evento por linha — na linha do tempo ela volta a ser
     * UMA ocorrência de erro legível (achado do caso real Lambda + LocalStack).
     */
    void ingestFolded(String group, List<String[]> events) {
        String[] pending = null;
        for (String[] ev : events) {
            String msg = ev[2].stripTrailing();
            if (pending != null && pending[0].equals(ev[0]) && isContinuation(msg)
                    && pending[2].length() < 8_000) {
                pending[2] = pending[2] + "\n" + msg;
                continue;
            }
            if (pending != null) {
                ingest(group, pending[0], Long.parseLong(pending[1]), pending[2]);
            }
            pending = new String[] {ev[0], ev[1], msg};
        }
        if (pending != null) {
            ingest(group, pending[0], Long.parseLong(pending[1]), pending[2]);
        }
    }

    static boolean isContinuation(String line) {
        String t = line.stripLeading();
        return (line.startsWith("\t") || line.startsWith("    ")) && (t.startsWith("at ") || t.startsWith("... "))
                || t.startsWith("Caused by:") || t.startsWith("Suppressed:");
    }

    /** Converte um evento do CloudWatch em {@link LogEntry} correlacionável por RequestId. */
    void ingest(String group, String stream, long ts, String rawMessage) {
        String message = rawMessage.stripTrailing();
        if (message.isEmpty()) {
            return;
        }
        String requestId = null;
        boolean platform = message.startsWith("START RequestId:") || message.startsWith("END RequestId:")
                || message.startsWith("REPORT RequestId:") || message.startsWith("INIT_START");
        Matcher m = REQUEST_ID.matcher(message);
        if (m.find()) {
            requestId = m.group(1);
        }
        if (message.startsWith("START RequestId:") && requestId != null) {
            currentRequestByStream.put(stream, requestId);
        } else if (requestId == null) {
            requestId = currentRequestByStream.get(stream);
        }
        if (message.startsWith("END RequestId:")) {
            currentRequestByStream.remove(stream);
        }
        String level = platform ? "PLATFORM" : detectLevel(message);
        store.append(new LogEntry(Instant.ofEpochMilli(ts), level, "cloudwatch", message,
                null, null, requestId, group, stream, LogEntry.LogSource.CLOUDWATCH));
        linesRead++;
    }

    static String detectLevel(String line) {
        String head = line.length() > 120 ? line.substring(0, 120) : line;
        Matcher m = LEVEL.matcher(head);
        if (!m.find()) {
            // sem nível explícito: exceção do runtime ("…: java.lang.XException", stack) é ERRO
            return EXCEPTION.matcher(head).find() || line.contains("\n\tat ") ? "ERROR" : "INFO";
        }
        return switch (m.group(1)) {
            case "WARNING" -> "WARN";
            case "SEVERE", "FATAL" -> "ERROR";
            default -> m.group(1);
        };
    }

    private JsonNode call(String action, ObjectNode body) throws Exception {
        Instant now = Instant.now();
        // cabeçalho no FORMATO SigV4 (o LocalStack extrai região/conta dele; não valida a assinatura)
        String auth = "AWS4-HMAC-SHA256 Credential=test/" + AMZ_DAY.format(now) + "/" + region
                + "/logs/aws4_request, SignedHeaders=host;x-amz-date;x-amz-target, Signature=0";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .header("X-Amz-Date", AMZ_DATE.format(now))
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 && response.body().contains("ResourceNotFoundException")) {
            return JsonSupport.MAPPER.createObjectNode();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(action + " → HTTP " + response.statusCode());
        }
        return JsonSupport.MAPPER.readTree(response.body());
    }

    /** Estado para o endpoint de saúde da linha do tempo. */
    Map<String, Object> status() {
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("endpoint", endpoint.toString());
        s.put("groupPrefix", groupPrefix);
        synchronized (groups) {
            s.put("groups", List.copyOf(groups));
        }
        s.put("linesRead", linesRead);
        s.put("lastError", lastError);
        return s;
    }

    private static long parseLong(String v, long fallback) {
        try {
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void close() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
