package io.gavio.prompts;

import java.util.LinkedHashMap;
import java.util.Map;

/** Result for one eval assertion. */
public record EvalAssertionResult(String type, boolean passed, Object expected, String reason) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("passed", passed);
        out.put("expected", expected);
        out.put("reason", reason);
        return out;
    }
}
