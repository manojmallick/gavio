package io.gavio.interceptors.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ProviderUnavailableException;
import io.gavio.GavioException.ServerException;
import io.gavio.GavioException.TimeoutException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.MockProvider;
import io.gavio.types.Provider;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReliabilityTest {

    private static GavioRequest req() {
        return GavioRequest.builder().message("user", "hi").model("mock").provider(Provider.MOCK).build();
    }

    private static GavioResponse resp(String content) {
        return GavioResponse.builder().traceId("t").content(content).model("mock").provider("mock").build();
    }

    @Test
    void retrySucceedsAfterTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        Executor flaky = request -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ServerException("boom");
            }
            return CompletableFuture.completedFuture(resp("recovered"));
        };
        RetryInterceptor retry = RetryInterceptor.builder()
                .maxAttempts(3).baseDelayMs(1).jitter(false).build();
        GavioResponse out = retry.around(req(), new InterceptorContext("t"), flaky).join();
        assertEquals("recovered", out.content());
        assertEquals(3, attempts.get());
    }

    @Test
    void retryExhaustsAndRaisesLastError() {
        Executor alwaysFail = request -> {
            throw new ServerException("nope");
        };
        RetryInterceptor retry = RetryInterceptor.builder()
                .maxAttempts(2).baseDelayMs(1).jitter(false).build();
        assertThrows(ServerException.class,
                () -> retry.around(req(), new InterceptorContext("t"), alwaysFail));
    }

    @Test
    void retryDoesNotRetryNonTransient() {
        AtomicInteger attempts = new AtomicInteger();
        Executor failsHard = request -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent");
        };
        RetryInterceptor retry = RetryInterceptor.builder()
                .maxAttempts(5).baseDelayMs(1).jitter(false).build();
        assertThrows(IllegalStateException.class,
                () -> retry.around(req(), new InterceptorContext("t"), failsHard));
        assertEquals(1, attempts.get());
    }

    @Test
    void timeoutPolicyRaises() {
        Executor slow = request -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return resp("late");
        });
        TimeoutPolicy policy = TimeoutPolicy.builder().timeoutSeconds(0.01).build();
        assertThrows(TimeoutException.class,
                () -> policy.around(req(), new InterceptorContext("t"), slow));
    }

    @Test
    void fallbackUsesSecondaryAdapter() {
        FallbackChain fallback = FallbackChain.builder()
                .fallback(MockProvider.withResponse("from-fallback"))
                .build();
        Executor primaryFails = request -> {
            throw new ProviderUnavailableException("down");
        };
        GavioResponse out = fallback.around(req(), new InterceptorContext("t"), primaryFails).join();
        assertEquals("from-fallback", out.content());
        assertEquals("mock", out.provider());
    }

    @Test
    void fallbackChainRequiresAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new FallbackChain(List.of()));
    }

    @Test
    void retryFiredRecordedInContext() {
        InterceptorContext ctx = new InterceptorContext("t");
        Executor ok = request -> CompletableFuture.completedFuture(resp("ok"));
        RetryInterceptor.builder().maxAttempts(1).build().around(req(), ctx, ok).join();
        assertTrue(ctx.interceptorsFired().contains("retry"));
    }
}
