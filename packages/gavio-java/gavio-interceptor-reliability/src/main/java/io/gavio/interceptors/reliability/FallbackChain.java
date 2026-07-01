package io.gavio.interceptors.reliability;

import io.gavio.GavioException.ProviderException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * Route to a secondary provider on failure (F-REL-02).
 *
 * <p>Try the primary executor; on a provider error, try fallback adapters. The
 * request's provider is rewritten per fallback so the audit record reflects who
 * actually answered.
 */
public final class FallbackChain implements ExecutorPolicy {

    private static final Logger LOG = Logger.getLogger("gavio.fallback");

    private final List<ProviderAdapter> fallbacks;

    public FallbackChain(List<ProviderAdapter> fallbacks) {
        if (fallbacks == null || fallbacks.isEmpty()) {
            throw new IllegalArgumentException("FallbackChain requires at least one fallback adapter");
        }
        this.fallbacks = List.copyOf(fallbacks);
    }

    @Override
    public String name() {
        return "fallback";
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        ProviderException primaryError;
        try {
            return CompletableFuture.completedFuture(callNext.execute(request).join());
        } catch (Throwable wrapper) {
            Throwable err = unwrap(wrapper);
            if (!(err instanceof ProviderException pe)) {
                throw asRuntime(err);
            }
            primaryError = pe;
        }

        LOG.warning(String.format("fallback: primary failed (%s); trying %d fallback(s)",
                primaryError.getClass().getSimpleName(), fallbacks.size()));

        Throwable lastError = primaryError;
        for (ProviderAdapter adapter : fallbacks) {
            try {
                GavioRequest rerouted = request.withProvider(Provider.coerce(adapter.providerName()));
                return CompletableFuture.completedFuture(adapter.complete(rerouted).join());
            } catch (Throwable wrapper) {
                Throwable err = unwrap(wrapper);
                if (err instanceof ProviderException pe) {
                    lastError = pe;
                    LOG.warning(String.format("fallback: %s also failed (%s)",
                            adapter.providerName(), err.getClass().getSimpleName()));
                } else {
                    throw asRuntime(err);
                }
            }
        }
        throw asRuntime(lastError);
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static RuntimeException asRuntime(Throwable t) {
        return t instanceof RuntimeException re ? re : new CompletionException(t);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link FallbackChain}. */
    public static final class Builder {
        private final List<ProviderAdapter> fallbacks = new ArrayList<>();

        public Builder fallback(ProviderAdapter adapter) {
            this.fallbacks.add(adapter);
            return this;
        }

        public Builder fallbacks(List<ProviderAdapter> adapters) {
            this.fallbacks.addAll(adapters);
            return this;
        }

        public FallbackChain build() {
            return new FallbackChain(fallbacks);
        }
    }
}
