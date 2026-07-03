package io.gavio.providers;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.types.TokenUsage;
import java.util.List;

/** Base adapter sharing a pricing provider and a response builder. */
public abstract class AbstractProviderAdapter implements ProviderAdapter {

    protected final PricingProvider pricing;

    protected AbstractProviderAdapter(PricingProvider pricing) {
        this.pricing = pricing != null ? pricing : new PricingProvider();
    }

    /**
     * Build a response from a fully buffered stream (F-REL-06). Streamed chunks
     * carry text only, so token usage is estimated from prompt + content.
     */
    @Override
    public GavioResponse buildStreamResponse(GavioRequest request, String content, long startedNanos) {
        TokenUsage usage = new TokenUsage(
                PricingProvider.estimateTokens(request.promptText()),
                PricingProvider.estimateTokens(content));
        String mv = reportedModelVersion().orElse(request.model());
        return buildResponse(request, content, usage, mv, startedNanos);
    }

    /**
     * Build an embedding response (F-SEC-10) — empty content, one vector per
     * input message, prompt-only token usage.
     */
    protected GavioResponse buildEmbedResponse(
            GavioRequest request,
            List<List<Double>> vectors,
            TokenUsage usage,
            String modelVersion,
            long startedNanos) {
        return buildResponse(request, "", usage, modelVersion, startedNanos)
                .withEmbeddings(vectors);
    }

    protected GavioResponse buildResponse(
            GavioRequest request,
            String content,
            TokenUsage usage,
            String modelVersion,
            long startedNanos) {
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        String mv = (modelVersion == null || modelVersion.isEmpty()) ? request.model() : modelVersion;
        return GavioResponse.builder()
                .traceId(request.traceId())
                .content(content)
                .model(request.model())
                .provider(providerName())
                .modelVersion(mv)
                .usage(usage)
                .costUsd(pricing.estimate(request.model(), usage))
                .latencyMs(latencyMs)
                .build();
    }
}
