package io.gavio.providers.ollama;

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

/** Local models via the Ollama chat API (/api/chat). No API key; cost is $0. */
public final class OllamaAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

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

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
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
        payload.put("stream", false);
        payload.put("options", Map.of("temperature", request.temperature()));

        return HttpJson.postJson(baseUrl + "/api/chat", payload, Map.of(), timeoutSeconds)
                .thenApply(data -> {
                    String content = extractContent(data);
                    TokenUsage usage = new TokenUsage(
                            asInt(data.get("prompt_eval_count")), asInt(data.get("eval_count")));
                    String modelVersion = data.get("model") != null
                            ? String.valueOf(data.get("model")) : request.model();
                    return buildResponse(request, content, usage, modelVersion, started);
                });
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> data) {
        Map<String, Object> message = (Map<String, Object>) data.get("message");
        Object content = message != null ? message.get("content") : null;
        return content != null ? content.toString() : "";
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
