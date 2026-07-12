package io.gavio;

import io.gavio.inspector.Inspector;
import io.gavio.inspector.TraceEmitter;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorChain;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.ProviderAdapter;
import io.gavio.providers.StreamBuffer;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Inspector inspector;
    private final Map<String, Object> controlPlaneConfig;

    Gateway(ProviderAdapter adapter, String model, List<Interceptor> interceptors, boolean dryRun) {
        this(adapter, model, interceptors, dryRun, null);
    }

    Gateway(ProviderAdapter adapter, String model, List<Interceptor> interceptors,
            boolean dryRun, Inspector inspector) {
        this(adapter, model, interceptors, dryRun, inspector, null);
    }

    Gateway(ProviderAdapter adapter, String model, List<Interceptor> interceptors,
            boolean dryRun, Inspector inspector, Map<String, Object> controlPlaneConfig) {
        this.adapter = adapter;
        this.model = model;
        this.dryRun = dryRun;
        this.inspector = inspector;
        this.controlPlaneConfig = controlPlaneConfig;
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
        if (inspector != null) {
            // POST /api/replay re-fires through this gateway's complete(), so a
            // replayed request always runs the full interceptor chain (F-DX-11).
            inspector.setReplayHandler(this::replay);
        }
    }

    /** Replay handler wired into the inspector — builds a request and re-fires it. */
    private CompletableFuture<GavioResponse> replay(
            List<Message> messages, String replayModel,
            Map<String, Object> metadata, Map<String, Object> options) {
        GavioRequest.Builder builder = GavioRequest.builder()
                .messages(messages)
                .model(replayModel != null ? replayModel : model)
                .provider(Provider.coerce(adapter.providerName()));
        if (metadata != null) {
            metadata.forEach(builder::metadata);
        }
        if (options != null) {
            options.forEach(builder::option);
        }
        return complete(builder.build());
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

    /** The inspector wired at build time, or null when inspection is disabled (F-DX-09). */
    public Inspector inspector() {
        return inspector;
    }

    /** Runtime config loaded from the optional self-hosted control plane, or null. */
    public Map<String, Object> controlPlaneConfig() {
        return controlPlaneConfig;
    }

    private TraceEmitter newEmitter() {
        return inspector == null ? null : inspector.newEmitter();
    }

    public CompletableFuture<GavioResponse> complete(GavioRequest request) {
        InterceptorContext ctx = InterceptorContext.fromRequest(request, dryRun);
        TraceEmitter emitter = newEmitter();
        Executor executor = buildExecutor(ctx, adapter::complete, emitter);
        return chain.execute(request, ctx, executor, emitter);
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

    /**
     * Stream a completion, buffering the provider stream (F-REL-06).
     *
     * <p>The provider stream is buffered in full so the post-interceptor pipeline
     * (guardrails, PII restore, audit) runs on the complete response before any
     * chunk reaches the caller. The returned publisher emits the final, fully
     * processed content as a single item. Pre/post interceptors run via the
     * chain; executor policies (retry, circuit breaker, cache) are not applied to
     * the streaming path.
     */
    public Flow.Publisher<String> stream(GavioRequest request) {
        InterceptorContext ctx = InterceptorContext.fromRequest(request, dryRun);
        long started = System.nanoTime();
        Executor bufferingExecutor = req -> StreamBuffer.collect(adapter.stream(req))
                .thenApply(buffer -> adapter.buildStreamResponse(req, buffer.text(), started));
        // The streaming path shares chain.execute, so it emits the same span
        // events as complete(): trace.start, interceptor.*, provider.call.*,
        // trace.end (F-DX-09).
        TraceEmitter emitter = newEmitter();
        CompletableFuture<GavioResponse> responseFuture =
                chain.execute(request, ctx, buildExecutor(ctx, bufferingExecutor, emitter), emitter);

        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean served = false;

            @Override
            public void request(long n) {
                if (served || n <= 0) {
                    return;
                }
                served = true;
                responseFuture.whenComplete((response, error) -> {
                    if (error != null) {
                        subscriber.onError(error);
                    } else {
                        subscriber.onNext(response.content());
                        subscriber.onComplete();
                    }
                });
            }

            @Override
            public void cancel() {
                served = true;
            }
        });
    }

    /** Convenience overload matching {@code complete(messages)}. */
    public Flow.Publisher<String> stream(List<Message> messages) {
        return stream(GavioRequest.builder()
                .messages(messages)
                .model(model)
                .provider(Provider.coerce(adapter.providerName()))
                .build());
    }

    /**
     * Embed texts through the same interceptor pipeline as completions (F-SEC-10).
     *
     * <p>Every input runs the full pre-interceptor chain — PII guard included —
     * before the provider's embedding API is called, and the post chain (audit,
     * metrics) runs on the way out. The response carries one vector per input in
     * {@link GavioResponse#embeddings()} and empty {@code content}.
     */
    public CompletableFuture<GavioResponse> embed(List<String> texts) {
        return embed(texts, null, null, null, null, null);
    }

    /** Full-argument overload of {@link #embed(List)}; any argument may be null. */
    public CompletableFuture<GavioResponse> embed(
            List<String> texts, String embedModel, String agentId,
            String parentTraceId, String sessionId, Map<String, Object> metadata) {
        GavioRequest.Builder builder = GavioRequest.builder()
                .model(embedModel != null ? embedModel : model)
                .provider(Provider.coerce(adapter.providerName()))
                .agentId(agentId)
                .parentTraceId(parentTraceId)
                .sessionId(sessionId);
        for (String text : texts) {
            builder.message("user", text);
        }
        if (metadata != null) {
            metadata.forEach(builder::metadata);
        }
        builder.metadata("call_type", "embedding");
        GavioRequest request = builder.build();
        InterceptorContext ctx = InterceptorContext.fromRequest(request, dryRun);
        TraceEmitter emitter = newEmitter();
        Executor executor = buildExecutor(ctx, adapter::embed, emitter);
        return chain.execute(request, ctx, executor, emitter);
    }

    public CompletableFuture<Boolean> healthCheck() {
        return adapter.healthCheck();
    }

    private Executor buildExecutor(InterceptorContext ctx) {
        return buildExecutor(ctx, adapter::complete, null);
    }

    /** Compose the executor policies around a provider call — {@code adapter::complete}
     * for completions, {@code adapter::embed} for embeddings (F-SEC-10). */
    private Executor buildExecutor(InterceptorContext ctx, Executor call) {
        return buildExecutor(ctx, call, null);
    }

    /** Compose executor policies around a provider call instrumented per attempt. */
    private Executor buildExecutor(InterceptorContext ctx, Executor call, TraceEmitter emitter) {
        Executor executor = call;
        if (emitter != null) {
            executor = instrumentProviderCall(executor, emitter);
        }
        // Wrap so the first-registered policy ends up outermost.
        for (int i = policies.size() - 1; i >= 0; i--) {
            executor = wrapPolicy(policies.get(i), executor, ctx);
        }
        return executor;
    }

    private static Executor instrumentProviderCall(Executor inner, TraceEmitter emitter) {
        AtomicInteger attempts = new AtomicInteger();
        return request -> {
            int attempt = attempts.incrementAndGet();
            emitter.providerCallStart(request, attempt);
            long started = System.nanoTime();
            try {
                return inner.execute(request).whenComplete((response, error) -> {
                    if (error != null) {
                        emitter.providerCallError(System.nanoTime() - started, unwrap(error), attempt);
                    } else {
                        emitter.providerCallEnd(System.nanoTime() - started, response, attempt);
                    }
                });
            } catch (Throwable error) {
                emitter.providerCallError(System.nanoTime() - started, unwrap(error), attempt);
                throw error;
            }
        };
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return error;
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
