package io.gavio.providers.gemini;

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
 * Gemini generateContent adapter. Gemini uses roles user/model (not assistant)
 * and a separate systemInstruction field.
 */
public final class GeminiAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

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

    /** Map Gavio messages to Gemini contents; index 0 is the system instruction (nullable). */
    public static Map.Entry<String, List<Object>> toContents(List<Message> messages) {
        String system = null;
        List<Object> contents = new ArrayList<>();
        for (Message m : messages) {
            if ("system".equals(m.role())) {
                system = system == null ? m.content() : system + "\n" + m.content();
                continue;
            }
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("text", m.content());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "assistant".equals(m.role()) ? "model" : "user");
            entry.put("parts", List.of(part));
            contents.add(entry);
        }
        return Map.entry(system == null ? "" : system, contents);
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ConfigurationException("GEMINI_API_KEY not set");
        }
        long started = System.nanoTime();
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

        String url = baseUrl + "/models/" + request.model() + ":generateContent?key=" + apiKey;
        return HttpJson.postJson(url, payload, Map.of(), timeoutSeconds).thenApply(data -> {
            String content = extractContent(data);
            TokenUsage usage = extractUsage(data);
            return buildResponse(request, content, usage, request.model(), started);
        });
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> data) {
        List<Object> candidates = (List<Object>) data.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) candidates.get(0)).get("content");
        if (content == null) {
            return "";
        }
        List<Object> parts = (List<Object>) content.get("parts");
        if (parts == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            Object t = ((Map<String, Object>) p).get("text");
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static TokenUsage extractUsage(Map<String, Object> data) {
        Map<String, Object> um = (Map<String, Object>) data.get("usageMetadata");
        if (um == null) {
            return new TokenUsage();
        }
        return new TokenUsage(asInt(um.get("promptTokenCount")), asInt(um.get("candidatesTokenCount")));
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
