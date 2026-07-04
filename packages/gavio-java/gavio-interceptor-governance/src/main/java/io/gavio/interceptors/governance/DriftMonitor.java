package io.gavio.interceptors.governance;

import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DriftMonitor (F-GOV-07) — alert when a provider's response distribution shifts.
 *
 * <p>A {@link DriftDetector} is fed one metric sample per request (latency,
 * tokens, …); the default {@link StatisticalDriftDetector} keeps a rolling
 * baseline and flags a sample that deviates beyond a z-score threshold. Alerts
 * surface as {@code governance.event} inspector events (and in {@code /api/stats})
 * and are logged.
 */
public final class DriftMonitor implements Interceptor {

    private static final Logger LOG = Logger.getLogger("gavio.drift");

    private final List<String> metrics;
    private final DriftDetector detector;

    private DriftMonitor(Builder b) {
        this.metrics = b.metrics;
        int min = b.minSamples != null ? b.minSamples : b.windowSize;
        this.detector = b.detector != null
                ? b.detector
                : new StatisticalDriftDetector(b.windowSize, min, b.threshold);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "drift_monitor";
    }

    @Override
    public boolean dryRunSafe() {
        return false; // never let a dry run pollute the baseline
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        for (DriftAlert alert : detector.observe(extract(response, ctx))) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", "drift");
            data.put("detector", detector.name());
            data.put("metric", alert.metric());
            data.put("value", alert.value());
            data.put("baseline", alert.baseline());
            data.put("z", alert.z());
            data.put("threshold", alert.threshold());
            ctx.recordGovernanceEvent(data);
            LOG.log(Level.WARNING, () -> "drift: " + alert.metric() + "=" + alert.value()
                    + " from baseline " + alert.baseline());
        }
        return CompletableFuture.completedFuture(response);
    }

    private Map<String, Double> extract(GavioResponse response, InterceptorContext ctx) {
        Map<String, Double> sample = new LinkedHashMap<>();
        for (String metric : metrics) {
            switch (metric) {
                case "latency_ms" -> sample.put(metric, (double) response.latencyMs());
                case "total_tokens" -> sample.put(metric, (double) response.usage().totalTokens());
                case "cost_usd" -> sample.put(metric, response.costUsd());
                case "risk_score" -> {
                    if (ctx.riskScore() != null) {
                        sample.put(metric, ctx.riskScore());
                    }
                }
                default -> {
                    // unknown metric — ignored
                }
            }
        }
        return sample;
    }

    /** Fluent builder for {@link DriftMonitor}. */
    public static final class Builder {
        private List<String> metrics = List.of("latency_ms", "total_tokens");
        private DriftDetector detector;
        private int windowSize = 50;
        private Integer minSamples;
        private double threshold = 3.0;

        public Builder metrics(List<String> metrics) {
            this.metrics = List.copyOf(metrics);
            return this;
        }

        public Builder detector(DriftDetector detector) {
            this.detector = detector;
            return this;
        }

        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        public Builder minSamples(int minSamples) {
            this.minSamples = minSamples;
            return this;
        }

        public Builder threshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public DriftMonitor build() {
            return new DriftMonitor(this);
        }
    }
}
