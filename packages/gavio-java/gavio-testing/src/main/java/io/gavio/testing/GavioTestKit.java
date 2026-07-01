package io.gavio.testing;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.ExecutorPolicy;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorChain;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Run interceptor chains in isolation for unit tests.
 *
 * <p>Drives a chain against a {@link MockProvider} and lets you assert on PII
 * detection, the redacted request, and the resulting audit record. Direct port
 * of the Python {@code GavioTestKit}.
 */
public final class GavioTestKit {

    /** Records the request as it reaches the provider (post-redaction). */
    private static final class CaptureInterceptor implements Interceptor {
        private GavioRequest captured;

        @Override
        public String name() {
            return "_capture";
        }

        @Override
        public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
            this.captured = request;
            return CompletableFuture.completedFuture(request);
        }
    }

    private final List<Interceptor> interceptors;
    private final ProviderAdapter provider;
    private final String model;

    public GavioTestKit(List<Interceptor> interceptors, ProviderAdapter provider, String model) {
        this.interceptors = new ArrayList<>(interceptors != null ? interceptors : List.of());
        this.provider = provider != null ? provider : new MockProvider();
        this.model = model != null ? model : "mock";
    }

    public CompletableFuture<GavioTestResult> run(GavioRequest request) {
        CaptureInterceptor capture = new CaptureInterceptor();

        List<Interceptor> all = new ArrayList<>(interceptors);
        all.add(capture);

        List<ExecutorPolicy> policies = new ArrayList<>();
        List<Interceptor> regular = new ArrayList<>();
        for (Interceptor i : all) {
            if (i instanceof ExecutorPolicy p) {
                policies.add(p);
            } else {
                regular.add(i);
            }
        }

        InterceptorContext ctx = new InterceptorContext(request.traceId());
        InterceptorChain chain = new InterceptorChain(regular);

        Executor executor = provider::complete;
        for (int i = policies.size() - 1; i >= 0; i--) {
            ExecutorPolicy policy = policies.get(i);
            Executor inner = executor;
            executor = req -> policy.around(req, ctx, inner);
        }

        return chain.execute(request, ctx, executor)
                .thenApply(resp -> new GavioTestResult(resp, ctx, capture.captured));
    }

    /** Convenience: run with a freshly-built request from messages. */
    public CompletableFuture<GavioTestResult> run(List<Message> messages) {
        return run(GavioRequest.builder()
                .messages(messages)
                .model(model)
                .provider(Provider.coerce(provider.providerName()))
                .build());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link GavioTestKit}. */
    public static final class Builder {
        private final List<Interceptor> interceptors = new ArrayList<>();
        private ProviderAdapter provider;
        private String model = "mock";

        public Builder interceptor(Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public Builder interceptors(List<Interceptor> interceptors) {
            this.interceptors.addAll(interceptors);
            return this;
        }

        public Builder provider(ProviderAdapter provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public GavioTestKit build() {
            return new GavioTestKit(interceptors, provider, model);
        }
    }
}
