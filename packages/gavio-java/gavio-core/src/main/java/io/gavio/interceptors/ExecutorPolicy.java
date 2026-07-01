package io.gavio.interceptors;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Interceptors that wrap the provider call itself.
 *
 * <p>Retry, timeout, and fallback can't be expressed as plain before/after
 * hooks — they need to re-invoke (or race) the executor. They implement
 * {@code ExecutorPolicy} so the Gateway composes them <em>around</em> the
 * provider call, first-registered outermost.
 */
public interface ExecutorPolicy extends Interceptor {

    /** Invoke {@code callNext} (the wrapped executor) with this policy applied. */
    CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext);
}
