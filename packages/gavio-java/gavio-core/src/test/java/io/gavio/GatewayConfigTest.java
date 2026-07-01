package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import java.util.List;
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
}
