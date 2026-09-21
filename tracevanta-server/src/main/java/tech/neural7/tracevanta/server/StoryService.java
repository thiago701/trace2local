package tech.neural7.tracevanta.server;

import tech.neural7.tracevanta.model.DataMutation;
import tech.neural7.tracevanta.model.Execution;
import tech.neural7.tracevanta.model.Node;
import tech.neural7.tracevanta.model.NodeKind;
import tech.neural7.tracevanta.model.Trigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_DYNAMO_TABLES;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_SNS_TOPIC;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.AWS_SQS_QUEUE;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.DB_COLLECTION;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.DB_OPERATION;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.FAAS_NAME;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_METHOD;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_ROUTE;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.HTTP_STATUS;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.MESSAGING_DESTINATION;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.MESSAGING_OPERATION;
import static tech.neural7.tracevanta.otel.OtelAttributeNames.RPC_METHOD;

/**
 * Descoberta de especificação e regras de negócio por CONTEXTO, DOCS e
 * ENGENHARIA REVERSA (SPEC §4.12 bis — storytelling):
 *
 * <ul>
 *   <li><b>Contexto</b>: semântica do nó (kind), atributos OTel (HTTP, banco,
 *       mensageria, Lambda), mutação de dados, status e erro;</li>
 *   <li><b>Engenharia reversa</b>: nomes de métodos/operações em camelCase
 *       humanizados com mapa de verbos de negócio PT-BR
 *       ({@code CreateOrder} → "cria o pedido");</li>
 *   <li><b>Docs</b>: {@link BusinessGlossary} opcional ({@code tracevanta-business.md})
 *       sobrescreve a nota quando o time documenta o termo.</li>
 * </ul>
 *
 * O resultado é uma NARRATIVA: cada nó ganha uma nota em linguagem de negócio
 * e a execução ganha uma história ordenada (intro → passos → desfecho) — o
 * canvas vira ferramenta de apresentação para PO, dev e QA.
 */
public final class StoryService {

    private final BusinessGlossary glossary;

    public StoryService() {
        this(new BusinessGlossary());
    }

    public StoryService(BusinessGlossary glossary) {
        this.glossary = glossary;
    }

    /** Nota de negócio para um nó (anotação exibida ao lado da funcionalidade). */
    public String noteFor(Node node) {
        if (node == null) {
            return "";
        }
        // glossário descreve FUNCIONALIDADES, não entry points HTTP (a rota
        // "POST /orders" não é a tabela "orders")
        NodeKind kind = node.kind() != null ? node.kind() : NodeKind.UNKNOWN;
        if (kind != NodeKind.HTTP_SERVER && kind != NodeKind.HTTP_CLIENT) {
            String fromGlossary = glossary.noteFor(node.label());
            if (!fromGlossary.isBlank()) {
                return fromGlossary;
            }
        }
        Map<String, String> attrs = node.attributes() != null ? node.attributes() : Map.of();
        String base = switch (kind) {
            case HTTP_SERVER -> "Porta de entrada: requisição " + method(attrs) + " " + route(attrs)
                    + statusSuffix(attrs) + ".";
            case HTTP_CLIENT -> "Chamada externa " + method(attrs) + " para " + (route(attrs).isBlank() ? node.label() : route(attrs)) + ".";
            case BUSINESS -> businessNote(node.label());
            case DYNAMODB -> dynamoNote(attrs, node);
            case SQS -> "Mensageria SQS: " + messaging(attrs) + " em " + queue(attrs, node) + ".";
            case SNS -> "Mensageria SNS: " + messaging(attrs) + " no tópico " + topic(attrs) + ".";
            case SQL -> "Banco SQL: " + dbOperation(attrs) + " em " + sqlTarget(attrs, node) + ".";
            case LAMBDA -> "Função Lambda " + faasName(attrs, node) + ": execução serverless da funcionalidade.";
            case UNKNOWN -> humanize(node.label()) + " — passo do fluxo.";
        };
        return base + mutationSuffix(node);
    }

    /** História completa da execução: intro → passos em ordem → desfecho. */
    public Story storyFor(Execution execution) {
        if (execution == null) {
            return null;
        }
        String title = "Jornada " + (execution.trigger() != null ? triggerLabel(execution.trigger()) : "");
        String intro = introOf(execution);
        List<StoryStep> steps = new ArrayList<>();
        int[] order = { 1 };
        for (Node root : execution.roots() != null ? execution.roots() : List.<Node>of()) {
            collectSteps(root, steps, order);
        }
        String conclusion = conclusionOf(execution, steps);
        return new Story(execution.executionId(), title, intro, steps, conclusion,
                execution.status() != null ? execution.status().name() : "?",
                execution.duration() != null ? execution.duration().toMillis() : 0L);
    }

