package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.model.ExecutionId;

import java.util.concurrent.atomic.AtomicLong;

/** Gerador de {@code TV-NNNNN} (SPEC §4.9). */
public final class ExecutionIds {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private ExecutionIds() {}

    public static String next() {
        return ExecutionId.format(SEQ.getAndIncrement());
    }
}
