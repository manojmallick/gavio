package io.gavio.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.inspector.CaptureMode;
import io.gavio.inspector.InspectorConfig;
import io.gavio.inspector.InspectorEvent;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeExporterTest {

    @Test
    @SuppressWarnings("unchecked")
    void metadataOnlyEventStripsContentVector() throws Exception {
        Path vectorPath = repoRoot().resolve("test-vectors/runtime-events/export-redaction.json");
        Map<String, Object> vector = Json.parseObject(Files.readString(vectorPath));
        Map<String, Object> event = (Map<String, Object>) vector.get("event");
        InspectorEvent inspectorEvent = new InspectorEvent(
                (String) event.get("eventId"),
                (String) event.get("traceId"),
                (String) event.get("type"),
                ((Number) event.get("tNs")).longValue(),
                ((Number) event.get("seq")).intValue(),
                (Map<String, Object>) event.get("data"));

        Map<String, Object> redacted = RuntimeEventPrivacy.metadataOnly(inspectorEvent);

        assertEquals(vector.get("expectedData"), redacted.get("data"));
        String serialized = Json.write(redacted);
        for (Object key : (List<?>) vector.get("contentKeys")) {
            assertFalse(serialized.contains("\"" + key + "\""));
        }
    }

    @Test
    void exporterAutoEnablesMetadataEventsWithoutServer() {
        List<String> lines = new ArrayList<>();
        Gateway gateway = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .exporter(new JsonlRuntimeExporter(lines::add))
                .build();

        assertNotNull(gateway.inspector());
        assertEquals(CaptureMode.METADATA, gateway.inspector().mode());
        assertNull(gateway.inspector().server());

        gateway.complete(List.of(Message.of("user", "hello export"))).join();

        List<String> types = lines.stream()
                .map(Json::parseObject)
                .map(event -> String.valueOf(event.get("type")))
                .toList();
        assertEquals(List.of("trace.start", "provider.call.start", "provider.call.end", "trace.end"), types);
        for (String line : lines) {
            Map<String, Object> event = Json.parseObject(line);
            Map<?, ?> data = (Map<?, ?>) event.get("data");
            assertFalse(data.containsKey("messages"));
            assertFalse(data.containsKey("content"));
            assertFalse(data.containsKey("diff"));
        }
    }

    @Test
    void exporterStripsContentEvenWhenInspectorIsFull() {
        List<String> lines = new ArrayList<>();
        Gateway gateway = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.FULL)
                        .startServer(false)
                        .unsafeContentCaptureAck(true)
                        .build())
                .exporter(new JsonlRuntimeExporter(lines::add))
                .build();

        List<InspectorEvent> inspectorEvents = new ArrayList<>();
        gateway.inspector().bus().subscribe(inspectorEvents::add);
        gateway.complete(GavioRequest.builder()
                .provider(Provider.MOCK)
                .model("mock")
                .message("user", "mail jan@example.com")
                .build()).join();

        assertTrue(inspectorEvents.stream().anyMatch(event -> event.data().containsKey("messages")));
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            Map<String, Object> event = Json.parseObject(line);
            String data = Json.write((Map<?, ?>) event.get("data"));
            assertFalse(data.contains("\"messages\""));
            assertFalse(data.contains("\"content\""));
            assertFalse(data.contains("\"diff\""));
        }
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("test-vectors"))) {
                return p;
            }
        }
        throw new AssertionError("repository root not found from " + cwd);
    }
}
