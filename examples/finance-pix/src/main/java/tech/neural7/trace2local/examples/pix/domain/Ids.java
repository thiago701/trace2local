package tech.neural7.trace2local.examples.pix.domain;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Identificadores do domínio Pix. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final DateTimeFormatter E2E_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private Ids() {}

    public static String transferId() {
        return "pix-" + random(12).toLowerCase();
    }

    /** endToEndId: {@code E} + ISPB do pagador (8) + data/hora UTC (12) + sequencial (11) = 32 caracteres. */
    public static String endToEndId(String payerIspb) {
        return "E" + payerIspb + ZonedDateTime.now(ZoneOffset.UTC).format(E2E_TIME) + random(11);
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
