package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GatewayConfigTest {

    @Test
    void builderRequiresProviderWithoutDevMode() {
        assertThrows(ConfigurationException.class, () -> Gateway.builder().build());
    }

    @Test
    void explicitAdapterAndModel() {
        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("fixed"))
                .model("mock")
                .build();
        GavioResponse resp = gw.complete(List.of(Message.of("user", "anything"))).join();
        assertEquals("fixed", resp.content());
    }

    @Test
    void devModeEchoesUserMessage() {
        // No audit module on the core test classpath, so this exercises the
        // MockProvider echo path without auto-audit.
        Gateway gw = Gateway.builder().devMode(true).build();
        GavioResponse resp = gw.complete(List.of(Message.of("user", "sync call"))).join();
        assertTrue(resp.content().contains("sync call"));
        assertEquals("mock", resp.provider());
    }

    @Test
    void runtimeContextDerivesFirstClassMetadata() {
        AtomicReference<InterceptorContext> captured = new AtomicReference<>();
        Interceptor capture = new Interceptor() {
            @Override
            public String name() {
                return "runtime_capture";
            }

            @Override
            public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
                captured.set(ctx);
                return CompletableFuture.completedFuture(request);
            }
        };
        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("ok"))
                .model("mock")
                .use(capture)
                .build();
        GavioRequest request = GavioRequest.builder()
                .message("user", "hi")
                .metadata("tenant", "acme")
                .metadata("feature", "support")
                .metadata("costDimensions", Map.of("workflow", "triage"))
                .metadata("retry", Map.of("attempt", 1))
                .metadata("tools", Map.of("allowed", List.of("search")))
                .metadata("policy", Map.of("pack", "fintech"))
                .build();

        gw.complete(request).join();

        InterceptorContext ctx = captured.get();
        assertEquals("acme", ctx.tenant());
        assertEquals("support", ctx.feature());
        assertEquals("acme", ctx.cost().get("tenant"));
        assertEquals("support", ctx.cost().get("feature"));
        assertEquals("triage", ((Map<?, ?>) ctx.cost().get("dimensions")).get("workflow"));
        assertEquals(1, ctx.retry().get("attempt"));
        assertEquals(List.of("search"), ctx.tools().get("allowed"));
        assertEquals("fintech", ctx.policy().get("pack"));
    }
}
