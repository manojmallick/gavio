package io.gavio.interceptors.reliability;

import io.gavio.GavioException.LoopGuardException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.RootRunResolver;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.reliability.LoopGuardDecision.RepeatedToolCall;
import io.gavio.interceptors.reliability.LoopGuardPolicy.Action;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;

/**
 * Stops a runaway agent loop (F-REL-09).
 *
 * <p>Before every provider call the guard resolves the request's root run
 * ({@link RootRunResolver}: {@code metadata.runId}, else the root of the {@code parentTraceId}
 * chain, else the trace id), compares the run's state with the {@link LoopGuardPolicy} and acts on
 * the verdict: {@code stop} fails the call with a {@link LoopGuardException}, {@code fallback}
 * reroutes it to {@code fallbackModel}, {@code dry_run} only records the breach. After the call it
 * adds the response's cost and tool calls to the run. The verdict is attached to the audit record
 * (counts only), the Inspector, and, on a breach, a governance event.
 *
 * <p>An agent loop must pass {@code metadata.runId} or {@code parentTraceId} on its calls — calls
 * with neither are separate runs, which no limit can link. In a gateway {@code dryRun} the guard
 * still evaluates and records, but never stops or reroutes a call. State is in memory, per guard
 * ({@link LoopGuardStore}). Port of {@code gavio.interceptors.reliability.loop_guard} (Python),
 * pinned by {@code test-vectors/loop-guard/decisions.json}.
 */
public final class LoopGuard implements Interceptor {

    private static final String RUN_KEY = "loop_guard:run_id";

    private final LoopGuardPolicy policy;
    private final RootRunResolver resolver;
    private final LoopGuardStore store;
    private final DoubleSupplier clock;
    private final Object lock = new Object();

    private LoopGuard(Builder b) {
        this.policy = b.policy;
        this.clock = b.clock != null ? b.clock : monotonicSeconds();
        this.resolver = b.resolver != null ? b.resolver : new RootRunResolver();
        this.store = b.store != null ? b.store : new LoopGuardStore(this.clock);
    }

    public static Builder builder(LoopGuardPolicy policy) {
        return new Builder(policy);
    }

    /** A guard with the given policy and default resolver, store and clock. */
    public static LoopGuard of(LoopGuardPolicy policy) {
        return builder(policy).build();
    }

    static DoubleSupplier monotonicSeconds() {
        return () -> System.nanoTime() / 1e9;
    }

    @Override
    public String name() {
        return "loop_guard";
    }

    public LoopGuardPolicy policy() {
        return policy;
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        ctx.markFired(name());
        String runId = resolver.resolve(request.traceId(), request.parentTraceId(), request.metadata());
        LoopGuardDecision decision;
        synchronized (lock) { // evaluate and count atomically
            LoopGuardState state = store.acquire(runId);
            double now = clock.getAsDouble();
            Double startedAt = state.startedAt();
            decision = evaluate(policy, state, runId, now - (startedAt != null ? startedAt : now));
            if (ctx.dryRun() && ("stop".equals(decision.action()) || "fallback".equals(decision.action()))) {
                // A gateway dry run observes; it never refuses or reroutes a call.
                decision = decision.asDryRun();
            }
            if (decision.allowed()) {
                state.startCall();
            }
        }

        Map<String, Object> wire = decision.toMap();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("policy_id", decision.policyId());
        audit.put("run_id", decision.runId());
        audit.put("action", decision.action());
        audit.put("breached", decision.breached());
        audit.put("steps", decision.steps());
        audit.put("cost_usd", decision.costUsd());
        audit.put("elapsed_seconds", decision.elapsedSeconds());
        ctx.loopGuard(audit);
        ctx.inspect("loop_guard", wire);
        if (decision.breached() != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("kind", "loop_guard");
            event.put("decision", wire);
            event.put("policyId", policy.id());
            ctx.recordGovernanceEvent(event);
        }
        if ("stop".equals(decision.action())) {
            return CompletableFuture.failedFuture(new LoopGuardException(stopMessage(decision), wire));
        }
        ctx.state().put(RUN_KEY, runId);
        if ("fallback".equals(decision.action()) && decision.targetModel() != null
                && !decision.targetModel().equals(request.model())) {
            return CompletableFuture.completedFuture(request.withModel(decision.targetModel()));
        }
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        if (ctx.state().get(RUN_KEY) instanceof String runId) {
            synchronized (lock) {
                LoopGuardState state = store.get(runId);
                if (state != null) { // null: the run expired or was evicted while the call was in flight
                    state.addResult(response.costUsd(), response.toolCalls());
                }
            }
        }
        return CompletableFuture.completedFuture(response);
    }

