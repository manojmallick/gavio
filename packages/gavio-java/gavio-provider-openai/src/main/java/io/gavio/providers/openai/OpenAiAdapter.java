package io.gavio.providers.openai;

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

/** Talks to the OpenAI Chat Completions endpoint (GPT-4o, o1, ...). */
public final class OpenAiAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String apiKey;
    private final String baseUrl;
    private final double timeoutSeconds;
    private final String organization;

    public OpenAiAdapter(String apiKey, String baseUrl, double timeoutSeconds,
                         String organization, PricingProvider pricing) {
        super(pricing);
        this.apiKey = apiKey != null ? apiKey : System.getenv("OPENAI_API_KEY");
        this.baseUrl = stripTrailingSlash(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        this.timeoutSeconds = timeoutSeconds;
        this.organization = organization;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    private Map<String, String> headers() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ConfigurationException(
                    "OPENAI_API_KEY not set (pass apiKey or set the env var)");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        if (organization != null) {
            h.put("OpenAI-Organization", organization);
        }
        return h;
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        Map<String, String> headers = headers();

        List<Object> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content());
            messages.add(msg);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", messages);
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());

        return HttpJson.postJson(baseUrl + "/chat/completions", payload, headers, timeoutSeconds)
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
        List<Object> choices = (List<Object>) data.get("choices");
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Map<String, Object> choice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        Object content = message != null ? message.get("content") : null;
        return content != null ? content.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private static TokenUsage extractUsage(Map<String, Object> data) {
        Map<String, Object> usage = (Map<String, Object>) data.get("usage");
        if (usage == null) {
            return new TokenUsage();
        }
        return new TokenUsage(asInt(usage.get("prompt_tokens")), asInt(usage.get("completion_tokens")));
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

    /** Fluent builder for {@link OpenAiAdapter}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private double timeoutSeconds = 30.0;
        private String organization;
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

        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        public Builder pricing(PricingProvider pricing) {
            this.pricing = pricing;
            return this;
        }

        public OpenAiAdapter build() {
            return new OpenAiAdapter(apiKey, baseUrl, timeoutSeconds, organization, pricing);
        }
    }
}
