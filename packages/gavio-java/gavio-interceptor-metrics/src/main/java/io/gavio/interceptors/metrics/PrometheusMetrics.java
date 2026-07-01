package io.gavio.interceptors.metrics;

import java.util.Map;
import java.util.TreeMap;

/**
 * Prometheus metrics registry (F-OBS-08) — zero-dependency exposition.
 *
 * <p>Holds counters and a latency histogram keyed by {@code (provider, model)}
 * and renders them in the Prometheus text exposition format. No client library —
 * the format is hand-rolled so the core stays dependency-free. Thread-safe.
 */
public final class PrometheusMetrics {

    // Cumulative histogram bucket upper bounds, in milliseconds.
    private static final double[] LATENCY_BUCKETS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private record Key(String provider, String model) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = provider.compareTo(o.provider);
            return c != 0 ? c : model.compareTo(o.model);
        }
    }

    private static final class Histogram {
        final long[] bucketCounts = new long[LATENCY_BUCKETS.length];
        double sum = 0;
        long count = 0;

        void observe(double value) {
            count++;
            sum += value;
            for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
                if (value <= LATENCY_BUCKETS[i]) {
                    bucketCounts[i]++;
                }
            }
        }
    }

    private final String ns;
    private final Map<Key, Long> requests = new TreeMap<>();
    private final Map<Key, long[]> tokens = new TreeMap<>(); // [prompt, completion]
    private final Map<Key, Double> cost = new TreeMap<>();
    private final Map<Key, Long> cacheHits = new TreeMap<>();
    private final Map<Key, Histogram> latency = new TreeMap<>();

    public PrometheusMetrics() {
        this("gavio");
    }

    public PrometheusMetrics(String namespace) {
        this.ns = namespace;
    }

    public synchronized void record(
            String provider,
            String model,
            long promptTokens,
            long completionTokens,
            double costUsd,
            double latencyMs,
            boolean cacheHit) {
        Key k = new Key(provider, model);
        requests.merge(k, 1L, Long::sum);
        long[] tok = tokens.computeIfAbsent(k, x -> new long[2]);
        tok[0] += promptTokens;
        tok[1] += completionTokens;
        cost.merge(k, costUsd, Double::sum);
        latency.computeIfAbsent(k, x -> new Histogram()).observe(latencyMs);
        if (cacheHit) {
            cacheHits.merge(k, 1L, Long::sum);
        }
    }

    /** Return the Prometheus text exposition of all metrics. */
    public synchronized String render() {
        StringBuilder sb = new StringBuilder();

        help(sb, "requests_total", "Total gateway requests.", "counter");
        for (Map.Entry<Key, Long> e : requests.entrySet()) {
            line(sb, "requests_total", baseLabels(e.getKey()), Long.toString(e.getValue()));
        }

        help(sb, "tokens_total", "Total tokens processed.", "counter");
        for (Map.Entry<Key, long[]> e : tokens.entrySet()) {
            line(sb, "tokens_total", kindLabels(e.getKey(), "completion"), Long.toString(e.getValue()[1]));
            line(sb, "tokens_total", kindLabels(e.getKey(), "prompt"), Long.toString(e.getValue()[0]));
        }

        help(sb, "cost_usd_total", "Total estimated cost in USD.", "counter");
        for (Map.Entry<Key, Double> e : cost.entrySet()) {
            line(sb, "cost_usd_total", baseLabels(e.getKey()), num(e.getValue()));
        }

        help(sb, "request_latency_ms", "Request latency in milliseconds.", "histogram");
        for (Map.Entry<Key, Histogram> e : latency.entrySet()) {
            Key k = e.getKey();
            Histogram h = e.getValue();
            for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
                line(sb, "request_latency_ms_bucket", leLabels(k, num(LATENCY_BUCKETS[i])),
                        Long.toString(h.bucketCounts[i]));
            }
            line(sb, "request_latency_ms_bucket", leLabels(k, "+Inf"), Long.toString(h.count));
            line(sb, "request_latency_ms_sum", baseLabels(k), num(h.sum));
            line(sb, "request_latency_ms_count", baseLabels(k), Long.toString(h.count));
        }

        help(sb, "cache_hits_total", "Total cache hits.", "counter");
        for (Map.Entry<Key, Long> e : cacheHits.entrySet()) {
            line(sb, "cache_hits_total", baseLabels(e.getKey()), Long.toString(e.getValue()));
        }

        return sb.toString();
    }

    private void help(StringBuilder sb, String metric, String help, String type) {
        sb.append("# HELP ").append(ns).append('_').append(metric).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(ns).append('_').append(metric).append(' ').append(type).append('\n');
    }

    private void line(StringBuilder sb, String metric, String labels, String value) {
        sb.append(ns).append('_').append(metric).append(labels).append(' ').append(value).append('\n');
    }

    private static String baseLabels(Key k) {
        return "{provider=\"" + escape(k.provider()) + "\",model=\"" + escape(k.model()) + "\"}";
    }

    private static String kindLabels(Key k, String kind) {
        return "{provider=\"" + escape(k.provider()) + "\",model=\"" + escape(k.model())
                + "\",kind=\"" + escape(kind) + "\"}";
    }

    private static String leLabels(Key k, String le) {
        return "{provider=\"" + escape(k.provider()) + "\",model=\"" + escape(k.model())
                + "\",le=\"" + escape(le) + "\"}";
    }

    private static String num(double v) {
        if (!Double.isInfinite(v) && v == Math.floor(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
