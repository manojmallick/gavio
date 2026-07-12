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
        return out;
    }
}
