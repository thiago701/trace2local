package tech.neural7.trace2local.predictive.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitor dos artefatos do projeto (ADR-013 §4) — análise ESTÁTICA leve, por
 * expressão regular, sem parser XML (imune a XXE), sem seguir links simbólicos,
 * com limites de arquivos, profundidade e tamanho. Roda no worker de projeto do
 * pipeline preditivo, nunca no ingest.
 */
public final class ProjectScanner {

    private static final int MAX_FILES = 6000;
    private static final int MAX_DEPTH = 12;
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".idea", ".mvn", ".gradle",
            ".terraform", ".trace2local", "dist", "build-cache");

    private static final Pattern TF_ASSIGN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_\\-]*)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern TF_BLOCK = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_\\-]*)((?:\\s+\"[^\"]*\")*)\\s*(=\\s*)?\\{\\s*$");
    private static final Pattern PKG = Pattern.compile("<package name=\"([^\"]+)\">(.*?)</package>", Pattern.DOTALL);
    private static final Pattern LINE_COUNTER = Pattern.compile("<counter type=\"LINE\" missed=\"(\\d+)\" covered=\"(\\d+)\"/>");
    private static final Pattern JACOCO_LIMIT = Pattern.compile(
            "<limit>(?:(?!</limit>).)*?<counter>LINE</counter>(?:(?!</limit>).)*?<minimum>([0-9.]+)</minimum>(?:(?!</limit>).)*?</limit>",
            Pattern.DOTALL);
    private static final Pattern CLASS_DECL = Pattern.compile("^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|static\\s+)*(?:class|record|interface|enum)\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern RESILIENCE = Pattern.compile(
            "(@CircuitBreaker|@Retry\\b|@Retryable|@TimeLimiter|@Bulkhead|RetryPolicy|CircuitBreaker\\.of|RetryStrategy"
                    + "|\\.connectTimeout\\(|\\.readTimeout\\(|\\.timeout\\(|apiCallTimeout|apiCallAttemptTimeout|\\.retryPolicy\\()");
    private static final Pattern TIMEOUT_KEY = Pattern.compile(
            "(?i)^\\s*-?\\s*([A-Za-z0-9_.\\-]*(timeout|retry|retries|batch[_-]?size|concurrenc|visibility)[A-Za-z0-9_.\\-]*)\\s*[:=]\\s*(.+?)\\s*$");

    private final List<Path> roots;

    public ProjectScanner(List<Path> roots) {
        this.roots = roots;
    }

    /** Diretórios a varrer: {@code trace2local.project.dirs} / {@code TRACE2LOCAL_PROJECT_DIRS}, senão os do catálogo de infra, senão ".". */
    public static List<Path> rootsFromEnvironment(String infraScanDirs) {
        String raw = System.getProperty("trace2local.project.dirs", System.getenv("TRACE2LOCAL_PROJECT_DIRS"));
        if (raw == null || raw.isBlank()) {
            raw = infraScanDirs;
        }
        if (raw == null || raw.isBlank()) {
            raw = ".";
        }
        List<Path> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            if (!p.isBlank()) {
                out.add(Path.of(p.trim()).toAbsolutePath().normalize());
            }
        }
        return out;
    }

    /** Maior mtime dos arquivos interessantes — o pipeline só re-varre quando muda. */
    public long fingerprint() {
        long[] max = {0};
        int[] count = {0};
        for (Path root : roots) {
            visit(root, 0, (path, name) -> {
                if (interesting(path, name)) {
                    try {
                        max[0] = Math.max(max[0], Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
                        count[0]++;
                    } catch (IOException ignored) {
                        // arquivo sumiu no meio da varredura
                    }
                }
            });
        }
        return max[0] * 31 + count[0];
    }

    public ProjectSnapshot scan() {
        Map<String, Map<String, ProjectSnapshot.Assignment>> terraform = new LinkedHashMap<>();
        ProjectSnapshot.Coverage[] coverage = {null};
        ProjectSnapshot.Gate[] gate = {envGate()};
        Map<String, List<ProjectSnapshot.Marker>> resilience = new LinkedHashMap<>();
        List<ProjectSnapshot.Assignment> timeouts = new ArrayList<>();
        Map<String, ProjectSnapshot.Location> sources = new LinkedHashMap<>();
        for (Path root : roots) {
            visit(root, 0, (path, name) -> {
                try {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".tf") || lower.endsWith(".tfvars")) {
                        String env = environmentOf(root, path);
                        List<String> lines = read(path);
                        if (env != null) {
                            parseTerraform(rel(path), lines, terraform.computeIfAbsent(env, k -> new LinkedHashMap<>()));
                        }
                        collectTimeouts(rel(path), lines, timeouts);
                    } else if (lower.equals("jacoco.xml")) {
                        ProjectSnapshot.Coverage c = parseJacoco(rel(path), Files.readString(path, StandardCharsets.UTF_8));
                        if (c != null && (coverage[0] == null || c.covered() + c.missed() > coverage[0].covered() + coverage[0].missed())) {
                            coverage[0] = c;
                        }
                    } else if (lower.equals("pom.xml") && gate[0] == null) {
                        gate[0] = parsePomGate(rel(path), Files.readString(path, StandardCharsets.UTF_8));
                    } else if (lower.equals("sonar-project.properties") && gate[0] == null) {
                        gate[0] = parseSonarGate(rel(path), read(path));
                    } else if (lower.endsWith(".java") && path.toString().replace('\\', '/').contains("/src/main/")) {
                        indexJava(rel(path), read(path), sources, resilience);
                    } else if ((lower.startsWith("application") && (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")))
                            || lower.startsWith("docker-compose")) {
                        collectTimeouts(rel(path), read(path), timeouts);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // arquivo ilegível é pulado — análise é best-effort
                }
            });
        }
        return new ProjectSnapshot(roots.stream().map(Path::toString).toList(), terraform, coverage[0], gate[0],
                resilience, timeouts, sources, Instant.now());
    }

    // ------------------------------------------------------------------ varredura

    private interface Visitor {
        void accept(Path path, String name);
    }

    private void visit(Path root, int depth, Visitor visitor) {
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {root, 0});
        int files = 0;
        while (!stack.isEmpty() && files < MAX_FILES) {
            Object[] item = stack.pop();
            Path dir = (Path) item[0];
            int d = (int) item[1];
            if (d > MAX_DEPTH || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (var list = Files.list(dir)) {
                for (Path p : list.toList()) {
                    String name = p.getFileName().toString();
                    if (Files.isSymbolicLink(p)) {
                        continue; // nunca segue link: sem fuga da raiz, sem ciclo
                    }
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        if (SKIP_DIRS.contains(name) || (name.equals("target") && !hasJacoco(p))) {
                            continue;
                        }
                        stack.push(new Object[] {p, d + 1});
                    } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            if (Files.size(p) > MAX_BYTES) {
                                continue;
                            }
                        } catch (IOException e) {
                            continue;
                        }
                        files++;
                        visitor.accept(p, name);
                    }
                }
            } catch (IOException ignored) {
                // diretório ilegível
            }
        }
    }

    private static boolean hasJacoco(Path target) {
        return Files.isDirectory(target.resolve("site"), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean interesting(Path p, String name) {
        String l = name.toLowerCase(Locale.ROOT);
        return l.endsWith(".tf") || l.endsWith(".tfvars") || l.equals("jacoco.xml") || l.equals("pom.xml")
                || l.equals("sonar-project.properties") || l.endsWith(".java") || l.startsWith("application");
    }

    private static List<String> read(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static String rel(Path p) {
        Path cwd = Path.of("").toAbsolutePath();
        Path abs = p.toAbsolutePath();
        return (abs.startsWith(cwd) ? cwd.relativize(abs) : abs).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ terraform

    /** Ambiente inferido do caminho/arquivo: dev, hml, prod (aliases normalizados). */
    static String environmentOf(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String seg : rel.split("[/._\\-]")) {
            switch (seg) {
                case "dev", "develop", "development", "local" -> {
                    return "dev";
                }
                case "hml", "homolog", "homologacao", "staging", "stg", "qa", "uat" -> {
                    return "hml";
                }
                case "prod", "prd", "production", "producao" -> {
                    return "prod";
                }
                default -> {
                    // continua
                }
            }
        }
        return null;
    }

    static void parseTerraform(String file, List<String> lines, Map<String, ProjectSnapshot.Assignment> out) {
        Deque<String> blocks = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            Matcher block = TF_BLOCK.matcher(line);
            if (block.matches()) {
                String labels = block.group(2) == null ? "" : block.group(2).replace("\"", "").trim().replace(' ', '.');
                blocks.push(block.group(1) + (labels.isEmpty() ? "" : "." + labels));
                continue;
            }
            if (line.trim().startsWith("}")) {
                if (!blocks.isEmpty()) {
                    blocks.pop();
                }
                continue;
            }
            Matcher a = TF_ASSIGN.matcher(line);
            if (a.matches()) {
                String value = a.group(2).trim();
                if (value.endsWith("{") || value.endsWith("[")) {
                    continue;
                }
                List<String> path = new ArrayList<>(blocks);
                java.util.Collections.reverse(path);
                path.add(a.group(1));
                String key = String.join(".", path);
                out.put(key, new ProjectSnapshot.Assignment(key, unquote(value), file, i + 1));
            }
        }
    }

    private static String stripComment(String line) {
        String t = line;
        int hash = indexOutsideQuotes(t, '#');
        if (hash >= 0) {
            t = t.substring(0, hash);
        }
        int slash = t.indexOf("//");
        if (slash >= 0 && !t.substring(0, slash).contains("\"http") && !t.contains("://")) {
            t = t.substring(0, slash);
        }
        return t;
    }

    private static int indexOutsideQuotes(String s, char c) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
            } else if (ch == c && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String v) {
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static void collectTimeouts(String file, List<String> lines, List<ProjectSnapshot.Assignment> out) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TIMEOUT_KEY.matcher(lines.get(i));
            if (m.matches()) {
                out.add(new ProjectSnapshot.Assignment(m.group(1), unquote(m.group(3)), file, i + 1));
            }
        }
    }

    // ------------------------------------------------------------------ cobertura

    static ProjectSnapshot.Coverage parseJacoco(String file, String xml) {
        Map<String, long[]> byPackage = new LinkedHashMap<>();
        Matcher pkg = PKG.matcher(xml);
        int lastPackageEnd = 0;
        while (pkg.find()) {
            Matcher c = LINE_COUNTER.matcher(pkg.group(2));
            long[] last = null;
            while (c.find()) {
                last = new long[] {Long.parseLong(c.group(1)), Long.parseLong(c.group(2))};
            }
            if (last != null) {
                byPackage.put(pkg.group(1).replace('/', '.'), last);
            }
            lastPackageEnd = pkg.end();
        }
        Matcher total = LINE_COUNTER.matcher(xml.substring(lastPackageEnd));
        long missed = -1;
        long covered = -1;
        while (total.find()) {
            missed = Long.parseLong(total.group(1));
            covered = Long.parseLong(total.group(2));
        }
        if (missed < 0) {
            missed = byPackage.values().stream().mapToLong(v -> v[0]).sum();
            covered = byPackage.values().stream().mapToLong(v -> v[1]).sum();
        }
        if (missed + covered <= 0) {
            return null;
        }
        return new ProjectSnapshot.Coverage(file, covered, missed, byPackage);
    }

    static ProjectSnapshot.Gate parsePomGate(String file, String pom) {
        Matcher m = JACOCO_LIMIT.matcher(pom);
        if (m.find()) {
            double min = Double.parseDouble(m.group(1));
            int line = pom.substring(0, m.start(1)).split("\n", -1).length;
            return new ProjectSnapshot.Gate(min > 1 ? min / 100 : min, "jacoco:check (LINE COVEREDRATIO)", file, line);
        }
        return null;
    }

    static ProjectSnapshot.Gate parseSonarGate(String file, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("sonar.qualitygate.coverage") || l.startsWith("trace2local.quality-gate.coverage")) {
                int eq = l.indexOf('=');
                if (eq > 0) {
                    try {
                        double v = Double.parseDouble(l.substring(eq + 1).trim().replace("%", ""));
                        return new ProjectSnapshot.Gate(v > 1 ? v / 100 : v, "sonar-project.properties", file, i + 1);
                    } catch (NumberFormatException ignored) {
                        // valor inválido
                    }
                }
            }
        }
        return null;
    }

    private static ProjectSnapshot.Gate envGate() {
        String v = System.getProperty("trace2local.quality-gate.coverage", System.getenv("TRACE2LOCAL_QUALITY_GATE_COVERAGE"));
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(v.trim().replace("%", ""));
            return new ProjectSnapshot.Gate(d > 1 ? d / 100 : d, "TRACE2LOCAL_QUALITY_GATE_COVERAGE", null, 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ código

    static void indexJava(String file, List<String> lines, Map<String, ProjectSnapshot.Location> sources,
                          Map<String, List<ProjectSnapshot.Marker>> resilience) {
        String currentClass = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher decl = CLASS_DECL.matcher(line);
            if (decl.find()) {
                currentClass = decl.group(1);
                sources.putIfAbsent(currentClass, new ProjectSnapshot.Location(file, i + 1));
            }
            Matcher r = RESILIENCE.matcher(line);
            while (r.find() && currentClass != null) {
                resilience.computeIfAbsent(currentClass, k -> new ArrayList<>())
                        .add(new ProjectSnapshot.Marker(r.group(1), file, i + 1));
            }
        }
    }
}
