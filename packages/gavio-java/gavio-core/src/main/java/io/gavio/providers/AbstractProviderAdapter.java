package io.gavio.providers;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.PricingProvider;
import io.gavio.types.TokenUsage;

/** Base adapter sharing a pricing provider and a response builder. */
public abstract class AbstractProviderAdapter implements ProviderAdapter {

    protected final PricingProvider pricing;

    protected AbstractProviderAdapter(PricingProvider pricing) {
        this.pricing = pricing != null ? pricing : new PricingProvider();
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
