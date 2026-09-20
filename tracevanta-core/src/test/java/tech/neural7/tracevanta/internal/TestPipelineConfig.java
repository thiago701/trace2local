package tech.neural7.tracevanta.internal;

import tech.neural7.tracevanta.config.TraceVantaConfig;

/** Configurações de teste: quiescência curta para os asserts não dependerem de relógio. */
public final class TestPipelineConfig {

    private TestPipelineConfig() {}

    public static TraceVantaConfig fast() {
        return TraceVantaConfig.builder()
                .quiescenceMs(2_000)
                .retentionMaxExecutions(100)
                .build();
    }

    public static TraceVantaConfig fastWithRetention(int retention) {
        return TraceVantaConfig.builder()
                .quiescenceMs(2_000)
                .retentionMaxExecutions(retention)
                .build();
    }

    public static TraceVantaConfig fastWithBuffer(int bufferCapacity) {
        return TraceVantaConfig.builder()
                .quiescenceMs(2_000)
                .bufferCapacity(bufferCapacity)
                .retentionMaxExecutions(100)
                .build();
    }

    /** Buffer minúsculo + quiescência curta: descarte e completude por janela acontecem rápido. */
    public static TraceVantaConfig fastDrop() {
        return TraceVantaConfig.builder()
                .quiescenceMs(300)
                .bufferCapacity(4)
                .retentionMaxExecutions(100)
                .build();
    }
}
