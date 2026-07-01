package io.gavio.interceptors.guardrails;

import io.gavio.GavioException.GuardrailViolationException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.InterceptorContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GuardrailsInterceptor (F-QUA-01/02) — validate responses, act on failure.
 *
 * <p>An ExecutorPolicy so it can re-run the provider on failure. Records the
 * outcome in ctx.guardrailOutcome for the audit trail.
 */
public final class GuardrailsInterceptor implements ExecutorPolicy {

    public enum OnFailure {
        ERROR,
        RETRY,
        WARN
    }

    private final List<OutputValidator> validators;
    private final OnFailure onFailure;
    private final int maxRetries;

    private GuardrailsInterceptor(Builder b) {
        this.validators = List.copyOf(b.validators);
        this.onFailure = b.onFailure;
        this.maxRetries = b.maxRetries;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "guardrails";
    }

    @Override
    public CompletableFuture<GavioResponse> around(
            GavioRequest request, InterceptorContext ctx, Executor callNext) {
        ctx.markFired(name());
        int attempts = onFailure == OnFailure.RETRY ? maxRetries + 1 : 1;
        return attempt(request, ctx, callNext, attempts);
    }

    private CompletableFuture<GavioResponse> attempt(
            GavioRequest request, InterceptorContext ctx, Executor callNext, int remaining) {
        return callNext.execute(request).thenCompose(response -> {
            List<String> failures = new ArrayList<>();
            for (OutputValidator v : validators) {
                ValidationResult r = v.validate(response.content());
                if (!r.ok()) {
                    failures.add(v.name() + ": " + r.reason());
                }
            }
            if (failures.isEmpty()) {
                ctx.guardrailOutcome("PASS");
                return CompletableFuture.completedFuture(response);
            }
            if (remaining > 1) {
                return attempt(request, ctx, callNext, remaining - 1);
            }
            ctx.guardrailOutcome("FAIL");
            if (onFailure == OnFailure.WARN) {
                return CompletableFuture.completedFuture(response);
            }
            return CompletableFuture.failedFuture(
                    new GuardrailViolationException(String.join("; ", failures)));
        });
    }

    /** Builder for {@link GuardrailsInterceptor}. */
    public static final class Builder {
        private final List<OutputValidator> validators = new ArrayList<>();
        private OnFailure onFailure = OnFailure.ERROR;
        private int maxRetries = 2;

        public Builder validator(OutputValidator v) {
            this.validators.add(v);
            return this;
        }

        public Builder onFailure(OnFailure v) {
            this.onFailure = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public GuardrailsInterceptor build() {
            return new GuardrailsInterceptor(this);
        }
    }
}
