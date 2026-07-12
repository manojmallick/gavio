package io.gavio.platform;

import io.gavio.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds and verifies metadata-only Platform Runtime Profiles. */
public final class PlatformRuntime {

    public static final String SCHEMA_VERSION = "2.0";

    public static final List<String> DEFAULT_REQUIRED_SURFACES = List.of(
            "runtime_events",
            "audit_hashes",
            "policy_packs",
            "cost_governance",
            "tool_runtime",
            "trust_evidence");

    private static final Set<String> CONTENT_KEY_NAMES = Set.of(
            "messages",
            "content",
            "diff",
            "rawmessages",
            "rawprompt",
            "rawresponse",
            "prompttext",
            "responsetext",
            "inputtext",
            "outputtext",
            "rawinput",
            "rawoutput");

    private PlatformRuntime() {}

    public static Builder builder(String profileId) {
        return new Builder(profileId);
    }

    public static PlatformRuntimeVerification verify(Map<String, Object> profile) {
        String computedHash = hashProfile(profile);
        Map<String, Object> readiness = readiness(profile);
        List<String> errors = new ArrayList<>();

        if (!SCHEMA_VERSION.equals(profile.get("schemaVersion"))) {
            errors.add("schemaVersion must be 2.0");
        }
        if (!computedHash.equals(profile.get("profileHash"))) {
            errors.add("profileHash does not match profile content");
        }
        if (containsContentKeys(profile)) {
            errors.add("profile contains content-bearing keys");
        }
        if (!readiness.equals(profile.get("readiness"))) {
            errors.add("readiness does not match profile content");
        }

        return new PlatformRuntimeVerification(errors.isEmpty(), List.copyOf(errors), computedHash, readiness);
    }

    public static String hashProfile(Map<String, Object> profile) {
        Map<String, Object> copy = new LinkedHashMap<>(profile);
        copy.remove("profileHash");
        return "sha256:" + sha256(canonicalJson(copy));
    }

