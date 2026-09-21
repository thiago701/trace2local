package software.amazon.awssdk.services.dynamodb;

/** Stub no pacote do AWS SDK v2 para exercitar a detecção por owner do scanner. */
public final class StubClient {

    private StubClient() {}

    public static void putItem(String table) {
        // no-op — só existe para o bytecode conter o owner software/amazon/awssdk/services/dynamodb
    }
}
