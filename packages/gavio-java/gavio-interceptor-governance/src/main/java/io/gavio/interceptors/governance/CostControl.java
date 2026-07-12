package io.gavio.interceptors.governance;

import io.gavio.GavioException.BudgetExceededException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;

/** CostControl (F-GOV-02) — soft/hard budget caps per scope and window. */
public final class CostControl implements Interceptor {

    private final double hardCapUsd;
    private final Double softCapUsd;
    private final String scope;
    private final String window;
    private final String fallbackModel;
    private final Map<String, Double> spend = new ConcurrentHashMap<>();
    private static final String COST_CONTROL_KEY_STATE = "cost_control:budget_key";

    private CostControl(Builder b) {
        this.hardCapUsd = b.hardCapUsd;
        this.softCapUsd = b.softCapUsd;
        this.scope = b.scope;
        this.window = b.window;
        this.fallbackModel = b.fallbackModel;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "cost_control";
    }

    private String key(GavioRequest request, InterceptorContext ctx) {
        return Scopes.scopeKey(scope, request, ctx) + "|" + Scopes.windowBucket(window);
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        String key = key(request, ctx);
        ctx.state().put(COST_CONTROL_KEY_STATE, key);
        double spent = spend.getOrDefault(key, 0.0);
        if (spent >= hardCapUsd) {
            boolean canFallback = fallbackModel != null && !fallbackModel.equals(request.model());
            Map<String, Object> event = budgetEvent(
                    canFallback ? "fallback" : "block",
                    key,
                    "spentUsd",
                    round4(spent),
                    "hardCapUsd",
                    hardCapUsd);
            ctx.inspect("budget", event);
            ctx.recordGovernanceEvent(event);
            if (canFallback) {
                return CompletableFuture.completedFuture(request.withModel(fallbackModel));
            }
            return CompletableFuture.failedFuture(
                    new BudgetExceededException("budget hard cap $" + hardCapUsd + " reached (spent $" + spent + ")"));
        }
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        Object stateKey = ctx.state().get(COST_CONTROL_KEY_STATE);
        String key = stateKey instanceof String s ? s : "global|" + Scopes.windowBucket(window);
        double total = spend.merge(key, response.costUsd(), Double::sum);
        if (softCapUsd != null && total >= softCapUsd) {
            Map<String, Object> event = budgetEvent(
                    "warn",
                    key,
                    "spentUsd",
                    round4(total),
                    "softCapUsd",
                    softCapUsd);
            ctx.inspect("budget", event);
            ctx.recordGovernanceEvent(event);
        }
        return CompletableFuture.completedFuture(response);
    }

    private Map<String, Object> budgetEvent(
            String action, String key, String amountKey, double amount, String capKey, double cap) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "budget");
        event.put("action", action);
        event.put("scope", scope);
        event.put("key", key);
        event.put(amountKey, amount);
        event.put(capKey, cap);
        return event;
    }

    private static double round4(double value) {
        return Math.round(value * 1e4) / 1e4;
    }

    /** Builder for {@link CostControl}. */
    public static final class Builder {
        private double hardCapUsd = Double.MAX_VALUE;
        private Double softCapUsd;
        private String scope = "global";
        private String window = "day";
        private String fallbackModel;

        public Builder hardCapUsd(double v) {
            this.hardCapUsd = v;
            return this;
        }

        public Builder softCapUsd(double v) {
            this.softCapUsd = v;
            return this;
        }

        public Builder scope(String v) {
            this.scope = v;
            return this;
        }

        public Builder window(String v) {
            this.window = v;
            return this;
        }

        public Builder fallbackModel(String v) {
            this.fallbackModel = v;
            return this;
        }

        public CostControl build() {
            return new CostControl(this);
        }
    }
}
