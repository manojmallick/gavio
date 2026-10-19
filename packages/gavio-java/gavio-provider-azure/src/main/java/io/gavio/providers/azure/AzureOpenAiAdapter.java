package io.gavio.providers.azure;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.providers.AbstractProviderAdapter;
import io.gavio.providers.ChatCompletionsWire;
import io.gavio.toolcalling.ProviderCompletion;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Azure OpenAI deployment-based chat completions adapter. */
public final class AzureOpenAiAdapter extends AbstractProviderAdapter {

    private static final String DEFAULT_API_VERSION = "2024-06-01";

    private final String apiKey;
    private final String endpoint;
    private final String deployment;
    private final String apiVersion;
    private final double timeoutSeconds;

    public AzureOpenAiAdapter(
            String apiKey, String endpoint, String deployment, String apiVersion,
            double timeoutSeconds, PricingProvider pricing) {
        super(pricing);
        this.apiKey = apiKey != null ? apiKey : System.getenv("AZURE_OPENAI_API_KEY");
        String ep = endpoint != null ? endpoint : System.getenv("AZURE_OPENAI_ENDPOINT");
        this.endpoint = (ep != null ? ep : "").replaceAll("/+$", "");
        this.deployment = deployment != null ? deployment : System.getenv("AZURE_OPENAI_DEPLOYMENT");
        this.apiVersion = apiVersion != null ? apiVersion : DEFAULT_API_VERSION;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerName() {
        return "azure_openai";
    }

    public String url(GavioRequest request) {
        String dep = deployment != null ? deployment : request.model();
        return endpoint + "/openai/deployments/" + dep + "/chat/completions?api-version=" + apiVersion;
    }

    /**
     * Build the Chat Completions request body for {@code request} (F-ADP-03): the OpenAI shape
     * without {@code model}, since Azure routes by deployment. Pure: no network.
     */
    public static Map<String, Object> toProviderPayload(GavioRequest request) {
        return ChatCompletionsWire.toProviderPayload(request, false);
    }

    /** Parse a Chat Completions response body into normalised parts (F-ADP-03). Pure: no network. */
    public static ProviderCompletion fromProviderResponse(Map<String, Object> payload) {
        return ChatCompletionsWire.fromProviderResponse(payload);
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        if (apiKey == null || apiKey.isEmpty() || endpoint.isEmpty()) {
            throw new ConfigurationException("AZURE_OPENAI_API_KEY and AZURE_OPENAI_ENDPOINT must be set");
        }
        long started = System.nanoTime();
        Map<String, Object> payload = toProviderPayload(request);

        return HttpJson.postJson(url(request), payload, Map.of("api-key", apiKey), timeoutSeconds)
                .thenApply(data -> buildCompletionResponse(
                        request, fromProviderResponse(data), started));
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(apiKey != null && !apiKey.isEmpty() && !endpoint.isEmpty());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link AzureOpenAiAdapter}. */
    public static final class Builder {
        private String apiKey;
        private String endpoint;
        private String deployment;
        private String apiVersion;
        private double timeoutSeconds = 30.0;
        private PricingProvider pricing;

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        public Builder endpoint(String v) {
            this.endpoint = v;
            return this;
        }

        public Builder deployment(String v) {
            this.deployment = v;
            return this;
        }

        public Builder apiVersion(String v) {
            this.apiVersion = v;
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

        public AzureOpenAiAdapter build() {
            return new AzureOpenAiAdapter(apiKey, endpoint, deployment, apiVersion, timeoutSeconds, pricing);
        }
    }
}
