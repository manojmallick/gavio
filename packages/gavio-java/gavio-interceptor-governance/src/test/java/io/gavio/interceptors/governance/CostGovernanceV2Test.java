package io.gavio.interceptors.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.BudgetExceededException;
import io.gavio.GavioResponse;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class CostGovernanceV2Test {

    @Test
    @SuppressWarnings("unchecked")
    void budgetDecisionVectors() throws IOException {
        for (Map<String, Object> c : cases("budget-decisions.json")) {
            BudgetPolicyV2 policy = BudgetPolicyV2.fromMap((Map<String, Object>) c.get("policy"));
            BudgetDecision decision = BudgetPolicyEvaluator.evaluate(
                    policy,
                    (String) c.get("scope"),
                    num(c.get("currentSpendUsd")),
                    num(c.get("requestCostUsd")));
            Map<String, Object> out = decision.toMap();
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                assertValue(e.getValue(), out.get(e.getKey()), c.get("id") + "." + e.getKey());
            }
            assertEquals(policy.id(), out.get("policyId"));
            assertEquals(c.get("scope"), out.get("scope"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void costGovernanceReportVectors() throws IOException {
        for (Map<String, Object> c : cases("cost-report.json")) {
            List<BudgetPolicyV2> policies = new ArrayList<>();
            for (Object raw : (List<Object>) c.get("policies")) {
                policies.add(BudgetPolicyV2.fromMap((Map<String, Object>) raw));
            }
            Map<String, Object> report = CostGovernanceReport.build(
                    (List<Map<String, Object>>) (Object) c.get("summaries"),
                    policies,
                    (String) c.get("groupBy"),
                    null,
                    num(c.get("usageElapsedRatio")));
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            assertSubset((Map<String, Object>) expected.get("total"), (Map<String, Object>) report.get("total"));
            Map<String, Object> expectedGroups = (Map<String, Object>) expected.get("groups");
            Map<String, Object> groups = (Map<String, Object>) report.get("groups");
            for (Map.Entry<String, Object> group : expectedGroups.entrySet()) {
                assertSubset((Map<String, Object>) group.getValue(), (Map<String, Object>) groups.get(group.getKey()));
            }
            List<Object> expectedBudgets = (List<Object>) expected.get("budgets");
            List<Object> budgets = (List<Object>) report.get("budgets");
            for (int i = 0; i < expectedBudgets.size(); i++) {
                assertSubset((Map<String, Object>) expectedBudgets.get(i), (Map<String, Object>) budgets.get(i));
            }
        }
    }

    @Test
    void budgetPolicyControlFallsBackFromStoreState() {
        BudgetPolicyV2 policy = new BudgetPolicyV2(
                "tenant-total", "tenant", "acme", "total", 1.0, 0.8,
                "fallback", List.of(), "mock-mini", Map.of());
        InMemoryBudgetStore store = new InMemoryBudgetStore(Map.of("tenant:acme|total", 0.95));
        Gateway gw = Gateway.builder()
                .adapter(new MockProvider("ok"))
                .model("mock")
                .use(BudgetPolicyControl.builder(policy)
                        .store(store)
                        .estimatedRequestCostUsd(0.1)
                        .build())
                .build();

        GavioResponse response = gw.complete(req()).join();

        assertEquals("mock-mini", response.model());
        assertTrue(store.get("tenant:acme|total") >= 0.95);
    }

    @Test
    void budgetPolicyControlBlocksWhenRequired() {
        BudgetPolicyV2 policy = new BudgetPolicyV2(
                "tenant-total", "tenant", "acme", "total", 1.0, 0.8,
                "block", List.of(), null, Map.of());
        InMemoryBudgetStore store = new InMemoryBudgetStore(Map.of("tenant:acme|total", 0.95));
        Gateway gw = Gateway.builder()
                .adapter(new MockProvider("ok"))
                .model("mock")
                .use(BudgetPolicyControl.builder(policy)
                        .store(store)
                        .estimatedRequestCostUsd(0.1)
                        .build())
                .build();

        CompletionException ex = assertThrows(CompletionException.class, () -> gw.complete(req()).join());
        assertInstanceOf(BudgetExceededException.class, ex.getCause());
    }

    private static io.gavio.GavioRequest req() {
        return io.gavio.GavioRequest.builder()
                .message("user", "hello")
                .metadata("costDimensions", Map.of("tenant", "acme"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String name) throws IOException {
        return (List<Map<String, Object>>) (Object) Json.parseObject(Files.readString(vector(name))).get("cases");
    }

    private static Path vector(String name) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/cost-governance").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/cost-governance/" + name);
    }

    private static void assertSubset(Map<String, Object> expected, Map<String, Object> actual) {
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            assertValue(e.getValue(), actual.get(e.getKey()), e.getKey());
        }
    }

    private static void assertValue(Object expected, Object actual, String label) {
        if (expected instanceof Number e) {
            assertEquals(e.doubleValue(), num(actual), 1e-9, label);
            return;
        }
        assertEquals(expected, actual, label);
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
