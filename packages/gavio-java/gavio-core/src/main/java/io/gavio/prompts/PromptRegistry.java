package io.gavio.prompts;

import io.gavio.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Registry for versioned prompt templates, including file-backed manifests. */
public final class PromptRegistry {
    private final Map<String, PromptTemplate> templates = new LinkedHashMap<>();
    private final Map<String, String> latest = new LinkedHashMap<>();

    public PromptRegistry() {
    }

    public PromptRegistry(List<PromptTemplate> templates) {
        for (PromptTemplate template : templates) {
            register(template);
        }
    }

    public PromptTemplate register(PromptTemplate template) {
        templates.put(key(template.id(), template.version()), template);
        latest.put(template.id(), resolveLatestAfterRegister(template.id(), template.version()));
        return template;
    }

    public PromptTemplate get(String templateId) {
        return get(templateId, null);
    }

    public PromptTemplate get(String templateId, String version) {
        String resolved = resolveVersion(templateId, version);
        PromptTemplate template = resolved == null ? null : templates.get(key(templateId, resolved));
        if (template == null) {
            throw new IllegalArgumentException(
                    "prompt template not found: " + templateId + "@" + (version == null ? "latest" : version));
        }
        return template;
    }

    public RenderedPrompt render(String templateId, Map<String, Object> variables) {
        return render(templateId, variables, null);
    }

    public RenderedPrompt render(String templateId, Map<String, Object> variables, String version) {
        return get(templateId, version).render(variables);
    }

    public List<String> versions(String templateId) {
        return templates.values().stream()
                .filter(template -> template.id().equals(templateId))
                .map(PromptTemplate::version)
                .sorted(SemanticVersions::compareStrings)
                .toList();
    }

    public PromptDiff diff(String templateId, String fromVersion, String toVersion) {
        return get(templateId, fromVersion).diff(get(templateId, toVersion));
    }

    @SuppressWarnings("unchecked")
    public static PromptRegistry fromManifest(Map<String, Object> manifest) {
        return fromManifest(manifest, null, null);
    }

    @SuppressWarnings("unchecked")
    public static PromptRegistry fromManifest(
            Map<String, Object> manifest,
            String verifySecret,
            Boolean validateSemver) {
        if (verifySecret != null && !PromptManifests.verifySignature(manifest, verifySecret)) {
            throw new IllegalArgumentException("prompt manifest signature verification failed");
        }
        boolean requireSemver = validateSemver != null
                ? validateSemver
                : PromptManifests.SCHEMA_VERSION.equals(manifest.get("schemaVersion"));
        PromptRegistry registry = new PromptRegistry();
        Object rawTemplates = manifest.get("templates");
        if (rawTemplates instanceof List<?> list) {
            for (Object rawTemplate : list) {
                Map<String, Object> data = (Map<String, Object>) rawTemplate;
                PromptTemplate template = PromptTemplate.fromMap(data);
                if (requireSemver) {
                    SemanticVersions.validate(template.version());
                }
                registry.register(template);
            }
        }
        return registry;
    }

    public static PromptRegistry fromFile(Path path, String verifySecret) throws IOException {
        return fromManifest(Json.parseObject(Files.readString(path)), verifySecret, null);
    }

    public Map<String, Object> toManifest(String registryId, Map<String, Object> metadata) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", PromptManifests.SCHEMA_VERSION);
        manifest.put("registryId", registryId);
        manifest.put("metadata", metadata == null ? Map.of() : metadata);
        manifest.put("templates", templates.values().stream()
                .sorted(Comparator.comparing(PromptTemplate::id)
                        .thenComparing(PromptTemplate::version, SemanticVersions::compareStrings))
                .map(PromptTemplate::toMap)
                .collect(Collectors.toList()));
        return manifest;
    }

    public Map<String, Object> toSignedManifest(
            String registryId,
            Map<String, Object> metadata,
            String secret,
            String keyId) {
        return PromptManifests.sign(toManifest(registryId, metadata), secret, keyId);
    }

    private String resolveVersion(String templateId, String selector) {
        if (selector == null || "latest".equals(selector)) {
            return latest.get(templateId);
        }
        if (templates.containsKey(key(templateId, selector))) {
            return selector;
        }
        return templates.values().stream()
                .filter(template -> template.id().equals(templateId))
                .map(PromptTemplate::version)
                .filter(version -> SemanticVersions.parse(version) != null)
                .sorted((left, right) -> SemanticVersions.compareStrings(right, left))
                .filter(version -> SemanticVersions.matchesSelector(version, selector))
                .findFirst()
                .orElse(selector);
    }

    private String resolveLatestAfterRegister(String templateId, String registered) {
        List<String> versions = templates.values().stream()
                .filter(template -> template.id().equals(templateId))
                .map(PromptTemplate::version)
                .toList();
        List<String> semverVersions = versions.stream()
                .filter(version -> SemanticVersions.parse(version) != null)
                .sorted(SemanticVersions::compareStrings)
                .toList();
        if (semverVersions.size() == versions.size()) {
            return semverVersions.get(semverVersions.size() - 1);
        }
        return registered;
    }

    private static String key(String id, String version) {
        return id + "@" + version;
    }
}
