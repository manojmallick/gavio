package io.gavio.interceptors.governance;

import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CostRouter (F-GOV-06) — reroute a request to {@code simpleModel} when its
 * complexity score is low.
 *
 * <p>Register early in the chain, before caching, so a rerouted request's
 * cache key reflects the model it actually ran on. Register after
 * {@link ModelPolicy} if RBAC should gate on the caller's <em>requested</em>
 * model, not the rerouted one.
 */
public final class CostRouter implements Interceptor {

    private final String simpleModel;
    private final double complexityThreshold;
    private final ComplexityScorer scorer;

    private CostRouter(Builder b) {
        this.simpleModel = b.simpleModel;
        this.complexityThreshold = b.complexityThreshold;
        this.scorer = b.scorer != null ? b.scorer : new HeuristicComplexityScorer();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "cost_router";
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        double score = scorer.score(request.promptText());
        boolean rerouted = score < complexityThreshold && !simpleModel.equals(request.model());
        ctx.state().put("cost_router", Map.of(
                "rerouted", rerouted,
                "originalModel", request.model(),
                "complexityScore", score));
        GavioRequest result = rerouted ? request.withModel(simpleModel) : request;
        return CompletableFuture.completedFuture(result);
    }

    /** Builder for {@link CostRouter}. */
    public static final class Builder {
        private String simpleModel;
        private double complexityThreshold = 0.35;
        private ComplexityScorer scorer;

        public Builder simpleModel(String v) {
            this.simpleModel = v;
            return this;
        }

        public Builder complexityThreshold(double v) {
            this.complexityThreshold = v;
            return this;
        }

        public Builder scorer(ComplexityScorer v) {
            this.scorer = v;
            return this;
        }

        public CostRouter build() {
            if (simpleModel == null) {
                throw new IllegalStateException("CostRouter requires simpleModel");
            }
            return new CostRouter(this);
        }
    }
}
