package io.gavio.providers.anthropic;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.providers.AbstractProviderAdapter;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to the Anthropic Messages endpoint (Claude Sonnet, Haiku, Opus).
 *
 * <p>Anthropic splits the system prompt from the message list, so any
 * {@code role == "system"} messages are extracted into the {@code system} field.
 */
public final class AnthropicAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

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

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        Map<String, String> headers = headers();

        StringBuilder systemParts = new StringBuilder();
        List<Object> chat = new ArrayList<>();
        for (Message m : request.messages()) {
            if ("system".equals(m.role())) {
                if (systemParts.length() > 0) {
                    systemParts.append('\n');
                }
                systemParts.append(m.content());
            } else {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", m.role());
                msg.put("content", m.content());
                chat.add(msg);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", chat);
        payload.put("max_tokens", request.maxTokens());
        payload.put("temperature", request.temperature());
        if (systemParts.length() > 0) {
            payload.put("system", systemParts.toString());
        }

        return HttpJson.postJson(baseUrl + "/messages", payload, headers, timeoutSeconds)
                .thenApply(data -> {
                    String content = extractContent(data);
                    TokenUsage usage = extractUsage(data);
                    String modelVersion = data.get("model") != null
                            ? String.valueOf(data.get("model")) : request.model();
                    return buildResponse(request, content, usage, modelVersion, started);
                });
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> data) {
        Object contentObj = data.get("content");
        if (!(contentObj instanceof List<?> blocks)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object b : blocks) {
            if (b instanceof Map<?, ?> block && "text".equals(block.get("type"))) {
                Object text = ((Map<String, Object>) block).get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static TokenUsage extractUsage(Map<String, Object> data) {
        Map<String, Object> usage = (Map<String, Object>) data.get("usage");
        if (usage == null) {
            return new TokenUsage();
        }
        return new TokenUsage(asInt(usage.get("input_tokens")), asInt(usage.get("output_tokens")));
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
