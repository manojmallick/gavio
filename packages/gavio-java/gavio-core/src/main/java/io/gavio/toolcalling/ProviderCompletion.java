package io.gavio.toolcalling;

import io.gavio.types.FinishReason;
import io.gavio.types.TokenUsage;
import io.gavio.types.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A provider response parsed into normalised parts ({@code fromProviderResponse}, F-ADP-03).
 *
 * @param content the text, {@code ""} when the model only called tools
 * @param finishReason {@code null} when the provider reports none
 * @param modelVersion {@code null} when the provider reports none
 */
public record ProviderCompletion(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        String modelVersion) {

    public ProviderCompletion {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? new TokenUsage() : usage;
    }

    /** The spec (camelCase) shape the shared test vectors use. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("content", content);
        List<Object> calls = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            calls.add(call.toMap());
        }
        wire.put("toolCalls", calls);
        wire.put("finishReason", finishReason == null ? null : finishReason.value());
        Map<String, Object> usageWire = new LinkedHashMap<>();
        usageWire.put("promptTokens", usage.promptTokens());
        usageWire.put("completionTokens", usage.completionTokens());
        usageWire.put("totalTokens", usage.totalTokens());
        wire.put("usage", usageWire);
        wire.put("modelVersion", modelVersion);
        return wire;
    }
}
