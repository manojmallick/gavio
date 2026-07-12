package io.gavio.prompts;

import java.util.Map;

/** Connects one prompt version to an eval suite and baseline. */
public record PromptEvalLink(
        String promptId,
        String promptVersion,
        String suiteId,
        Double baselineScore,
        Double failUnder,
        double maxRegression,
        Map<String, Object> metadata) {

    public PromptEvalLink {
        metadata = Map.copyOf(metadata == null ? Map.of() : EvalFailureTriage.sanitize(metadata));
    }

    @SuppressWarnings("unchecked")
    public static PromptEvalLink fromMap(Map<String, Object> data) {
        String promptId = first(data, "promptId", "templateId");
        String promptVersion = first(data, "promptVersion", "templateVersion");
        String suiteId = first(data, "suiteId", "evalSuiteId", "id");
        if (promptId == null || promptVersion == null || suiteId == null) {
            throw new IllegalArgumentException("prompt eval link requires promptId, promptVersion, and suiteId");
        }
        return new PromptEvalLink(
                promptId,
                promptVersion,
                suiteId,
                number(data.get("baselineScore")),
                number(data.get("failUnder")),
                number(data.get("maxRegression")) == null ? 0.0 : number(data.get("maxRegression")),
                data.get("metadata") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
    }

    public Map<String, Object> toMap() {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("promptId", promptId);
        out.put("promptVersion", promptVersion);
        out.put("suiteId", suiteId);
        out.put("maxRegression", maxRegression);
        if (baselineScore != null) {
            out.put("baselineScore", baselineScore);
        }
        if (failUnder != null) {
            out.put("failUnder", failUnder);
        }
        if (!metadata.isEmpty()) {
            out.put("metadata", metadata);
        }
        return out;
    }

    private static String first(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(String.valueOf(value));
    }
}
