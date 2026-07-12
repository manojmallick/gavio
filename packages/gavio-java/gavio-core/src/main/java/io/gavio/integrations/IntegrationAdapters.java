package io.gavio.integrations;

import io.gavio.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Dependency-light adapter payload builders for common ecosystem tools. */
public final class IntegrationAdapters {

    public static final String ADAPTER_SCHEMA_VERSION = "gavio.integration-adapter.v1";

    private static final Set<String> CONTENT_KEYS = Set.of(
            "messages",
            "content",
            "diff",
            "prompt",
            "response",
            "output",
            "renderedPrompt",
            "rendered_prompt");

    private IntegrationAdapters() {}

    public static Map<String, Object> payload(String integrationId, Map<String, Object> source) {
        return payload(integrationId, source, Map.of(), null);
    }

    public static Map<String, Object> payload(
            String integrationId, Map<String, Object> source, Map<String, Object> metadata) {
        return payload(integrationId, source, metadata, null);
    }

    public static Map<String, Object> payload(
            String integrationId,
            Map<String, Object> source,
            Map<String, Object> metadata,
            String operation) {
        IntegrationRecipe recipe = IntegrationCatalog.get(integrationId);
        Map<String, Object> sourceMap = source == null ? Map.of() : deepCopyMap(source);
        Map<String, Object> labels = adapterMetadata(recipe.id(), sourceMap, metadata == null ? Map.of() : metadata);
        Map<String, Object> summary = adapterSummary(sourceMap);
        String op = operation == null || operation.isBlank() ? defaultOperation(recipe.id()) : operation;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", ADAPTER_SCHEMA_VERSION);
        out.put("adapter", recipe.id());
        out.put("target", recipe.id());
        out.put("kind", recipe.category());
        out.put("payload", payloadFor(recipe.id(), labels, summary, op));
        return out;
    }

    public static Map<String, Object> litellm(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("litellm", source, metadata);
    }

    public static Map<String, Object> promptfoo(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("promptfoo", source, metadata);
    }

    public static Map<String, Object> langfuse(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("langfuse", source, metadata);
    }

    public static Map<String, Object> openlit(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("openlit", source, metadata);
    }

    public static Map<String, Object> langchain(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("langchain", source, metadata);
    }

    public static Map<String, Object> langgraph(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("langgraph", source, metadata);
    }

    public static Map<String, Object> vercelAiSdk(Map<String, Object> source, Map<String, Object> metadata) {
        return payload("vercel-ai-sdk", source, metadata);
    }

    private static Map<String, Object> payloadFor(
            String integrationId,
            Map<String, Object> labels,
            Map<String, Object> summary,
            String operation) {
        List<String> tags = tags(labels, integrationId);
        String traceId = string(summary.getOrDefault("traceId", labels.getOrDefault("trace_id", "")));
        Map<String, Object> merged = clean(merge(labels, prefixSummary(summary)));
        return switch (integrationId) {
            case "litellm" -> Map.of(
                    "completionKwargs",
                    mapOf(
                            "metadata",
                            merged,
                            "extraHeaders",
                            traceHeaders(traceId, integrationId)));
            case "promptfoo" -> mapOf(
                    "defaultTest",
                    mapOf(
                            "metadata",
                            labels,
                            "assert",
                            List.of(
                                    mapOf(
                                            "type",
                                            "javascript",
                                            "value",
                                            "context.vars.gavio.status !== 'error'",
                                            "metric",
                                            "gavio_status"),
                                    mapOf(
                                            "type",
                                            "javascript",
                                            "value",
                                            "(context.vars.gavio.failedCases ?? 0) === 0",
                                            "metric",
                                            "gavio_eval_failures"))),
                    "vars",
                    Map.of("gavio", summary));
            case "langfuse" -> mapOf(
                    "trace",
                    mapOf("id", traceId, "name", operation, "metadata", merged, "tags", tags),
                    "generation",
                    clean(mapOf(
                            "id",
                            traceId.isEmpty() ? "gavio:generation" : traceId + ":generation",
                            "traceId",
                            traceId,
                            "name",
                            "gavio.request",
                            "model",
                            summary.get("model"),
                            "metadata",
                            summary)));
            case "openlit" -> Map.of(
                    "span",
                    mapOf(
                            "name",
                            operation,
                            "attributes",
                            merge(
                                    clean(mapOf(
                                            "gavio.integration",
                                            integrationId,
                                            "gavio.trace_id",
                                            traceId,
                                            "gavio.event_type",
                                            summary.get("eventType"),
                                            "gavio.status",
                                            summary.get("status"),
                                            "gavio.latency_ms",
                                            summary.get("latencyMs"),
                                            "gen_ai.system",
                                            summary.get("provider"),
                                            "gen_ai.request.model",
                                            summary.get("model"),
                                            "gen_ai.usage.cost",
                                            summary.get("costUsd"))),
                                    prefixLabels(labels))));
            case "langchain" -> Map.of(
                    "runnableConfig",
                    mapOf("run_name", operation, "metadata", merged, "tags", tags));
            case "langgraph" -> Map.of(
                    "runnableConfig",
                    mapOf(
                            "run_name",
                            operation,
                            "metadata",
                            merged,
                            "tags",
                            tags,
                            "configurable",
                            mapOf(
                                    "thread_id",
                                    string(labels.getOrDefault("workflow", traceId.isEmpty() ? "gavio" : traceId)),
                                    "gavio_trace_id",
                                    traceId)));
            case "vercel-ai-sdk" -> Map.of(
                    "request",
                    mapOf(
                            "headers",
                            traceHeaders(traceId, integrationId),
                            "experimental_telemetry",
                            mapOf(
                                    "isEnabled",
                                    true,
                                    "functionId",
                                    operation,
                                    "metadata",
                                    merged)));
            default -> mapOf("metadata", merged, "summary", summary);
        };
    }