    public static Map<String, Object> readiness(Map<String, Object> profile) {
        Map<String, Object> requirements = asMap(profile.get("requirements"));
        List<String> required = uniqueSorted(
                listOrDefault(requirements.get("requiredSurfaces"), DEFAULT_REQUIRED_SURFACES));
        Set<String> surfaces = new LinkedHashSet<>(stringList(profile.get("surfaces")));
        Map<String, Object> runtime = asMap(profile.get("runtime"));
        Map<String, Object> evidence = asMap(profile.get("evidence"));
        Map<String, Object> runtimeEvents = asMap(evidence.get("runtimeEvents"));
        Map<String, Object> auditChain = asMap(evidence.get("auditChain"));
        List<Map<String, Object>> controls = mapList(profile.get("controls"));

        List<Map<String, Object>> gaps = new ArrayList<>();
        for (String surface : required) {
            if (!surfaces.contains(surface)) {
                gaps.add(gap("missing_surface:" + surface, "required surface " + surface + " is not enabled"));
            }
        }
        if (!"metadata_only".equals(runtime.get("eventExportMode"))) {
            gaps.add(gap("runtime.event_export_mode", "runtime.eventExportMode must be metadata_only"));
        }
        if (!Boolean.TRUE.equals(runtimeEvents.get("contentFree"))) {
            gaps.add(gap("runtime_events.content_free", "runtime event evidence must be content-free"));
        }
        if (!Boolean.TRUE.equals(auditChain.get("verified"))) {
            gaps.add(gap("audit_chain.verified", "audit-chain evidence must be verified"));
        }
        for (Map<String, Object> control : controls) {
            if ("fail".equals(control.get("status"))) {
                String controlId = String.valueOf(control.getOrDefault("id", "unknown"));
                gaps.add(gap("control_failed:" + controlId, "control " + controlId + " failed"));
            }
        }

        int totalChecks = Math.max(1, required.size() + 3 + controls.size());
        long score = Math.max(0, Math.round(100f * (totalChecks - gaps.size()) / totalChecks));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", gaps.isEmpty());
        out.put("score", score);
        out.put("requiredSurfaces", required);
        out.put("gaps", gaps);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<String> listOrDefault(Object value, List<String> fallback) {
        List<String> out = stringList(value);
        return out.isEmpty() ? fallback : out;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static List<String> uniqueSorted(Collection<String> values) {
        return values.stream().map(String::valueOf).distinct().sorted().toList();
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("severity", "error");
        gap.put("message", message);
        return gap;
    }

    private static boolean containsContentKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replace("_", "").replace("-", "").toLowerCase();
                if (CONTENT_KEY_NAMES.contains(key)) {
                    return true;
                }
                if (containsContentKeys(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (containsContentKeys(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String canonicalJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Map.Entry<?, ?> entry = entries.get(i);
                sb.append(Json.write(String.valueOf(entry.getKey())));
                sb.append(':');
                sb.append(canonicalJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> items) {
            StringBuilder sb = new StringBuilder("[");
            int index = 0;
            for (Object item : items) {
                if (index > 0) {
                    sb.append(',');
                }
                sb.append(canonicalJson(item));
                index++;
            }
            return sb.append(']').toString();
        }
        return Json.write(value);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Builder for deterministic platform runtime profiles. */
    public static final class Builder {
        private final String profileId;
        private String generatedAt;
        private Map<String, Object> sdk = mapOf("name", "gavio-java", "version", "2.2.0");
        private Map<String, Object> runtime = new LinkedHashMap<>();
        private List<String> surfaces = new ArrayList<>();
        private List<String> exporters = new ArrayList<>();
        private List<String> integrations = new ArrayList<>();
        private List<Map<String, Object>> controls = new ArrayList<>();
        private Map<String, Object> evidence = new LinkedHashMap<>();
        private List<String> requiredSurfaces = new ArrayList<>(DEFAULT_REQUIRED_SURFACES);

        private Builder(String profileId) {
            this.profileId = profileId;
        }

        public Builder generatedAt(String generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public Builder sdk(String name, String version) {
            this.sdk = mapOf("name", name, "version", version);
            return this;
        }

        public Builder runtime(Map<String, Object> runtime) {
            this.runtime = new LinkedHashMap<>(runtime);
            return this;
        }

        public Builder surfaces(List<String> surfaces) {
            this.surfaces = new ArrayList<>(surfaces);
            return this;
        }

        public Builder exporters(List<String> exporters) {
            this.exporters = new ArrayList<>(exporters);
            return this;
        }

        public Builder integrations(List<String> integrations) {
            this.integrations = new ArrayList<>(integrations);
            return this;
        }

        public Builder controls(List<Map<String, Object>> controls) {
            this.controls = new ArrayList<>(controls);
            return this;
        }

        public Builder evidence(Map<String, Object> evidence) {
            this.evidence = new LinkedHashMap<>(evidence);
            return this;
        }

        public Builder requiredSurfaces(List<String> requiredSurfaces) {
            this.requiredSurfaces = new ArrayList<>(requiredSurfaces);
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("schemaVersion", SCHEMA_VERSION);
            profile.put("profileId", profileId);
            profile.put("generatedAt", generatedAt);
            profile.put("sdk", sdk);
            profile.put("runtime", runtime);
            profile.put("surfaces", uniqueSorted(surfaces));
            profile.put("exporters", uniqueSorted(exporters));
            profile.put("integrations", uniqueSorted(integrations));
            profile.put("controls", controls);
            profile.put("evidence", defaultEvidence(evidence));
            profile.put("requirements", mapOf("requiredSurfaces", uniqueSorted(requiredSurfaces)));
            profile.put("readiness", readiness(profile));
            profile.put("profileHash", hashProfile(profile));
            return profile;
        }
    }

    private static Map<String, Object> defaultEvidence(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>(input);
        out.putIfAbsent("auditChain", mapOf("recordCount", 0, "verified", false));
        out.putIfAbsent("runtimeEvents", mapOf("eventCount", 0, "contentFree", false));
        return out;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            out.put(String.valueOf(values[i]), values[i + 1]);
        }
        return out;
    }
}
