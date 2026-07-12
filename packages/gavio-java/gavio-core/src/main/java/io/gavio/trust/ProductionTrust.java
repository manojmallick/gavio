package io.gavio.trust;

import io.gavio.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds and verifies metadata-only Production Trust Bundles. */
public final class ProductionTrust {

    public static final String SCHEMA_VERSION = "1.0";

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

    private ProductionTrust() {}

    public static Builder builder(String bundleId) {
        return new Builder(bundleId);
    }

    public static ProductionTrustVerification verify(Map<String, Object> bundle) {
        String computedHash = hashBundle(bundle);
        List<String> errors = new ArrayList<>();

        if (!SCHEMA_VERSION.equals(bundle.get("schemaVersion"))) {
            errors.add("schemaVersion must be 1.0");
        }
        if (!computedHash.equals(bundle.get("bundleHash"))) {
            errors.add("bundleHash does not match bundle content");
        }
        if (containsContentKeys(bundle)) {
            errors.add("bundle contains content-bearing keys");
        }

        Map<String, Object> privacy = asMap(bundle.get("privacy"));
        if (!"metadata_only".equals(privacy.get("contentMode"))) {
            errors.add("privacy.contentMode must be metadata_only");
        }
        if (!Boolean.FALSE.equals(privacy.get("containsRawContent"))) {
            errors.add("privacy.containsRawContent must be false");
        }

        Map<String, Object> evidence = asMap(bundle.get("evidence"));
        Map<String, Object> auditChain = asMap(evidence.get("auditChain"));
        if (!Boolean.TRUE.equals(auditChain.get("verified"))) {
            errors.add("evidence.auditChain.verified must be true");
        }
        Map<String, Object> runtimeEvents = asMap(evidence.get("runtimeEvents"));
        if (!Boolean.TRUE.equals(runtimeEvents.get("contentFree"))) {
            errors.add("evidence.runtimeEvents.contentFree must be true");
        }

        return new ProductionTrustVerification(errors.isEmpty(), List.copyOf(errors), computedHash);
    }

    public static String hashBundle(Map<String, Object> bundle) {
        Map<String, Object> copy = new LinkedHashMap<>(bundle);
        copy.remove("bundleHash");
        return "sha256:" + sha256(canonicalJson(copy));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String prefixedHash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    public static final class Builder {
        private final String bundleId;
        private String generatedAt;
        private Map<String, Object> sdk = mapOf("name", "gavio-java", "version", "2.2.0");
        private Map<String, Object> release = new LinkedHashMap<>();
        private Map<String, Object> runtime = new LinkedHashMap<>();
        private Map<String, Object> privacy = mapOf(
                "contentMode", "metadata_only",
                "containsRawContent", Boolean.FALSE,
                "redactedFields", List.of("messages", "content", "diff"));
        private Map<String, Object> auditChain = mapOf(
                "recordCount", 0,
                "verified", Boolean.TRUE,
                "headHash", "",
                "tailHash", "");
        private Map<String, Object> runtimeEvents = mapOf(
                "eventCount", 0,
                "contentFree", Boolean.TRUE,
                "eventTypes", List.of());
        private final List<Map<String, Object>> controls = new ArrayList<>();
        private final List<Map<String, Object>> documents = new ArrayList<>();

        private Builder(String bundleId) {
            this.bundleId = bundleId;
        }

        public Builder generatedAt(String generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public Builder sdk(String name, String version) {
            this.sdk = mapOf("name", name, "version", version);
            return this;
        }

        public Builder release(String version, String tag, String commit) {
            this.release = mapOf("version", version, "tag", tag, "commit", commit);
            return this;
        }

        public Builder runtime(
                String environment,
                String policySource,
                boolean controlPlaneEnabled,
                String eventExportMode) {
            this.runtime = mapOf(
                    "environment", environment,
                    "policySource", policySource,
                    "controlPlaneEnabled", controlPlaneEnabled,
                    "eventExportMode", eventExportMode);
            return this;
        }

        public Builder privacy(Map<String, Object> privacy) {
            this.privacy = new LinkedHashMap<>(privacy);
            return this;
        }

        public Builder auditChain(
                int recordCount,
                boolean verified,
                String headHash,
                String tailHash) {
            this.auditChain = mapOf(
                    "recordCount", recordCount,
                    "verified", verified,
                    "headHash", prefixedHash(headHash),
                    "tailHash", prefixedHash(tailHash));
            return this;
        }

        public Builder runtimeEvents(int eventCount, boolean contentFree, List<String> eventTypes) {
            List<String> sortedTypes = new ArrayList<>(eventTypes);
            sortedTypes.sort(String::compareTo);
            this.runtimeEvents = mapOf(
                    "eventCount", eventCount,
                    "contentFree", contentFree,
                    "eventTypes", sortedTypes);
            return this;
        }

        public Builder addControl(String type, String id, String status, String source) {
            this.controls.add(mapOf(
                    "type", type,
                    "id", id,
                    "status", status,
                    "source", source));
            return this;
        }

        public Builder addControl(Map<String, Object> control) {
            this.controls.add(new LinkedHashMap<>(control));
            return this;
        }

        public Builder addDocument(String name, String path, String sha256) {
            this.documents.add(mapOf("name", name, "path", path, "sha256", prefixedHash(sha256)));
            return this;
        }

        public Builder addDocument(Map<String, Object> document) {
            this.documents.add(new LinkedHashMap<>(document));
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("auditChain", auditChain);
            evidence.put("runtimeEvents", runtimeEvents);
            evidence.put("controls", List.copyOf(controls));

            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("schemaVersion", SCHEMA_VERSION);
            bundle.put("bundleId", bundleId);
            bundle.put("generatedAt", generatedAt);
            bundle.put("sdk", sdk);
            bundle.put("release", release);
            bundle.put("runtime", runtime);
            bundle.put("privacy", privacy);
            bundle.put("evidence", evidence);
            bundle.put("documents", List.copyOf(documents));
            bundle.put("bundleHash", hashBundle(bundle));
            return bundle;
        }
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
