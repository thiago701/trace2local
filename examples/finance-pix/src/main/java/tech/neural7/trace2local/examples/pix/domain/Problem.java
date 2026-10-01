package tech.neural7.trace2local.examples.pix.domain;

/** Recusa de negócio ou erro de entrada com código do contrato (Problem do OpenAPI). */
public final class Problem extends RuntimeException {

    private final int status;
    private final String code;
    private final String partner;

    public Problem(int status, String code, String message) {
        this(status, code, message, null);
    }

    public Problem(int status, String code, String message, String partner) {
        super(message);
        this.status = status;
        this.code = code;
        this.partner = partner;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String partner() { return partner; }
}
