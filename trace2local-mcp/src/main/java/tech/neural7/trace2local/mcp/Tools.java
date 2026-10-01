package tech.neural7.trace2local.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static tech.neural7.trace2local.mcp.Formats.ms;
import static tech.neural7.trace2local.mcp.Formats.millis;
import static tech.neural7.trace2local.mcp.Formats.oneLine;
import static tech.neural7.trace2local.mcp.Formats.truncate;
import static tech.neural7.trace2local.mcp.Trace2LocalClient.enc;

/**
 * Catálogo de ferramentas do servidor MCP. Descrições escritas para o MODELO: quando usar,
 * o que devolve e qual ferramenta encadear em seguida. Leitura por padrão; as que disparam o
 * app do dev ou mudam mocks só existem com {@code --allow-mutations}.
 */
final class Tools {

    private static final String[] NONE = {};
    private static final String[] EXEC_ID = {"executionId"};
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final Trace2LocalClient api;
    private final McpConfig config;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    Tools(Trace2LocalClient api, McpConfig config) {
        this.api = api;
        this.config = config;
        register();
    }

    /** Ferramentas visíveis nesta configuração (mutações só com opt-in). */
    List<Tool> visible() {
        return tools.values().stream().filter(this::enabled).toList();
    }

    Tool find(String name) {
        Tool t = tools.get(name);
        return t == null || !enabled(t) ? null : t;
    }

    /** Mutação só com opt-in; perfil de ferramentas (TRACE2LOCAL_MCP_TOOLS) recorta o catálogo. */
    private boolean enabled(Tool t) {
        if (t.mutating() && !config.allowMutations()) {
            return false;
        }
        return config.toolFilter() == null || config.toolFilter().contains(t.name())
                // com mutações ligadas, o perfil core mantém o laço de ação completo
                || (t.mutating() && config.toolFilter() == McpConfig.CORE_TOOLS);
    }

    /** Ferramenta existe mas está bloqueada (para uma mensagem melhor que "desconhecida"). */
    boolean isBlockedMutation(String name) {
        Tool t = tools.get(name);
        return t != null && t.mutating() && !config.allowMutations();
    }

    private void add(String name, String title, String description, ObjectNode schema, boolean mutating,
                     boolean destructive, boolean idempotent, Tool.Handler h) {
        tools.put(name, new Tool(name, title, description, schema, !mutating, destructive, idempotent, mutating, h));
    }

