package io.gavio.interceptors.pii.policy;

/** Custom organization regex detector rule (F-PACK-05). */
public record RegexPolicyRule(
        String name,
        String entityType,
        String pattern,
        double confidence,
        String replacementPrefix,
        PolicyAction action,
        RedactionStrategy redactionStrategy,
        String label) {

    public RegexPolicyRule(String name, String entityType, String pattern) {
        this(name, entityType, pattern, 1.0, null, null, null, null);
    }
}
