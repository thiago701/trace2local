package tech.neural7.trace2local.predictive.api;

/**
 * Uma EVIDÊNCIA (princípio Evidence First): de onde o insight saiu e para onde a
 * UI navega. Nenhuma recomendação relevante existe sem pelo menos uma.
 *
 * @param kind   natureza da fonte
 * @param label  o que é (ex.: {@code "SQS wait"}, {@code "orders PutItem ×121"})
 * @param value  valor observado (ex.: {@code "4.1 s"}, {@code "78.8%"})
 * @param ref    alvo de navegação — ver {@link Ref}
 */
public record Evidence(Kind kind, String label, String value, Ref ref) {

    public enum Kind { SPAN, SEGMENT, METRIC, LOG, HISTORY, CONFIG, IAC, TEST, CODE, DATA }

    /**
     * Alvo navegável. Campos nulos quando não se aplicam.
     *
     * @param executionId execução
     * @param nodeId      nó (span) da árvore
     * @param file        arquivo do projeto (Terraform, pom, application.yml, relatório de teste)
     * @param line        linha no arquivo (1-based) ou 0
     * @param component   componente da topologia (fila, tabela, função, rota)
     */
    public record Ref(String executionId, String nodeId, String file, int line, String component) {

        public static Ref node(String executionId, String nodeId) {
            return new Ref(executionId, nodeId, null, 0, null);
        }

        public static Ref file(String file, int line) {
            return new Ref(null, null, file, line, null);
        }

        public static Ref component(String component) {
            return new Ref(null, null, null, 0, component);
        }

        public static Ref execution(String executionId) {
            return new Ref(executionId, null, null, 0, null);
        }
    }

    public static Evidence span(String label, String value, String executionId, String nodeId) {
        return new Evidence(Kind.SPAN, label, value, Ref.node(executionId, nodeId));
    }

    public static Evidence segment(String label, String value, String executionId, String nodeId) {
        return new Evidence(Kind.SEGMENT, label, value, Ref.node(executionId, nodeId));
    }

    public static Evidence metric(String label, String value) {
        return new Evidence(Kind.METRIC, label, value, null);
    }

    public static Evidence history(String label, String value) {
        return new Evidence(Kind.HISTORY, label, value, null);
    }

    public static Evidence file(Kind kind, String label, String value, String file, int line) {
        return new Evidence(kind, label, value, Ref.file(file, line));
    }
}