    private void collectSteps(Node node, List<StoryStep> out, int[] order) {
        if (node == null) {
            return;
        }
        out.add(new StoryStep(
                node.nodeId(),
                order[0]++,
                iconOf(node.kind()),
                node.kind() != null ? node.kind().name() : NodeKind.UNKNOWN.name(),
                noteFor(node),
                node.totalTime() != null ? node.totalTime().toMillis() : 0L,
                node.status() != null ? node.status().name() : "OK",
                node.error() != null,
                mutationSummary(node)));
        for (Node child : node.children() != null ? node.children() : List.<Node>of()) {
            collectSteps(child, out, order);
        }
    }

    // ------------------------------------------------------------------ notas

    private String businessNote(String label) {
        String name = humanize(label != null ? label : "negócio");
        // regra de negócio inferida do nome (engenharia reversa leve)
        return "Passo de negócio \"" + name + "\": " + verbPhrase(label);
    }

    private String verbPhrase(String label) {
        if (label == null) {
            return "executa a lógica da funcionalidade.";
        }
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("create") || l.contains("criar") || l.contains("save")) {
            return "cria o recurso e dispara os efeitos colaterais do fluxo.";
        }
        if (l.contains("update") || l.contains("atualizar")) {
            return "atualiza o estado do recurso.";
        }
        if (l.contains("delete") || l.contains("remover") || l.contains("remove")) {
            return "remove o recurso.";
        }
        if (l.contains("confirm")) {
            return "aplica a regra de confirmação (condição de estado) e segue o fluxo.";
        }
        if (l.contains("bill") || l.contains("charge") || l.contains("cobrar")) {
            return "executa a cobrança após a confirmação.";
        }
        if (l.contains("find") || l.contains("get") || l.contains("buscar") || l.contains("consult")) {
            return "busca e retorna o estado atual do recurso.";
        }
        if (l.contains("process")) {
            return "processa a entrada e coordena os próximos passos.";
        }
        if (l.contains("publish") || l.contains("send") || l.contains("notify")) {
            return "publica o evento para os consumidores.";
        }
        if (l.contains("validate") || l.contains("validar")) {
            return "valida a entrada segundo as regras de negócio.";
        }
        return "executa a lógica da funcionalidade.";
    }

    private String dynamoNote(Map<String, String> attrs, Node node) {
        String table = attrs.getOrDefault(AWS_DYNAMO_TABLES, node.label());
        String op = attrs.getOrDefault(RPC_METHOD, "operação");
        DataMutation m = node.mutation();
        if (m != null && m.kind() != null) {
            return switch (m.kind()) {
                case CREATE -> "Grava " + (m.key() != null ? m.key() : "o registro") + " na tabela " + table
                        + " (fidelidade " + m.fidelity() + ").";
                case UPDATE -> "Atualiza " + (m.key() != null ? m.key() : "o registro") + " na tabela " + table
                        + (m.before() != null ? " — o antes e o depois estão no inspector." : ".");
                case DELETE -> "Remove " + (m.key() != null ? m.key() : "o registro") + " da tabela " + table + ".";
                case READ_ONLY -> "Lê " + (m.key() != null ? m.key() : "dados") + " da tabela " + table + " (somente leitura).";
            };
        }
        return "DynamoDB: " + op + " na tabela " + table + ".";
    }

    private String sqlTarget(Map<String, String> attrs, Node node) {
        String c = attrs.getOrDefault(DB_COLLECTION, "");
        return c.isBlank() ? (node.label() != null ? node.label() : "?") : c;
    }

    private String dbOperation(Map<String, String> attrs) {
        return attrs.getOrDefault(DB_OPERATION, "operação");
    }

    private String messaging(Map<String, String> attrs) {
        String op = attrs.getOrDefault(MESSAGING_OPERATION, "");
        if (op.isBlank()) {
            op = attrs.getOrDefault(RPC_METHOD, "");
        }
        return switch (op.toLowerCase(Locale.ROOT)) {
            case "publish" -> "publica o evento";
            case "receive" -> "recebe o evento";
            case "sendmessage" -> "envia a mensagem";
            case "receivemessage" -> "recebe a mensagem";
            case "process" -> "processa a mensagem";
            default -> op.isBlank() ? "troca mensagens" : op;
        };
    }

    private String queue(Map<String, String> attrs, Node node) {
        String q = attrs.getOrDefault(AWS_SQS_QUEUE, "");
        if (q.isBlank()) {
            q = attrs.getOrDefault(MESSAGING_DESTINATION, "");
        }
        if (q.isBlank() && node.label() != null) {
            return node.label();
        }
        String[] parts = q.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : q;
    }

    private String topic(Map<String, String> attrs) {
        String arn = attrs.getOrDefault(AWS_SNS_TOPIC, attrs.getOrDefault(MESSAGING_DESTINATION, "?"));
        String[] parts = arn.split(":");
        return parts.length > 0 ? parts[parts.length - 1] : arn;
    }

    private String method(Map<String, String> attrs) {
        String m = attrs.getOrDefault(HTTP_METHOD, "");
        return m.isBlank() ? "HTTP" : m;
    }

    private String route(Map<String, String> attrs) {
        String r = attrs.getOrDefault(HTTP_ROUTE, "");
        return r.isBlank() ? "ao serviço" : r;
    }

    private String statusSuffix(Map<String, String> attrs) {
        String s = attrs.getOrDefault(HTTP_STATUS, "");
        return s.isBlank() ? "" : " (status " + s + ")";
    }

    private String faasName(Map<String, String> attrs, Node node) {
        String n = attrs.getOrDefault(FAAS_NAME, "");
        return n.isBlank() ? (node.label() != null ? node.label() : "?") : n;
    }

    private String mutationSuffix(Node node) {
        DataMutation m = node.mutation();
        if (m == null || m.kind() == null) {
            return "";
        }
        return switch (m.kind()) {
            case CREATE -> " O delta de dados (antes/depois) está no inspector.";
            case UPDATE -> " O delta de dados (antes/depois) está no inspector.";
            case DELETE -> " O que existia antes está no inspector.";
            case READ_ONLY -> " Sem escrita: leitura não altera estado.";
        };
    }

    // ------------------------------------------------------------------ história

    private String introOf(Execution execution) {
        String how = switch (execution.trigger() != null ? execution.trigger() : Trigger.EXTERNAL) {
            case UI_DISPATCH -> "foi disparada pelo botão EXECUTE REQUEST da própria UI";
            case LAMBDA_EVENT -> "começou quando o evento chegou à função Lambda";
            case TEST -> "foi disparada por um teste automatizado";
            default -> "começou quando o serviço recebeu uma chamada externa";
        };
        return "A execução " + how + ".";
    }

    private String conclusionOf(Execution execution, List<StoryStep> steps) {
        long mutations = steps.stream().filter(s -> s.mutationSummary() != null && !s.mutationSummary().isBlank()).count();
        long errors = steps.stream().filter(StoryStep::error).count();
        StringBuilder sb = new StringBuilder();
        sb.append("No fim, a jornada terminou ");
        String status = execution.status() != null ? execution.status().name() : "?";
        sb.append(switch (status) {
            case "COMPLETED" -> "com sucesso";
            case "FAILED" -> "com erro — " + errors + " passo(s) vermelho(s) na árvore";
            case "PARTIAL" -> "parcialmente (há avisos de honestidade no painel AVISOS)";
            case "ORPHANED" -> "sem raiz observável";
            default -> "com status " + status;
        });
        sb.append(" em ").append(fmtDuration(execution.duration())).append(".");
        if (mutations > 0) {
            sb.append(" ").append(mutations).append(" passo(s) alteraram dados — os deltas estão nos inspectors.");
        }
        return sb.toString();
    }

    private String triggerLabel(Trigger trigger) {
        return switch (trigger) {
            case UI_DISPATCH -> "disparada pela UI";
            case LAMBDA_EVENT -> "do evento Lambda";
            case TEST -> "de teste";
            case EXTERNAL -> "externa";
        };
    }

    private String iconOf(NodeKind kind) {
        return switch (kind != null ? kind : NodeKind.UNKNOWN) {
            case HTTP_SERVER, HTTP_CLIENT -> "🌐";
            case BUSINESS -> "◆";
            case DYNAMODB -> "🗄️";
            case SQS -> "📬";
            case SNS -> "📣";
            case SQL -> "🗃️";
            case LAMBDA -> "λ";
            case UNKNOWN -> "·";
        };
    }

    static String humanize(String camel) {
        if (camel == null || camel.isBlank()) {
            return "funcionalidade";
        }
        String words = camel
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("[_\\-:]+", " ");
        if (words.isBlank()) {
            return camel;
        }
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    static String fmtDuration(Duration d) {
        if (d == null) {
            return "tempo não medido";
        }
        long ms = d.toMillis();
        return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }

    static String mutationSummary(Node node) {
        DataMutation m = node.mutation();
        if (m == null) {
            return "";
        }
        return (m.kind() != null ? m.kind() : "?") + " " + (m.key() != null ? m.key() : "")
                + " (" + m.fidelity() + ")";
    }

    // ------------------------------------------------------------------ records

    /** História completa de uma execução, em linguagem de negócio. */
    public record Story(
            String executionId,
            String title,
            String intro,
            List<StoryStep> steps,
            String conclusion,
            String status,
            long durationMs) {}

    /** Um passo da narrativa — corresponde a um nó da árvore, em ordem de fluxo. */
    public record StoryStep(
            String nodeId,
            int order,
            String icon,
            String kind,
            String text,
            long durationMs,
            String status,
            boolean error,
            String mutationSummary) {}
}
