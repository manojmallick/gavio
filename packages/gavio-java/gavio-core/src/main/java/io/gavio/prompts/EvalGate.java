package io.gavio.prompts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CI gate decision for one eval run (fail-under and baseline regression). */
public record EvalGate(
        boolean passed,
        List<String> reasons,
        Double failUnder,
        Double baselineScore,
        double maxRegression,
        Double scoreDelta) {

    public EvalGate {
        reasons = List.copyOf(reasons);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", passed);
        out.put("reasons", reasons);
        out.put("failUnder", failUnder);
        out.put("baselineScore", baselineScore);
        out.put("maxRegression", maxRegression);
        out.put("scoreDelta", scoreDelta);
        return out;
    }
}
