package tech.neural7.trace2local.internal;

import tech.neural7.trace2local.config.Trace2LocalConfig;

/** Configurações de teste: quiescência curta para os asserts não dependerem de relógio. */
public final class TestPipelineConfig {

    private TestPipelineConfig() {}

    public static Trace2LocalConfig fast() {
        return Trace2LocalConfig.builder()
                .quiescenceMs(2_000)
                .retentionMaxExecutions(100)
                .build();
    }

    public static Trace2LocalConfig fastWithRetention(int retention) {
        return Trace2LocalConfig.builder()
                .quiescenceMs(2_000)
                .retentionMaxExecutions(retention)
                .build();
    }

    public static Trace2LocalConfig fastWithBuffer(int bufferCapacity) {
        return Trace2LocalConfig.builder()
                .quiescenceMs(2_000)
                .bufferCapacity(bufferCapacity)
                .retentionMaxExecutions(100)
                .build();
    }

    /** Buffer minúsculo + quiescência curta: descarte e completude por janela acontecem rápido. */
    public static Trace2LocalConfig fastDrop() {
        return Trace2LocalConfig.builder()
                .quiescenceMs(300)
                .bufferCapacity(4)
                .retentionMaxExecutions(100)
                .build();
    }
}
