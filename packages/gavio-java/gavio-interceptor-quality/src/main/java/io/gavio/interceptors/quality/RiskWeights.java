package io.gavio.interceptors.quality;

/**
 * Weights for the composite risk score. {@code pii + guardrail + injection} need
 * not sum to 1, but do by default so a maxed-out request scores 1.0.
 *
 * @param piiSaturation PII entity count at which the PII signal saturates to 1.0
 *                      ({@code <= 0} → any PII = 1.0)
 */
public record RiskWeights(double pii, double guardrail, double injection, int piiSaturation) {

    public static RiskWeights defaults() {
        return new RiskWeights(0.3, 0.4, 0.3, 4);
    }
}
