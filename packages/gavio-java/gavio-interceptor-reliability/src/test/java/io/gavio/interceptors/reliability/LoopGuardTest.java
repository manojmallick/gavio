package io.gavio.interceptors.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.LoopGuardException;
import io.gavio.GavioException.ProviderUnavailableException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.RootRunResolver;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.reliability.LoopGuardPolicy.Action;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Provider;
import io.gavio.types.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * Loop guard (F-REL-09): the interceptor and its store. The limit semantics themselves are pinned
 * by the shared vectors in {@code LoopGuardVectorsTest}; audit wiring is tested in
 * {@code gavio-testing}.
 */
class LoopGuardTest {

    /** A mock provider with a price per call, optional tool calls, and optional failures. */
    private static final class Scripted implements ProviderAdapter {
        final List<GavioRequest> seen = new ArrayList<>();
        private final MockProvider delegate = new MockProvider("ok");
        private final double costUsd;
        private final List<ToolCall> toolCalls;
        private final boolean fail;

        Scripted(double costUsd, List<ToolCall> toolCalls, boolean fail) {
            this.costUsd = costUsd;
            this.toolCalls = toolCalls;
            this.fail = fail;
        }

        static Scripted plain() {
            return new Scripted(0.0, List.of(), false);
        }

        List<String> models() {
            return seen.stream().map(GavioRequest::model).toList();
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            seen.add(request);
            if (fail) {
                return CompletableFuture.failedFuture(new ProviderUnavailableException("down"));
            }
            return delegate.complete(request).thenApply(r -> GavioResponse.builder()
                    .traceId(r.traceId()).content(r.content()).model(r.model()).provider(r.provider())
                    .modelVersion(r.modelVersion()).usage(r.usage()).costUsd(costUsd)
                    .toolCalls(toolCalls).build());
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    private static LoopGuardPolicy.Builder policy() {
        return LoopGuardPolicy.builder("agent");
    }

    private static Gateway gateway(ProviderAdapter adapter, LoopGuard guard) {
        return Gateway.builder().adapter(adapter).model("mock").use(guard).build();
    }

    private static GavioRequest.Builder request() {
        return GavioRequest.builder().message("user", "keep going").model("mock").provider(Provider.MOCK);
    }

    /** An agent loop: the first call is the root, every later call names it as its parent. */
    private static List<GavioResponse> agentLoop(Gateway gateway, int calls) {
        List<GavioResponse> responses = new ArrayList<>();
        responses.add(gateway.complete(request().build()).join());
        for (int i = 1; i < calls; i++) {
            responses.add(gateway.complete(request().parentTraceId(responses.get(0).traceId()).build()).join());
        }
        return responses;
    }

    private static LoopGuardException stopped(Runnable run) {
        CompletionException wrapper = assertThrows(CompletionException.class, run::run);
        assertTrue(wrapper.getCause() instanceof LoopGuardException, String.valueOf(wrapper.getCause()));
        return (LoopGuardException) wrapper.getCause();
    }

    // ---- the demo from the roadmap: a runaway agent is stopped --------------------------------

    @Test
    void aRunawayAgentIsStoppedAt25Steps() {
        Scripted provider = Scripted.plain();
        Gateway gateway = gateway(provider, LoopGuard.of(policy().maxSteps(25).build()));

        LoopGuardException stopped = stopped(() -> agentLoop(gateway, 40));

        assertEquals(25, provider.seen.size()); // the 26th call never reached the provider
        assertEquals("max_steps", stopped.decision().get("breached"));
        assertEquals(25, stopped.decision().get("steps"));
        assertTrue(stopped.getMessage().contains("max_steps reached"));
    }

    @Test
    void aRunawayAgentIsStoppedByACostCap() {
        Scripted provider = new Scripted(0.4, List.of(), false);
        Gateway gateway = gateway(provider, LoopGuard.of(policy().maxCostUsd(1.0).build()));

        LoopGuardException stopped = stopped(() -> agentLoop(gateway, 40));

        // $0.40 x 3 = $1.20 crosses the $1.00 cap; the call that crosses it still happened.
        assertEquals(3, provider.seen.size());
        assertEquals("max_cost", stopped.decision().get("breached"));
        assertEquals(1.2, (Double) stopped.decision().get("costUsd"), 1e-9);
    }

    @Test
    void repeatedToolCallsAreStoppedWithoutLeakingTheArguments() {
        String secret = "jan@example.com";
        Scripted provider = new Scripted(0.0, List.of(new ToolCall("c", "lookup", args("email", secret))), false);
        Gateway gateway = gateway(provider, LoopGuard.of(policy().maxRepeatedToolCalls(3).build()));

        LoopGuardException stopped = stopped(() -> agentLoop(gateway, 10));

        assertEquals(3, provider.seen.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> repeated = (Map<String, Object>) stopped.decision().get("repeatedToolCall");
        assertEquals("lookup", repeated.get("name"));
        assertEquals(3, repeated.get("count"));
        assertTrue(stopped.getMessage().contains("'lookup'"));
        assertFalse(stopped.getMessage().contains(secret));
        assertFalse(stopped.decision().toString().contains(secret));
    }

    @Test
    void wallTimeIsMeasuredFromTheRunsFirstCall() {
        double[] now = {1000.0};
        DoubleSupplier clock = () -> now[0];
        Gateway gateway = gateway(Scripted.plain(),
                LoopGuard.builder(policy().maxWallTimeSeconds(30).build()).clock(clock).build());

        GavioResponse first = gateway.complete(request().build()).join();
        now[0] += 29.9;
        gateway.complete(request().parentTraceId(first.traceId()).build()).join();
        now[0] += 0.1;
        LoopGuardException stopped = stopped(
                () -> gateway.complete(request().parentTraceId(first.traceId()).build()).join());
        assertEquals("max_wall_time", stopped.decision().get("breached"));
    }

    @Test
    void aLoopOfFailingCallsStillCountsItsSteps() {
        Scripted provider = new Scripted(0.0, List.of(), true);
        Gateway gateway = gateway(provider, LoopGuard.of(policy().maxSteps(3).build()));
        Runnable call = () -> gateway.complete(request().metadata("runId", "r").build()).join();

        for (int i = 0; i < 3; i++) {
            CompletionException failure = assertThrows(CompletionException.class, call::run);
            assertTrue(failure.getCause() instanceof ProviderUnavailableException);
        }
        stopped(call);
        assertEquals(3, provider.seen.size());
    }

    // ---- which calls belong to one run ----------------------------------------------------------

    @Test
    void callsWithAnExplicitRunIdShareARunAndOtherRunsAreIndependent() {
        Gateway gateway = gateway(Scripted.plain(), LoopGuard.of(policy().maxSteps(2).build()));

        gateway.complete(request().metadata("runId", "a").build()).join();
        gateway.complete(request().metadata("runId", "a").build()).join();
        gateway.complete(request().metadata("runId", "b").build()).join(); // a fresh run
        stopped(() -> gateway.complete(request().metadata("run_id", "a").build()).join()); // snake_case too
    }

    @Test
    void subAgentsUnderOneRootAreCountedTogether() {
        Gateway gateway = gateway(Scripted.plain(), LoopGuard.of(policy().maxSteps(3).build()));
        GavioResponse root = gateway.complete(request().agentId("orchestrator").build()).join();
        gateway.complete(request().agentId("agent-a").parentTraceId(root.traceId()).build()).join();
        gateway.complete(request().agentId("agent-b").parentTraceId(root.traceId()).build()).join();
        stopped(() -> gateway.complete(request().agentId("agent-a").parentTraceId(root.traceId()).build()).join());
    }

    @Test
    void callsWithoutARunIdOrParentAreSeparateRuns() {
        Scripted provider = Scripted.plain();
        Gateway gateway = gateway(provider, LoopGuard.of(policy().maxSteps(1).build()));
        for (int i = 0; i < 5; i++) {
            gateway.complete(request().build()).join(); // each is its own run, so none breaches
        }
        assertEquals(5, provider.seen.size());
    }

    // ---- actions ---------------------------------------------------------------------------------

    @Test
    void fallbackReroutesToTheFallbackModelAndKeepsTheTools() {
        Scripted provider = Scripted.plain();
        Gateway gateway = gateway(provider, LoopGuard.of(
                policy().maxSteps(2).action(Action.FALLBACK).fallbackModel("mock-mini").build()));
        List<Map<String, Object>> tools = List.of(args("name", "lookup"));
        GavioResponse root = gateway.complete(request().tools(tools).toolChoice("auto").build()).join();
        List<GavioResponse> rest = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rest.add(gateway.complete(
                    request().parentTraceId(root.traceId()).tools(tools).toolChoice("auto").build()).join());
        }

        assertEquals(List.of("mock", "mock", "mock-mini", "mock-mini"), provider.models());
        assertEquals("mock-mini", rest.get(2).model());
        // a rerouted request still declares its tools
        assertEquals(tools, provider.seen.get(2).tools());
        assertEquals("auto", provider.seen.get(2).toolChoice().mode().value());
    }

    @Test
    void dryRunActionRecordsTheBreachAndLetsEveryCallThrough() {
        Scripted provider = Scripted.plain();
        GavioRequest root = request().build();
        LoopGuard guard = LoopGuard.of(policy().maxSteps(2).action(Action.DRY_RUN).build());
        List<Map<String, Object>> verdicts = new ArrayList<>();
        Gateway gateway = gateway(provider, guard);
        GavioResponse first = gateway.complete(root).join();
        for (int i = 0; i < 3; i++) {
            gateway.complete(request().parentTraceId(first.traceId()).build()).join();
        }
        assertEquals(4, provider.seen.size());

        // Evaluate directly to see each verdict.
        LoopGuardState state = new LoopGuardState();
        for (int i = 0; i < 4; i++) {
            verdicts.add(LoopGuard.evaluate(guard.policy(), state, "r", 0).toMap());
            state.record(0, List.of());
        }
        assertEquals(List.of("allow", "allow", "dry_run", "dry_run"),
                verdicts.stream().map(v -> v.get("action")).toList());
        assertTrue(verdicts.stream().allMatch(v -> (Boolean) v.get("allowed")));
    }

    @Test
    void aGatewayDryRunNeverStopsOrReroutes() {
        Scripted provider = Scripted.plain();
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").dryRun(true)
                .use(LoopGuard.of(policy().maxSteps(1).build())).build();

        agentLoop(gateway, 3);

        assertEquals(3, provider.seen.size());
    }

    // ---- context wiring ----------------------------------------------------------------------------

    @Test
    void aBreachIsReportedToTheContextTheInspectorAndAsAGovernanceEvent() {
        LoopGuard guard = LoopGuard.of(policy().maxSteps(1).action(Action.DRY_RUN).build());
        GavioRequest first = request().build();
        InterceptorContext ctx = new InterceptorContext(first.traceId());

        guard.before(first, ctx).join(); // step 1: within limits
        assertNull(ctx.loopGuard().get("breached"));
        assertEquals("allow", ctx.loopGuard().get("action"));
        assertTrue(ctx.drainGovernance().isEmpty());

        GavioRequest again = request().parentTraceId(first.traceId()).build();
        InterceptorContext ctx2 = new InterceptorContext(again.traceId());
        guard.before(again, ctx2).join();
        assertEquals("max_steps", ctx2.loopGuard().get("breached"));
        assertEquals(List.of("policy_id", "run_id", "action", "breached", "steps", "cost_usd", "elapsed_seconds"),
                new ArrayList<>(ctx2.loopGuard().keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> inspected = (Map<String, Object>) ctx2.drainInspections().get("loop_guard");
        assertEquals("max_steps", inspected.get("breached"));
        List<Map<String, Object>> events = ctx2.drainGovernance();
        assertEquals(List.of("loop_guard"), events.stream().map(e -> e.get("kind")).toList());
        assertEquals("agent", events.get(0).get("policyId"));
        assertTrue(ctx2.interceptorsFired().contains("loop_guard"));
    }

    // ---- the store ---------------------------------------------------------------------------------

    @Test
    void theStoreEvictsTheLeastRecentlyUsedRun() {
        LoopGuardStore store = new LoopGuardStore(2, 3600, () -> 0.0);
        LoopGuardState a = store.acquire("a");
        store.acquire("b");
        store.acquire("a"); // refresh a
        store.acquire("c"); // evicts b
        assertEquals(2, store.size());
        assertNull(store.get("b"));
        assertSame(a, store.get("a"));
    }

    @Test
    void theStoreForgetsIdleRuns() {
        double[] now = {1000.0};
        LoopGuardStore store = new LoopGuardStore(10, 60, () -> now[0]);
        LoopGuardState first = store.acquire("a");
        first.startCall();
        now[0] += 59;
        assertSame(first, store.get("a")); // reading refreshes it
        now[0] += 59;
        assertSame(first, store.get("a"));
        now[0] += 61;
        assertNull(store.get("a"));
        LoopGuardState restarted = store.acquire("a");
        assertNotSame(first, restarted);
        assertEquals(0, restarted.steps());
        assertEquals(now[0], restarted.startedAt());
    }

    @Test
    void theStoreAndResolverValidateTheirBounds() {
        for (int bad : new int[] {0, -1}) {
            assertThrows(ConfigurationException.class, () -> new LoopGuardStore(bad, 60, () -> 0.0));
            assertThrows(ConfigurationException.class, () -> new RootRunResolver(bad));
        }
        assertThrows(ConfigurationException.class, () -> new LoopGuardStore(10, 0, () -> 0.0));
        RootRunResolver resolver = new RootRunResolver(3);
        for (int i = 0; i < 10; i++) {
            resolver.resolve("t" + i, null, null);
        }
        assertEquals(3, resolver.size());
    }

    @Test
    void aRunThatExpiresMidCallDoesNotBreakTheResponse() {
        double[] now = {1000.0};
        DoubleSupplier clock = () -> now[0];
        LoopGuardStore store = new LoopGuardStore(10, 10, clock);
        LoopGuard guard = LoopGuard.builder(policy().maxSteps(5).build()).store(store).clock(clock).build();
        GavioRequest request = request().build();
        InterceptorContext ctx = new InterceptorContext(request.traceId());
        guard.before(request, ctx).join();
        now[0] += 100; // the run idles out while the provider call is in flight
        GavioResponse response = GavioResponse.builder().traceId(request.traceId()).content("x")
                .model("mock").provider("mock").build();
        assertSame(response, guard.after(response, ctx).join());
    }

    // ---- policy and state ----------------------------------------------------------------------------

    @Test
    void aPolicyRoundTripsThroughItsWireShapeAndAcceptsSnakeCase() {
        LoopGuardPolicy policy = policy().action(Action.FALLBACK).maxSteps(25).maxCostUsd(1.5)
                .maxWallTimeSeconds(60).maxRepeatedToolCalls(3).fallbackModel("m").build();
        assertEquals(policy, LoopGuardPolicy.from(policy.toMap()));
        assertEquals(2, LoopGuardPolicy.from(args("id", "p", "max_steps", 2L, "action", "stop")).maxSteps());
        // An integer-valued float (JSON 3.0) is an integer; a fraction is not.
        assertEquals(3, LoopGuardPolicy.from(args("id", "p", "maxSteps", 3.0)).maxSteps());
        assertThrows(ConfigurationException.class, () -> LoopGuardPolicy.from(args("id", "p", "maxSteps", true)));
        assertThrows(ConfigurationException.class, () -> policy().maxSteps(0).build());
        assertThrows(ConfigurationException.class, () -> policy().maxCostUsd(Double.NaN).build());
        assertNotNull(LoopGuard.of(policy().build()).policy());
    }

    @Test
    void stateRecordsToolCallsByHashAndKeepsFirstSeenOrder() {
        LoopGuardState state = new LoopGuardState();
        state.record(0.1, List.of(new ToolCall("a", "search", args("q", "x", "n", 1L)),
                new ToolCall("b", "read", args("q", "x"))));
        state.record(0.2, List.of(new ToolCall("c", "search", args("n", 1L, "q", "x"))));
        assertEquals(2, state.steps());
        assertEquals(0.3, state.costUsd(), 1e-9);
        assertEquals(List.of("search", "read"),
                state.toolCallCounts().stream().map(c -> c.name()).toList());
        assertEquals(List.of(2, 1), state.toolCallCounts().stream().map(c -> c.count()).toList());
    }
}
