package io.gavio.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static Map<String, Object> loadCatalog() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/integrations/catalog.json")));
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
