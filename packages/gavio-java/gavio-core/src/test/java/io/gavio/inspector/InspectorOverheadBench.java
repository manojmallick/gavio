package io.gavio.inspector;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Inspector overhead benchmark — Java SDK (INSPECTOR_PLAN §13).
 *
 * <p>Same methodology as {@code benchmarks/inspector/bench.py}: per-request
 * latency with the inspector disabled (baseline), in metadata mode, and in
 * full mode — bus + ring buffer + emitter only, no HTTP server. A delay-padded
 * MockProvider emulates a real call and one mutating interceptor makes full
 * mode pay for its diff computation.
 *
 * <p>The class name deliberately does not match surefire's {@code *Test}
 * includes, so it is skipped by the normal build. Run it explicitly:
 * {@code mvn -pl gavio-core test -Dtest=InspectorOverheadBench}. It fails when
 * the CI thresholds are breached: metadata p50 overhead ≥ 10% of the simulated
 * provider call, full ≥ 25%.
 */
class InspectorOverheadBench {

    private static final double SIMULATED_DELAY_MS = 5.0;
    private static final int WARMUP = 20;
    private static final int ITERATIONS = 200;
    private static final double METADATA_BUDGET_PCT = 10.0;
    private static final double FULL_BUDGET_PCT = 25.0;

    private static final List<Message> MESSAGES =
            List.of(Message.of("user", "benchmark the inspector overhead ".repeat(8)));

    /** MockProvider padded with a fixed delay to emulate a real provider call. */
    private static final class DelayedMockProvider implements ProviderAdapter {
        private final MockProvider delegate = new MockProvider();

        @Override
        public String providerName() {
            return delegate.providerName();
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            return CompletableFuture
                    .runAsync(() -> { },
                            CompletableFuture.delayedExecutor(
                                    (long) SIMULATED_DELAY_MS, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> delegate.complete(request));
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return delegate.healthCheck();
        }
    }

    /** Mutates every request so full mode computes a mutation diff. */
    private static final class AnnotatorInterceptor implements Interceptor {
        @Override
        public String name() {
            return "annotator";
        }

        @Override
        public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
            List<Message> messages = new ArrayList<>(request.messages());
            Message first = messages.get(0);
            messages.set(0, Message.of(first.role(), first.content() + " ·"));
            return CompletableFuture.completedFuture(request.withMessages(messages));
        }
    }

    private static Gateway buildGateway(CaptureMode mode) {
        var builder = Gateway.builder()
                .adapter(new DelayedMockProvider())
                .model("mock")
                .use(new AnnotatorInterceptor());
        if (mode != null) {
            builder.inspect(InspectorConfig.builder()
                    .enabled(true)
                    .mode(mode)
                    .startServer(false)
                    .unsafeContentCaptureAck(true)
                    .build());
        }
        return builder.build();
    }

    private static double[] measure(Gateway gateway) {
        double[] samplesUs = new double[ITERATIONS];
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long started = System.nanoTime();
            gateway.complete(GavioRequest.builder()
                    .messages(MESSAGES)
                    .model("mock")
                    .provider(Provider.MOCK)
                    .build()).join();
            double elapsedUs = (System.nanoTime() - started) / 1000.0;
            if (i >= WARMUP) {
                samplesUs[i - WARMUP] = elapsedUs;
            }
        }
        return samplesUs;
    }

    private static Map<String, Object> summarize(double[] samplesUs) {
        double[] ordered = samplesUs.clone();
        Arrays.sort(ordered);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("p50Us", Math.round(ordered[ordered.length / 2] * 10) / 10.0);
        out.put("p95Us", Math.round(ordered[(int) Math.ceil(ordered.length * 0.95) - 1] * 10) / 10.0);
        return out;
    }

    @Test
    void inspectorOverheadStaysWithinBudget() {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        results.put("disabled", summarize(measure(buildGateway(null))));
        results.put("metadata", summarize(measure(buildGateway(CaptureMode.METADATA))));
        results.put("full", summarize(measure(buildGateway(CaptureMode.FULL))));

        double delayUs = SIMULATED_DELAY_MS * 1000.0;
        double baseline = (double) results.get("disabled").get("p50Us");
        boolean pass = true;
        for (Map.Entry<String, Double> entry : Map.of(
                "metadata", METADATA_BUDGET_PCT, "full", FULL_BUDGET_PCT).entrySet()) {
            Map<String, Object> mode = results.get(entry.getKey());
            double overheadUs = Math.round(((double) mode.get("p50Us") - baseline) * 10) / 10.0;
            double overheadPct = Math.round(overheadUs / delayUs * 10000) / 100.0;
            mode.put("overheadP50Us", overheadUs);
            mode.put("overheadPct", overheadPct);
            mode.put("budgetPct", entry.getValue());
            boolean ok = overheadPct < entry.getValue();
            mode.put("pass", ok);
            pass = pass && ok;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("benchmark", "inspector-overhead");
        report.put("sdk", "java");
        report.put("simulatedDelayMs", SIMULATED_DELAY_MS);
        report.put("iterations", ITERATIONS);
        report.put("results", results);
        report.put("pass", pass);
        System.out.println(Json.write(report));

        assertTrue(pass, "inspector overhead exceeded budget: " + Json.write(results));
    }
}
