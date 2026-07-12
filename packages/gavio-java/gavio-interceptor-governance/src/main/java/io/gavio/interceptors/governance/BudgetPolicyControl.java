package io.gavio.interceptors.governance;

import io.gavio.GavioException.BudgetExceededException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Interceptor that applies a Cost Governance v2 policy before provider calls. */
public final class BudgetPolicyControl implements Interceptor {
    private static final String SCOPE_STATE = "budget_v2:scope";
    private static final String DECISION_STATE = "budget_v2:decision";

    private final BudgetPolicyV2 policy;
    private final BudgetStore store;
    private final double estimatedRequestCostUsd;

    private BudgetPolicyControl(Builder b) {
        this.policy = b.policy;
        this.store = b.store;
        this.estimatedRequestCostUsd = b.estimatedRequestCostUsd;
    }

    public static Builder builder(BudgetPolicyV2 policy) {
        return new Builder(policy);
    }

    @Override
    public String name() {
        return "budget_policy";
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        String scope = BudgetPolicyEvaluator.resolvePolicyScope(policy, request, ctx);
        BudgetDecision decision = BudgetPolicyEvaluator.evaluate(
                policy, scope, store.get(scope), estimatedRequestCostUsd);
        Map<String, Object> decisionMap = decision.toMap();
        ctx.state().put(SCOPE_STATE, scope);
        ctx.state().put(DECISION_STATE, decisionMap);
        ctx.inspect("budget_decision", decisionMap);
        if (!"ok".equals(decision.thresholdStatus())) {
            ctx.recordGovernanceEvent(Map.of(
                    "kind", "budget",
                    "decision", decisionMap,
                    "policyId", policy.id()));
        }
        if (("fallback".equals(decision.action()) || "downgrade_model".equals(decision.action()))
                && decision.targetModel() != null
                && !decision.targetModel().equals(request.model())) {
            return CompletableFuture.completedFuture(request.withModel(decision.targetModel()));
        }
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(new BudgetExceededException(
                    "budget policy " + policy.id() + " exceeded for " + scope
                            + ": projected $" + decision.projectedSpendUsd()
                            + " > $" + policy.limitUsd()));
        }
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        Object scope = ctx.state().get(SCOPE_STATE);
        if (scope instanceof String s) {
            store.add(s, response.costUsd());
        }
        return CompletableFuture.completedFuture(response);
    }

    /** Builder for {@link BudgetPolicyControl}. */
    public static final class Builder {
        private final BudgetPolicyV2 policy;
        private BudgetStore store = new InMemoryBudgetStore();
        private double estimatedRequestCostUsd;

        private Builder(BudgetPolicyV2 policy) {
            this.policy = policy;
        }

        public Builder store(BudgetStore v) {
            this.store = v;
            return this;
        }

        public Builder estimatedRequestCostUsd(double v) {
            this.estimatedRequestCostUsd = v;
            return this;
        }

        public BudgetPolicyControl build() {
            return new BudgetPolicyControl(this);
        }
    }
}
