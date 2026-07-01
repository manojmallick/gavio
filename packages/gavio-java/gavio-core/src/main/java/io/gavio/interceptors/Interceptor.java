package io.gavio.interceptors;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import java.util.concurrent.CompletableFuture;

/**
 * A pre/post hook around the provider call — the unit of composition in Gavio.
 *
 * <p>{@code before} runs in registration order on the request; {@code after}
 * runs in reverse order on the response (onion model). Either may be a no-op.
 * Throwing (completing exceptionally) from {@code before} aborts the call.
 */
public interface Interceptor {

    /** Unique identifier, recorded in audit logs. Convention: kebab/snake-case. */
    String name();

    /** Pre-interceptor. Return a (possibly modified) request or abort by failing. */
    default CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        return CompletableFuture.completedFuture(request);
    }

    /** Post-interceptor. Return a (possibly modified) response. */
    default CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        return CompletableFuture.completedFuture(response);
    }

    /** Called if the provider call or a downstream interceptor throws. */
    default void onError(Throwable error, InterceptorContext ctx) {
    }

    /** If true, this interceptor still runs in dry-run mode (logs only). */
    default boolean dryRunSafe() {
        return true;
    }
}
