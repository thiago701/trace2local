package tech.neural7.trace2local.predictive.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cassete do Jev — o caminho DETERMINÍSTICO para o modelo real (ADR-011):
 * no modo {@code record} cada resposta do Jev é gravada sob
 * {@code sha256(modelo | estado canônico | pergunta canônica)}; no modo
 * {@code replay} as mesmas perguntas sobre o mesmo estado devolvem EXATAMENTE a
 * mesma resposta, offline, sem chave e sem custo — reprodutível em CI.
 *
 * <p>Privacidade: o arquivo guarda só o hash e a resposta numérica; o estado
 * (rótulos, erros, logs) nunca é persistido.
 */
public final class JevCassetteModel implements DecisionModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final String model;
    private final Map<String, Answer> byKey = new ConcurrentHashMap<>();
    private volatile long hits;
    private volatile long misses;

    public JevCassetteModel(Path file, String model) {
        this.file = file;
        this.model = model;
        load();
    }

    @Override
    public String name() {
        return Answer.ENGINE_REPLAY;
    }

    /** Responde só o que está gravado; o resto fica para o próximo modelo da cascata. */
    @Override
    public Map<String, Answer> decide(Map<String, String> state, List<Question> questions) {
        Map<String, Answer> out = new LinkedHashMap<>();
        String stateKey = canonicalState(state);
        for (Question q : questions) {
            Answer a = byKey.get(key(stateKey, q));
            if (a != null) {
                hits++;
                out.put(q.id(), new Answer(q.id(), a.type(), a.choice(), a.value(), a.confidence(),
                        a.probabilities(), Answer.ENGINE_REPLAY, a.model(), null));
            } else {
                misses++;
            }
        }
        return out;
    }

    /** Grava respostas do Jev real (modo record). */
    public synchronized void record(Map<String, String> state, List<Question> questions, Map<String, Answer> answers) {
        String stateKey = canonicalState(state);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (Question q : questions) {
                    Answer a = answers.get(q.id());
                    if (a == null || !Answer.ENGINE_JEV.equals(a.engine())) {
                        continue;
                    }
                    String k = key(stateKey, q);
                    if (byKey.putIfAbsent(k, a) != null) {
                        continue;
                    }
                    ObjectNode line = MAPPER.createObjectNode();
                    line.put("k", k);
                    line.put("t", a.type().name());
                    if (a.choice() != null) {
                        line.put("c", a.choice());
                    }
                    line.put("v", a.value());
                    line.put("f", a.confidence());
                    line.put("m", a.model());
                    ObjectNode p = line.putObject("p");
                    a.probabilities().forEach(p::put);
                    w.write(MAPPER.writeValueAsString(line));
                    w.newLine();
                }
            }
        } catch (IOException ignored) {
            // cassete é conveniência: falha de disco não derruba a análise
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (raw.isBlank()) {
                    continue;
                }
                try {
                    JsonNode n = MAPPER.readTree(raw);
                    Map<String, Double> probs = new LinkedHashMap<>();
                    n.path("p").fields().forEachRemaining(e -> probs.put(e.getKey(), e.getValue().asDouble()));
                    Question.Type type = Question.Type.valueOf(n.path("t").asText("NOUL"));
                    byKey.put(n.path("k").asText(), new Answer(null, type, n.hasNonNull("c") ? n.path("c").asText() : null,
                            n.path("v").asDouble(), n.path("f").asDouble(), probs, Answer.ENGINE_REPLAY,
                            n.path("m").asText(model), null));
                } catch (RuntimeException | IOException skip) {
                    // linha corrompida não invalida o cassete
                }
            }
        } catch (IOException ignored) {
            // sem cassete legível = sem replay
        }
    }

    static String canonicalState(Map<String, String> state) {
        return new TreeMap<>(state).toString();
    }

    private String key(String stateKey, Question q) {
        StringBuilder sb = new StringBuilder(model).append('|').append(stateKey).append('|')
                .append(q.type()).append('|').append(q.instructions()).append('|')
                .append(new TreeMap<>(q.criteria())).append('|').append(q.levels());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int size() {
        return byKey.size();
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public Path file() {
        return file;
    }
}
