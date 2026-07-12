package io.gavio.prompts;

import java.util.LinkedHashMap;
import java.util.Map;

/** One metadata-safe prompt template difference. */
public record PromptDiffChange(
        String path,
        String type,
        String beforeHash,
        String afterHash,
        Object before,
        Object after) {

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("type", type);
        if (beforeHash != null) {
            out.put("beforeHash", beforeHash);
        }
        if (afterHash != null) {
            out.put("afterHash", afterHash);
        }
        if (before != null) {
            out.put("before", before);
        }
        if (after != null) {
            out.put("after", after);
        }
        return out;
    }
}
