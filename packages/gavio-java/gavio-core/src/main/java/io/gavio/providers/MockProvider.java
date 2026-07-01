package io.gavio.providers;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic, offline provider for dev mode and tests.
 *
 * <p>If {@code response} is null, it echoes the last user message so the
 * pipeline (including PII restore) is observable end to end.
 */
public final class MockProvider extends AbstractProviderAdapter {

    private final String response;
    private final String modelVersion;

    public MockProvider() {
        this(null, "mock-1", null);
    }

    public MockProvider(String response) {
        this(response, "mock-1", null);
    }

    public MockProvider(String response, String modelVersion, PricingProvider pricing) {
        super(pricing);
        this.response = response;
        this.modelVersion = modelVersion;
    }

    /** Factory mirroring the plan: {@code MockProvider.withResponse(...)}. */
    public static MockProvider withResponse(String response) {
        return new MockProvider(response);
    }

    @Override
    public String providerName() {
        return "mock";
    }

    private String contentFor(GavioRequest request) {
        if (response != null) {
            return response;
        }
        List<Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return "[mock reply] " + messages.get(i).content();
            }
        }
        return "[mock reply] ";
    }

    @Override
    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        long started = System.nanoTime();
        String content = contentFor(request);
        TokenUsage usage = new TokenUsage(
                PricingProvider.estimateTokens(request.promptText()),
                PricingProvider.estimateTokens(content));
        return CompletableFuture.completedFuture(
                buildResponse(request, content, usage, modelVersion, started));
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public java.util.Optional<String> reportedModelVersion() {
        return java.util.Optional.ofNullable(modelVersion);
    }
}
