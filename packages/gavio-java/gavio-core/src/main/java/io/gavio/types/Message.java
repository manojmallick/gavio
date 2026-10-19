package io.gavio.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A provider-agnostic chat message. Immutable record.
 *
 * <p>Besides {@code role} and {@code content}, a message carries the tool-calling members of
 * {@code spec/GavioRequest.schema.json} (F-ADP-03): an assistant turn that called tools has
 * {@code toolCalls} (and an empty {@code content} when it only called tools); a {@code tool}
 * message has the {@code toolCallId} it answers, an optional tool {@code name}, and
 * {@code isError}. Plain messages leave them empty.
 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name,
        boolean isError) {

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (toolCallId != null && toolCallId.isEmpty()) {
            toolCallId = null;
        }
        if (name != null && name.isEmpty()) {
            name = null;
        }
    }

    /** A plain message without tool-calling members. */
    public Message(String role, String content) {
        this(role, content, List.of(), null, null, false);
    }

    /** Factory mirroring the Java SDK plan: {@code Message.of(role, content)}. */
    public static Message of(String role, String content) {
        return new Message(role, content);
    }

    /** The assistant turn that made {@code calls}; {@code content} is {@code ""} when it only called tools. */
    public static Message assistantToolCalls(String content, List<ToolCall> calls) {
        return new Message("assistant", content == null ? "" : content, calls, null, null, false);
    }

    /** A {@code tool}-role message answering the call {@code toolCallId}. */
    public static Message toolResult(String toolCallId, String content) {
        return toolResult(toolCallId, content, null, false);
    }

    /** A {@code tool}-role message answering the call {@code toolCallId}, optionally naming the tool. */
    public static Message toolResult(String toolCallId, String content, String name, boolean isError) {
        return new Message("tool", content, List.of(), toolCallId, name, isError);
    }

    /** Return a copy with replaced content; the tool-calling members are kept. */
    public Message withContent(String newContent) {
        return new Message(role, newContent, toolCalls, toolCallId, name, isError);
    }

    /** Return a copy with replaced tool calls. */
    public Message withToolCalls(List<ToolCall> newToolCalls) {
        return new Message(role, content, newToolCalls, toolCallId, name, isError);
    }

    /** True for an assistant turn that made tool calls. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** True when the message has any tool-calling member beyond role and content. */
    public boolean hasToolFields() {
        return hasToolCalls() || toolCallId != null || name != null || isError;
    }

    /**
     * The spec shape: {@code role} and {@code content}, plus {@code toolCalls}, {@code toolCallId},
     * {@code name} and {@code isError} when set (camelCase, as in {@code spec/GavioRequest.schema.json}).
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role);
        out.put("content", content);
        if (hasToolCalls()) {
            List<Object> calls = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                calls.add(call.toMap());
            }
            out.put("toolCalls", calls);
        }
        if (toolCallId != null) {
            out.put("toolCallId", toolCallId);
        }
        if (name != null) {
            out.put("name", name);
        }
        if (isError) {
            out.put("isError", true);
        }
        return out;
    }

    /**
     * Read a message from its spec shape. The tool keys are camelCase ({@code toolCalls},
     * {@code toolCallId}, {@code isError}); Python's snake_case spellings ({@code tool_calls},
     * {@code tool_call_id}, {@code is_error}) are accepted too. A {@code null} or missing
     * {@code content} (OpenAI-style tool-call turns) reads as {@code ""}.
     */
    public static Message from(Map<?, ?> raw) {
        Object content = raw.get("content");
        Object calls = raw.containsKey("toolCalls") ? raw.get("toolCalls") : raw.get("tool_calls");
        List<ToolCall> toolCalls = new ArrayList<>();
        if (calls instanceof List<?> list) {
            for (Object item : list) {
                toolCalls.add(ToolCall.from(item));
            }
        }
        Object callId = raw.containsKey("toolCallId") ? raw.get("toolCallId") : raw.get("tool_call_id");
        Object name = raw.get("name");
        Object error = raw.containsKey("isError") ? raw.get("isError") : raw.get("is_error");
        return new Message(
                String.valueOf(raw.get("role")),
                content == null ? "" : String.valueOf(content),
                toolCalls,
                callId == null ? null : String.valueOf(callId),
                name == null ? null : String.valueOf(name),
                Boolean.TRUE.equals(error));
    }
}
