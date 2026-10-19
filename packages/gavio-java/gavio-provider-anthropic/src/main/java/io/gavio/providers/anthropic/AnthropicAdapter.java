package io.gavio.providers.anthropic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to the Anthropic Messages endpoint (Claude Sonnet, Haiku, Opus).
 *
 * <p>Anthropic splits the system prompt from the message list, so any
 * {@code role == "system"} messages are extracted into the {@code system} field. Tool calling
 * (F-ADP-03) maps to {@code tool_use} / {@code tool_result} content blocks.
 */
public final class AnthropicAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

    private static final Map<String, FinishReason> FINISH_REASONS = Map.of(
            "end_turn", FinishReason.STOP,
            "stop_sequence", FinishReason.STOP,
            "max_tokens", FinishReason.LENGTH,
            "tool_use", FinishReason.TOOL_CALLS,
            "refusal", FinishReason.CONTENT_FILTER);

    private final String apiKey;
    private final String baseUrl;
    private final double timeoutSeconds;

    public AnthropicAdapter(String apiKey, String baseUrl, double timeoutSeconds, PricingProvider pricing) {
        super(pricing);
        this.apiKey = apiKey != null ? apiKey : System.getenv("ANTHROPIC_API_KEY");
        this.baseUrl = stripTrailingSlash(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    private Map<String, String> headers() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ConfigurationException(
                    "ANTHROPIC_API_KEY not set (pass apiKey or set the env var)");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-api-key", apiKey);
        h.put("anthropic-version", API_VERSION);
        return h;
    }

    /**
     * Build the Messages API request body for {@code request} (F-ADP-03). Pure: no network.
     *
     * <p>System messages move to the {@code system} field. An assistant turn with tool calls becomes
     * {@code tool_use} blocks; consecutive tool-role messages become one user turn of
     * {@code tool_result} blocks, as the API requires for parallel calls.
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request) {
        StringBuilder system = new StringBuilder();
        boolean hasSystem = false;
        List<Message> chat = new ArrayList<>();
        for (Message m : request.messages()) {
            if ("system".equals(m.role())) {
                if (hasSystem) {
                    system.append('\n');
                }
                system.append(m.content());
                hasSystem = true;
            } else {
                chat.add(m);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", toNativeMessages(chat));
        payload.put("max_tokens", request.maxTokens());
        payload.put("temperature", request.temperature());
        if (system.length() > 0) {
            payload.put("system", system.toString());
        }
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

    /** Parse a Messages API response body into normalised parts (F-ADP-03). Pure: no network. */
    @SuppressWarnings("unchecked")
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (payload.get("content") instanceof List<?> blocks) {
            for (Object raw : blocks) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> block = (Map<String, Object>) map;
                if ("text".equals(block.get("type")) && block.get("text") != null) {
                    content.append(block.get("text"));
                } else if ("tool_use".equals(block.get("type"))) {
                    toolCalls.add(fromNativeBlock(block));
                }
            }
        }
        Map<String, Object> usage = payload.get("usage") instanceof Map<?, ?> u
                ? (Map<String, Object>) u : Map.of();
        Object stopReason = payload.get("stop_reason");
        Object model = payload.get("model");
        return new ProviderCompletion(
                content.toString(),
                toolCalls,
                ToolCalling.normaliseFinishReason(
                        stopReason == null ? null : stopReason.toString(), FINISH_REASONS, toolCalls),
                new TokenUsage(asInt(usage.get("input_tokens")), asInt(usage.get("output_tokens"))),
                model == null ? null : model.toString());
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        Map<String, String> headers = headers();
        Map<String, Object> payload = toProviderPayload(request);

        return HttpJson.postJson(baseUrl + "/messages", payload, headers, timeoutSeconds)
                .thenApply(data -> buildCompletionResponse(
                        request, fromProviderResponse(data), started));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toNativeMessages(List<Message> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message message : messages) {
            if ("assistant".equals(message.role()) && message.hasToolCalls()) {
                List<Object> blocks = new ArrayList<>();
                if (!message.content().isEmpty()) {
                    Map<String, Object> text = new LinkedHashMap<>();
                    text.put("type", "text");
                    text.put("text", message.content());
                    blocks.add(text);
                }
                for (ToolCall call : message.toolCalls()) {
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", call.id());
                    use.put("name", call.name());
                    use.put("input", JsonValues.mutableMap(call.arguments()));
                    blocks.add(use);
                }
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("role", "assistant");
                turn.put("content", blocks);
                out.add(turn);
            } else if ("tool".equals(message.role()) && message.toolCallId() != null) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", message.toolCallId());
                block.put("content", message.content());
                if (message.isError()) {
                    block.put("is_error", true);
                }
                Map<String, Object> last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (isToolResultTurn(last)) {
                    ((List<Object>) last.get("content")).add(block);
                } else {
                    List<Object> blocks = new ArrayList<>();
                    blocks.add(block);
                    Map<String, Object> turn = new LinkedHashMap<>();
                    turn.put("role", "user");
                    turn.put("content", blocks);
                    out.add(turn);
                }
            } else {
                Map<String, Object> turn = new LinkedHashMap<>();
                turn.put("role", message.role());
                turn.put("content", message.content());
                out.add(turn);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isToolResultTurn(Map<String, Object> message) {
        if (message == null || !"user".equals(message.get("role"))
                || !(message.get("content") instanceof List<?> blocks)) {
            return false;
        }
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> map) || !"tool_result".equals(map.get("type"))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> toNativeTool(ToolSpec spec) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", spec.name());
        if (spec.description() != null) {
            tool.put("description", spec.description());
        }
        tool.put("input_schema",
                spec.inputSchema() != null ? spec.inputSchema() : ToolCalling.emptyObjectSchema());
        return tool;
    }

    private static Map<String, Object> toNativeChoice(ToolChoice choice) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (choice.mode()) {
            case TOOL -> {
                out.put("type", "tool");
                out.put("name", choice.name());
            }
            case REQUIRED -> out.put("type", "any");
            default -> out.put("type", choice.mode().value());
        }
        return out;
    }

    private static ToolCall fromNativeBlock(Map<String, Object> block) {
        Object name = block.get("name");
        Object id = block.get("id");
        if (name == null || name.toString().isEmpty() || id == null || id.toString().isEmpty()) {
            throw new MalformedToolCallException("tool_use block is missing its id or name");
        }
        return new ToolCall(
                id.toString(), name.toString(), ToolCalling.parseArguments(block.get("input"), name.toString()));
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(apiKey != null && !apiKey.isEmpty());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link AnthropicAdapter}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private double timeoutSeconds = 30.0;
        private PricingProvider pricing;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder timeoutSeconds(double timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder pricing(PricingProvider pricing) {
            this.pricing = pricing;
            return this;
        }

        public AnthropicAdapter build() {
            return new AnthropicAdapter(apiKey, baseUrl, timeoutSeconds, pricing);
        }
    }
}
