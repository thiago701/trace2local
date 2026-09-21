package tech.neural7.trace2local.testing;

import tech.neural7.trace2local.config.Trace2LocalConfig;
import tech.neural7.trace2local.internal.Trace2LocalPipeline;

/** Acesso ao pipeline ativo do teste. */
public final class Trace2LocalRuntime {

    private static volatile Trace2LocalPipeline pipeline;
    private static volatile Trace2LocalConfig config;

    private Trace2LocalRuntime() {}

    static void start(Trace2LocalConfig cfg) {
        config = cfg;
        pipeline = Trace2LocalPipeline.start(cfg);
    }

    static void stop() {
        if (pipeline != null) {
            pipeline.close();
        }
        pipeline = null;
        config = null;
    }

    public static Trace2LocalPipeline pipeline() {
        if (pipeline == null) {
            throw new IllegalStateException("Trace2Local não está ativo — anote a classe com @Trace2LocalTest");
        }
        return pipeline;
    }

    public static Trace2LocalConfig config() {
        return config != null ? config : Trace2LocalConfig.defaults();
    }
}
