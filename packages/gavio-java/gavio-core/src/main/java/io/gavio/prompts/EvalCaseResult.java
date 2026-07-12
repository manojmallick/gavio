package io.gavio.prompts;

import io.gavio.types.PromptLineage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metadata-safe result for one eval case. */
public record EvalCaseResult(
        String id,
        String templateId,
        String templateVersion,
        boolean passed,
        double score,
        String outputHash,
        List<EvalAssertionResult> assertions,
        PromptLineage lineage,
        EvalFailureTriage triage) {

    public EvalCaseResult {
        assertions = List.copyOf(assertions);
    }

    public EvalCaseResult(
            String id,
            String templateId,
            String templateVersion,
            boolean passed,
            double score,
            String outputHash,
            List<EvalAssertionResult> assertions,
            PromptLineage lineage) {
        this(id, templateId, templateVersion, passed, score, outputHash, assertions, lineage, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("templateId", templateId);
        out.put("templateVersion", templateVersion);
        out.put("passed", passed);
        out.put("score", score);
        out.put("outputHash", outputHash);
        List<Object> assertionMaps = new ArrayList<>();
        for (EvalAssertionResult assertion : assertions) {
            assertionMaps.add(assertion.toMap());
        }
        out.put("assertions", assertionMaps);
        out.put("lineage", lineageToCamel(lineage));
        if (triage != null) {
            out.put("triage", triage.toMap());
        }
        return out;
    }

    private static Map<String, Object> lineageToCamel(PromptLineage lineage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", lineage.templateId());
        out.put("templateVersion", lineage.templateVersion());
        out.put("variables", new LinkedHashMap<>(lineage.variables()));
        out.put("ragChunks", List.of());
        return out;
    }
}
