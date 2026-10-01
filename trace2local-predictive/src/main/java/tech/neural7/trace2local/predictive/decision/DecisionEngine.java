package tech.neural7.trace2local.predictive.decision;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Motor de decisão em CASCATA (ADR-011): {@code cassete → Jev → determinístico}.
 * Cada pergunta sai respondida — o que muda é a procedência declarada.
 *
 * <p>Proteções corporativas: disjuntor (3 falhas seguidas abrem por 60 s; chave
 * recusada abre por 10 min), limite de requisições/minuto, teto diário de tokens
 * de entrada, e telemetria sem conteúdo (contagens, latência, motivos de falha).
 */
public final class DecisionEngine {

    private static final Logger LOG = Logger.getLogger(DecisionEngine.class.getName());

    private final IntelligenceConfig cfg;
    private final JevHttpModel jev;
    private final JevCassetteModel cassette;

    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private volatile long circuitOpenUntil;
    private volatile int consecutiveFailures;
    private volatile String lastFailure;
    private volatile LocalDate tokenDay = LocalDate.now(ZoneOffset.UTC);
    private final AtomicLong tokensToday = new AtomicLong();
    private final AtomicLong liveCalls = new AtomicLong();
    private final AtomicLong liveFailures = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final Map<String, AtomicLong> answersByEngine = new LinkedHashMap<>();

    public DecisionEngine(IntelligenceConfig cfg) {
        this.cfg = cfg;
        IntelligenceConfig.Mode mode = cfg.effectiveMode();
        this.jev = cfg.hasKey() && (mode == IntelligenceConfig.Mode.LIVE || mode == IntelligenceConfig.Mode.RECORD)
                ? new JevHttpModel(cfg) : null;
        this.cassette = mode == IntelligenceConfig.Mode.RECORD || mode == IntelligenceConfig.Mode.REPLAY
                ? new JevCassetteModel(cfg.cassette(), cfg.model()) : null;
        if (jev != null) {
            LOG.warning("Inteligência Jev ATIVA (" + cfg + "): metadados " + cfg.egress().name().toLowerCase()
                    + " e REDIGIDOS das execuções saem para " + hostOf(cfg.endpoint())
                    + " — desligue com TRACE2LOCAL_JEV_ENABLED=false (ADR-011)");
        }
    }

    public IntelligenceConfig config() {
        return cfg;
    }

    /**
     * Responde todas as perguntas. {@code deterministic} é o modelo de último
     * recurso já contextualizado com os fatos da execução.
     */
    public Map<String, Answer> ask(Map<String, String> state, List<Question> questions, DecisionModel deterministic) {
        Map<String, Answer> out = new LinkedHashMap<>();
        if (questions.isEmpty() || cfg.effectiveMode() == IntelligenceConfig.Mode.OFF) {
            return out;
        }
        List<Question> pending = new ArrayList<>(questions);
        if (cassette != null) {
            Map<String, Answer> replayed = cassette.decide(state, pending);
            out.putAll(replayed);
            pending.removeIf(q -> replayed.containsKey(q.id()));
        }
        if (jev != null && !pending.isEmpty()) {
            try {
                admit(pending.size());
                long t0 = System.nanoTime();
                // EGRESSO: só o estado sanitizado sai da máquina (redação + pseudônimo de host)
                Map<String, Answer> live = jev.decide(EgressSanitizer.sanitize(state, cfg.egress()), pending);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalLatencyMs.addAndGet(ms);
                liveCalls.incrementAndGet();
                consecutiveFailures = 0;
                lastFailure = null;
                trackTokens();
                out.putAll(live);
                if (cassette != null && cfg.effectiveMode() == IntelligenceConfig.Mode.RECORD) {
                    cassette.record(state, pending, live);
                }
                pending.removeIf(q -> live.containsKey(q.id()));
            } catch (DecisionModel.DecisionException e) {
                onFailure(e);
            }
        }
        if (!pending.isEmpty() && deterministic != null) {
            try {
                out.putAll(deterministic.decide(state, pending));
            } catch (DecisionModel.DecisionException impossible) {
                // o determinístico não lança
            }
        }
        out.values().forEach(a -> count(a.engine()));
        return out;
    }

