package io.gavio.interceptors.reliability;

import io.gavio.GavioException.TimeoutException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.InterceptorContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Abort the provider call if it exceeds {@code timeoutSeconds} (F-REL-07). */
public final class TimeoutPolicy implements ExecutorPolicy {

    private final double timeoutSeconds;

    public TimeoutPolicy(double timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "timeout";
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        long millis = (long) (timeoutSeconds * 1000);
        try {
            GavioResponse resp = callNext.execute(request).get(millis, TimeUnit.MILLISECONDS);
            return CompletableFuture.completedFuture(resp);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new TimeoutException("Request exceeded " + timeoutSeconds + "s timeout", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new CompletionException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link TimeoutPolicy}. */
    public static final class Builder {
        private double timeoutSeconds = 30.0;

        public Builder timeoutSeconds(double timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public TimeoutPolicy build() {
            return new TimeoutPolicy(timeoutSeconds);
        }
    }
}
