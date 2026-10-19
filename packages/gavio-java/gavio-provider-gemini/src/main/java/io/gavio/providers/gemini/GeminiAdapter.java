package io.gavio.providers.gemini;

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

/**
 * Gemini generateContent adapter. Gemini uses roles user/model (not assistant)
 * and a separate systemInstruction field. Tool calling (F-ADP-03) maps to
 * {@code functionCall} / {@code functionResponse} parts.
 */
public final class GeminiAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private static final Map<String, FinishReason> FINISH_REASONS = Map.of(
            "STOP", FinishReason.STOP,
            "MAX_TOKENS", FinishReason.LENGTH,
            "SAFETY", FinishReason.CONTENT_FILTER,
            "RECITATION", FinishReason.CONTENT_FILTER,
            "BLOCKLIST", FinishReason.CONTENT_FILTER,
            "PROHIBITED_CONTENT", FinishReason.CONTENT_FILTER,
            "SPII", FinishReason.CONTENT_FILTER);

    private static final Map<ToolChoice.Mode, String> CALLING_MODES = Map.of(
            ToolChoice.Mode.AUTO, "AUTO",
            ToolChoice.Mode.NONE, "NONE",
            ToolChoice.Mode.REQUIRED, "ANY",
            ToolChoice.Mode.TOOL, "ANY");

    private final String apiKey;
    private final String baseUrl;
    private final double timeoutSeconds;

    public GeminiAdapter(String apiKey, String baseUrl, double timeoutSeconds, PricingProvider pricing) {
        super(pricing);
        String key = apiKey != null ? apiKey : System.getenv("GEMINI_API_KEY");
        this.apiKey = key != null ? key : System.getenv("GOOGLE_API_KEY");
        this.baseUrl = (baseUrl != null ? baseUrl : DEFAULT_BASE_URL).replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    /**
     * Map Gavio messages to Gemini contents; the entry key is the system instruction ({@code ""}
     * when there is none).
     *
     * <p>Gemini has no tool-call ids: results are matched back to calls by name. A call id the
     * provider did supply is echoed on the matching {@code functionResponse}; a synthetic
     * {@code call_<index>_<name>} id is not.
     *
     * @throws ConfigurationException when a tool result matches no earlier call and has no name
     */
    public static Map.Entry<String, List<Object>> toContents(List<Message> messages) {
        String system = null;
        List<Map<String, Object>> contents = new ArrayList<>();
        // call id -> (tool name, whether the provider supplied the id)
        Map<String, CallRef> callsById = new HashMap<>();
        for (Message m : messages) {
            String role = m.role();
            String text = m.content();
            if ("system".equals(role)) {
                system = system == null ? text : system + "\n" + text;
                continue;
            }
            if ("assistant".equals(role) && m.hasToolCalls()) {
                List<Object> parts = new ArrayList<>();
                if (!text.isEmpty()) {
                    parts.add(textPart(text));
                }
                List<ToolCall> calls = m.toolCalls();
                for (int index = 0; index < calls.size(); index++) {
                    ToolCall call = calls.get(index);
                    boolean supplied = !call.id().equals(ToolCalling.syntheticCallId(index, call.name()));
                    callsById.put(call.id(), new CallRef(call.name(), supplied));
                    Map<String, Object> functionCall = new LinkedHashMap<>();
                    functionCall.put("name", call.name());
                    functionCall.put("args", JsonValues.mutableMap(call.arguments()));
                    if (supplied) {
                        functionCall.put("id", call.id());
                    }
                    parts.add(Map.of("functionCall", functionCall));
                }
                contents.add(turn("model", parts));
            } else if ("tool".equals(role) && m.toolCallId() != null) {
                CallRef ref = callsById.get(m.toolCallId());
                String name = ref != null ? ref.name() : m.name();
                if (name == null || name.isEmpty()) {
                    throw new ConfigurationException("tool result '" + m.toolCallId()
                            + "' matches no earlier tool call and has no name");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", name);
                result.put("response", Map.of(m.isError() ? "error" : "output", text));
                if (ref != null && ref.supplied()) {
                    result.put("id", m.toolCallId());
                }
                Map<String, Object> part = Map.of("functionResponse", result);
                Map<String, Object> last = contents.isEmpty() ? null : contents.get(contents.size() - 1);
                if (isToolResultTurn(last)) {
                    @SuppressWarnings("unchecked")
                    List<Object> lastParts = (List<Object>) last.get("parts");
                    lastParts.add(part);
                } else {
                    List<Object> parts = new ArrayList<>();
                    parts.add(part);
                    contents.add(turn("user", parts));
                }
            } else {
                List<Object> parts = new ArrayList<>();
                parts.add(textPart(text));
                contents.add(turn("assistant".equals(role) ? "model" : "user", parts));
            }
        }
        return Map.entry(system == null ? "" : system, new ArrayList<Object>(contents));
    }

    /**
     * Build the {@code generateContent} request body for {@code request} (F-ADP-03). Pure: no
     * network.
     *
     * @throws ConfigurationException for an invalid tool declaration or choice, or a tool result
     *     that matches no call
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request) {
        Map.Entry<String, List<Object>> mapped = toContents(request.messages());
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("temperature", request.temperature());
        genConfig.put("maxOutputTokens", request.maxTokens());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", mapped.getValue());
        payload.put("generationConfig", genConfig);
        if (!mapped.getKey().isEmpty()) {
            payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", mapped.getKey()))));
        }
        List<ToolSpec> specs = ToolCalling.toolSpecs(request.tools());
        if (!specs.isEmpty()) {
            List<Object> declarations = new ArrayList<>();
            for (ToolSpec spec : specs) {
                declarations.add(toNativeTool(spec));
            }
            payload.put("tools", List.of(Map.of("functionDeclarations", declarations)));
            ToolChoice choice = ToolCalling.activeToolChoice(request);
            if (choice != null) {
                payload.put("toolConfig", Map.of("functionCallingConfig", toNativeChoice(choice)));
            }
        }
        return payload;
    }

    /** Parse a {@code generateContent} response body into normalised parts (F-ADP-03). Pure: no network. */
    @SuppressWarnings("unchecked")
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        Map<String, Object> candidate = Map.of();
        if (payload.get("candidates") instanceof List<?> candidates && !candidates.isEmpty()
                && candidates.get(0) instanceof Map<?, ?> first) {
            candidate = (Map<String, Object>) first;
        }
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        Map<String, Object> body = candidate.get("content") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : Map.of();
        if (body.get("parts") instanceof List<?> parts) {
            for (Object raw : parts) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> part = (Map<String, Object>) map;
                if (part.get("text") != null) {
                    content.append(part.get("text"));
                }
                if (part.get("functionCall") instanceof Map<?, ?> call) {
                    toolCalls.add(fromNativeCall(toolCalls.size(), (Map<String, Object>) call));
                }
            }
        }
        Map<String, Object> usage = payload.get("usageMetadata") instanceof Map<?, ?> u
                ? (Map<String, Object>) u : Map.of();
        Object finish = candidate.get("finishReason");
        return new ProviderCompletion(
                content.toString(),
                toolCalls,
                ToolCalling.normaliseFinishReason(
                        finish == null ? null : finish.toString(), FINISH_REASONS, toolCalls),
                new TokenUsage(asInt(usage.get("promptTokenCount")), asInt(usage.get("candidatesTokenCount"))),
                null);
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ConfigurationException("GEMINI_API_KEY not set");
        }
        long started = System.nanoTime();
        Map<String, Object> payload = toProviderPayload(request);

        String url = baseUrl + "/models/" + request.model() + ":generateContent?key=" + apiKey;
        return HttpJson.postJson(url, payload, Map.of(), timeoutSeconds)
                .thenApply(data -> buildCompletionResponse(
                        request, fromProviderResponse(data), started));
    }

    private record CallRef(String name, boolean supplied) {
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", text);
        return part;
    }

    private static Map<String, Object> turn(String role, List<Object> parts) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("role", role);
        turn.put("parts", parts);
        return turn;
    }

    private static boolean isToolResultTurn(Map<String, Object> content) {
        if (content == null || !"user".equals(content.get("role"))
                || !(content.get("parts") instanceof List<?> parts)) {
            return false;
        }
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> map) || !map.containsKey("functionResponse")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> toNativeTool(ToolSpec spec) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", spec.name());
        if (spec.description() != null) {
            declaration.put("description", spec.description());
        }
        // Gemini rejects an object schema without properties, so a no-argument tool omits it.
        if (spec.inputSchema() != null && spec.inputSchema().get("properties") instanceof Map<?, ?> props
                && !props.isEmpty()) {
            declaration.put("parameters", spec.inputSchema());
        }
        return declaration;
    }

    private static Map<String, Object> toNativeChoice(ToolChoice choice) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", CALLING_MODES.get(choice.mode()));
        if (choice.mode() == ToolChoice.Mode.TOOL) {
            config.put("allowedFunctionNames", List.of(choice.name()));
        }
        return config;
    }

    private static ToolCall fromNativeCall(int index, Map<String, Object> call) {
        Object name = call.get("name");
        if (name == null || name.toString().isEmpty()) {
            throw new MalformedToolCallException("functionCall at index " + index + " has no name");
        }
        Object id = call.get("id");
        return new ToolCall(
                id == null || id.toString().isEmpty()
                        ? ToolCalling.syntheticCallId(index, name.toString()) : id.toString(),
                name.toString(),
                ToolCalling.parseArguments(call.get("args"), name.toString()));
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(apiKey != null && !apiKey.isEmpty());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link GeminiAdapter}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private double timeoutSeconds = 30.0;
        private PricingProvider pricing;

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

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

        public GeminiAdapter build() {
            return new GeminiAdapter(apiKey, baseUrl, timeoutSeconds, pricing);
        }
    }
}
