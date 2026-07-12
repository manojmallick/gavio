package io.gavio.interceptors.pii.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Manifest entry for one detector inside a {@link PolicyPack}. */
public record PolicyDetector(
        String name,
        String entityType,
        String type,
        PolicyAction action,
        String label,
        double confidence,
        RedactionStrategy redactionStrategy,
        String pattern) {

    public Map<String, Object> manifest() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("entityType", entityType);
        out.put("type", type);
        out.put("action", action.wireValue());
        out.put("confidence", confidence);
        out.put("redactionStrategy", redactionStrategy.wireValue());
        if (label != null) {
            out.put("label", label);
        }
        if (pattern != null) {
            out.put("pattern", pattern);
        }
        return out;
    }

    public static PolicyDetector scanner(String name, String entityType, String label) {
        return new PolicyDetector(
                name, entityType, "scanner", PolicyAction.REDACT, label, 1.0,
                RedactionStrategy.TOKENIZE, null);
    }

    public static PolicyDetector regex(
            RegexPolicyRule rule, PolicyAction defaultAction,
            RedactionStrategy defaultStrategy) {
        return new PolicyDetector(
                rule.name(),
                rule.entityType(),
                "regex",
                rule.action() != null ? rule.action() : defaultAction,
                rule.label(),
                rule.confidence(),
                rule.redactionStrategy() != null ? rule.redactionStrategy() : defaultStrategy,
                rule.pattern());
    }
}
