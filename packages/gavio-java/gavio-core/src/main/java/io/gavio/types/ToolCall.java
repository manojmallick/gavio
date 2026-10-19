package io.gavio.types;

import io.gavio.json.CanonicalJson;
import io.gavio.json.JsonValues;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A model-requested tool invocation, normalised across providers (F-ADP-03).
 *
 * <p>{@code arguments} is always a parsed JSON object (unmodifiable; key order is kept). {@code id}
 * is the provider's call id, or a deterministic synthetic {@code call_<index>_<name>} for providers
 * that send none (Gemini, Ollama). Shape: {@code spec/ToolCall.schema.json}.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("tool call id must not be empty");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool call name must not be empty");
        }
        arguments = JsonValues.immutableMap(arguments);
    }

    /** A call without arguments. */
    public ToolCall(String id, String name) {
        this(id, name, Map.of());
    }

    /**
     * Lowercase hex SHA-256 of the canonical JSON of {@link #arguments()} — what audit records
     * keep instead of the arguments. Keep integers and strings in arguments you intend to audit:
     * a float that is integral ({@code 1.0}) hashes as {@code 1}, which Python spells {@code 1.0}.
     */
    public String argumentsHash() {
        return CanonicalJson.sha256Hex(arguments);
    }

    /** The {@link ToolResult} answering this call. */
    public ToolResult result(String content) {
        return new ToolResult(id, content, name, false);
    }

    /** The {@link ToolResult} answering this call, flagged as a failure when {@code isError}. */
    public ToolResult result(String content, boolean isError) {
        return new ToolResult(id, content, name, isError);
    }

    /** The spec shape: {@code {"id", "name", "arguments"}}; the arguments are copied. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("arguments", JsonValues.mutableMap(arguments));
        return out;
    }

    /**
     * Read a call from its spec shape.
     *
     * @throws IllegalArgumentException when {@code raw} is not an object, lacks {@code id} or
     *     {@code name}, or has arguments that are not an object
     */
    @SuppressWarnings("unchecked")
    public static ToolCall from(Object raw) {
        if (raw instanceof ToolCall call) {
            return call;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("tool call must be an object");
        }
        Object id = map.get("id");
        Object name = map.get("name");
        if (id == null || name == null) {
            throw new IllegalArgumentException("tool call needs an id and a name");
        }
        Object arguments = map.get("arguments");
        if (arguments != null && !(arguments instanceof Map)) {
            throw new IllegalArgumentException("tool call arguments must be a JSON object");
        }
        return new ToolCall(String.valueOf(id), String.valueOf(name), (Map<String, Object>) arguments);
    }
}
