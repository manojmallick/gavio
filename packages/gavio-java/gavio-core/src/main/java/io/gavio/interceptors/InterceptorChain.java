package io.gavio.interceptors;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
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

        return CompletableFuture.supplyAsync(() -> {
            try {
                GavioRequest req = request;
                for (Interceptor interceptor : interceptors) {
                    if (ctx.dryRun() && !interceptor.dryRunSafe()) {
                        LOG.fine("dry-run: skipping " + interceptor.name() + ".before");
                        continue;
                    }
                    req = interceptor.before(req, ctx).join();
                    ctx.markFired(interceptor.name());
                }

                GavioResponse response = executor.execute(req).join();

                for (int i = interceptors.size() - 1; i >= 0; i--) {
                    Interceptor interceptor = interceptors.get(i);
                    if (ctx.dryRun() && !interceptor.dryRunSafe()) {
                        continue;
                    }
                    response = interceptor.after(response, ctx).join();
                }

                return response.withInterceptorsFired(new ArrayList<>(ctx.interceptorsFired()));
            } catch (Throwable error) {
                Throwable cause = unwrap(error);
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
