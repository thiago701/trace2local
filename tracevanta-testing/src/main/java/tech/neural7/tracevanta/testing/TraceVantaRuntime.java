package tech.neural7.tracevanta.testing;

import tech.neural7.tracevanta.config.TraceVantaConfig;
import tech.neural7.tracevanta.internal.TraceVantaPipeline;

/** Acesso ao pipeline ativo do teste. */
public final class TraceVantaRuntime {

    private static volatile TraceVantaPipeline pipeline;
    private static volatile TraceVantaConfig config;

    private TraceVantaRuntime() {}

    static void start(TraceVantaConfig cfg) {
        config = cfg;
        pipeline = TraceVantaPipeline.start(cfg);
    }

    static void stop() {
        if (pipeline != null) {
            pipeline.close();
        }
        pipeline = null;
        config = null;
    }

    public static TraceVantaPipeline pipeline() {
        if (pipeline == null) {
            throw new IllegalStateException("TraceVanta não está ativo — anote a classe com @TraceVantaTest");
        }
        return pipeline;
    }

    public static TraceVantaConfig config() {
        return config != null ? config : TraceVantaConfig.defaults();
    }
}
