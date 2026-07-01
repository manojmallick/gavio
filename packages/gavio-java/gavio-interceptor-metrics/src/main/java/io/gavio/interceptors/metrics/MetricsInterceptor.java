package io.gavio.interceptors.metrics;

import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.concurrent.CompletableFuture;

/**
 * Post-interceptor that records per-request metrics into a registry (F-OBS-08).
 *
 * <p>Holds the {@link PrometheusMetrics} registry so callers can scrape it:
 *
 * <pre>{@code
 * MetricsInterceptor metrics = new MetricsInterceptor();
 * Gateway gw = Gateway.builder().devMode(true).use(metrics).build();
 * // ...
 * System.out.println(metrics.metrics().render());
 * }</pre>
 *
 * <p>Observation-only, so it always runs (including in dry-run).
 */
public final class MetricsInterceptor implements Interceptor {

    private final PrometheusMetrics metrics;

    public MetricsInterceptor() {
        this(new PrometheusMetrics());
    }

    public MetricsInterceptor(PrometheusMetrics metrics) {
        this.metrics = metrics != null ? metrics : new PrometheusMetrics();
    }

    public PrometheusMetrics metrics() {
        return metrics;
    }

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public boolean dryRunSafe() {
        return true;
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        metrics.record(
                response.provider(),
                response.model(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.costUsd(),
                response.latencyMs(),
                response.cacheHit());
        return CompletableFuture.completedFuture(response);
    }
}
