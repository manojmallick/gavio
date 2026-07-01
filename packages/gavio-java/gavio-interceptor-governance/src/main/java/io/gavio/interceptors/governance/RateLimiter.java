package io.gavio.interceptors.governance;

import io.gavio.GavioException.RateLimitExceededException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** RateLimiter (F-GOV-03) — fixed-window requests/tokens per minute per scope. */
public final class RateLimiter implements Interceptor {

    private static final class Window {
        long minute;
        int requests;
        long tokens;
    }

    private final Integer maxRequestsPerMinute;
    private final Long maxTokensPerMinute;
    private final String scope;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private RateLimiter(Builder b) {
        this.maxRequestsPerMinute = b.maxRequestsPerMinute;
        this.maxTokensPerMinute = b.maxTokensPerMinute;
        this.scope = b.scope;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "rate_limiter";
    }

    private Window windowFor(InterceptorContext ctx) {
        long minute = System.currentTimeMillis() / 60000;
        String key = Scopes.scopeKey(scope, ctx);
        return windows.compute(key, (k, w) -> {
            if (w == null || w.minute != minute) {
                Window fresh = new Window();
                fresh.minute = minute;
                return fresh;
            }
            return w;
        });
    }

    @Override
    public synchronized CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        Window w = windowFor(ctx);
        if (maxRequestsPerMinute != null && w.requests >= maxRequestsPerMinute) {
            return CompletableFuture.failedFuture(
                    new RateLimitExceededException("rate limit: " + maxRequestsPerMinute + " requests/min exceeded"));
        }
        if (maxTokensPerMinute != null && w.tokens >= maxTokensPerMinute) {
            return CompletableFuture.failedFuture(
                    new RateLimitExceededException("rate limit: " + maxTokensPerMinute + " tokens/min exceeded"));
        }
        w.requests += 1;
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public synchronized CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        if (maxTokensPerMinute != null) {
            windowFor(ctx).tokens += response.usage().totalTokens();
        }
        return CompletableFuture.completedFuture(response);
    }

    /** Builder for {@link RateLimiter}. */
    public static final class Builder {
        private Integer maxRequestsPerMinute;
        private Long maxTokensPerMinute;
        private String scope = "global";

        public Builder maxRequestsPerMinute(int v) {
            this.maxRequestsPerMinute = v;
            return this;
        }

        public Builder maxTokensPerMinute(long v) {
            this.maxTokensPerMinute = v;
            return this;
        }

        public Builder scope(String v) {
            this.scope = v;
            return this;
        }

        public RateLimiter build() {
            return new RateLimiter(this);
        }
    }
}
