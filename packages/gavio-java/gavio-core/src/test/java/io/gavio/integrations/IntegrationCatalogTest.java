package io.gavio.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationCatalogTest {

    @Test
    @SuppressWarnings("unchecked")
    void integrationCatalogMatchesSharedVector() throws Exception {
        List<Map<String, Object>> expected = (List<Map<String, Object>>) loadCatalog().get("recipes");
        List<Map<String, Object>> actual = IntegrationCatalog.list().stream()
                .map(IntegrationRecipe::toMap)
                .toList();

        assertEquals(expected, actual);
    }

    @Test
    void integrationMetadataAddsRequestLabels() {
        Map<String, String> labels = IntegrationCatalog.metadata(
                "litellm",
                Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));

        assertEquals(
                Map.of(
                        "gateway",
                        "litellm",
                        "integration",
                        "litellm",
                        "integration_kind",
                        "gateway",
                        "tenant",
                        "acme",
                        "feature",
                        "support-chat",
                        "environment",
                        "prod"),
                labels);
    }

    @Test
    void integrationHelpersFilterAndRaiseCleanly() {
        assertEquals(
                List.of("langfuse", "openlit"),
                IntegrationCatalog.listByCategory("observability").stream()
                        .map(IntegrationRecipe::id)
                        .toList());
        assertEquals(List.of("otel"), IntegrationCatalog.get("openlit").recommendedExporters());
        assertThrows(IllegalArgumentException.class, () -> IntegrationCatalog.get("missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compatibilityMatrixOmitsMetadataPayloads() throws Exception {
        List<Map<String, Object>> matrix = IntegrationCatalog.compatibilityMatrix();

        assertEquals(((List<?>) loadCatalog().get("recipes")).size(), matrix.size());
        assertFalse(matrix.get(0).containsKey("metadata"));
        assertEquals("docs/integrations/litellm.md", matrix.get(0).get("docsPath"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void integrationAdaptersMatchSharedVector() throws Exception {
        Map<String, Object> vector = loadAdapters();
        Map<String, Object> source = (Map<String, Object>) vector.get("source");
        Map<String, Object> metadata = (Map<String, Object>) vector.get("metadata");
        List<String> forbiddenStrings = (List<String>) vector.get("forbiddenStrings");

        for (Map<String, Object> adapter : (List<Map<String, Object>>) vector.get("adapters")) {
            Map<String, Object> payload =
                    IntegrationAdapters.payload(String.valueOf(adapter.get("id")), source, metadata);

            assertEquals(IntegrationAdapters.ADAPTER_SCHEMA_VERSION, payload.get("schemaVersion"));
            assertEquals(adapter.get("id"), payload.get("adapter"));
            assertEquals(adapter.get("id"), payload.get("target"));
            assertEquals(adapter.get("kind"), payload.get("kind"));
            for (Map<String, Object> expectation : (List<Map<String, Object>>) adapter.get("expects")) {
                List<Object> path = (List<Object>) expectation.get("path");
                if (Boolean.TRUE.equals(expectation.get("absent"))) {
                    assertTrue(missing(payload, path));
                } else {
                    assertEquals(expectation.get("value"), at(payload, path));
                }
            }
            String serialized = Json.write(payload);
            for (String forbidden : forbiddenStrings) {
                assertFalse(serialized.contains(forbidden), forbidden);
            }
        }
    }

    private static Map<String, Object> loadCatalog() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/integrations/catalog.json")));
    }

    private static Map<String, Object> loadAdapters() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/integrations/adapters.json")));
    }

    private static Object at(Object value, List<Object> path) {
        Object current = value;
        for (Object part : path) {
            if (part instanceof Number index) {
                current = ((List<?>) current).get(index.intValue());
            } else {
                current = ((Map<?, ?>) current).get(String.valueOf(part));
            }
        }
        return current;
    }

    private static boolean missing(Object value, List<Object> path) {
        Object current = value;
        for (Object part : path) {
            if (part instanceof Number index) {
                if (!(current instanceof List<?> list) || index.intValue() >= list.size()) {
                    return true;
                }
                current = list.get(index.intValue());
            } else {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(String.valueOf(part))) {
                    return true;
                }
                current = map.get(String.valueOf(part));
            }
        }
        return false;
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
