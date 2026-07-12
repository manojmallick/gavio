package io.gavio.interceptors.pii.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manifest entry for one detector inside a {@link PolicyPack}. */
public record PolicyDetector(
        String name,
        String entityType,
        String type,
        PolicyAction action,
        String label,
        String severity,
        double confidence,
        RedactionStrategy redactionStrategy,
        String pattern,
        String replacementPrefix,
        List<String> suppressionPatterns) {

    public PolicyDetector {
        suppressionPatterns = suppressionPatterns == null ? List.of() : List.copyOf(suppressionPatterns);
    }

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
        if (severity != null) {
            out.put("severity", severity);
        }
        if (pattern != null) {
            out.put("pattern", pattern);
        }
        if (replacementPrefix != null) {
            out.put("replacementPrefix", replacementPrefix);
        }
        if (!suppressionPatterns.isEmpty()) {
            out.put("suppressionPatterns", suppressionPatterns);
        }
        return out;
    }

    public static PolicyDetector scanner(String name, String entityType, String label) {
        return new PolicyDetector(
                name, entityType, "scanner", PolicyAction.REDACT, label, null, 1.0,
                RedactionStrategy.TOKENIZE, null, null, List.of());
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
                rule.severity(),
                rule.confidence(),
                rule.redactionStrategy() != null ? rule.redactionStrategy() : defaultStrategy,
                rule.pattern(),
                rule.replacementPrefix(),
                rule.suppressionPatterns());
    }
}
