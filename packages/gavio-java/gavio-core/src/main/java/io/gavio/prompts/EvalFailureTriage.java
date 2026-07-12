package io.gavio.prompts;

import io.gavio.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Metadata for routing a failed eval case without storing model output. */
public record EvalFailureTriage(
        String category,
        String severity,
        String owner,
        String action,
        String notes,
        Map<String, Object> metadata) {

    private static final Set<String> CONTENT_KEYS = Set.of(
            "content",
            "completion",
            "messages",
            "output",
            "prompt",
            "raw",
            "rawOutput",
            "renderedPrompt",
            "response",
            "text");

    public EvalFailureTriage {
        metadata = Map.copyOf(metadata == null ? Map.of() : sanitize(metadata));
    }

    @SuppressWarnings("unchecked")
    public static EvalFailureTriage fromMap(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object metadata = data.get("metadata");
        return new EvalFailureTriage(
                optional(data.get("category")),
                optional(data.get("severity")),
                optional(data.get("owner")),
                optional(data.get("action")),
                optional(data.get("notes")),
                metadata instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "category", category);
        put(out, "severity", severity);
        put(out, "owner", owner);
        put(out, "action", action);
        put(out, "notes", notes);
        if (!metadata.isEmpty()) {
            out.put("metadata", sanitize(metadata));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitize(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (CONTENT_KEYS.contains(key)) {
                out.put(key + "Hash", sha256(Json.write(value)));
            } else if (value instanceof Map<?, ?> m) {
                out.put(key, sanitize((Map<String, Object>) m));
            } else if (value instanceof List<?> list) {
                out.put(key, list.stream()
                        .map(item -> item instanceof Map<?, ?> m
                                ? sanitize((Map<String, Object>) m)
                                : item)
                        .toList());
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static String optional(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
