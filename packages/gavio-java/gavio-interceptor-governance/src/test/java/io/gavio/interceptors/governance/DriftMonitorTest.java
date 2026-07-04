package io.gavio.interceptors.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.inspector.CaptureMode;
import io.gavio.inspector.InspectorAnalytics;
import io.gavio.inspector.InspectorConfig;
import io.gavio.inspector.InspectorEvent;
import io.gavio.providers.MockProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DriftMonitorTest {

    // ── StatisticalDriftDetector ─────────────────────────────────────────────

    @Test
    void silentWhileBaselineFills() {
        StatisticalDriftDetector d = new StatisticalDriftDetector(10, 10, 3.0);
        for (int i = 0; i < 9; i++) {
            assertTrue(d.observe(Map.of("latency_ms", (double) (100 + i))).isEmpty());
        }
    }

    @Test
    void flagsZScoreSpike() {
        StatisticalDriftDetector d = new StatisticalDriftDetector(20, 12, 3.0);
        for (int i = 0; i < 12; i++) {
            d.observe(Map.of("latency_ms", (double) (100 + i % 5)));
        }
        assertTrue(d.observe(Map.of("latency_ms", 105.0)).isEmpty()); // in-distribution
        List<DriftAlert> alerts = d.observe(Map.of("latency_ms", 900.0)); // spike
        assertEquals(1, alerts.size());
        assertEquals("latency_ms", alerts.get(0).metric());
        assertEquals(900.0, alerts.get(0).value());
        assertTrue(Math.abs(alerts.get(0).z()) > 3);
    }

    @Test
    void zeroVarianceBaselineIsDriftWithNullZ() {
        StatisticalDriftDetector d = new StatisticalDriftDetector(10, 5, 3.0);
        for (int i = 0; i < 5; i++) {
            d.observe(Map.of("total_tokens", 42.0));
        }
        List<DriftAlert> alerts = d.observe(Map.of("total_tokens", 43.0));
        assertEquals(1, alerts.size());
        assertNull(alerts.get(0).z());
        assertEquals(0.0, alerts.get(0).baseline().get("std"));
    }

    @Test
    void metricsTrackedIndependently() {
        StatisticalDriftDetector d = new StatisticalDriftDetector(10, 4, 3.0);
        for (int i = 0; i < 4; i++) {
            d.observe(Map.of("latency_ms", (double) (100 + i % 3), "total_tokens", (double) (50 + i % 3)));
        }
        List<DriftAlert> alerts = d.observe(Map.of("latency_ms", 100.0, "total_tokens", 5000.0));
        assertEquals(List.of("total_tokens"), alerts.stream().map(DriftAlert::metric).toList());
    }

    // ── driftMonitor interceptor — governance.event + /api/stats ─────────────

    private static final DriftDetector ALWAYS_DRIFT = new DriftDetector() {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public List<DriftAlert> observe(Map<String, Double> sample) {
            Map.Entry<String, Double> e = sample.entrySet().iterator().next();
            return List.of(new DriftAlert(
                    e.getKey(), e.getValue(), Map.of("mean", 100.0, "std", 10.0, "n", 20), 80.0, 3.0));
        }
    };

    private static Gateway gw(DriftDetector detector) {
        return Gateway.builder()
                .adapter(new MockProvider("ok"))
                .model("mock")
                .use(DriftMonitor.builder().detector(detector).metrics(List.of("latency_ms")).build())
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.METADATA)
                        .startServer(false)
                        .build())
                .build();
    }

    private static GavioRequest req(String content) {
        return GavioRequest.builder().message("user", content).model("mock").build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsGovernanceEventAndSurfacesInStats() {
        Gateway gw = gw(ALWAYS_DRIFT);
        List<InspectorEvent> events = new ArrayList<>();
        gw.inspector().bus().subscribe(events::add);

        gw.complete(req("a")).join();
        gw.complete(req("b")).join();

        List<InspectorEvent> governance =
                events.stream().filter(e -> e.type().equals("governance.event")).toList();
        assertEquals(2, governance.size());
        assertEquals("drift", governance.get(0).data().get("kind"));
        assertEquals("latency_ms", governance.get(0).data().get("metric"));

        Map<String, Object> stats =
                InspectorAnalytics.buildStats(gw.inspector().buffer().summaries(-1), null, null);
        Map<String, Object> total = (Map<String, Object>) stats.get("total");
        assertEquals(Map.of("latency_ms", 2L), total.get("driftAlerts"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noGovernanceEventWhenNoDrift() {
        DriftDetector quiet = new DriftDetector() {
            @Override
            public String name() {
                return "quiet";
            }

            @Override
            public List<DriftAlert> observe(Map<String, Double> sample) {
                return List.of();
            }
        };
        Gateway gw = gw(quiet);
        List<InspectorEvent> events = new ArrayList<>();
        gw.inspector().bus().subscribe(events::add);

        gw.complete(req("a")).join();

        assertTrue(events.stream().noneMatch(e -> e.type().equals("governance.event")));
        Map<String, Object> stats =
                InspectorAnalytics.buildStats(gw.inspector().buffer().summaries(-1), null, null);
        Map<String, Object> total = (Map<String, Object>) stats.get("total");
        assertEquals(Map.of(), total.get("driftAlerts"));
    }
}
