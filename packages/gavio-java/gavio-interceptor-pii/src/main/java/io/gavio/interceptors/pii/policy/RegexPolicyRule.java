package io.gavio.interceptors.pii.policy;

import java.util.List;

/** Custom organization regex detector rule (F-PACK-05). */
public record RegexPolicyRule(
        String name,
        String entityType,
        String pattern,
        double confidence,
        String replacementPrefix,
        PolicyAction action,
        RedactionStrategy redactionStrategy,
        String label,
        String severity,
        List<String> suppressionPatterns) {

    public RegexPolicyRule {
        suppressionPatterns = suppressionPatterns == null ? List.of() : List.copyOf(suppressionPatterns);
    }

    public RegexPolicyRule(
            String name,
            String entityType,
            String pattern,
            double confidence,
            String replacementPrefix,
            PolicyAction action,
            RedactionStrategy redactionStrategy,
            String label) {
        this(name, entityType, pattern, confidence, replacementPrefix, action, redactionStrategy,
                label, null, List.of());
    }

    public RegexPolicyRule(String name, String entityType, String pattern) {
        this(name, entityType, pattern, 1.0, null, null, null, null, null, List.of());
    }
}
