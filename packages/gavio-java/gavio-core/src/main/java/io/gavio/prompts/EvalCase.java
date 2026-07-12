package io.gavio.prompts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One prompt eval case. */
public record EvalCase(
        String id,
        String templateId,
        String templateVersion,
        Map<String, Object> variables,
        List<EvalAssertion> assertions,
        Map<String, Object> metadata,
        EvalFailureTriage triage) {

    public EvalCase {
        variables = Map.copyOf(variables == null ? Map.of() : variables);
        assertions = List.copyOf(assertions == null ? List.of() : assertions);
        metadata = Map.copyOf(metadata == null ? Map.of() : EvalFailureTriage.sanitize(metadata));
    }

    public EvalCase(
            String id,
            String templateId,
            String templateVersion,
            Map<String, Object> variables,
            List<EvalAssertion> assertions,
            Map<String, Object> metadata) {
        this(id, templateId, templateVersion, variables, assertions, metadata, null);
    }

    @SuppressWarnings("unchecked")
    public static EvalCase fromMap(Map<String, Object> data) {
        List<EvalAssertion> assertions = new ArrayList<>();
        for (Object raw : (List<Object>) data.getOrDefault("assertions", List.of())) {
            assertions.add(EvalAssertion.fromMap((Map<String, Object>) raw));
        }
        Map<String, Object> metadata = (Map<String, Object>) data.getOrDefault("metadata", Map.of());
        Object rawTriage = data.get("triage");
        if (rawTriage == null && metadata.get("triage") instanceof Map<?, ?> t) {
            rawTriage = t;
        }
        return new EvalCase(
                String.valueOf(data.get("id")),
                String.valueOf(data.get("templateId")),
                (String) data.get("templateVersion"),
                (Map<String, Object>) data.getOrDefault("variables", Map.of()),
                assertions,
                metadata,
                rawTriage instanceof Map<?, ?> t
                        ? EvalFailureTriage.fromMap((Map<String, Object>) t)
                        : null);
    }
}
