package io.gavio.interceptors.reliability;

import io.gavio.GavioException.ProviderUnavailableException;
import io.gavio.GavioException.RateLimitException;
import io.gavio.GavioException.ServerException;
import io.gavio.GavioException.TimeoutException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.InterceptorContext;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/** Retry the provider call on transient errors with capped exponential backoff (F-REL-01). */
public final class RetryInterceptor implements ExecutorPolicy {

    private static final Logger LOG = Logger.getLogger("gavio.retry");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<Class<? extends Throwable>> DEFAULT_RETRY_ON = List.of(
            RateLimitException.class,
            TimeoutException.class,
            ServerException.class,
            ProviderUnavailableException.class);

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final boolean jitter;
    private final List<Class<? extends Throwable>> retryOn;

    public RetryInterceptor(int maxAttempts, long baseDelayMs, long maxDelayMs, boolean jitter,
                            List<Class<? extends Throwable>> retryOn) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitter = jitter;
        this.retryOn = retryOn != null ? List.copyOf(retryOn) : DEFAULT_RETRY_ON;
    }

    @Override
    public String name() {
        return "retry";
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return CompletableFuture.completedFuture(callNext.execute(request).join());
            } catch (Throwable wrapper) {
                Throwable error = unwrap(wrapper);
                if (!shouldRetry(error)) {
                    throw asRuntime(error);
                }
                lastError = error;
                if (attempt >= maxAttempts) {
                    break;
                }
                long delayMs = delayMillis(attempt);
                LOG.warning(String.format(
                        "retry: attempt %d/%d failed (%s); backing off %dms",
                        attempt, maxAttempts, error.getClass().getSimpleName(), delayMs));
                sleep(delayMs);
            }
        }
        throw asRuntime(lastError);
    }

    private boolean shouldRetry(Throwable error) {
        for (Class<? extends Throwable> type : retryOn) {
            if (type.isInstance(error)) {
                return true;
            }
        }
        return false;
    }

    private long delayMillis(int attempt) {
        long raw = baseDelayMs * (1L << (attempt - 1));
        long capped = Math.min(raw, maxDelayMs);
        if (jitter) {
            double frac = (RANDOM.nextInt(0xFFFF + 1)) / (double) 0xFFFF;
            capped = (long) (capped * frac);
        }
        return capped;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
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

    /** Fluent builder for {@link RetryInterceptor}. */
    public static final class Builder {
        private int maxAttempts = 3;
        private long baseDelayMs = 500;
        private long maxDelayMs = 10_000;
        private boolean jitter = true;
        private List<Class<? extends Throwable>> retryOn;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder baseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
            return this;
        }

        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        public Builder jitter(boolean jitter) {
            this.jitter = jitter;
            return this;
        }

        public Builder retryOn(List<Class<? extends Throwable>> retryOn) {
            this.retryOn = retryOn;
            return this;
        }

        public RetryInterceptor build() {
            return new RetryInterceptor(maxAttempts, baseDelayMs, maxDelayMs, jitter, retryOn);
        }
    }
}
