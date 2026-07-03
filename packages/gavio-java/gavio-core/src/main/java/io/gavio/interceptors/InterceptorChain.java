package io.gavio.interceptors;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.inspector.TraceEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ordered list of interceptors wrapping an executor.
 *
 * <p>{@code before} hooks fire in order; the executor runs; {@code after} hooks
 * fire in reverse order (onion model). If any stage throws, every interceptor's
 * {@code onError} is invoked before the error propagates.
 */
public final class InterceptorChain {

    private static final Logger LOG = Logger.getLogger("gavio.chain");

    private final List<Interceptor> interceptors;

    public InterceptorChain(List<Interceptor> interceptors) {
        this.interceptors = new ArrayList<>(interceptors);
    }

    public List<Interceptor> interceptors() {
        return List.copyOf(interceptors);
    }

    public CompletableFuture<GavioResponse> execute(
            GavioRequest request, InterceptorContext ctx, Executor executor) {
        return execute(request, ctx, executor, null);
    }

    /**
     * Execute the chain, optionally emitting inspector span events (F-DX-09).
     * With a null {@code emitter} behaviour is identical to the 3-arg overload.
     */
    public CompletableFuture<GavioResponse> execute(
            GavioRequest request, InterceptorContext ctx, Executor executor, TraceEmitter emitter) {

        return CompletableFuture.supplyAsync(() -> {
            if (emitter != null) {
                emitter.traceStart(request);
            }
            // Where a failure originated, for the inspector's trace.error event.
            String origin = "chain";
            String failedInterceptor = null;
            try {
                GavioRequest req = request;
                for (Interceptor interceptor : interceptors) {
                    if (ctx.dryRun() && !interceptor.dryRunSafe()) {
                        LOG.fine("dry-run: skipping " + interceptor.name() + ".before");
                        continue;
                    }
                    if (emitter != null) {
                        emitter.interceptorBeforeStart(interceptor.name());
                    }
                    long hookStart = System.nanoTime();
                    origin = "interceptor";
                    failedInterceptor = interceptor.name();
                    GavioRequest beforeReq = req;
                    req = interceptor.before(req, ctx).join();
                    origin = "chain";
                    failedInterceptor = null;
                    if (emitter != null) {
                        emitter.interceptorBeforeEnd(
                                interceptor.name(), System.nanoTime() - hookStart, beforeReq, req, ctx);
                    }
                    ctx.markFired(interceptor.name());
                }

                if (emitter != null) {
                    emitter.providerCallStart(req, 1);
                }
                long callStart = System.nanoTime();
                GavioResponse response;
                try {
                    origin = "provider";
                    response = executor.execute(req).join();
                    origin = "chain";
                } catch (Throwable providerError) {
                    if (emitter != null) {
                        emitter.providerCallError(System.nanoTime() - callStart, unwrap(providerError));
                    }
                    throw providerError;
                }
                if (emitter != null) {
                    emitter.providerCallEnd(System.nanoTime() - callStart, response);
                }

                for (int i = interceptors.size() - 1; i >= 0; i--) {
                    Interceptor interceptor = interceptors.get(i);
                    if (ctx.dryRun() && !interceptor.dryRunSafe()) {
                        continue;
                    }
                    if (emitter != null) {
                        emitter.interceptorAfterStart(interceptor.name());
                    }
                    long hookStart = System.nanoTime();
                    origin = "interceptor";
                    failedInterceptor = interceptor.name();
                    GavioResponse beforeResp = response;
                    response = interceptor.after(response, ctx).join();
                    origin = "chain";
                    failedInterceptor = null;
                    if (emitter != null) {
                        emitter.interceptorAfterEnd(
                                interceptor.name(), System.nanoTime() - hookStart, beforeResp, response, ctx);
                    }
                }

                GavioResponse finalResponse =
                        response.withInterceptorsFired(new ArrayList<>(ctx.interceptorsFired()));
                if (emitter != null) {
                    emitter.traceEnd(finalResponse, ctx);
                }
                return finalResponse;
            } catch (Throwable error) {
                Throwable cause = unwrap(error);
                if (emitter != null) {
                    emitter.traceError(origin, failedInterceptor, cause);
                    emitter.traceEndError(ctx);
                }
                for (Interceptor interceptor : interceptors) {
                    try {
                        interceptor.onError(cause, ctx);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "onError failed in " + interceptor.name(), e);
                    }
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new CompletionException(cause);
            }
        });
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
