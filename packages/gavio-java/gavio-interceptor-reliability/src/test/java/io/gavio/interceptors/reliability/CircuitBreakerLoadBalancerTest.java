package io.gavio.interceptors.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gavio.Gateway;
import io.gavio.GavioException.CircuitOpenException;
import io.gavio.GavioException.ServerException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Executor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.MockProvider;
import io.gavio.types.Provider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class CircuitBreakerLoadBalancerTest {

    private static GavioRequest req() {
        return GavioRequest.builder().message("user", "hi").model("mock").provider(Provider.MOCK).build();
    }

    private static InterceptorContext ctx() {
        return new InterceptorContext("t");
    }

    private static final Executor FAIL =
            r -> CompletableFuture.failedFuture(new ServerException("boom"));

    private static Executor ok() {
        return r ->
                CompletableFuture.completedFuture(
                        GavioResponse.builder().traceId("t").content("ok").model("mock").provider("mock").build());
    }

    @Test
    void opensAfterThresholdThenFastFails() {
        CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(3).recoveryTimeoutSeconds(60).build();
        for (int i = 0; i < 3; i++) {
            CompletionException ex =
                    assertThrows(CompletionException.class, () -> cb.around(req(), ctx(), FAIL).join());
            assertInstanceOf(ServerException.class, ex.getCause());
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        int[] called = {0};
        Executor spy = r -> {
            called[0]++;
            return ok().execute(r);
        };
        CompletionException ex =
                assertThrows(CompletionException.class, () -> cb.around(req(), ctx(), spy).join());
        assertInstanceOf(CircuitOpenException.class, ex.getCause());
        assertEquals(0, called[0]);
    }

    @Test
    void recoversOnSuccessAfterTimeout() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.builder().failureThreshold(1).recoveryTimeoutSeconds(0.05).build();
        assertThrows(CompletionException.class, () -> cb.around(req(), ctx(), FAIL).join());
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        Thread.sleep(70);
        GavioResponse r = cb.around(req(), ctx(), ok()).join();
        assertEquals("ok", r.content());
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void loadBalancerRoundRobin() {
        MockProvider a = new MockProvider("from-a");
        MockProvider b = new MockProvider("from-b");
        LoadBalancer lb = LoadBalancer.builder().add(a).add(b).build();
        Gateway gw = Gateway.builder().adapter(a).model("mock").use(lb).build();
        String r1 = gw.complete(req()).join().content();
        String r2 = gw.complete(req()).join().content();
        String r3 = gw.complete(req()).join().content();
        assertEquals("from-a", r1);
        assertEquals("from-b", r2);
        assertEquals("from-a", r3);
    }

    @Test
    void loadBalancerWeighted() {
        MockProvider a = new MockProvider("a");
        MockProvider b = new MockProvider("b");
        LoadBalancer lb = LoadBalancer.builder().add(a, 2).add(b, 1).build();
        Gateway gw = Gateway.builder().adapter(a).model("mock").use(lb).build();
        assertEquals("a", gw.complete(req()).join().content());
        assertEquals("a", gw.complete(req()).join().content());
        assertEquals("b", gw.complete(req()).join().content());
    }
}
