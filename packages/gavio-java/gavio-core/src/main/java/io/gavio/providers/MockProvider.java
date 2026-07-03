package io.gavio.providers;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

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

    /** Deterministic 8-dim vector per message content (F-SEC-10). */
    @Override
    public CompletableFuture<GavioResponse> embed(GavioRequest request) {
        long started = System.nanoTime();
        List<List<Double>> vectors = new ArrayList<>();
        for (Message message : request.messages()) {
            vectors.add(mockVector(message.content()));
        }
        TokenUsage usage = new TokenUsage(PricingProvider.estimateTokens(request.promptText()), 0);
        return CompletableFuture.completedFuture(
                buildEmbedResponse(request, vectors, usage, modelVersion, started));
    }

    /** Stable pseudo-embedding: sha256 bytes scaled to [0, 1) — same numbers as the Python SDK. */
    private static List<Double> mockVector(String text) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        List<Double> vector = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            vector.add((digest[i] & 0xFF) / 255.0);
        }
        return List.copyOf(vector);
    }

    @Override
    public Flow.Publisher<String> stream(GavioRequest request) {
        String[] tokens = contentFor(request).split(" ");
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int idx = 0;
            private boolean cancelled = false;

            @Override
            public void request(long n) {
                for (long k = 0; k < n && idx < tokens.length && !cancelled; k++) {
                    subscriber.onNext(tokens[idx++] + " ");
                }
                if (idx >= tokens.length && !cancelled) {
                    cancelled = true;
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
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
