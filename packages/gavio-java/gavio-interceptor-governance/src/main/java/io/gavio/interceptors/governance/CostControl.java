package io.gavio.interceptors.governance;

import io.gavio.GavioException.BudgetExceededException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** CostControl (F-GOV-02) — soft/hard budget caps per scope and window. */
public final class CostControl implements Interceptor {

    private final double hardCapUsd;
    private final Double softCapUsd;
    private final String scope;
    private final String window;
    private final Map<String, Double> spend = new ConcurrentHashMap<>();

    private CostControl(Builder b) {
        this.hardCapUsd = b.hardCapUsd;
        this.softCapUsd = b.softCapUsd;
        this.scope = b.scope;
        this.window = b.window;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "cost_control";
    }

    private String key(InterceptorContext ctx) {
        return Scopes.scopeKey(scope, ctx) + "|" + Scopes.windowBucket(window);
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        double spent = spend.getOrDefault(key(ctx), 0.0);
        if (spent >= hardCapUsd) {
            return CompletableFuture.failedFuture(
                    new BudgetExceededException("budget hard cap $" + hardCapUsd + " reached (spent $" + spent + ")"));
        }
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        spend.merge(key(ctx), response.costUsd(), Double::sum);
        return CompletableFuture.completedFuture(response);
    }

    /** Builder for {@link CostControl}. */
    public static final class Builder {
        private double hardCapUsd = Double.MAX_VALUE;
        private Double softCapUsd;
        private String scope = "global";
        private String window = "day";

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

        public CostControl build() {
            return new CostControl(this);
        }
    }
}
