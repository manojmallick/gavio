package io.gavio;

import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorChain;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The entry point. Wires interceptors around a provider adapter.
 *
 * <p>Build via {@link Gateway#builder()}. A single instance is safe to share
 * across threads — per-request state lives in an {@link InterceptorContext}
 * created fresh for every call.
 *
 * <p>Plain pre/post interceptors are separated from executor-wrapping
 * {@link ExecutorPolicy} instances; policies are composed around the provider
 * executor (first-registered = outermost), exactly like the Python gateway.
 */
public final class Gateway {

    private final ProviderAdapter adapter;
    private final String model;
    private final boolean dryRun;
    private final List<ExecutorPolicy> policies;
    private final InterceptorChain chain;

    Gateway(ProviderAdapter adapter, String model, List<Interceptor> interceptors, boolean dryRun) {
        this.adapter = adapter;
        this.model = model;
        this.dryRun = dryRun;
        this.policies = new ArrayList<>();
        List<Interceptor> regular = new ArrayList<>();
        for (Interceptor i : interceptors) {
            if (i instanceof ExecutorPolicy p) {
                policies.add(p);
            } else {
                regular.add(i);
            }
        }
        this.chain = new InterceptorChain(regular);
    }

    public static GavioBuilder builder() {
        return new GavioBuilder();
    }

    public String model() {
        return model;
    }

    public String providerName() {
        return adapter.providerName();
    }

    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        InterceptorContext ctx = new InterceptorContext(request.traceId())
                .agentId(request.agentId())
                .parentTraceId(request.parentTraceId())
                .sessionId(request.sessionId())
                .dryRun(dryRun);
        Executor executor = buildExecutor(ctx);
        return chain.execute(request, ctx, executor);
    }

    /** Convenience overload matching the Python {@code complete(messages, ...)}. */
    public CompletableFuture<GavioResponse> complete(List<Message> messages) {
        return complete(GavioRequest.builder()
                .messages(messages)
                .model(model)
                .provider(Provider.coerce(adapter.providerName()))
                .build());
    }

    public CompletableFuture<GavioResponse> complete(
            List<Message> messages, String agentId, Map<String, Object> options) {
        GavioRequest.Builder b = GavioRequest.builder()
                .messages(messages)
                .model(model)
                .provider(Provider.coerce(adapter.providerName()))
                .agentId(agentId);
        if (options != null) {
            options.forEach(b::option);
        }
        return complete(b.build());
    }

    public CompletableFuture<Boolean> healthCheck() {
        return adapter.healthCheck();
    }

    private Executor buildExecutor(InterceptorContext ctx) {
        Executor base = adapter::complete;
        Executor executor = base;
        // Wrap so the first-registered policy ends up outermost.
        for (int i = policies.size() - 1; i >= 0; i--) {
            executor = wrapPolicy(policies.get(i), executor, ctx);
        }
        return executor;
    }

    private static Executor wrapPolicy(ExecutorPolicy policy, Executor inner, InterceptorContext ctx) {
        return request -> {
            if (ctx.dryRun() && !policy.dryRunSafe()) {
                return inner.execute(request);
            }
            return policy.around(request, ctx, inner);
        };
    }
}
