package io.gavio.prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metadata-safe prompt release evidence bundle. */
public record PromptReleaseBundle(
        String bundleId,
        String promptId,
        String promptVersion,
        String generatedAt,
        Map<String, Object> manifestIdentity,
        List<PromptVersionGate> gates,
        List<EvalReport> reports,
        PromptDiff promptDiff,
        Map<String, Object> metadata) {

    public PromptReleaseBundle {
        manifestIdentity = Map.copyOf(manifestIdentity);
        gates = List.copyOf(gates);
        reports = List.copyOf(reports);
        metadata = Map.copyOf(metadata == null ? Map.of() : EvalFailureTriage.sanitize(metadata));
    }

    public boolean passed() {
        return gates.stream().allMatch(PromptVersionGate::passed);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "gavio.prompt-release-bundle.v1");
        out.put("bundleId", bundleId);
        out.put("prompt", Map.of("id", promptId, "version", promptVersion));
        out.put("generatedAt", generatedAt);
        out.put("manifest", manifestIdentity);
        out.put("passed", passed());
        List<Object> gateMaps = new ArrayList<>();
        for (PromptVersionGate gate : gates) {
            gateMaps.add(gate.toMap());
        }
        out.put("gates", gateMaps);
        List<Object> reportMaps = new ArrayList<>();
        for (EvalReport report : reports) {
            reportMaps.add(report.toMap());
        }
        out.put("evalReports", reportMaps);
        if (promptDiff != null) {
            out.put("promptDiff", promptDiff.toMap());
        }
        if (!metadata.isEmpty()) {
            out.put("metadata", metadata);
        }
        return out;
    }
}