    private static Map<String, Object> adapterMetadata(
            String integrationId, Map<String, Object> source, Map<String, Object> metadata) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.putAll(IntegrationCatalog.metadata(integrationId));
        labels.putAll(sanitizeMetadataMap(metadata));
        String traceId = traceId(source);
        if (traceId != null && !labels.containsKey("trace_id")) {
            labels.put("trace_id", traceId);
        }
        return clean(labels);
    }

    private static Map<String, Object> adapterSummary(Map<String, Object> source) {
        Map<String, Object> data = mapValue(source.get("data"));
        Map<String, Object> summary = new LinkedHashMap<>();
        copyFirst(summary, "traceId", source, data, List.of("traceId", "trace_id"));
        if (source.containsKey("type")) {
            summary.put("eventType", source.get("type"));
        }
        for (String key : List.of(
                "status",
                "latencyMs",
                "costUsd",
                "piiEntityTypes",
                "interceptorsFired",
                "model",
                "provider",
                "score",
                "suiteId",
                "totalCases",
                "passedCases",
                "failedCases",
                "passed",
                "bundleId")) {
            copyFirst(summary, key, source, data, List.of(key));
        }
        return clean(summary);
    }

    private static void copyFirst(
            Map<String, Object> out,
            String outKey,
            Map<String, Object> primary,
            Map<String, Object> secondary,
            List<String> keys) {
        for (String key : keys) {
            if (primary.containsKey(key) && primary.get(key) != null) {
                out.put(outKey, deepCopy(primary.get(key)));
                return;
            }
            if (secondary.containsKey(key) && secondary.get(key) != null) {
                out.put(outKey, deepCopy(secondary.get(key)));
                return;
            }
        }
    }

    private static String traceId(Map<String, Object> source) {
        Map<String, Object> data = mapValue(source.get("data"));
        Object value = source.getOrDefault("traceId", source.getOrDefault("trace_id", data.get("traceId")));
        String traceId = string(value);
        return traceId.isBlank() ? null : traceId;
    }

    private static Map<String, Object> sanitizeMetadataMap(Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (CONTENT_KEYS.contains(entry.getKey())) {
                out.put(camelHashKey(entry.getKey()), hashValue(entry.getValue()));
            } else {
                out.put(entry.getKey(), sanitizeMetadata(entry.getValue()));
            }
        }
        return out;
    }

    private static Object sanitizeMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeMetadataMap(deepCopyMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(sanitizeMetadata(item));
                }
            }
            return out;
        }
        return value;
    }

    private static String camelHashKey(String key) {
        if (!key.contains("_")) {
            return key + "Hash";
        }
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                out.append(ch);
            }
        }
        return out + "Hash";
    }

    private static String hashValue(Object value) {
        String payload = value instanceof String s ? s : Json.write(canonical(value));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hashed) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return new LinkedHashMap<>(out);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(canonical(item));
            }
            return out;
        }
        return value;
    }

    private static Map<String, Object> clean(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static List<String> tags(Map<String, Object> labels, String integrationId) {
        List<String> tags = new ArrayList<>();
        tags.add("gavio");
        tags.add("integration:" + integrationId);
        for (String key : List.of("tenant", "feature", "environment", "workflow")) {
            String value = string(labels.get(key));
            if (!value.isBlank()) {
                tags.add(key + ":" + value);
            }
        }
        return tags;
    }

    private static Map<String, Object> traceHeaders(String traceId, String integrationId) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-gavio-integration", integrationId);
        if (!traceId.isBlank()) {
            headers.put("x-gavio-trace-id", traceId);
        }
        return headers;
    }

    private static Map<String, Object> prefixSummary(Map<String, Object> summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            if (entry.getValue() != null) {
                out.put("gavio." + entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> prefixLabels(Map<String, Object> labels) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            if (entry.getValue() != null) {
                out.put("gavio.label." + entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static String defaultOperation(String integrationId) {
        if ("promptfoo".equals(integrationId)) {
            return "gavio.eval";
        }
        if ("vercel-ai-sdk".equals(integrationId)) {
            return "gavio.route";
        }
        return "gavio.request";
    }

    private static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> out = new LinkedHashMap<>(left);
        out.putAll(right);
        return out;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            out.put(String.valueOf(values[i]), values[i + 1]);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, Object> deepCopyMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            out.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
        }
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(deepCopy(item));
            }
            return out;
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
