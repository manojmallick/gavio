package io.gavio.types;

/**
 * The outcome of executing a {@link ToolCall} (F-ADP-03). In a conversation it travels as a
 * {@code tool}-role {@link Message}; see {@link #toMessage()}. Shape:
 * {@code spec/ToolResult.schema.json}.
 *
 * @param toolCallId the {@link ToolCall#id()} this result answers
 * @param content tool output as text; structured output is JSON-encoded by the caller
 * @param name optional tool name, used by providers that match results by name (Gemini, Ollama)
 * @param isError true when the tool failed
 */
public record ToolResult(String toolCallId, String content, String name, boolean isError) {

    public ToolResult {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("toolCallId must not be empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (name != null && name.isEmpty()) {
            name = null;
        }
    }

    public ToolResult(String toolCallId, String content) {
        this(toolCallId, content, null, false);
    }

    /** The {@code tool}-role message to append to the conversation. */
    public Message toMessage() {
        return Message.toolResult(toolCallId, content, name, isError);
    }
}
