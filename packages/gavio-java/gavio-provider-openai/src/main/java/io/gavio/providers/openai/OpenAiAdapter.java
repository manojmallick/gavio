package io.gavio.providers.openai;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.providers.AbstractProviderAdapter;
import io.gavio.providers.ChatCompletionsWire;
import io.gavio.toolcalling.ProviderCompletion;
import java.util.LinkedHashMap;
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

    /**
     * Build the Chat Completions request body for {@code request} (F-ADP-03). Pure: no network.
     * OpenRouter and Azure OpenAI speak the same shape.
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request) {
        return ChatCompletionsWire.toProviderPayload(request, true);
    }

    /** Parse a Chat Completions response body into normalised parts (F-ADP-03). Pure: no network. */
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        return ChatCompletionsWire.fromProviderResponse(payload);
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        Map<String, String> headers = headers();
        Map<String, Object> payload = toProviderPayload(request);

        return HttpJson.postJson(baseUrl + "/chat/completions", payload, headers, timeoutSeconds)
                .thenApply(data -> buildCompletionResponse(
                        request, fromProviderResponse(data), started));
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
