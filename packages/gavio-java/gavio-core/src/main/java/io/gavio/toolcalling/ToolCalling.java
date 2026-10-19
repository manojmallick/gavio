package io.gavio.toolcalling;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.MalformedToolCallException;
import io.gavio.GavioRequest;
import io.gavio.json.Json;
import io.gavio.json.JsonValues;
import io.gavio.types.FinishReason;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalised tool-calling helpers shared by provider adapters and interceptors (F-ADP-03).
 *
 * <p>Everything here is pure: no network, no clock. Provider adapters use it to translate between
 * Gavio's normalised tool calls and each provider's native shape. Port of {@code gavio.tool_calling}
 * (Python).
 */
public final class ToolCalling {

    private ToolCalling() {
    }

    /** The input schema sent for a tool that declares none: an object without properties. */
    public static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    /**
     * The provider-relevant part of a tool declaration: name, description, input schema.
     * Governance fields (permissions, risk, ...) are not part of it and never reach the provider.
     *
     * @param description {@code null} when the declaration has none
     * @param inputSchema {@code null} when the declaration has none
     */
    public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    /**
     * Normalise {@link GavioRequest#tools()}; reads {@code inputSchema} or Python's
     * {@code input_schema}.
     *
     * @throws ConfigurationException when a declaration has no name
     */
    public static List<ToolSpec> toolSpecs(List<Map<String, Object>> tools) {
        List<ToolSpec> specs = new ArrayList<>();
        if (tools == null) {
            return specs;
        }
        for (Map<String, Object> tool : tools) {
            Object name = tool == null ? null : tool.get("name");
            if (name == null || String.valueOf(name).isEmpty()) {
                throw new ConfigurationException("tool declaration is missing a name");
            }
            Object schema = tool.containsKey("inputSchema") ? tool.get("inputSchema") : tool.get("input_schema");
            Object description = tool.get("description");
            specs.add(new ToolSpec(
                    String.valueOf(name),
                    description == null || String.valueOf(description).isEmpty()
                            ? null : String.valueOf(description),
                    schema instanceof Map<?, ?> map && !map.isEmpty() ? JsonValues.mutableMap(asObjectMap(map)) : null));
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** The tool choice to send — {@code null} when the request declares no tools. */
    public static ToolChoice activeToolChoice(GavioRequest request) {
        return request.hasTools() ? request.toolChoice() : null;
    }

    /** Deterministic id for providers that send none: {@code call_<index>_<name>}. */
    public static String syntheticCallId(int index, String name) {
        return "call_" + index + "_" + name;
    }

    /** Compact JSON in insertion order — the string OpenAI-style APIs expect for arguments. */
    public static String dumpArguments(Map<String, Object> arguments) {
        return Json.write(arguments);
    }

    /**
     * Parse native tool-call arguments into an object.
     *
     * <p>Accepts an already-parsed object, a JSON string, an empty string or {@code null} (no
     * arguments). Anything else throws {@link MalformedToolCallException} naming the tool only —
     * the arguments may hold PII, so neither they nor the parser's message are repeated.
     */
    public static Map<String, Object> parseArguments(Object raw, String toolName) {
        if (raw == null || (raw instanceof String text && text.isBlank())) {
            return new LinkedHashMap<>();
        }
        Object value = raw;
        if (raw instanceof String text) {
            try {
                value = Json.parse(text);
            } catch (RuntimeException e) {
                throw new MalformedToolCallException(
                        "tool call '" + toolName + "' has arguments that are not valid JSON");
            }
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new MalformedToolCallException(
                    "tool call '" + toolName + "' has arguments that are not a JSON object");
        }
        return JsonValues.mutableMap(asObjectMap(map));
    }

    /**
     * Map a native stop reason; a plain stop that produced tool calls is {@code tool_calls}.
     * A native value missing from {@code mapping} is {@link FinishReason#OTHER}; a {@code null}
     * one stays {@code null} unless there are tool calls.
     */
    public static FinishReason normaliseFinishReason(
            String nativeReason, Map<String, FinishReason> mapping, List<ToolCall> toolCalls) {
        FinishReason reason = nativeReason == null
                ? null : mapping.getOrDefault(nativeReason, FinishReason.OTHER);
        if (!toolCalls.isEmpty() && (reason == null || reason == FinishReason.STOP)) {
            return FinishReason.TOOL_CALLS;
        }
        return reason;
    }
}
