package io.gavio.integrations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compatibility metadata for one ecosystem integration. */
public record IntegrationRecipe(
        String id,
        String name,
        String category,
        List<String> externalOwns,
        List<String> gavioOwns,
        List<String> gavioSurfaces,
        List<String> recommendedExporters,
        Map<String, String> metadata,
        String docsPath,
        String examplePath) {

    public IntegrationRecipe {
        externalOwns = List.copyOf(externalOwns);
        gavioOwns = List.copyOf(gavioOwns);
        gavioSurfaces = List.copyOf(gavioSurfaces);
        recommendedExporters = List.copyOf(recommendedExporters);
        metadata = Map.copyOf(metadata);
    }

    public Map<String, String> metadataFor(Map<String, String> overrides) {
        Map<String, String> out = new LinkedHashMap<>(metadata);
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value != null) {
                    out.put(key, value);
                }
            });
        }
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("category", category);
        out.put("externalOwns", externalOwns);
        out.put("gavioOwns", gavioOwns);
        out.put("gavioSurfaces", gavioSurfaces);
        out.put("recommendedExporters", recommendedExporters);
        out.put("metadata", metadata);
        out.put("docsPath", docsPath);
        out.put("examplePath", examplePath);
        return out;
    }
}
