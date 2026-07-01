package io.gavio.interceptors.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Risk scoring (F-QUA-06). */
class RiskScorerTest {

    @Test
    void scoresZeroWithNoSignals() {
        assertEquals(0.0, new RiskScorer().score(0, null, null));
    }

    @Test
    void weightsEachSignal() {
        RiskScorer s = new RiskScorer(); // 0.3 / 0.4 / 0.3, saturation 4
        assertEquals(0.15, s.score(2, null, null), 1e-9);
        assertEquals(0.4, s.score(0, "FAIL", null), 1e-9);
        assertEquals(0.24, s.score(0, "HITL", null), 1e-9);
        assertEquals(0.15, s.score(0, null, 0.5), 1e-9);
    }

    @Test
    void saturatesAndClamps() {
        RiskScorer s = new RiskScorer();
        assertEquals(1.0, s.score(10, "FAIL", 1.0));
        assertEquals(1.0, s.score(10, "FAIL", 5.0));
    }

    @Test
    void customWeights() {
        RiskScorer s = new RiskScorer(new RiskWeights(1.0, 0.0, 0.0, 2));
        assertEquals(0.5, s.score(1, "FAIL", 1.0), 1e-9);
    }

    @Test
    void writesCompositeVisibleToLaterInterceptor() {
        AtomicReference<Double> seen = new AtomicReference<>();
        Interceptor capture = new Interceptor() {
            @Override
            public String name() {
                return "capture";
            }

            @Override
            public CompletableFuture<GavioResponse> after(GavioResponse r, InterceptorContext ctx) {
                seen.set(ctx.riskScore());
                return CompletableFuture.completedFuture(r);
            }
        };
        Interceptor seeder = new Interceptor() {
            @Override
            public String name() {
                return "seeder";
            }

            @Override
            public CompletableFuture<GavioRequest> before(GavioRequest req, InterceptorContext ctx) {
                ctx.recordPii(List.of("EMAIL", "IBAN"));
                ctx.guardrailOutcome("FAIL");
                ctx.riskScore(1.0);
                return CompletableFuture.completedFuture(req);
            }
        };

        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(capture) // registered first → after runs last (sees the composite)
                .use(seeder)
                .use(new RiskScorer())
                .build();
        gw.complete(GavioRequest.builder().message("user", "hi").model("mock").build()).join();

        // pii 0.5 (2/4) → 0.15; guardrail 0.4; injection 0.3 → 0.85
        assertEquals(0.85, seen.get(), 1e-9);
    }
}
