package io.gavio.interceptors.pii.policy;

import io.gavio.interceptors.pii.PiiScanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A domain policy pack: scanner composition plus JSON-compatible manifest metadata. */
public final class PolicyPack {

    private final String id;
    private final String name;
    private final String version;
    private final String domain;
    private final String description;
    private final List<PolicyDetector> detectors;
    private final List<PiiScanner> scanners;
    private final PolicyAction defaultAction;
    private final RedactionStrategy redactionStrategy;
    private final List<String> auditLabels;
    private final Map<String, String> compatibility;
    private final PolicyPackSignature signature;
    private final String schema;
    private final String schemaVersion;

    public PolicyPack(
            String id,
            String name,
            String version,
            String domain,
            String description,
            List<PolicyDetector> detectors,
            List<PiiScanner> scanners,
            PolicyAction defaultAction,
            RedactionStrategy redactionStrategy,
            List<String> auditLabels) {
        this(id, name, version, domain, description, detectors, scanners, defaultAction,
                redactionStrategy, auditLabels, Map.of(), null, null, null);
    }

    public PolicyPack(
            String id,
            String name,
            String version,
            String domain,
            String description,
            List<PolicyDetector> detectors,
            List<PiiScanner> scanners,
            PolicyAction defaultAction,
            RedactionStrategy redactionStrategy,
            List<String> auditLabels,
            Map<String, String> compatibility,
            PolicyPackSignature signature,
            String schema,
            String schemaVersion) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.domain = domain;
        this.description = description;
        this.detectors = List.copyOf(detectors);
        this.scanners = List.copyOf(scanners);
        this.defaultAction = defaultAction;
        this.redactionStrategy = redactionStrategy;
        this.auditLabels = List.copyOf(auditLabels);
        this.compatibility = Map.copyOf(compatibility);
        this.signature = signature;
        this.schema = schema;
        this.schemaVersion = schemaVersion;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String domain() {
        return domain;
    }

    public String description() {
        return description;
    }

    public List<PolicyDetector> detectors() {
        return detectors;
    }

    public List<PiiScanner> scanners() {
        return scanners;
    }

    public PolicyAction defaultAction() {
        return defaultAction;
    }

    public RedactionStrategy redactionStrategy() {
        return redactionStrategy;
    }

    public List<String> auditLabels() {
        return auditLabels;
    }

    public Map<String, String> compatibility() {
        return compatibility;
    }

    public PolicyPackSignature signature() {
        return signature;
    }

    public boolean verifySignature() {
        return signature != null
                && "sha256".equals(signature.algorithm())
                && signature.value() != null
                && PolicyPacks.canonicalManifestDigest(manifest()).equals(signature.value());
    }

    public PolicyPack withOverrides(Map<String, Object> overrides) {
        Map<String, Object> out = manifest();
        if (overrides.containsKey("defaultAction")) {
            out.put("defaultAction", overrides.get("defaultAction"));
        }
        if (overrides.containsKey("redactionStrategy")) {
            out.put("redactionStrategy", overrides.get("redactionStrategy"));
        }
        if (overrides.containsKey("auditLabels")) {
            out.put("auditLabels", overrides.get("auditLabels"));
        }
        applyDetectorOverrides(out, overrides);
        out.remove("signature");
        return PolicyPacks.fromManifest(out);
    }

    @SuppressWarnings("unchecked")
    private static void applyDetectorOverrides(Map<String, Object> manifest, Map<String, Object> overrides) {
        Object rawOverrides = overrides.get("detectors");
        if (!(rawOverrides instanceof Map<?, ?> detectorOverrides)) {
            return;
        }
        Object rawDetectors = manifest.get("detectors");
        if (!(rawDetectors instanceof List<?> detectors)) {
            return;
        }
        for (Object rawDetector : detectors) {
            Map<String, Object> detector = (Map<String, Object>) rawDetector;
            Object rawOverride = detectorOverrides.get(detector.get("name"));
            if (rawOverride instanceof Map<?, ?> override) {
                for (Map.Entry<?, ?> entry : override.entrySet()) {
                    detector.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
    }

    public Map<String, Object> manifest() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("version", version);
        out.put("domain", domain);
        out.put("description", description);
        out.put("defaultAction", defaultAction.wireValue());
        out.put("redactionStrategy", redactionStrategy.wireValue());
        out.put("auditLabels", auditLabels);
        List<Map<String, Object>> detectorManifests = new ArrayList<>();
        for (PolicyDetector detector : detectors) {
            detectorManifests.add(detector.manifest());
        }
        out.put("detectors", detectorManifests);
        if (schema != null) {
            out.put("$schema", schema);
        }
        if (schemaVersion != null) {
            out.put("schemaVersion", schemaVersion);
        }
        if (!compatibility.isEmpty()) {
            out.put("compatibility", compatibility);
        }
        if (signature != null) {
            out.put("signature", signature.manifest());
        }
        return out;
    }
}
