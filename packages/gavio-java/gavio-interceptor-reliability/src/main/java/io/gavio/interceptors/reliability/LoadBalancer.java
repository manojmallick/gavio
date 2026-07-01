package io.gavio.interceptors.reliability;

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
import java.util.concurrent.atomic.AtomicInteger;

/** LoadBalancer (F-REL-04) — weighted round-robin across provider adapters. */
public final class LoadBalancer implements ExecutorPolicy {

    private final List<ProviderAdapter> pool;
    private final AtomicInteger index = new AtomicInteger();

    private LoadBalancer(List<ProviderAdapter> pool) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("LoadBalancer requires at least one adapter");
        }
        this.pool = pool;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "load_balancer";
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        ProviderAdapter adapter = pool.get(Math.floorMod(index.getAndIncrement(), pool.size()));
        GavioRequest rerouted = request.withProvider(Provider.coerce(adapter.providerName()));
        return adapter.complete(rerouted);
    }

    /** Builder that expands adapters by weight for round-robin. */
    public static final class Builder {
        private final List<ProviderAdapter> pool = new ArrayList<>();

        public Builder add(ProviderAdapter adapter) {
            return add(adapter, 1);
        }

        public Builder add(ProviderAdapter adapter, int weight) {
            for (int i = 0; i < Math.max(1, weight); i++) {
                pool.add(adapter);
            }
            return this;
        }

        public LoadBalancer build() {
            return new LoadBalancer(pool);
        }
    }
}
