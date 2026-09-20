package tech.neural7.tracevanta.server;

import tech.neural7.tracevanta.internal.ExecutionStore;
import tech.neural7.tracevanta.internal.LiveEvent;
import tech.neural7.tracevanta.model.Execution;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hub SSE (ADR-004): um único stream por aba multiplexando todos os tipos de
 * evento; {@code id:} monotônico; {@code retry: 2000}; heartbeat de comentário a
 * cada 15 s; coalescência de no máximo 20 frames/s por execução (tick de 50 ms).
 * Na (re)conexão o servidor reenvia snapshots completos das execuções recentes —
 * retomada simples e robusta, equivalente ao {@code Last-Event-ID}.
 */
public final class SseHub implements java.util.function.Consumer<LiveEvent>, AutoCloseable {

    private static final long TICK_MS = 50;          // 20 frames/s
    private static final long HEARTBEAT_MS = 15_000;
    private static final String HEARTBEAT = ": ping\n\n";

    private final ExecutionStore store;
    private final long tickMs;
    private final long heartbeatMs;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(1);
    private final Thread flusher;
    private volatile boolean closed;

    public SseHub(ExecutionStore store) {
        this(store, TICK_MS, HEARTBEAT_MS);
    }

    /** Intervalos configuráveis para teste (coalescência/heartbeat). */
    SseHub(ExecutionStore store, long tickMs, long heartbeatMs) {
        this.store = store;
        this.tickMs = tickMs;
        this.heartbeatMs = heartbeatMs;
        this.flusher = Thread.ofVirtual().name("tracevanta-sse-hub").unstarted(this::run);
    }

    public void start() {
        flusher.start();
    }

    /** Registra um assinante (uma conexão HTTP) e envia os snapshots iniciais. */
    public void subscribe(OutputStream out, Runnable onDisconnect) {
        Subscriber subscriber = new Subscriber(out, onDisconnect);
        subscribers.add(subscriber);
        writeSnapshot(subscriber);
    }

    public int connectedClients() {
        return subscribers.size();
    }

    @Override
    public void accept(LiveEvent event) {
        if (closed) {
            return;
        }
        Frame frame = toFrame(event);
        if (frame == null) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            subscriber.offer(frame);
        }
    }

    private Frame toFrame(LiveEvent event) {
        return switch (event) {
            case LiveEvent.ExecutionStarted s -> new Frame("execution.started",
                    JsonCodec.MAPPER.valueToTree(s), s.executionId());
            case LiveEvent.NodeUpserted u -> new Frame("node.upserted",
                    JsonCodec.MAPPER.valueToTree(u), u.executionId());
            case LiveEvent.NodeMutation m -> new Frame("node.mutation",
                    JsonCodec.MAPPER.valueToTree(m), m.executionId());
            case LiveEvent.ExecutionCompleted c -> new Frame("execution.completed",
                    JsonCodec.MAPPER.valueToTree(c), c.executionId());
            case LiveEvent.SystemWarning w -> new Frame("system.warning",
                    JsonCodec.MAPPER.valueToTree(w.warning()), "*");
        };
    }

    private void writeSnapshot(Subscriber subscriber) {
        try {
            List<Execution> executions = new ArrayList<>();
            store.recent(50).forEach(summary -> store.get(summary.executionId()).ifPresent(executions::add));
            for (Execution execution : executions) {
                Frame frame = new Frame("execution.snapshot",
                        JsonCodec.MAPPER.createObjectNode().put("executionId", execution.executionId())
                                .set("execution", JsonCodec.MAPPER.valueToTree(execution)),
                        execution.executionId());
                subscriber.offer(frame);
            }
        } catch (Throwable ignored) {
            // snapshot é best-effort
        }
    }

    // ---------------------------------------------------------------- flusher

    private void run() {
        while (!closed) {
            try {
                for (Subscriber subscriber : subscribers) {
                    flush(subscriber);
                    if (System.currentTimeMillis() - subscriber.lastWriteMs >= heartbeatMs) {
                        subscriber.writeRaw(HEARTBEAT);
                    }
                }
                Thread.sleep(tickMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flush(Subscriber subscriber) {
        List<Frame> batch = subscriber.drain();
        if (batch.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(batch.size() * 96);
        for (Frame frame : batch) {
            sb.append("event: ").append(frame.event()).append('\n')
              .append("data: ").append(frame.data().toString()).append('\n')
              .append("id: ").append(frame.sequence()).append('\n')
              .append('\n');
        }
        subscriber.writeRaw(sb.toString());
    }

    // ---------------------------------------------------------------- subscriber

    private final class Subscriber {
        private final OutputStream out;
        private final Runnable onDisconnect;
        // key = executionId|type|nodeId — coalesce: último upsert de um nó vence no tick
        private final Map<String, Frame> pending = new LinkedHashMap<>();
        private volatile long lastWriteMs = System.currentTimeMillis();

        Subscriber(OutputStream out, Runnable onDisconnect) {
            this.out = out;
            this.onDisconnect = onDisconnect;
        }

        void offer(Frame frame) {
            String key = frame.event().equals("node.upserted")
                    ? "u|" + frame.executionId() + "|" + frame.data().get("node").get("nodeId").asText()
                    : frame.event() + "|" + frame.executionId() + "|" + sequence.incrementAndGet();
            synchronized (pending) {
                if (frame.event().equals("node.upserted") || frame.event().equals("node.mutation")) {
                    pending.put(key, frame.withSequence(sequence.incrementAndGet()));
                } else {
                    pending.put(key, frame.withSequence(sequence.incrementAndGet()));
                }
            }
        }

        List<Frame> drain() {
            synchronized (pending) {
                if (pending.isEmpty()) {
                    return List.of();
                }
                List<Frame> batch = new ArrayList<>(pending.values());
                pending.clear();
                return batch;
            }
        }

        void writeRaw(String payload) {
            try {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastWriteMs = System.currentTimeMillis();
            } catch (IOException disconnected) {
                disconnect();
            }
        }

        void disconnect() {
            subscribers.remove(this);
            onDisconnect.run();
        }
    }

    record Frame(String event, com.fasterxml.jackson.databind.JsonNode data, String executionId, long sequence) {
        Frame(String event, com.fasterxml.jackson.databind.JsonNode data, String executionId) {
            this(event, data, executionId, 0);
        }

        Frame withSequence(long seq) {
            return new Frame(event, data, executionId, seq);
        }
    }

    @Override
    public void close() {
        closed = true;
        flusher.interrupt();
        for (Subscriber subscriber : subscribers) {
            subscriber.disconnect();
        }
    }
}