    /**
     * Compares a run's state with a policy before its next provider call. Pure.
     *
     * <p>A limit is reached when {@code steps >= maxSteps}, {@code cost >= maxCostUsd},
     * {@code elapsed >= maxWallTimeSeconds} (cost and elapsed time compared after rounding to 8
     * places) or any counted tool call has reached {@code maxRepeatedToolCalls}.
     */
    public static LoopGuardDecision evaluate(
            LoopGuardPolicy policy, LoopGuardState state, String runId, double elapsedSeconds) {
        double cost = LoopGuardState.round8(Math.max(state.costUsd(), 0.0));
        double elapsed = LoopGuardState.round8(Math.max(elapsedSeconds, 0.0));

        List<String> breaches = new ArrayList<>();
        RepeatedToolCall repeated = null;
        if (policy.maxSteps() != null && state.steps() >= policy.maxSteps()) {
            breaches.add("max_steps");
        }
        if (policy.maxCostUsd() != null && cost >= policy.maxCostUsd()) {
            breaches.add("max_cost");
        }
        if (policy.maxWallTimeSeconds() != null && elapsed >= policy.maxWallTimeSeconds()) {
            breaches.add("max_wall_time");
        }
        if (policy.maxRepeatedToolCalls() != null) {
            for (RepeatedToolCall entry : state.toolCallCounts()) {
                if (entry.count() >= policy.maxRepeatedToolCalls()) {
                    repeated = entry;
                    breaches.add("repeated_tool_call");
                    break;
                }
            }
        }

        String breached = breaches.isEmpty() ? null : breaches.get(0);
        String action = breached == null ? "allow" : policy.action().value();
        return new LoopGuardDecision(
                policy.id(),
                runId,
                !"stop".equals(action),
                action,
                breached,
                breaches,
                breached == null ? "within_limits" : breached + "_reached",
                state.steps(),
                cost,
                elapsed,
                repeated,
                policy.action() == Action.FALLBACK && breached != null ? policy.fallbackModel() : null);
    }

    private static String stopMessage(LoopGuardDecision decision) {
        String detail = "";
        RepeatedToolCall call = decision.repeatedToolCall();
        if (call != null) {
            detail = " ('" + call.name() + "' requested " + call.count() + " times with the same arguments)";
        }
        return String.format(Locale.ROOT,
                "loop guard %s stopped run %s: %s reached%s after %d steps, $%.4f, %.1fs",
                decision.policyId(), decision.runId(), decision.breached(), detail,
                decision.steps(), decision.costUsd(), decision.elapsedSeconds());
    }

    /** Fluent builder for {@link LoopGuard}. */
    public static final class Builder {
        private final LoopGuardPolicy policy;
        private RootRunResolver resolver;
        private LoopGuardStore store;
        private DoubleSupplier clock;

        private Builder(LoopGuardPolicy policy) {
            this.policy = java.util.Objects.requireNonNull(policy, "policy");
        }

        public Builder resolver(RootRunResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        public Builder store(LoopGuardStore store) {
            this.store = store;
            return this;
        }

        /** Seconds from any monotonic clock; default {@code System.nanoTime() / 1e9}. */
        public Builder clock(DoubleSupplier clock) {
            this.clock = clock;
            return this;
        }

        public LoopGuard build() {
            return new LoopGuard(this);
        }
    }
}
