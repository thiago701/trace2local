package tech.neural7.trace2local.otel;

/**
 * ÚNICO lugar do projeto onde os nomes de atributo do OpenTelemetry existem como
 * literais (ADR-008). Os módulos que PRODUZEM spans (jdbc, starter) importam
 * estas constantes — nunca escrevem o nome no próprio código. O teste de
 * arquitetura faz grep dos literais {@code db.}, {@code aws.}, {@code messaging.},
 * {@code rpc.} e {@code http.} fora deste módulo.
 */
public final class OtelAttributeNames {

    // banco de dados (estável)
    public static final String DB_SYSTEM = "db.system.name";
    public static final String DB_NAMESPACE = "db.namespace";
    public static final String DB_OPERATION = "db.operation.name";
    public static final String DB_COLLECTION = "db.collection.name";
    public static final String DB_QUERY_TEXT = "db.query.text";
    public static final String DB_STATEMENT = "db.statement";
    public static final String DB_ROWS_AFFECTED = "db.response.returned_rows";

    // AWS (development)
    public static final String RPC_SYSTEM = "rpc.system";
    public static final String RPC_SERVICE = "rpc.service";
    public static final String RPC_METHOD = "rpc.method";
    public static final String AWS_DYNAMO_TABLES = "aws.dynamodb.table_names";
    public static final String AWS_SNS_TOPIC = "aws.sns.topic.arn";
    public static final String AWS_SQS_QUEUE = "aws.sqs.queue.url";

    // mensageria (development)
    public static final String MESSAGING_SYSTEM = "messaging.system";
    public static final String MESSAGING_DESTINATION = "messaging.destination.name";
    public static final String MESSAGING_OPERATION = "messaging.operation.type";

    // HTTP (estável)
    public static final String HTTP_METHOD = "http.request.method";
    public static final String HTTP_ROUTE = "http.route";
    public static final String HTTP_STATUS = "http.response.status_code";

    // FaaS / Lambda (estável)
    public static final String FAAS_NAME = "faas.name";
    public static final String FAAS_INVOCATION_ID = "faas.invocation_id";

    // código (estável) — usados pelos nós BUSINESS
    public static final String CODE_NAMESPACE = "code.namespace";
    public static final String CODE_FUNCTION = "code.function";

    private OtelAttributeNames() {}
}