    // =====================================================================================
    private void register() {
        add("status", "Estado do Trace2Local",
                "Comece por aqui. Diz se o Trace2Local está acessível, o modo (embedded ou station), a aplicação, "
                        + "as capacidades (ex.: mocks), a saúde do buffer e qual motor de decisão está ativo.",
                Json.schema(NONE), false, false, true, a -> status());

        add("list_executions", "Listar execuções",
                "Execuções recentes (mais nova primeiro): id, rótulo da raiz (ex.: 'pix-api · POST /pix/transfers'), status "
                        + "(COMPLETED, FAILED, PARTIAL, ORPHANED), duração, passos, gatilho. Filtre por status ou texto. "
                        + "Em seguida use get_execution (árvore) ou diagnose_failure (falhas).",
                Json.schema(NONE,
                        "limit", Json.integer("máximo de itens", 1, 100, 20),
                        "status", Json.enumStr("filtra pelo status", "COMPLETED", "FAILED", "PARTIAL", "ORPHANED", "RUNNING"),
                        "query", Json.str("trecho do rótulo da raiz, do id ou do traceId (sem distinção de caixa)")),
                false, false, true, this::listExecutions);

        add("get_execution", "Árvore da execução",
                "A execução inteira como outline navegável: cada passo com [nodeId], tipo (LAMBDA, HTTP_SERVER, HTTP_CLIENT, "
                        + "BUSINESS, DYNAMODB, SQL, SQS, SNS), rótulo, duração e self time, ✕ erro com tipo e mensagem, Δ dado "
                        + "alterado, SIM/↪ resposta do Mock Connect e ⧗ espera em fila entre produtor e consumidor assíncrono "
                        + "(consumidores SQS/SNS aparecem como filhos da publicação). Use get_step para o detalhe de um passo.",
                Json.schema(EXEC_ID,
                        "executionId", Json.str("id da execução (list_executions)"),
                        "maxNodes", Json.integer("limite de passos no outline", 10, 1000, 200)),
                false, false, true, this::getExecution);

        add("get_step", "Detalhe de um passo",
                "Tudo sobre um passo: caminho desde a raiz, atributos, erro com stack, Δ de dados, payload (se "
                        + "TRACE2LOCAL_MCP_DATA=full), marca do Mock Connect, filhos e as linhas de log presas ao passo.",
                Json.schema(new String[]{"executionId", "nodeId"},
                        "executionId", Json.str("id da execução"),
                        "nodeId", Json.str("id do passo ([nodeId] no outline)")),
                false, false, true, this::getStep);

        add("get_logs", "Logs da execução",
                "Linhas de log correlacionadas à execução (app e plataforma — START/END/REPORT da Lambda, CloudWatch), "
                        + "em ordem, com deslocamento desde o início e o passo (spanId) quando conhecido. Filtre por nível mínimo "
                        + "ou texto.",
                Json.schema(EXEC_ID,
                        "executionId", Json.str("id da execução"),
                        "minLevel", Json.enumStr("nível mínimo (linhas de plataforma sempre entram, a menos que onlyApp)", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"),
                        "contains", Json.str("trecho a procurar (sem distinção de caixa)"),
                        "onlyApp", Json.bool("ignora START/END/REPORT da plataforma", false),
                        "limit", Json.integer("máximo de linhas", 1, 1000, 200)),
                false, false, true, this::getLogs);

        add("diagnose_failure", "Diagnosticar falha",
                "Ponto de partida para 'por que falhou?'. Sem executionId, pega a falha mais recente. Devolve a causa "
                        + "raiz (passo mais profundo com erro e o caminho até ele), exceção e stack, logs de erro/aviso, laudo "
                        + "(desfecho, risco, anomalias), insights que citam o passo e — se for parceiro fora do ar — a sugestão "
                        + "de mock pronta. Separe FATO (observado) de HIPÓTESE ao responder.",
                Json.schema(NONE, "executionId", Json.str("opcional: id da execução; vazio = falha mais recente")),
                false, false, true, this::diagnose);

        add("explain_execution", "Laudo da execução",
                "Leitura executiva (desfecho, prontidão para homologar, risco, regras de negócio × fluxo com veredito, "
                        + "checklist) e técnica (hotspots, caminho crítico, anomalias), mais a narrativa em linguagem de negócio. "
                        + "Cada decisão traz o motor que decidiu e a confiança.",
                Json.schema(EXEC_ID,
                        "executionId", Json.str("id da execução"),
                        "audience", Json.enumStr("recorte", "technical", "executive", "both")),
                false, false, true, this::explain);

        add("list_insights", "Insights preditivos",
                "Pontos de atenção ranqueados pelas Regras Assíncronas Preditivas (N+1, idempotência, fila lenta, "
                        + "regressão, resiliência, dado sensível…), com natureza (fato/correlação/hipótese), confiança, "
                        + "evidência e recomendação.",
                Json.schema(NONE,
                        "limit", Json.integer("máximo de itens", 1, 100, 12),
                        "minSeverity", Json.enumStr("severidade mínima", "LOW", "MEDIUM", "HIGH", "CRITICAL")),
                false, false, true, this::listInsights);

        add("get_topology", "Topologia (anatomia)",
                "Componentes observados por zona — núcleo (código), fronteira local (AWS/LocalStack: tabelas, filas, "
                        + "tópicos, banco), fronteira externa (parceiros HTTP) e declarado no IaC nunca observado — com "
                        + "chamadas, erros e ligações (assíncronas marcadas).",
                Json.schema(NONE), false, false, true, a -> topology());

        add("compare_executions", "Comparar execuções",
                "Diferença entre duas execuções do mesmo fluxo: passos só em A ou só em B, mudanças de status, "
                        + "durações que variaram mais de 20 % e efeitos de dados diferentes. Útil para 'o que mudou "
                        + "entre a que passou e a que falhou?'.",
                Json.schema(new String[]{"a", "b"},
                        "a", Json.str("executionId de referência (ex.: a que passou)"),
                        "b", Json.str("executionId a comparar (ex.: a que falhou)")),
                false, false, true, this::compare);

        add("list_endpoints", "Endpoints do projeto",
                "Endpoints que o Trace2Local sabe disparar (descobertos no app ou no contrato OpenAPI): id, método, "
                        + "caminho, parâmetros declarados e corpo de exemplo. Disparo: dispatch_endpoint (requer mutações).",
                Json.schema(NONE), false, false, true, a -> endpoints());

        add("wait_for_execution", "Aguardar execução",
                "Espera uma execução aparecer CONCLUÍDA no acervo (por executionId ou traceId) e devolve o resumo. Use "
                        + "depois de disparar uma chamada fora do Trace2Local (curl, teste) com traceparent conhecido.",
                Json.schema(NONE,
                        "executionId", Json.str("id da execução"),
                        "traceId", Json.str("traceId W3C (32 hex)"),
                        "timeoutSeconds", Json.integer("tempo máximo de espera", 1, 120, 30)),
                false, false, true, this::waitFor);

        // ----------------------------------------------------------------- Mock Connect (leitura)
        add("list_mock_suggestions", "Sugestões de mock",
                "O conselheiro do Mock Connect lê as execuções e indica QUANDO plugar um mock (API indisponível, "
                        + "resposta que decide o fluxo, só caminho feliz, lentidão, fora do contrato) e QUAIS variações "
                        + "testar (ids prontos). Aplicar: apply_mock_suggestion (requer mutações).",
                Json.schema(NONE, "host", Json.str("opcional: só sugestões para este host (ex.: kyc.bureau.local)")),
                false, false, true, this::mockSuggestions);

        add("list_mock_bindings", "Bindings de mock",
                "Mocks plugados (estilo conector do Kafka Connect): estado, alvo, fonte → destino, transformações, "
                        + "stubs, chamadas atendidas, endpoint e rotas publicadas para o cliente.",
                Json.schema(NONE), false, false, true, a -> mockBindings());

        add("get_mock_journal", "Journal do mock",
                "Cada chamada atendida pelo Mock Connect: binding, requisição, status, stub, variação aplicada, "
                        + "resultado e traceId (liga à execução).",
                Json.schema(NONE,
                        "binding", Json.str("opcional: nome do binding"),
                        "limit", Json.integer("máximo de linhas", 1, 500, 50)),
                false, false, true, this::mockJournal);

        add("list_mock_plugins", "Plugins de mock",
                "Catálogo de plugins (SOURCE, TRANSFORM, PREDICATE, SINK) com cada chave de configuração, tipo, "
                        + "obrigatoriedade e documentação — para escrever a config de um binding.",
                Json.schema(NONE, "type", Json.enumStr("filtra pelo tipo", "SOURCE", "TRANSFORM", "PREDICATE", "SINK")),
                false, false, true, this::mockPlugins);

        add("validate_mock_binding", "Validar binding de mock",
                "Valida uma config de binding (formato plano do Kafka Connect: target, source, source.*, transforms, "
                        + "transforms.<alias>.type, predicates…) chave a chave, SEM salvar. Devolve os erros por chave.",
                Json.schema(new String[]{"name", "config"},
                        "name", Json.str("nome do binding (a-z0-9._-)"),
                        "config", Json.stringMap("config plana {chave: valor}")),
                false, false, true, this::validateBinding);

        // ----------------------------------------------------------------- mutações (opt-in)
        add("dispatch_endpoint", "Disparar endpoint",
                "Dispara um endpoint do app do dev (list_endpoints) e, por padrão, espera a execução e devolve o outline. "
                        + "Para validar uma variação sob demanda do Mock Connect, envie o cabeçalho baggage: t2l.mock=<id>.",
                Json.schema(new String[]{"endpointId"},
                        "endpointId", Json.str("id do endpoint (list_endpoints)"),
                        "body", Json.anyObject("corpo JSON (opcional)"),
                        "headers", Json.stringMap("cabeçalhos e parâmetros de query declarados no contrato"),
                        "pathVariables", Json.stringMap("variáveis de caminho, ex.: {transferId: 'pix-123'}"),
                        "wait", Json.bool("espera a execução concluir", true),
                        "timeoutSeconds", Json.integer("espera máxima", 1, 120, 45)),
                true, false, false, this::dispatch);

        add("apply_mock_suggestion", "Aplicar sugestão de mock",
                "Pluga o mock sugerido (cria/atualiza o binding) com as variações escolhidas. mode=on-demand: a "
                        + "variação só vale quando a requisição traz baggage t2l.mock=<id> (não atrapalha o time); "
                        + "mode=exclusive: vale para toda chamada ao parceiro. Variações vazias = mock base.",
                Json.schema(new String[]{"suggestionId"},
                        "suggestionId", Json.str("id da sugestão (list_mock_suggestions)"),
                        "variations", Json.stringArray("ids das variações"),
                        "mode", Json.enumStr("quando aplicar as variações", "exclusive", "on-demand")),
                true, false, true, this::applySuggestion);

        add("put_mock_binding", "Criar/atualizar binding de mock",
                "Cria ou substitui um binding com config plana (valide antes com validate_mock_binding). Segredos: "
                        + "use ${env:NOME}, nunca o valor.",
                Json.schema(new String[]{"name", "config"},
                        "name", Json.str("nome do binding (a-z0-9._-)"),
                        "config", Json.stringMap("config plana {chave: valor}")),
                true, false, true, this::putBinding);

        add("control_mock_binding", "Controlar binding de mock",
                "Pausa, retoma, reinicia (relê contrato, zera contadores) ou remove um binding.",
                Json.schema(new String[]{"name", "action"},
                        "name", Json.str("nome do binding"),
                        "action", Json.enumStr("ação", "pause", "resume", "restart", "delete")),
                true, true, false, this::controlBinding);
    }

    // ===================================================================================== leitura

    private Tool.Result status() throws Trace2LocalClient.ApiException {
        JsonNode meta = api.get("/meta");
        JsonNode health = safe("/health");
        JsonNode intel = safe("/intelligence");
        StringBuilder sb = new StringBuilder();
        sb.append("Trace2Local acessível em ").append(api.base()).append('\n')
                .append("modo ").append(meta.path("mode").asText("?"))
                .append(" · app ").append(meta.path("app").asText("?"))
                .append(" · versão ").append(meta.path("version").asText("?"))
                .append(" · runtime ").append(meta.path("runtime").asText("?")).append('\n')
                .append("capacidades: ").append(join(meta.path("capabilities"))).append('\n');
        if (health != null) {
            sb.append("buffer ").append(health.path("bufferUsage").asText("0"))
                    .append(" · ao vivo ").append(health.path("liveExecutions").asInt())
                    .append(" · linhas de log ").append(health.path("logLines").asInt())
                    .append(" · descartados ").append(health.path("dropped").asInt()).append('\n');
        }
        if (intel != null) {
            JsonNode e = intel.path("engine");
            JsonNode p = intel.path("pipeline");
            sb.append("motor de decisão: ").append(e.path("effectiveMode").asText("?"))
                    .append(" (egress ").append(e.path("egress").asText("?")).append(")")
                    .append(" · análises ").append(p.path("analyzed").asInt()).append('\n');
        }
        sb.append("MCP: ").append(config.allowMutations() ? "mutações LIGADAS" : "somente leitura")
                .append(" · dados ").append(config.dataMode().name().toLowerCase(Locale.ROOT)).append('\n');
        ObjectNode st = Json.obj();
        st.set("meta", meta);
        if (health != null) {
            st.set("health", health);
        }
        return Tool.Result.ok(sb.toString(), st);
    }

    private Tool.Result listExecutions(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        int limit = a.integer("limit", 20, 1, 100);
        String status = Args.upper(a.optional("status"));
        String q = a.optional("query");
        JsonNode all = api.get("/executions?limit=200");
        ArrayNode picked = Json.arr();
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : all) {
            if (status != null && !status.equals(s.path("status").asText())) {
                continue;
            }
            if (q != null) {
                String hay = (s.path("rootLabel").asText() + " " + s.path("executionId").asText() + " " + s.path("traceId").asText())
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(q.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            picked.add(s);
            sb.append("- ").append(Formats.summaryLine(s)).append('\n');
            if (picked.size() >= limit) {
                break;
            }
        }
        if (picked.isEmpty()) {
            return Tool.Result.ok("Nenhuma execução casa com o filtro (o acervo tem " + all.size()
                    + "). Dispare o fluxo no app ou ajuste status/query.", Json.obj().set("executions", picked));
        }
        return Tool.Result.ok(picked.size() + " execução(ões):\n" + sb, Json.obj().set("executions", picked));
    }

    private Tool.Result getExecution(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        JsonNode e = execution(a.required("executionId"));
        return Tool.Result.ok(Formats.outline(e, a.integer("maxNodes", 200, 10, 1000), config.dataMode()));
    }

    private Tool.Result getStep(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.required("executionId");
        String nodeId = a.required("nodeId");
        JsonNode e = execution(id);
        JsonNode n = Formats.index(e).get(nodeId);
        if (n == null) {
            return Tool.Result.fail("Passo " + nodeId + " não existe na execução " + id + " — use get_execution para ver os [nodeId].");
        }
        StringBuilder sb = new StringBuilder();
        List<JsonNode> path = Formats.pathTo(e, nodeId);
        sb.append("Caminho: ");
        for (int i = 0; i < path.size(); i++) {
            sb.append(i == 0 ? "" : " › ").append(truncate(path.get(i).path("label").asText(), 60));
        }
        sb.append("\n[").append(nodeId).append("] ").append(n.path("kind").asText()).append(' ')
                .append(n.path("label").asText()).append('\n')
                .append("status ").append(n.path("status").asText()).append(" · duração ").append(ms(millis(n.path("totalTime"))))
                .append(" · self ").append(ms(millis(n.path("selfTime")))).append(" · início ").append(n.path("startedAt").asText())
                .append('\n');
        String mock = Formats.mockMark(n);
        if (!mock.isBlank()) {
            sb.append("Mock Connect:").append(mock).append('\n');
        }
        JsonNode err = n.path("error");
        if (!err.isNull() && !err.isMissingNode()) {
            sb.append("\nERRO ").append(err.path("type").asText()).append(": ").append(err.path("message").asText()).append('\n');
            String stack = err.path("stack").asText("");
            if (!stack.isBlank()) {
                sb.append("stack (início):\n").append(firstLines(stack, 25)).append('\n');
            }
        }
        sb.append("\nATRIBUTOS\n");
        Map<String, String> attrs = new TreeMap<>();
        n.path("attributes").fields().forEachRemaining(f -> attrs.put(f.getKey(), f.getValue().asText()));
        attrs.forEach((k, v) -> {
            if (config.dataMode() == McpConfig.DataMode.STRUCTURAL && k.startsWith("t2l.payload.")) {
                sb.append("  ").append(k).append(" = [omitido: ").append(v.length()).append(" caracteres — TRACE2LOCAL_MCP_DATA=full]\n");
            } else {
                sb.append("  ").append(k).append(" = ").append(truncate(v, 400)).append('\n');
            }
        });
        JsonNode m = n.path("mutation");
        if (!m.isNull() && !m.isMissingNode()) {
            sb.append("\nDADOS").append(Formats.mutationShort(m, config.dataMode())).append('\n');
            if (config.dataMode() == McpConfig.DataMode.FULL) {
                sb.append("antes: ").append(truncate(Json.write(m.path("before")), 2000)).append('\n')
                        .append("depois: ").append(truncate(Json.write(m.path("after")), 2000)).append('\n');
            }
            if ("INFERRED".equals(m.path("fidelity").asText())) {
                sb.append("(inferido do SQL: operação, tabela e chave; o driver não devolve valores antes/depois)\n");
            }
        }
        JsonNode p = n.path("payload");
        if (!p.isNull() && !p.isMissingNode()) {
            sb.append("\nPAYLOAD\n");
            for (String side : List.of("request", "response")) {
                String body = p.path(side).isNull() ? null : p.path(side).asText(null);
                if (body == null) {
                    continue;
                }
                sb.append(side).append(": ").append(config.dataMode() == McpConfig.DataMode.FULL
                        ? truncate(body, 4000) : "[omitido: " + body.length() + " caracteres — TRACE2LOCAL_MCP_DATA=full; já redigido na origem]")
                        .append('\n');
            }
        }
        if (n.path("children").size() > 0) {
            sb.append("\nFILHOS\n");
            for (JsonNode c : n.path("children")) {
                sb.append("  [").append(c.path("nodeId").asText()).append("] ").append(c.path("kind").asText()).append(' ')
                        .append(truncate(c.path("label").asText(), 80)).append(" — ").append(ms(millis(c.path("totalTime"))))
                        .append(c.path("error").isObject() ? " ✕" : "").append('\n');
            }
        }
        List<String> logs = stepLogs(id, e, n);
        if (!logs.isEmpty()) {
            sb.append("\nLOGS DO PASSO\n");
            logs.forEach(l -> sb.append("  ").append(l).append('\n'));
        }
        return Tool.Result.ok(sb.toString());
    }

    private Tool.Result getLogs(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.required("executionId");
        String min = Args.upper(a.optional("minLevel"));
        String contains = a.optional("contains");
        boolean onlyApp = a.bool("onlyApp", false);
        int limit = a.integer("limit", 200, 1, 1000);
        execution(id); // 404 claro antes de buscar os logs
        JsonNode logs = api.get("/executions/" + enc(id) + "/logs");
        List<String> out = new ArrayList<>();
        int total = 0;
        for (JsonNode l : logs.path("lines")) {
            total++;
            String level = l.path("level").asText("INFO").toUpperCase(Locale.ROOT);
            boolean platform = "PLATFORM".equals(level);
            if (platform && onlyApp) {
                continue;
            }
            if (!platform && min != null && LEVELS.indexOf(level) >= 0 && LEVELS.indexOf(level) < LEVELS.indexOf(min)) {
                continue;
            }
            String msg = l.path("message").asText("");
            if (contains != null && !msg.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(logLine(l));
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            return Tool.Result.ok("Nenhuma linha casa com o filtro (" + total + " linha(s) na execução). Logs exigem spanId no "
                    + "MDC ou RequestId da Lambda; no Station, o tail do CloudWatch do LocalStack.");
        }
        return Tool.Result.ok(out.size() + " de " + total + " linha(s):\n" + String.join("\n", out));
    }

    private Tool.Result diagnose(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.optional("executionId");
        if (id == null) {
            JsonNode all = api.get("/executions?limit=200");
            for (String wanted : List.of("FAILED", "PARTIAL", "ORPHANED")) {
                for (JsonNode s : all) {
                    if (wanted.equals(s.path("status").asText())) {
                        id = s.path("executionId").asText();
                        break;
                    }
                }
                if (id != null) {
                    break;
                }
            }
            if (id == null) {
                return Tool.Result.ok("Nenhuma execução com falha no acervo (" + all.size() + " execução(ões), todas concluídas). "
                        + "Se o problema é lentidão ou dado errado, use list_insights ou explain_execution.");
            }
        }
        JsonNode e = execution(id);
        StringBuilder sb = new StringBuilder();
        sb.append("Execução ").append(id).append(" · ").append(e.path("status").asText()).append(" · ")
                .append(ms(millis(e.path("duration")))).append(" · raiz: ")
                .append(e.path("roots").path(0).path("label").asText("?")).append('\n');
        for (JsonNode w : e.path("warnings")) {
            sb.append("AVISO ").append(w.path("kind").asText()).append(": ").append(w.path("message").asText()).append('\n');
        }
        // causa raiz = passo com erro sem descendente com erro
        List<JsonNode> errors = new ArrayList<>();
        Formats.walk(e.path("roots"), (n, d) -> {
            if (n.path("error").isObject() || "ERROR".equals(n.path("status").asText())) {
                errors.add(n);
            }
        });
        List<JsonNode> causes = new ArrayList<>();
        for (JsonNode n : errors) {
            boolean deeper = errors.stream().anyMatch(o -> o != n && isDescendant(n, o));
            if (!deeper) {
                causes.add(n);
            }
        }
        Set<String> hosts = new LinkedHashSet<>();
        Set<String> causeIds = new LinkedHashSet<>();
        if (causes.isEmpty()) {
            sb.append("\nNenhum passo com erro: a execução é ").append(e.path("status").asText())
                    .append(" por estrutura (passos órfãos/contexto perdido — veja os avisos) ou ainda incompleta.\n");
        } else {
            sb.append("\nCAUSA(S) RAIZ — FATO OBSERVADO\n");
            for (JsonNode c : causes) {
                causeIds.add(c.path("nodeId").asText());
                List<JsonNode> path = Formats.pathTo(e, c.path("nodeId").asText());
                sb.append("- [").append(c.path("nodeId").asText()).append("] ").append(c.path("kind").asText()).append(' ')
                        .append(c.path("label").asText()).append('\n').append("  caminho: ");
                for (int i = 0; i < path.size(); i++) {
                    sb.append(i == 0 ? "" : " › ").append(truncate(path.get(i).path("label").asText(), 50));
                }
                JsonNode err = c.path("error");
                sb.append("\n  erro: ").append(err.path("type").asText("(status ERROR)")).append(": ")
                        .append(truncate(oneLine(err.path("message").asText("")), 400)).append('\n');
                String stack = err.path("stack").asText("");
                if (!stack.isBlank()) {
                    sb.append("  stack:\n").append(indent(firstLines(stack, 12), "    ")).append('\n');
                }
                String mock = Formats.mockMark(c);
                if (!mock.isBlank()) {
                    sb.append("  resposta do Mock Connect:").append(mock).append('\n');
                }
                String host = Formats.hostOf(c);
                if (host != null) {
                    hosts.add(host);
                }
            }
            if (errors.size() > causes.size()) {
                sb.append("(+").append(errors.size() - causes.size()).append(" passo(s) ancestral(is) marcados com erro por propagação)\n");
            }
        }
        // logs de erro/aviso
        JsonNode logs = safe("/executions/" + enc(id) + "/logs");
        if (logs != null) {
            List<String> lines = new ArrayList<>();
            for (JsonNode l : logs.path("lines")) {
                String level = l.path("level").asText("").toUpperCase(Locale.ROOT);
                if (level.equals("ERROR") || level.equals("WARN") || causeIds.contains(l.path("spanId").asText())) {
                    lines.add(logLine(l));
                }
            }
            if (!lines.isEmpty()) {
                sb.append("\nLOGS (erro/aviso e do passo)\n");
                lines.stream().limit(25).forEach(l -> sb.append("  ").append(l).append('\n'));
            }
        }
        // laudo
        JsonNode assist = safe("/executions/" + enc(id) + "/insights");
        if (assist != null) {
            JsonNode ex = assist.path("executive");
            sb.append("\nLAUDO\n  desfecho: ").append(decision(ex.path("outcome"))).append('\n')
                    .append("  risco: ").append(decision(ex.path("risk"))).append('\n');
            for (JsonNode an : assist.path("technical").path("anomalies")) {
                sb.append("  anomalia (").append(an.path("severity").asText()).append("): ").append(an.path("title").asText())
                        .append(" — ").append(truncate(an.path("detail").asText(), 200)).append('\n');
            }
            for (JsonNode i : assist.path("insights")) {
                boolean cites = false;
                for (JsonNode ev : i.path("evidence")) {
                    if (causeIds.contains(ev.path("ref").path("nodeId").asText())) {
                        cites = true;
                    }
                }
                if (cites || causeIds.isEmpty()) {
                    sb.append("  insight ").append(i.path("id").asText()).append(" (").append(i.path("nature").asText().toLowerCase(Locale.ROOT))
                            .append(", ").append(Math.round(i.path("confidence").asDouble() * 100)).append("%): ")
                            .append(i.path("title").asText()).append('\n');
                }
            }
        }
        // parceiro indisponível? sugestão de mock pronta
        if (!hosts.isEmpty()) {
            JsonNode sugs = safe("/mocks/suggestions");
            if (sugs != null) {
                for (JsonNode s : sugs) {
                    String target = s.path("target").asText("");
                    String host = target.contains(":") ? target.substring(0, target.indexOf(':')) : target;
                    if (hosts.contains(host)) {
                        sb.append("\nMOCK CONNECT — ").append(s.path("title").asText()).append(" [").append(s.path("id").asText())
                                .append("]").append(s.path("activeBinding").isTextual() ? " (já plugado: " + s.path("activeBinding").asText() + ")" : "")
                                .append("\n  por quê: ").append(truncate(s.path("why").asText(), 300)).append('\n');
                    }
                }
            }
        }
        sb.append("\nPRÓXIMOS PASSOS\n");
        if (!causeIds.isEmpty()) {
            sb.append("- get_step(").append(id).append(", ").append(causeIds.iterator().next()).append(") para atributos, payload e logs do passo\n");
        }
        sb.append("- compare_executions com uma execução do mesmo fluxo que passou (list_executions status=COMPLETED query=<rótulo>)\n");
        if (!hosts.isEmpty()) {
            sb.append("- parceiro indisponível/instável: list_mock_suggestions host=").append(hosts.iterator().next())
                    .append(" e apply_mock_suggestion (requer mutações)\n");
        }
        return Tool.Result.ok(sb.toString(), Json.obj().put("executionId", id));
    }

    private Tool.Result explain(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.required("executionId");
        String audience = a.oneOf("audience", "both", "technical", "executive", "both");
        execution(id);
        JsonNode assist = api.get("/executions/" + enc(id) + "/insights");
        StringBuilder sb = new StringBuilder();
        JsonNode ex = assist.path("executive");
        if (!audience.equals("technical")) {
            sb.append("EXECUTIVO\n").append(ex.path("headline").asText()).append('\n').append(ex.path("summary").asText()).append('\n')
                    .append("desfecho: ").append(decision(ex.path("outcome"))).append('\n')
                    .append("prontidão para homologar: ").append(decision(ex.path("readiness"))).append('\n')
                    .append("risco: ").append(decision(ex.path("risk"))).append('\n')
                    .append("alterou dados: ").append(ex.path("dataChanged").path("label").asText("?"))
                    .append(" · efeitos assíncronos consumidos: ").append(ex.path("asyncComplete").path("label").asText("?")).append('\n');
            if (ex.path("rules").size() > 0) {
                sb.append("REGRAS × FLUXO\n");
                for (JsonNode r : ex.path("rules")) {
                    sb.append("- ").append(r.path("id").asText()).append(' ').append(r.path("label").asText().toUpperCase(Locale.ROOT))
                            .append(" · ").append(r.path("term").asText()).append(": ").append(truncate(oneLine(r.path("text").asText()), 220))
                            .append("\n    por quê: ").append(truncate(r.path("rationale").asText(), 200))
                            .append(" [").append(r.path("engine").asText()).append(", ").append(Math.round(r.path("confidence").asDouble() * 100))
                            .append("%]").append(r.path("nodeIds").size() > 0 ? " passos " + join(r.path("nodeIds")) : "").append('\n');
                }
            }
            if (ex.path("checklist").size() > 0) {
                sb.append("CHECKLIST\n");
                for (JsonNode c : ex.path("checklist")) {
                    sb.append(c.path("ok").asBoolean() ? "  [x] " : "  [ ] ").append(c.path("item").asText())
                            .append(" — ").append(c.path("detail").asText()).append('\n');
                }
            }
        }
        if (!audience.equals("executive")) {
            JsonNode te = assist.path("technical");
            sb.append("\nTÉCNICO\n");
            for (JsonNode h : te.path("hotspots")) {
                sb.append("- hotspot #").append(h.path("rank").asInt()).append(" [").append(h.path("nodeId").asText()).append("] ")
                        .append(h.path("label").asText()).append(" — ").append(ms(h.path("totalMs").asLong()))
                        .append(" (self ").append(ms(h.path("selfMs").asLong())).append(")")
                        .append(h.path("failed").asBoolean() ? " ✕" : "").append('\n');
            }
            if (te.path("criticalPath").size() > 0) {
                sb.append("caminho crítico: ").append(join(te.path("criticalPath"))).append('\n');
            }
            for (JsonNode an : te.path("anomalies")) {
                sb.append("- anomalia (").append(an.path("severity").asText()).append(") ").append(an.path("title").asText())
                        .append(": ").append(truncate(an.path("detail").asText(), 200)).append('\n');
            }
            for (JsonNode i : assist.path("insights")) {
                sb.append("- insight ").append(i.path("id").asText()).append(" ").append(i.path("severity").asText())
                        .append(" (").append(i.path("nature").asText().toLowerCase(Locale.ROOT)).append(", ")
                        .append(Math.round(i.path("confidence").asDouble() * 100)).append("%): ").append(i.path("title").asText()).append('\n');
            }
        }
        JsonNode story = safe("/executions/" + enc(id) + "/story");
        if (story != null) {
            sb.append("\nNARRATIVA\n").append(story.path("intro").asText()).append('\n');
            int k = 0;
            for (JsonNode s : story.path("steps")) {
                if (k++ >= 40) {
                    sb.append("  …\n");
                    break;
                }
                sb.append("  ").append(s.path("order").asInt()).append(". ").append(truncate(s.path("text").asText(), 220)).append('\n');
            }
            sb.append(story.path("conclusion").asText()).append('\n');
        }
        return Tool.Result.ok(sb.toString());
    }

    private Tool.Result listInsights(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        int limit = a.integer("limit", 12, 1, 100);
        String min = Args.upper(a.optional("minSeverity"));
        List<String> order = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
        JsonNode list = api.get("/insights?limit=" + limit);
        JsonNode items = list.isArray() ? list : list.path("insights");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode i : items) {
            if (min != null && order.indexOf(i.path("severity").asText()) < order.indexOf(min)) {
                continue;
            }
            n++;
            sb.append("- ").append(i.path("id").asText()).append(" · ").append(i.path("severity").asText()).append(" · ")
                    .append(i.path("nature").asText().toLowerCase(Locale.ROOT)).append(" · ")
                    .append(Math.round(i.path("confidence").asDouble() * 100)).append("% · ").append(i.path("title").asText()).append('\n')
                    .append("  observação: ").append(truncate(oneLine(i.path("observation").asText()), 300)).append('\n');
            JsonNode recs = i.path("recommendations");
            if (recs.isArray() && recs.size() > 0) {
                sb.append("  recomendação: ").append(truncate(oneLine(recs.get(0).asText(recs.get(0).path("text").asText())), 200)).append('\n');
            }
            if (i.path("executionIds").size() > 0) {
                sb.append("  execuções: ").append(join(i.path("executionIds"))).append('\n');
            }
        }
        if (n == 0) {
            return Tool.Result.ok("Nenhum ponto de atenção" + (min != null ? " com severidade ≥ " + min : "")
                    + ". As regras analisam cada execução em segundo plano — silêncio é bom sinal.");
        }
        return Tool.Result.ok(n + " insight(s):\n" + sb, Json.obj().set("insights", items));
    }

    private Tool.Result topology() throws Trace2LocalClient.ApiException {
        JsonNode t = api.get("/topology");
        Map<String, List<JsonNode>> byZone = new LinkedHashMap<>();
        for (String z : List.of("core", "boundary", "external", "declared")) {
            byZone.put(z, new ArrayList<>());
        }
        for (JsonNode c : t.path("components")) {
            byZone.computeIfAbsent(c.path("zone").asText("?"), k -> new ArrayList<>()).add(c);
        }
        Map<String, String> label = new HashMap<>();
        t.path("components").forEach(c -> label.put(c.path("id").asText(), c.path("label").asText()));
        StringBuilder sb = new StringBuilder();
        byZone.forEach((zone, list) -> {
            if (list.isEmpty()) {
                return;
            }
            sb.append(zone.toUpperCase(Locale.ROOT)).append(" — ").append(t.path("zones").path(zone).asText("")).append('\n');
            for (JsonNode c : list) {
                sb.append("  ").append(c.path("id").asText()).append(" (").append(c.path("kind").asText()).append(")")
                        .append(" chamadas ").append(c.path("calls").asInt()).append(" · erros ").append(c.path("errors").asInt());
                if (c.path("p50").asLong() > 0) {
                    sb.append(" · p50 ").append(ms(c.path("p50").asLong())).append(" · p95 ").append(ms(c.path("p95").asLong()));
                }
                if (c.has("source")) {
                    sb.append(" · declarado em ").append(c.path("source").asText());
                }
                sb.append('\n');
            }
        });
        sb.append("LIGAÇÕES\n");
        for (JsonNode e : t.path("edges")) {
            sb.append("  ").append(label.getOrDefault(e.path("from").asText(), e.path("from").asText()))
                    .append(e.path("async").asBoolean() ? " ⇢ " : " → ")
                    .append(label.getOrDefault(e.path("to").asText(), e.path("to").asText()))
                    .append(" (").append(e.path("calls").asInt()).append("×").append(e.path("errors").asInt() > 0 ? ", " + e.path("errors").asInt() + " erro(s)" : "")
                    .append(e.path("async").asBoolean() && e.path("waitP50").asLong() > 0 ? ", fila p50 " + ms(e.path("waitP50").asLong()) : "")
                    .append(")\n");
        }
        return Tool.Result.ok(sb.toString());
    }

    private Tool.Result compare(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String ida = a.required("a");
        String idb = a.required("b");
        JsonNode ea = execution(ida);
        JsonNode eb = execution(idb);
        Map<String, JsonNode> ka = keyed(ea);
        Map<String, JsonNode> kb = keyed(eb);
        StringBuilder sb = new StringBuilder();
        sb.append("A ").append(ida).append(" · ").append(ea.path("status").asText()).append(" · ").append(ms(millis(ea.path("duration"))))
                .append(" · ").append(ka.size()).append(" passos\n")
                .append("B ").append(idb).append(" · ").append(eb.path("status").asText()).append(" · ").append(ms(millis(eb.path("duration"))))
                .append(" · ").append(kb.size()).append(" passos\n");
        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (var e : ka.entrySet()) {
            JsonNode nb = kb.get(e.getKey());
            if (nb == null) {
                onlyA.add(e.getKey());
                continue;
            }
            JsonNode na = e.getValue();
            String sa = na.path("status").asText(), sbb = nb.path("status").asText();
            if (!sa.equals(sbb)) {
                changed.add(e.getKey() + ": status " + sa + " → " + sbb + (nb.path("error").isObject()
                        ? " (" + nb.path("error").path("type").asText() + ": " + truncate(oneLine(nb.path("error").path("message").asText()), 120) + ")" : ""));
            }
            long da = millis(na.path("totalTime")), db = millis(nb.path("totalTime"));
            if (Math.max(da, db) >= 5 && Math.abs(da - db) > 0.2 * Math.max(1, Math.min(da, db))) {
                changed.add(e.getKey() + ": " + ms(da) + " → " + ms(db));
            }
            String ma = na.path("mutation").path("kind").asText(""), mb = nb.path("mutation").path("kind").asText("");
            if (!ma.equals(mb)) {
                changed.add(e.getKey() + ": Δ " + (ma.isBlank() ? "nenhum" : ma) + " → " + (mb.isBlank() ? "nenhum" : mb));
            }
            String mka = Formats.mockMark(na), mkb = Formats.mockMark(nb);
            if (!mka.equals(mkb)) {
                changed.add(e.getKey() + ": mock" + (mka.isBlank() ? " (real)" : mka) + " → " + (mkb.isBlank() ? "(real)" : mkb));
            }
        }
        for (String k : kb.keySet()) {
            if (!ka.containsKey(k)) {
                onlyB.add(k);
            }
        }
        section(sb, "SÓ EM A (não aconteceu em B)", onlyA);
        section(sb, "SÓ EM B (novo em B)", onlyB);
        section(sb, "MUDOU", changed);
        if (onlyA.isEmpty() && onlyB.isEmpty() && changed.isEmpty()) {
            sb.append("\nMesma forma, mesmos status e durações dentro de 20 %.\n");
        }
        return Tool.Result.ok(sb.toString());
    }

    private Tool.Result endpoints() throws Trace2LocalClient.ApiException {
        JsonNode eps = api.get("/endpoints");
        JsonNode list = eps.isArray() ? eps : eps.path("endpoints");
        if (list.size() == 0) {
            return Tool.Result.ok("Nenhum endpoint descoberto neste modo (Lambda sem contrato, ou app sem API HTTP). "
                    + "No Station, defina TRACE2LOCAL_OPENAPI_SPEC para disparar pelo contrato.");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : list) {
            sb.append("- ").append(e.path("endpointId").asText()).append(" · ").append(e.path("method").asText()).append(' ')
                    .append(e.path("path").asText()).append(" · ").append(e.path("handler").asText("")).append('\n');
            for (JsonNode p : e.path("parameters")) {
                sb.append("    ").append(p.path("in").asText()).append(' ').append(p.path("name").asText())
                        .append(p.path("required").asBoolean() ? " (obrigatório)" : "")
                        .append(p.path("example").isTextual() ? " ex.: " + p.path("example").asText() : "").append('\n');
            }
            if (e.path("sampleBody").isTextual()) {
                sb.append("    corpo de exemplo: ").append(truncate(oneLine(e.path("sampleBody").asText()), 300)).append('\n');
            }
        }
        return Tool.Result.ok(sb.toString(), Json.obj().set("endpoints", list));
    }

    private Tool.Result waitFor(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.optional("executionId");
        String trace = a.optional("traceId");
        if (id == null && trace == null) {
            return Tool.Result.fail("informe executionId ou traceId");
        }
        JsonNode found = await(id, trace, a.integer("timeoutSeconds", 30, 1, 120));
        if (found == null) {
            return Tool.Result.fail("A execução ainda não chegou concluída ao Trace2Local. Confira se o app exporta para "
                    + api.base() + " (endpoint/token do Station) e tente de novo.");
        }
        return Tool.Result.ok(Formats.summaryLine(found), found);
    }

    // ----------------------------------------------------------------- Mock Connect (leitura)

    private Tool.Result mockSuggestions(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String host = a.optional("host");
        JsonNode list = mocks("/mocks/suggestions");
        StringBuilder sb = new StringBuilder();
        ArrayNode picked = Json.arr();
        for (JsonNode s : list) {
            String target = s.path("target").asText("");
            if (host != null && !target.startsWith(host)) {
                continue;
            }
            picked.add(s);
            sb.append("- [").append(s.path("id").asText()).append("] ").append(s.path("severity").asText()).append(" · ")
                    .append(s.path("kind").asText()).append(" · ").append(target)
                    .append(s.path("activeBinding").isTextual() ? " · JÁ PLUGADO (" + s.path("activeBinding").asText() + ")" : "")
                    .append('\n').append("  ").append(s.path("title").asText()).append('\n')
                    .append("  por quê: ").append(truncate(oneLine(s.path("why").asText()), 400)).append('\n');
            for (JsonNode ev : s.path("evidence")) {
                sb.append("  evidência: ").append(truncate(ev.path("text").asText(), 160)).append(" → execução ")
                        .append(ev.path("executionId").asText()).append(" passo ").append(ev.path("nodeId").asText()).append('\n');
                break;
            }
            if (s.path("variations").size() > 0) {
                sb.append("  variações:");
                for (JsonNode v : s.path("variations")) {
                    sb.append(' ').append(v.path("id").asText());
                }
                sb.append('\n');
                for (JsonNode v : s.path("variations")) {
                    sb.append("    ").append(v.path("id").asText()).append(" — ").append(v.path("title").asText())
                            .append(": ").append(truncate(v.path("rationale").asText(), 140))
                            .append(v.path("predicate").isNull() || v.path("predicate").isMissingNode() ? "" : " (predicado próprio; não selecionável por baggage)")
                            .append('\n');
                }
            }
        }
        if (picked.isEmpty()) {
            return Tool.Result.ok("Nenhuma sugestão" + (host != null ? " para " + host : "")
                    + ". O conselheiro só sugere com evidência: API fora do ar, resposta que decide o fluxo, só caminho feliz, "
                    + "lentidão ou divergência de contrato.");
        }
        sb.append("\nPara plugar: apply_mock_suggestion(suggestionId, variations, mode). Sob demanda, envie ")
                .append("'baggage: t2l.mock=<id>' na chamada ao serviço.\n");
        return Tool.Result.ok(sb.toString(), Json.obj().set("suggestions", picked));
    }

    private Tool.Result mockBindings() throws Trace2LocalClient.ApiException {
        JsonNode o = mocks("/mocks");
        StringBuilder sb = new StringBuilder();
        sb.append(o.path("service").asText("Mock Connect")).append(" · plugins ").append(o.path("plugins").asInt())
                .append(" · contratos ").append(o.path("contracts").asInt()).append(" · chamadas atendidas ")
                .append(o.path("journalTotal").asInt()).append('\n');
        if (o.path("bindings").size() == 0) {
            sb.append("Nenhum binding. Veja list_mock_suggestions.\n");
        }
        for (JsonNode b : o.path("bindings")) {
            sb.append("- ").append(b.path("name").asText()).append(" · ").append(b.path("state").asText()).append(" · ")
                    .append(b.path("api").asText()).append(" @ ").append(b.path("target").asText()).append(" · ")
                    .append(b.path("source").asText()).append(" → ").append(b.path("sink").asText())
                    .append(" · stubs ").append(b.path("stubs").asInt()).append(" · chamadas ").append(b.path("hits").asInt()).append('\n');
            if (b.path("transforms").size() > 0) {
                sb.append("  transformações: ").append(join(b.path("transforms"))).append('\n');
            }
            if (b.path("endpoint").isTextual()) {
                sb.append("  endpoint: ").append(b.path("endpoint").asText()).append(b.path("routing").asBoolean() ? " (roteado no cliente)" : "").append('\n');
            }
            if (b.path("trace").isTextual()) {
                sb.append("  FALHA: ").append(b.path("trace").asText()).append('\n');
            }
        }
        for (JsonNode w : o.path("warnings")) {
            sb.append("AVISO: ").append(w.asText()).append('\n');
        }
        return Tool.Result.ok(sb.toString(), o);
    }

    private Tool.Result mockJournal(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String binding = a.optional("binding");
        int limit = a.integer("limit", 50, 1, 500);
        JsonNode list = mocks("/mocks/journal?limit=" + limit + (binding != null ? "&binding=" + enc(binding) : ""));
        if (list.size() == 0) {
            return Tool.Result.ok("Nenhuma chamada atendida pelo mock ainda.");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode j : list) {
            sb.append("- ").append(j.path("at").asText()).append(" · ").append(j.path("binding").asText()).append(" · ")
                    .append(j.path("method").asText()).append(' ').append(j.path("path").asText()).append(" → ")
                    .append(j.path("status").asInt() < 0 ? "falha de rede" : j.path("status").asText())
                    .append(" · stub ").append(j.path("stubId").asText("—"))
                    .append(j.path("applied").size() > 0 ? " · variação " + join(j.path("applied")) : "")
                    .append(" · ").append(j.path("outcome").asText()).append(" · ").append(j.path("tookMs").asInt()).append(" ms")
                    .append(j.path("traceId").isTextual() ? " · trace " + j.path("traceId").asText() : "").append('\n');
        }
        return Tool.Result.ok(sb.toString());
    }

    private Tool.Result mockPlugins(JsonNode raw) throws Trace2LocalClient.ApiException {
        String type = Args.upper(new Args(raw).optional("type"));
        JsonNode list = mocks("/mocks/plugins");
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : list) {
            if (type != null && !type.equals(p.path("type").asText())) {
                continue;
            }
            sb.append("- ").append(p.path("type").asText()).append(' ').append(p.path("name").asText()).append(" v")
                    .append(p.path("version").asText()).append(": ").append(p.path("description").asText()).append('\n');
            for (JsonNode k : p.path("config")) {
                sb.append("    ").append(k.path("name").asText()).append(" (").append(k.path("type").asText().toLowerCase(Locale.ROOT))
                        .append(k.path("required").asBoolean() ? ", obrigatório" : k.path("defaultValue").isNull() ? "" : ", padrão " + k.path("defaultValue").asText())
                        .append(") ").append(truncate(k.path("documentation").asText(), 160)).append('\n');
            }
        }
        return Tool.Result.ok(sb.length() == 0 ? "Nenhum plugin" + (type != null ? " do tipo " + type : "") : sb.toString());
    }

    private Tool.Result validateBinding(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        ObjectNode body = Json.obj().put("name", a.required("name"));
        body.set("config", a.stringMap("config"));
        JsonNode r = mocks("PUT", "/mocks/validate", body);
        return Tool.Result.ok(validation(r), r);
    }

    // ===================================================================================== mutações

    private Tool.Result dispatch(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        ObjectNode body = Json.obj().put("endpointId", a.required("endpointId"));
        JsonNode payload = a.raw("body");
        body.set("body", payload == null ? Json.MAPPER.nullNode() : payload);
        body.set("headers", a.stringMap("headers"));
        body.set("pathVariables", a.stringMap("pathVariables"));
        JsonNode r = api.send("POST", "/execute", body);
        String id = r.path("executionId").isTextual() ? r.path("executionId").asText() : null;
        String trace = r.path("traceId").isTextual() ? r.path("traceId").asText() : null;
        String head = "Disparado: " + (id != null ? "execução " + id : "") + (trace != null ? " trace " + trace : "") + "\n";
        if (!a.bool("wait", true)) {
            return Tool.Result.ok(head + "Use wait_for_execution para acompanhar.", r);
        }
        JsonNode s = await(id, trace, a.integer("timeoutSeconds", 45, 1, 120));
        if (s == null) {
            return Tool.Result.ok(head + "A execução ainda não chegou concluída (Lambda fria? fila?). Use wait_for_execution.", r);
        }
        JsonNode e = execution(s.path("executionId").asText());
        return Tool.Result.ok(head + Formats.outline(e, 200, config.dataMode()), s);
    }

    private Tool.Result applySuggestion(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String id = a.required("suggestionId");
        ObjectNode body = Json.obj();
        ArrayNode vars = body.putArray("variations");
        a.strings("variations").forEach(vars::add);
        body.put("mode", a.oneOf("mode", "exclusive", "exclusive", "on-demand"));
        JsonNode info = mocks("POST", "/mocks/suggestions/" + enc(id) + "/apply", body);
        JsonNode st = info.path("status");
        StringBuilder sb = new StringBuilder();
        sb.append("Binding ").append(info.path("name").asText()).append(": ").append(st.path("state").asText())
                .append(" · ").append(st.path("stubs").asInt()).append(" stub(s)").append('\n');
        if (st.path("trace").isTextual()) {
            sb.append("FALHA: ").append(st.path("trace").asText()).append('\n');
        }
        if (st.path("endpoint").isTextual()) {
            sb.append("endpoint: ").append(st.path("endpoint").asText()).append('\n');
        }
        if ("on-demand".equals(body.path("mode").asText())) {
            for (JsonNode v : vars) {
                sb.append("ative por requisição com o cabeçalho → baggage: t2l.mock=").append(v.asText()).append('\n');
            }
        }
        sb.append("Clientes com Trace2LocalHttp e TRACE2LOCAL_MOCKS_ROUTING=on já chamam o mock; o passo aparece como SIM.\n");
        return Tool.Result.ok(sb.toString(), info);
    }

    private Tool.Result putBinding(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String name = a.required("name");
        JsonNode info = mocks("PUT", "/mocks/bindings/" + enc(name) + "/config", a.stringMap("config"));
        JsonNode st = info.path("status");
        return Tool.Result.ok("Binding " + info.path("name").asText(name) + ": " + st.path("state").asText()
                + (st.path("trace").isTextual() ? " — " + st.path("trace").asText() : "")
                + (st.path("endpoint").isTextual() ? "\nendpoint: " + st.path("endpoint").asText() : ""), info);
    }

    private Tool.Result controlBinding(JsonNode raw) throws Trace2LocalClient.ApiException {
        Args a = new Args(raw);
        String name = a.required("name");
        String action = a.oneOf("action", null, "pause", "resume", "restart", "delete");
        String path = "/mocks/bindings/" + enc(name);
        switch (action) {
            case "pause", "resume" -> mocks("PUT", path + "/" + action, null);
            case "restart" -> mocks("POST", path + "/restart", null);
            default -> mocks("DELETE", path, null);
        }
        return Tool.Result.ok("Binding " + name + ": " + action + " ok");
    }

    // ===================================================================================== apoio

    private JsonNode execution(String id) throws Trace2LocalClient.ApiException {
        try {
            return api.get("/executions/" + enc(id));
        } catch (Trace2LocalClient.ApiException e) {
            if (e.status() == 404) {
                throw new Trace2LocalClient.ApiException(404, "execução " + id + " não está no acervo (ainda em curso, "
                        + "descartada pela retenção ou id errado) — use list_executions");
            }
            throw e;
        }
    }

    private JsonNode mocks(String path) throws Trace2LocalClient.ApiException {
        return mocks("GET", path, null);
    }

    private JsonNode mocks(String method, String path, JsonNode body) throws Trace2LocalClient.ApiException {
        try {
            return api.send(method, path, body);
        } catch (Trace2LocalClient.ApiException e) {
            if (e.status() == 404 && path.startsWith("/mocks") && !path.contains("/bindings/") && !path.contains("/suggestions/")) {
                throw new Trace2LocalClient.ApiException(404, "Mock Connect indisponível neste modo — ele roda no Station "
                        + "(TRACE2LOCAL_MOCKS=on, padrão)");
            }
            throw e;
        }
    }

    private JsonNode safe(String path) {
        try {
            return api.get(path);
        } catch (Trace2LocalClient.ApiException e) {
            return null;
        }
    }

    private JsonNode await(String id, String trace, int timeoutSeconds) throws Trace2LocalClient.ApiException {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (true) {
            for (JsonNode s : api.get("/executions?limit=200")) {
                boolean match = (id != null && id.equals(s.path("executionId").asText()))
                        || (trace != null && trace.equals(s.path("traceId").asText()));
                if (match && !"RUNNING".equals(s.path("status").asText())) {
                    return s;
                }
            }
            if (System.nanoTime() > deadline) {
                return null;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private List<String> stepLogs(String id, JsonNode execution, JsonNode node) {
        JsonNode logs = safe("/executions/" + enc(id) + "/logs");
        if (logs == null) {
            return List.of();
        }
        Set<String> subtree = new LinkedHashSet<>();
        Formats.walk(Json.arr().add(node), (n, d) -> subtree.add(n.path("nodeId").asText()));
        boolean entry = Formats.isEntry(node);
        Set<String> requestIds = new LinkedHashSet<>();
        if (entry) {
            for (JsonNode l : logs.path("lines")) {
                if (subtree.contains(l.path("spanId").asText()) && l.path("requestId").isTextual()) {
                    requestIds.add(l.path("requestId").asText());
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (JsonNode l : logs.path("lines")) {
            String span = l.path("spanId").asText("");
            boolean mine = node.path("nodeId").asText().equals(span)
                    || (entry && span.isBlank() && requestIds.contains(l.path("requestId").asText()));
            if (mine) {
                out.add(logLine(l));
            }
            if (out.size() >= 60) {
                break;
            }
        }
        return out;
    }

    private static String logLine(JsonNode l) {
        String level = l.path("level").asText("INFO");
        return "+" + ms(l.path("offsetMs").asLong()) + " " + ("PLATFORM".equalsIgnoreCase(level) ? "PLATAF." : level)
                + (l.path("logGroup").isTextual() ? " [" + l.path("logGroup").asText() + "]" : "")
                + " " + truncate(oneLine(l.path("message").asText()), 500)
                + (l.path("spanId").isTextual() && !l.path("spanId").asText().isBlank() ? " (passo " + l.path("spanId").asText() + ")" : "");
    }

    private static boolean isDescendant(JsonNode ancestor, JsonNode candidate) {
        for (JsonNode c : ancestor.path("children")) {
            if (c == candidate || isDescendant(c, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Passos chaveados pelo caminho de rótulos (com ordinal para repetições) — base do diff A/B. */
    private static Map<String, JsonNode> keyed(JsonNode execution) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        Formats.walkWithParent(execution.path("roots"), null, 0, (n, parent, depth) -> {
            List<JsonNode> path = Formats.pathTo(execution, n.path("nodeId").asText());
            StringBuilder k = new StringBuilder();
            for (JsonNode p : path) {
                k.append(k.isEmpty() ? "" : " › ").append(truncate(p.path("label").asText(), 50));
            }
            String key = k.toString();
            int ord = seen.merge(key, 1, Integer::sum);
            out.put(ord == 1 ? key : key + " #" + ord, n);
        });
        return out;
    }

    private static void section(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append('\n');
        items.stream().limit(60).forEach(i -> sb.append("- ").append(i).append('\n'));
        if (items.size() > 60) {
            sb.append("… +").append(items.size() - 60).append('\n');
        }
    }

    private static String decision(JsonNode d) {
        if (d == null || d.isMissingNode() || d.isNull()) {
            return "—";
        }
        return d.path("label").asText("?") + " (" + Math.round(d.path("confidence").asDouble() * 100) + "%, "
                + d.path("engine").asText("?") + ") — " + d.path("rationale").asText("");
    }

    private static String validation(JsonNode r) {
        long errors = r.path("errorCount").asLong();
        if (errors == 0) {
            return "Config válida (" + r.path("configs").size() + " chave(s) conferidas).";
        }
        StringBuilder sb = new StringBuilder(errors + " problema(s):\n");
        for (JsonNode c : r.path("configs")) {
            for (JsonNode e : c.path("value").path("errors")) {
                sb.append("- ").append(c.path("value").path("name").asText()).append(": ").append(e.asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String join(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            arr.forEach(x -> out.add(x.asText()));
        }
        return String.join(", ", out);
    }

    private static String firstLines(String s, int n) {
        String[] lines = s.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > n) {
            sb.append("… (+").append(lines.length - n).append(" linhas)");
        }
        return sb.toString().stripTrailing();
    }

    private static String indent(String s, String pad) {
        return pad + s.replace("\n", "\n" + pad);
    }
}
