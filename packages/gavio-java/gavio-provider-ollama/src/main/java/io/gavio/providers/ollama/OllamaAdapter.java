package io.gavio.providers.ollama;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.MalformedToolCallException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.json.JsonValues;
import io.gavio.providers.AbstractProviderAdapter;
import io.gavio.toolcalling.ProviderCompletion;
import io.gavio.toolcalling.ToolCalling;
import io.gavio.toolcalling.ToolCalling.ToolSpec;
import io.gavio.types.FinishReason;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Local models via the Ollama chat API (/api/chat). No API key; cost is $0. */
public final class OllamaAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private static final Map<String, FinishReason> FINISH_REASONS = Map.of(
            "stop", FinishReason.STOP,
            "length", FinishReason.LENGTH);

    private final String baseUrl;
    private final double timeoutSeconds;

    public OllamaAdapter(String baseUrl, double timeoutSeconds, PricingProvider pricing) {
        super(pricing);
        String url = baseUrl != null ? baseUrl : System.getenv("OLLAMA_HOST");
        this.baseUrl = (url != null ? url : DEFAULT_BASE_URL).replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
    }

    public OllamaAdapter() {
        this(null, 60.0, null);
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    /**
     * Build the {@code /api/chat} request body for {@code request} (F-ADP-03). Pure: no network.
     *
     * <p>Follows the documented tool-calling shape: tools are {@code {"type": "function",
     * "function": {...}}} declarations, calls carry an object {@code arguments} and no id, and a
     * result is a {@code role: "tool"} message naming the tool in {@code tool_name}. Ollama has no
     * tool-choice parameter. The shape comes from that documentation and the shared vectors; it has
     * not been exercised against a live Ollama server.
     *
     * @throws ConfigurationException for a tool choice other than {@code auto}
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", toNativeMessages(request.messages()));
        payload.put("stream", false);
        payload.put("options", Map.of("temperature", request.temperature()));
        List<ToolSpec> specs = ToolCalling.toolSpecs(request.tools());
        if (!specs.isEmpty()) {
            ToolChoice choice = ToolCalling.activeToolChoice(request);
            if (choice != null && choice.mode() != ToolChoice.Mode.AUTO) {
                throw new ConfigurationException(
                        "ollama's /api/chat has no tool_choice parameter; only 'auto' is supported");
            }
            List<Object> tools = new ArrayList<>();
            for (ToolSpec spec : specs) {
                tools.add(toNativeTool(spec));
            }
            payload.put("tools", tools);
        }
        return payload;
    }

    /** Parse an {@code /api/chat} response body into normalised parts (F-ADP-03). Pure: no network. */
    @SuppressWarnings("unchecked")
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        Map<String, Object> message = payload.get("message") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> raw) {
            for (int index = 0; index < raw.size(); index++) {
                toolCalls.add(fromNativeCall(index, raw.get(index)));
            }
        }
        Object content = message.get("content");
        Object done = payload.get("done_reason");
        Object model = payload.get("model");
        return new ProviderCompletion(
                content == null ? "" : content.toString(),
                toolCalls,
                ToolCalling.normaliseFinishReason(
                        done == null ? null : done.toString(), FINISH_REASONS, toolCalls),
                new TokenUsage(asInt(payload.get("prompt_eval_count")), asInt(payload.get("eval_count"))),
                model == null ? null : model.toString());
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        Map<String, Object> payload = toProviderPayload(request);

        return HttpJson.postJson(baseUrl + "/api/chat", payload, Map.of(), timeoutSeconds)
                .thenApply(data -> buildCompletionResponse(
                        request, fromProviderResponse(data), started));
    }

    private static List<Object> toNativeMessages(List<Message> messages) {
        List<Object> out = new ArrayList<>();
        Map<String, String> namesById = new HashMap<>();
        for (Message message : messages) {
            Map<String, Object> turn = new LinkedHashMap<>();
            if ("assistant".equals(message.role()) && message.hasToolCalls()) {
                List<Object> calls = new ArrayList<>();
                for (ToolCall call : message.toolCalls()) {
                    namesById.put(call.id(), call.name());
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", JsonValues.mutableMap(call.arguments()));
                    calls.add(Map.of("function", function));
                }
                turn.put("role", "assistant");
                turn.put("content", message.content());
                turn.put("tool_calls", calls);
            } else if ("tool".equals(message.role()) && message.toolCallId() != null) {
                turn.put("role", "tool");
                turn.put("content", message.content());
                String name = namesById.get(message.toolCallId());
                if (name == null) {
                    name = message.name();
                }
                if (name != null && !name.isEmpty()) {
                    turn.put("tool_name", name);
                }
            } else {
                turn.put("role", message.role());
                turn.put("content", message.content());
            }
            out.add(turn);
        }
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

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(true);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link OllamaAdapter}. */
    public static final class Builder {
        private String baseUrl;
        private double timeoutSeconds = 60.0;
        private PricingProvider pricing;

        public Builder baseUrl(String v) {
            this.baseUrl = v;
            return this;
        }

        public Builder timeoutSeconds(double v) {
            this.timeoutSeconds = v;
            return this;
        }

        public Builder pricing(PricingProvider v) {
            this.pricing = v;
            return this;
        }

        public OllamaAdapter build() {
            return new OllamaAdapter(baseUrl, timeoutSeconds, pricing);
        }
    }
}
