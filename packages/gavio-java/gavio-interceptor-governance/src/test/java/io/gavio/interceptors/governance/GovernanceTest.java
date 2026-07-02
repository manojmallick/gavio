package io.gavio.interceptors.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.BudgetExceededException;
import io.gavio.GavioException.ModelNotAllowedException;
import io.gavio.GavioException.RateLimitExceededException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.interceptors.Interceptor;
import io.gavio.providers.MockProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class GovernanceTest {

    private static Gateway gw(MockProvider provider, Interceptor ic) {
        return Gateway.builder().adapter(provider).model("mock").use(ic).build();
    }

    private static GavioRequest req(String content, Map<String, Object> metadata) {
        var b = GavioRequest.builder().message("user", content).model("mock");
        if (metadata != null) {
            metadata.forEach(b::metadata);
        }
        return b.build();
    }

    @Test
    void budgetBlocksAfterHardCap() {
        PricingProvider pricing = new PricingProvider(Map.of("mock", new double[] {1000, 1000}));
        MockProvider provider = new MockProvider("x", "mock-1", pricing);
        Gateway gw = gw(provider, CostControl.builder().hardCapUsd(0.01).window("total").build());
        gw.complete(req("one", null)).join();
        CompletionException ex =
                assertThrows(CompletionException.class, () -> gw.complete(req("two", null)).join());
        assertInstanceOf(BudgetExceededException.class, ex.getCause());
    }

    @Test
    void rateLimiterBlocks() {
        Gateway gw = gw(new MockProvider("x"), RateLimiter.builder().maxRequestsPerMinute(2).build());
        gw.complete(req("1", null)).join();
        gw.complete(req("2", null)).join();
        CompletionException ex =
                assertThrows(CompletionException.class, () -> gw.complete(req("3", null)).join());
        assertInstanceOf(RateLimitExceededException.class, ex.getCause());
    }

    @Test
    void modelPolicyEnforcesRoles() {
        ModelPolicy policy = ModelPolicy.builder()
                .role("analyst", List.of("mock"))
                .role("guest", List.of())
                .build();
        Gateway gw = gw(new MockProvider("x"), policy);
        gw.complete(req("hi", Map.of("role", "analyst"))).join();
        CompletionException ex = assertThrows(
                CompletionException.class, () -> gw.complete(req("hi", Map.of("role", "guest"))).join());
        assertInstanceOf(ModelNotAllowedException.class, ex.getCause());
    }

    @Test
    void costRouterReroutesSimplePrompt() {
        Gateway gw = gw(new MockProvider("x"), CostRouter.builder().simpleModel("mock-mini").build());
        GavioResponse r = gw.complete(req("What is 2+2?", null)).join();
        assertEquals("mock-mini", r.model());
    }

    @Test
    void costRouterSkipsComplexPrompt() {
        Gateway gw = gw(
                new MockProvider("x"),
                CostRouter.builder().simpleModel("mock-mini").complexityThreshold(0.35).build());
        GavioResponse r = gw.complete(req(
                "Explain why the trade-off between consistency and availability matters here, "
                        + "and compare it to the CAP theorem, analyzing multiple failure scenarios in detail.",
                null)).join();
        assertEquals("mock", r.model());
    }

    @Test
    void costRouterSkipsWhenAlreadyOnSimpleModel() {
        Gateway gw = gw(new MockProvider("x"), CostRouter.builder().simpleModel("mock").build());
        GavioResponse r = gw.complete(req("hi", null)).join();
        assertEquals("mock", r.model());
    }

    @Test
    void costRouterAcceptsCustomScorer() {
        ComplexityScorer alwaysComplex = text -> 1.0;
        Gateway gw = gw(
                new MockProvider("x"),
                CostRouter.builder().simpleModel("mock-mini").scorer(alwaysComplex).build());
        GavioResponse r = gw.complete(req("What is 2+2?", null)).join();
        assertEquals("mock", r.model());
    }

    @Test
    void heuristicComplexityScorerRanksSimpleBelowComplex() {
        HeuristicComplexityScorer scorer = new HeuristicComplexityScorer();
        double simple = scorer.score("What is 2+2?");
        double complex = scorer.score(
                "Explain why the trade-off between consistency and availability matters, "
                        + "and compare it to the CAP theorem across failure scenarios.");
        assertTrue(simple >= 0.0 && simple <= 1.0);
        assertTrue(complex >= 0.0 && complex <= 1.0);
        assertTrue(simple < complex);
    }
}
