package io.gavio.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.gavio.inspector.InspectorEvent;
import io.gavio.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtelSpanExporterTest {

    @Test
    @SuppressWarnings("unchecked")
    void mapsRuntimeEventsToSharedOtelSpanVectors() throws Exception {
        Map<String, Object> vector = loadVector();
        List<String> contentKeys = (List<String>) vector.get("contentKeys");
        for (Map<String, Object> c : (List<Map<String, Object>>) vector.get("cases")) {
            List<InspectorEvent> events = events(c);
            List<Map<String, Object>> spans =
                    OtelSpanExporter.spansFromEvents(events, String.valueOf(c.get("serviceName")));

            assertCase(c, spans);
            String serialized = Json.write(spans);
            for (String key : contentKeys) {
                assertFalse(serialized.contains("\"" + key + "\""));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exporterWritesOtelSpanJsonl() throws Exception {
        Map<String, Object> vector = loadVector();
        Map<String, Object> c = ((List<Map<String, Object>>) vector.get("cases")).get(0);
        List<String> lines = new ArrayList<>();
        OtelSpanExporter exporter = new OtelSpanExporter(lines::add, String.valueOf(c.get("serviceName")));

        for (InspectorEvent event : events(c)) {
            exporter.exportEvent(event);
        }

        List<Map<String, Object>> spans = lines.stream().map(Json::parseObject).toList();
        assertCase(c, spans);
    }

    @SuppressWarnings("unchecked")
    private static void assertCase(Map<String, Object> c, List<Map<String, Object>> spans) {
        Map<String, Object> expected = (Map<String, Object>) c.get("expected");
        assertEquals(expected.get("spanNames"), spans.stream().map(span -> span.get("name")).toList());

        Map<String, Object> rootExpected = (Map<String, Object>) expected.get("root");
        Map<String, Object> root = span(spans, String.valueOf(rootExpected.get("name")));
        assertNull(root.get("parentSpanId"));
        assertStatus(root, String.valueOf(rootExpected.get("status")));
        assertEquals(
                longValue(rootExpected.get("durationNs")),
                longValue(root.get("endTimeUnixNano")) - longValue(root.get("startTimeUnixNano")));
        assertAttrs(root, (Map<String, Object>) rootExpected.get("attributes"));
        if (rootExpected.containsKey("eventNames")) {
            List<String> names = ((List<Map<String, Object>>) root.get("events"))
                    .stream()
                    .map(event -> String.valueOf(event.get("name")))
                    .toList();
            assertEquals(rootExpected.get("eventNames"), names);
        }

        for (String section : List.of("provider", "interceptor")) {
            if (!expected.containsKey(section)) {
                continue;
            }
            Map<String, Object> sectionExpected = (Map<String, Object>) expected.get(section);
            Map<String, Object> child = span(spans, String.valueOf(sectionExpected.get("name")));
            assertEquals(root.get("spanId"), child.get("parentSpanId"));
            assertStatus(child, String.valueOf(sectionExpected.get("status")));
            assertEquals(
                    longValue(sectionExpected.get("startOffsetNs")),
                    longValue(child.get("startTimeUnixNano")) - longValue(root.get("startTimeUnixNano")));
            assertEquals(
                    longValue(sectionExpected.get("endOffsetNs")),
                    longValue(child.get("endTimeUnixNano")) - longValue(root.get("startTimeUnixNano")));
            assertAttrs(child, (Map<String, Object>) sectionExpected.get("attributes"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertStatus(Map<String, Object> span, String expected) {
        assertEquals(expected, ((Map<String, Object>) span.get("status")).get("code"));
    }

    @SuppressWarnings("unchecked")
    private static void assertAttrs(Map<String, Object> span, Map<String, Object> expected) {
        Map<String, Object> attributes = (Map<String, Object>) span.get("attributes");
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), attributes.get(entry.getKey()));
        }
    }

    private static Map<String, Object> span(List<Map<String, Object>> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<InspectorEvent> events(Map<String, Object> c) {
        List<InspectorEvent> events = new ArrayList<>();
        for (Map<String, Object> event : (List<Map<String, Object>>) c.get("events")) {
            events.add(new InspectorEvent(
                    (String) event.get("eventId"),
                    (String) event.get("traceId"),
                    (String) event.get("type"),
                    longValue(event.get("tNs")),
                    (int) longValue(event.get("seq")),
                    (Map<String, Object>) event.get("data")));
        }
        return events;
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private static Map<String, Object> loadVector() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/otel/spans.json")));
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
