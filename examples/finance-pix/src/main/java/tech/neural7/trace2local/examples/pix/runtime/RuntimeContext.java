package tech.neural7.trace2local.examples.pix.runtime;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.nio.charset.StandardCharsets;

/** {@link Context} da invocação, montado a partir dos cabeçalhos da Runtime API e das variáveis padrão da Lambda. */
record RuntimeContext(String requestId, long deadlineMs, String functionArn) implements Context {

    @Override public String getAwsRequestId() { return requestId; }
    @Override public String getLogGroupName() { return System.getenv("AWS_LAMBDA_LOG_GROUP_NAME"); }
    @Override public String getLogStreamName() { return System.getenv("AWS_LAMBDA_LOG_STREAM_NAME"); }
    @Override public String getFunctionName() { return System.getenv("AWS_LAMBDA_FUNCTION_NAME"); }
    @Override public String getFunctionVersion() { return System.getenv("AWS_LAMBDA_FUNCTION_VERSION"); }
    @Override public String getInvokedFunctionArn() { return functionArn; }
    @Override public CognitoIdentity getIdentity() { return null; }
    @Override public ClientContext getClientContext() { return null; }
    @Override public int getRemainingTimeInMillis() { return (int) Math.max(0, deadlineMs - System.currentTimeMillis()); }

    @Override
    public int getMemoryLimitInMB() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("AWS_LAMBDA_FUNCTION_MEMORY_SIZE", "512"));
        } catch (NumberFormatException e) {
            return 512;
        }
    }

    @Override
    public LambdaLogger getLogger() {
        return new LambdaLogger() {
            @Override public void log(String message) { System.out.println(message); }
            @Override public void log(byte[] message) { System.out.println(new String(message, StandardCharsets.UTF_8)); }
        };
    }
}
