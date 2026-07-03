package io.gavio.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioResponse;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectorConfigTest {

    @Test
    void fullModeOutsideDevModeRequiresAck() {
        assertThrows(ConfigurationException.class, () -> Gateway.builder()
                .adapter(new MockProvider())
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.FULL)
                        .startServer(false)
                        .build())
                .build());
    }

    @Test
    void fullModeOutsideDevModeAllowedWithAck() {
        Gateway gw = Gateway.builder()
                .adapter(new MockProvider())
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.FULL)
                        .startServer(false)
                        .unsafeContentCaptureAck(true)
                        .build())
                .build();
        assertNotNull(gw.inspector());
        assertEquals(CaptureMode.FULL, gw.inspector().mode());
    }

    @Test
    void nonLoopbackBindWithoutTokenThrows() {
        assertThrows(ConfigurationException.class, () -> Gateway.builder()
                .devMode(true)
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .bind("0.0.0.0")
                        .port(0)
                        .build())
                .build());
    }

    @Test
    void modeDefaultsToFullInDevModeAndMetadataOtherwise() {
        InspectorConfig cfg = InspectorConfig.builder().enabled(true).build();
        assertEquals(CaptureMode.FULL, cfg.effectiveMode(true));
        assertEquals(CaptureMode.METADATA, cfg.effectiveMode(false));
    }

    @Test
    void metadataModeEventsCarryNoContent() {
        Gateway gw = Gateway.builder()
                .devMode(true)
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.METADATA)
                        .startServer(false)
                        .build())
                .build();
        List<InspectorEvent> events = Collections.synchronizedList(new ArrayList<>());
        gw.inspector().bus().subscribe(events::add);

        gw.complete(List.of(Message.of("user", "mail jan@example.com please"))).join();

        assertFalse(events.isEmpty());
        for (InspectorEvent event : events) {
            assertFalse(event.data().containsKey("messages"), event.type() + " leaked messages");
            assertFalse(event.data().containsKey("content"), event.type() + " leaked content");
            assertFalse(event.data().containsKey("diff"), event.type() + " leaked diff");
        }
    }

    @Test
    void disabledInspectorLeavesGatewayUnchanged() {
        Gateway gw = Gateway.builder().devMode(true).build();
        assertNull(gw.inspector());
        GavioResponse resp = gw.complete(List.of(Message.of("user", "no inspector here"))).join();
        assertTrue(resp.content().contains("no inspector here"));
    }

    @Test
    void explicitlyDisabledConfigLeavesGatewayUnchanged() {
        Gateway gw = Gateway.builder().devMode(true).inspect(false).build();
        assertNull(gw.inspector());
    }

    @Test
    void ringBufferEvictsOldestBeyondMaxTraces() {
        Gateway gw = Gateway.builder()
                .devMode(true)
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .maxTraces(2)
                        .startServer(false)
                        .build())
                .build();

        List<String> traceIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            traceIds.add(gw.complete(List.of(Message.of("user", "request " + i))).join().traceId());
        }

        RingBuffer buffer = gw.inspector().buffer();
        assertEquals(2, buffer.size());
        assertFalse(buffer.contains(traceIds.get(0)), "oldest trace should be evicted");
        assertTrue(buffer.contains(traceIds.get(1)));
        assertTrue(buffer.contains(traceIds.get(2)));
        assertEquals(2, buffer.summaries(0).size());
        assertEquals(1, buffer.summaries(1).size());
        assertEquals(traceIds.get(2), buffer.summaries(1).get(0).get("traceId"));
    }
}
