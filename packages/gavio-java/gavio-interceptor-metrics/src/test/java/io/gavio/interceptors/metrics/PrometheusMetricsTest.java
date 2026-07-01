package io.gavio.interceptors.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import org.junit.jupiter.api.Test;

/** Prometheus metrics (F-OBS-08). */
class PrometheusMetricsTest {

    @Test
    void rendersCountersAndHistogram() {
        PrometheusMetrics m = new PrometheusMetrics();
        m.record("openai", "gpt-4o", 10, 5, 0.002, 42, false);
        m.record("openai", "gpt-4o", 20, 8, 0.004, 8, false);
        String t = m.render();

        assertTrue(t.contains("gavio_requests_total{provider=\"openai\",model=\"gpt-4o\"} 2"));
        assertTrue(t.contains("gavio_tokens_total{provider=\"openai\",model=\"gpt-4o\",kind=\"prompt\"} 30"));
        assertTrue(t.contains("gavio_tokens_total{provider=\"openai\",model=\"gpt-4o\",kind=\"completion\"} 13"));
        // Histogram: le="10" has 1 (the 8ms obs), +Inf has 2, count 2, sum 50.
        assertTrue(t.contains("gavio_request_latency_ms_bucket{provider=\"openai\",model=\"gpt-4o\",le=\"10\"} 1"));
        assertTrue(t.contains("gavio_request_latency_ms_bucket{provider=\"openai\",model=\"gpt-4o\",le=\"+Inf\"} 2"));
        assertTrue(t.contains("gavio_request_latency_ms_count{provider=\"openai\",model=\"gpt-4o\"} 2"));
        assertTrue(t.contains("gavio_request_latency_ms_sum{provider=\"openai\",model=\"gpt-4o\"} 50"));
    }

    @Test
    void helpAndTypeForEveryMetric() {
        String t = new PrometheusMetrics().render();
        for (String metric : new String[] {
            "gavio_requests_total", "gavio_tokens_total", "gavio_cost_usd_total",
            "gavio_request_latency_ms", "gavio_cache_hits_total",
        }) {
            assertTrue(t.contains("# HELP " + metric), metric);
            assertTrue(t.contains("# TYPE " + metric), metric);
        }
        assertTrue(t.endsWith("\n"));
    }

    @Test
    void countsCacheHits() {
        PrometheusMetrics m = new PrometheusMetrics();
        m.record("mock", "mock", 0, 0, 0, 0, true);
        m.record("mock", "mock", 0, 0, 0, 0, false);
        assertTrue(m.render().contains("gavio_cache_hits_total{provider=\"mock\",model=\"mock\"} 1"));
    }

    @Test
    void separatesSeriesByProviderAndModel() {
        PrometheusMetrics m = new PrometheusMetrics();
        m.record("openai", "gpt-4o", 0, 0, 0, 0, false);
        m.record("anthropic", "claude-sonnet-4-6", 0, 0, 0, 0, false);
        String t = m.render();
        assertTrue(t.contains("gavio_requests_total{provider=\"openai\",model=\"gpt-4o\"} 1"));
        assertTrue(t.contains("gavio_requests_total{provider=\"anthropic\",model=\"claude-sonnet-4-6\"} 1"));
    }

    @Test
    void recordsFromGateway() {
        MetricsInterceptor mi = new MetricsInterceptor();
        Gateway gw = Gateway.builder().devMode(true).use(mi).build();
        for (int i = 0; i < 3; i++) {
            gw.complete(GavioRequest.builder().message("user", "m" + i).model("mock").build()).join();
        }
        String t = mi.metrics().render();
        assertTrue(t.contains("gavio_requests_total{provider=\"mock\",model=\"mock\"} 3"));
        assertTrue(t.contains("gavio_request_latency_ms_count{provider=\"mock\",model=\"mock\"} 3"));
    }
}
