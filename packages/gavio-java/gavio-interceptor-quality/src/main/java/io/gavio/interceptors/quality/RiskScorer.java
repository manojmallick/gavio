package io.gavio.interceptors.quality;

import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.concurrent.CompletableFuture;

/**
 * Composite risk score (F-QUA-06) from PII, guardrail, and injection signals.
 *
 * <p>Runs in {@code after}, reads the signals accumulated on the context, and
 * writes a composite score in {@code [0, 1]} to {@code ctx.riskScore()} — recorded
 * on the audit record. Register it <em>inside</em> the audit interceptor so audit
 * sees the composite.
 */
public final class RiskScorer implements Interceptor {

    private final RiskWeights weights;

    public RiskScorer() {
        this(RiskWeights.defaults());
    }

    public RiskScorer(RiskWeights weights) {
        this.weights = weights != null ? weights : RiskWeights.defaults();
    }

    @Override
    public String name() {
        return "risk_scorer";
    }

    @Override
    public boolean dryRunSafe() {
        return true;
    }

    /** Compute the composite risk score from the three raw signals. */
    public double score(long piiCount, String guardrailOutcome, Double injectionScore) {
        double piiSignal = 0.0;
        if (piiCount > 0) {
            piiSignal = weights.piiSaturation() <= 0
                    ? 1.0
                    : Math.min(1.0, (double) piiCount / weights.piiSaturation());
        }
        double guardrailSignal = "FAIL".equals(guardrailOutcome)
                ? 1.0
                : "HITL".equals(guardrailOutcome) ? 0.6 : 0.0;
        double injectionSignal = injectionScore != null ? injectionScore : 0.0;
        double composite = weights.pii() * piiSignal
                + weights.guardrail() * guardrailSignal
                + weights.injection() * injectionSignal;
        return Math.max(0.0, Math.min(1.0, composite));
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        long piiCount = ctx.piiEntityCounts().values().stream().mapToLong(Integer::longValue).sum();
        ctx.riskScore(score(piiCount, ctx.guardrailOutcome(), ctx.riskScore()));
        return CompletableFuture.completedFuture(response);
    }
}
