package io.gavio.prompts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-prompt-version eval gate result. */
public record PromptVersionGate(
        String promptId,
        String promptVersion,
        String suiteId,
        boolean passed,
        double score,
        int totalCases,
        int passedCases,
        List<String> failedCases,
        List<String> reasons,
        Double baselineScore,
        Double failUnder,
        double maxRegression,
        Double scoreDelta) {

    public PromptVersionGate {
        failedCases = List.copyOf(failedCases);
        reasons = List.copyOf(reasons);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promptId", promptId);
        out.put("promptVersion", promptVersion);
        out.put("suiteId", suiteId);
        out.put("passed", passed);
        out.put("score", score);
        out.put("totalCases", totalCases);
        out.put("passedCases", passedCases);
        out.put("failedCases", failedCases);
        out.put("reasons", reasons);
        out.put("baselineScore", baselineScore);
        out.put("failUnder", failUnder);
        out.put("maxRegression", maxRegression);
        out.put("scoreDelta", scoreDelta);
        return out;
    }
}
