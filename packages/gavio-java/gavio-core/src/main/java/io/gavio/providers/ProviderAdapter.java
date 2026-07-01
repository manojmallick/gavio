package io.gavio.providers;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Adapter to one LLM provider. */
public interface ProviderAdapter {

    String providerName();

    CompletableFuture<GavioResponse> complete(GavioRequest request);

    default Flow.Publisher<String> stream(GavioRequest request) {
        throw new UnsupportedOperationException(providerName() + " does not support streaming");
    }

    CompletableFuture<Boolean> healthCheck();

    default Optional<String> reportedModelVersion() {
        return Optional.empty();
    }
}
