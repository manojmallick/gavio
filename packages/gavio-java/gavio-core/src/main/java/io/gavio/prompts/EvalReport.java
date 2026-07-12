package io.gavio.prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metadata-safe Prompt Registry eval report. */
public record EvalReport(String suiteId, List<EvalCaseResult> cases) {

    public EvalReport {
        cases = List.copyOf(cases);
    }

    public int totalCases() {
        return cases.size();
    }

    public int passedCases() {
        int count = 0;
        for (EvalCaseResult c : cases) {
            if (c.passed()) {
                count++;
            }
        }
        return count;
    }

    public int failedCases() {
        return totalCases() - passedCases();
    }

    public double score() {
        if (cases.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (EvalCaseResult c : cases) {
            sum += c.score();
        }
        return round8(sum / cases.size());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suiteId", suiteId);
        out.put("totalCases", totalCases());
        out.put("passedCases", passedCases());
        out.put("failedCases", failedCases());
        out.put("score", score());
        List<Object> resultMaps = new ArrayList<>();
        for (EvalCaseResult c : cases) {
            resultMaps.add(c.toMap());
        }
        out.put("cases", resultMaps);
        return out;
    }

    static double round8(double value) {
        return Math.round(value * 100000000d) / 100000000d;
    }
}