    private void admit(int questions) throws DecisionModel.DecisionException {
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntil) {
            throw new DecisionModel.DecisionException("circuit-open", "disjuntor aberto após falhas do Jev");
        }
        synchronized (requestTimes) {
            while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() > 60_000) {
                requestTimes.removeFirst();
            }
            int needed = (int) Math.ceil(questions / (double) cfg.maxQuestionsPerRequest());
            if (requestTimes.size() + needed > cfg.maxRequestsPerMinute()) {
                throw new DecisionModel.DecisionException("rate", "limite de requisições/minuto ao Jev");
            }
            for (int i = 0; i < needed; i++) {
                requestTimes.addLast(now);
            }
        }
        rollDay();
        if (tokensToday.get() >= cfg.maxInputTokensPerDay()) {
            throw new DecisionModel.DecisionException("budget", "teto diário de tokens do Jev atingido");
        }
    }

    private void trackTokens() {
        rollDay();
        tokensToday.set(jev.inputTokens() - tokensBeforeToday);
    }

    private volatile long tokensBeforeToday;

    private void rollDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(tokenDay)) {
            tokenDay = today;
            tokensBeforeToday = jev != null ? jev.inputTokens() : 0;
            tokensToday.set(0);
        }
    }

    private void onFailure(DecisionModel.DecisionException e) {
        liveFailures.incrementAndGet();
        lastFailure = e.code() + ": " + e.getMessage();
        if ("rate".equals(e.code()) || "budget".equals(e.code()) || "circuit-open".equals(e.code())) {
            return; // proteção local, não falha do Jev
        }
        consecutiveFailures++;
        if ("auth".equals(e.code())) {
            circuitOpenUntil = System.currentTimeMillis() + 10 * 60_000L;
            LOG.warning("Jev recusou a chave — usando o modelo determinístico por 10 min");
        } else if (consecutiveFailures >= 3) {
            circuitOpenUntil = System.currentTimeMillis() + 60_000L;
            LOG.warning("Jev falhou 3 vezes seguidas (" + e.code() + ") — modelo determinístico por 60 s");
        }
    }

    private synchronized void count(String engine) {
        answersByEngine.computeIfAbsent(engine == null ? "?" : engine, k -> new AtomicLong()).incrementAndGet();
    }

    /** Estado para {@code GET /api/intelligence} — sem a chave, sem conteúdo de execução. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("mode", cfg.mode().name().toLowerCase());
        s.put("effectiveMode", cfg.effectiveMode().name().toLowerCase());
        s.put("keyConfigured", cfg.hasKey());
        s.put("egressEnabled", cfg.egressEnabled());
        s.put("egress", cfg.egress().name().toLowerCase());
        s.put("endpointHost", hostOf(cfg.endpoint()));
        s.put("model", jev != null && jev.lastModelVersion() != null ? jev.lastModelVersion() : cfg.model());
        s.put("acceptThreshold", cfg.acceptThreshold());
        boolean open = System.currentTimeMillis() < circuitOpenUntil;
        s.put("circuit", open ? "aberto" : "fechado");
        s.put("liveCalls", liveCalls.get());
        s.put("liveFailures", liveFailures.get());
        s.put("avgLatencyMs", liveCalls.get() == 0 ? 0 : totalLatencyMs.get() / liveCalls.get());
        s.put("inputTokensToday", tokensToday.get());
        s.put("maxInputTokensPerDay", cfg.maxInputTokensPerDay());
        // US$ 0,042 por milhão de tokens de entrada (preço publicado em set/2026)
        s.put("estimatedCostTodayUsd", Math.round(tokensToday.get() * 0.042 / 1_000_000.0 * 1e6) / 1e6);
        s.put("lastFailure", lastFailure);
        Map<String, Long> engines = new LinkedHashMap<>();
        answersByEngine.forEach((k, v) -> engines.put(k, v.get()));
        s.put("answersByEngine", engines);
        if (cassette != null) {
            s.put("cassette", Map.of("file", cassette.file().toString(), "entries", cassette.size(),
                    "hits", cassette.hits(), "misses", cassette.misses()));
        }
        s.put("deterministicVersion", DeterministicJevModel.VERSION);
        return s;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
