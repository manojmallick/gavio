package io.gavio.providers;

import io.gavio.GavioException.MalformedToolCallException;
import io.gavio.GavioRequest;
import io.gavio.toolcalling.ProviderCompletion;
import io.gavio.toolcalling.ToolCalling;
import io.gavio.toolcalling.ToolCalling.ToolSpec;
import io.gavio.types.FinishReason;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Chat Completions wire shape (F-ADP-03): the pure request and response translation shared by
 * the OpenAI, Azure OpenAI and OpenRouter adapters.
 *
 * <p>Those adapters live in separate modules that depend only on {@code gavio-core}, so the shared
 * mapping lives here; each exposes it as its own {@code toProviderPayload} / {@code
 * fromProviderResponse}. No network, no clock. Port of {@code gavio.providers.openai} (Python),
 * pinned by {@code test-vectors/tool-calling/openai.json} and {@code azure.json}.
 */
public final class ChatCompletionsWire {

    private static final Map<String, FinishReason> FINISH_REASONS = Map.of(
            "stop", FinishReason.STOP,
            "length", FinishReason.LENGTH,
            "tool_calls", FinishReason.TOOL_CALLS,
            "content_filter", FinishReason.CONTENT_FILTER);

    private ChatCompletionsWire() {
    }

    /**
     * Build the Chat Completions request body for {@code request}.
     *
     * @param includeModel {@code false} omits {@code model} for Azure, which routes by deployment
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request, boolean includeModel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (includeModel) {
            payload.put("model", request.model());
        }
        List<Object> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            messages.add(toNativeMessage(message));
        }
        payload.put("messages", messages);
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());
        List<ToolSpec> specs = ToolCalling.toolSpecs(request.tools());
        if (!specs.isEmpty()) {
            List<Object> tools = new ArrayList<>();
            for (ToolSpec spec : specs) {
                tools.add(toNativeTool(spec));
            }
            payload.put("tools", tools);
            ToolChoice choice = ToolCalling.activeToolChoice(request);
            if (choice != null) {
                payload.put("tool_choice", toNativeChoice(choice));
            }
        }
        return payload;
    }

    /** Parse a Chat Completions response body into normalised parts. */
    @SuppressWarnings("unchecked")
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        Map<String, Object> choice = Map.of();
        if (payload.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> first) {
            choice = (Map<String, Object>) first;
        }
        Map<String, Object> message = choice.get("message") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> raw) {
            for (int index = 0; index < raw.size(); index++) {
                toolCalls.add(fromNativeCall(index, raw.get(index)));
            }
        }
        Object content = message.get("content");
        Object finish = choice.get("finish_reason");
        Map<String, Object> usage = payload.get("usage") instanceof Map<?, ?> u
                ? (Map<String, Object>) u : Map.of();
        Object model = payload.get("model");
        return new ProviderCompletion(
                content == null ? "" : content.toString(),
                toolCalls,
                ToolCalling.normaliseFinishReason(
                        finish == null ? null : finish.toString(), FINISH_REASONS, toolCalls),
                new TokenUsage(asInt(usage.get("prompt_tokens")), asInt(usage.get("completion_tokens"))),
                model == null ? null : model.toString());
    }

    private static Map<String, Object> toNativeMessage(Message message) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("assistant".equals(message.role()) && message.hasToolCalls()) {
            out.put("role", "assistant");
            out.put("content", message.content().isEmpty() ? null : message.content());
            List<Object> calls = new ArrayList<>();
            for (ToolCall call : message.toolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", ToolCalling.dumpArguments(call.arguments()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", call.id());
                entry.put("type", "function");
                entry.put("function", function);
                calls.add(entry);
            }
            out.put("tool_calls", calls);
            return out;
        }
        if ("tool".equals(message.role()) && message.toolCallId() != null) {
            out.put("role", "tool");
            out.put("tool_call_id", message.toolCallId());
            out.put("content", message.content());
            return out;
        }
        out.put("role", message.role());
        out.put("content", message.content());
        return out;
    }

    private static Map<String, Object> toNativeTool(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        if (spec.description() != null) {
            function.put("description", spec.description());
        }
        function.put("parameters",
                spec.inputSchema() != null ? spec.inputSchema() : ToolCalling.emptyObjectSchema());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static Object toNativeChoice(ToolChoice choice) {
        if (choice.mode() == ToolChoice.Mode.TOOL) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", choice.name());
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("type", "function");
            named.put("function", function);
            return named;
        }
        return choice.mode().value();
    }

    @SuppressWarnings("unchecked")
    private static ToolCall fromNativeCall(int index, Object raw) {
        Map<String, Object> call = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> function = call.get("function") instanceof Map<?, ?> f
                ? (Map<String, Object>) f : Map.of();
        Object name = function.get("name");
        if (name == null || name.toString().isEmpty()) {
            throw new MalformedToolCallException("tool call at index " + index + " has no function name");
        }
        Object id = call.get("id");
        return new ToolCall(
                id == null || id.toString().isEmpty()
                        ? ToolCalling.syntheticCallId(index, name.toString()) : id.toString(),
                name.toString(),
                ToolCalling.parseArguments(function.get("arguments"), name.toString()));
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
