package io.gavio.interceptors.reliability;

import io.gavio.GavioException.CircuitOpenException;
import io.gavio.GavioException.ProviderException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.InterceptorContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** CircuitBreaker (F-REL-03) — open/half-open/closed state machine. */
public final class CircuitBreaker implements ExecutorPolicy {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long recoveryMs;
    private final int halfOpenMaxCalls;

    private State state = State.CLOSED;
    private int failures;
    private long openedAt;
    private int halfOpenCalls;

    private CircuitBreaker(Builder b) {
        this.failureThreshold = b.failureThreshold;
        this.recoveryMs = (long) (b.recoveryTimeoutSeconds * 1000);
        this.halfOpenMaxCalls = b.halfOpenMaxCalls;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "circuit_breaker";
    }

    public synchronized State state() {
        return state;
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        try {
            admit();
        } catch (CircuitOpenException e) {
            return CompletableFuture.failedFuture(e);
        }
        return callNext.execute(request).handle((resp, err) -> {
            if (err != null) {
                Throwable cause = err instanceof CompletionException && err.getCause() != null
                        ? err.getCause()
                        : err;
                if (cause instanceof ProviderException) {
                    onFailure();
                }
                throw new CompletionException(cause);
            }
            onSuccess();
            return resp;
        });
    }

    private synchronized void admit() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= recoveryMs) {
                state = State.HALF_OPEN;
                halfOpenCalls = 0;
            } else {
                throw new CircuitOpenException("circuit is open");
            }
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenCalls >= halfOpenMaxCalls) {
                throw new CircuitOpenException("circuit half-open probe limit reached");
            }
            halfOpenCalls += 1;
        }
    }

    private synchronized void onSuccess() {
        state = State.CLOSED;
        failures = 0;
    }

    private synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        failures += 1;
        if (failures >= failureThreshold) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openedAt = System.currentTimeMillis();
    }

    /** Builder for {@link CircuitBreaker}. */
    public static final class Builder {
        private int failureThreshold = 5;
        private double recoveryTimeoutSeconds = 30;
        private int halfOpenMaxCalls = 2;

        public Builder failureThreshold(int v) {
            this.failureThreshold = v;
            return this;
        }

        public Builder recoveryTimeoutSeconds(double v) {
            this.recoveryTimeoutSeconds = v;
            return this;
        }

        public Builder halfOpenMaxCalls(int v) {
            this.halfOpenMaxCalls = v;
            return this;
        }

        public CircuitBreaker build() {
            return new CircuitBreaker(this);
        }
    }
}
