package io.gavio.providers.openrouter;

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

/** Talks to OpenRouter's OpenAI-compatible Chat Completions endpoint. */
public final class OpenRouterAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    private final String apiKey;
    private final String baseUrl;
    private final double timeoutSeconds;
    private final String httpReferer;
    private final String appTitle;

    public OpenRouterAdapter(
            String apiKey,
            String baseUrl,
            double timeoutSeconds,
            String httpReferer,
            String appTitle,
            PricingProvider pricing) {
        super(pricing);
        this.apiKey = apiKey != null ? apiKey : System.getenv("OPENROUTER_API_KEY");
        String url = firstNonBlank(baseUrl, System.getenv("OPENROUTER_BASE_URL"), DEFAULT_BASE_URL);
        this.baseUrl = stripTrailingSlash(url);
        this.timeoutSeconds = timeoutSeconds;
        this.httpReferer = firstNonBlank(
                httpReferer,
                System.getenv("OPENROUTER_HTTP_REFERER"),
                System.getenv("OPENROUTER_REFERER"));
        this.appTitle = firstNonBlank(
                appTitle,
                System.getenv("OPENROUTER_APP_TITLE"),
                System.getenv("OPENROUTER_TITLE"));
    }

    @Override
    public String providerName() {
        return "openrouter";
    }

    public String url() {
        return baseUrl + "/chat/completions";
    }

    public Map<String, String> headers() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ConfigurationException(
                    "OPENROUTER_API_KEY not set (pass apiKey or set the env var)");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        if (httpReferer != null && !httpReferer.isEmpty()) {
            h.put("HTTP-Referer", httpReferer);
        }
        if (appTitle != null && !appTitle.isEmpty()) {
            h.put("X-OpenRouter-Title", appTitle);
        }
        return h;
    }

    /**
     * Build the Chat Completions request body for {@code request} (F-ADP-03). OpenRouter speaks the
     * OpenAI shape. Pure: no network.
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

        return HttpJson.postJson(url(), payload, headers, timeoutSeconds)
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link OpenRouterAdapter}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private double timeoutSeconds = 30.0;
        private String httpReferer;
        private String appTitle;
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

        public Builder httpReferer(String httpReferer) {
            this.httpReferer = httpReferer;
            return this;
        }

        public Builder appTitle(String appTitle) {
            this.appTitle = appTitle;
            return this;
        }

        public Builder pricing(PricingProvider pricing) {
            this.pricing = pricing;
            return this;
        }

        public OpenRouterAdapter build() {
            return new OpenRouterAdapter(
                    apiKey, baseUrl, timeoutSeconds, httpReferer, appTitle, pricing);
        }
    }
}
