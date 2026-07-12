package io.gavio.exporters;

import io.gavio.inspector.InspectorEvent;
import io.gavio.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** OpenTelemetry-style runtime span exporter. */
public final class OtelSpanExporter implements GavioRuntimeExporter {

    private final Path path;
    private final Consumer<String> writer;
    private final boolean metadataOnly;
    private final OtelSpanMapper mapper;

    public OtelSpanExporter(Path path) {
        this(path, "gavio", true);
    }

    public OtelSpanExporter(Path path, String serviceName) {
        this(path, serviceName, true);
    }

    public OtelSpanExporter(Path path, String serviceName, boolean metadataOnly) {
        this.path = path;
        this.writer = null;
        this.metadataOnly = metadataOnly;
        this.mapper = new OtelSpanMapper(serviceName);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create OTel export directory", e);
        }
    }

    public OtelSpanExporter(Consumer<String> writer) {
        this(writer, "gavio", true);
    }

    public OtelSpanExporter(Consumer<String> writer, String serviceName) {
        this(writer, serviceName, true);
    }

    public OtelSpanExporter(Consumer<String> writer, String serviceName, boolean metadataOnly) {
        this.path = null;
        this.writer = writer;
        this.metadataOnly = metadataOnly;
        this.mapper = new OtelSpanMapper(serviceName);
    }

    @Override
    public synchronized void exportEvent(InspectorEvent event) {
        Map<String, Object> payload = metadataOnly ? RuntimeEventPrivacy.metadataOnly(event) : event.toMap();
        for (Map<String, Object> span : mapper.consume(payload)) {
            write(Json.write(span) + "\n");
        }
    }

    public static List<Map<String, Object>> spansFromEvents(List<InspectorEvent> events, String serviceName) {
        return spansFromEvents(events, serviceName, true);
    }

    public static List<Map<String, Object>> spansFromEvents(
            List<InspectorEvent> events, String serviceName, boolean metadataOnly) {
        OtelSpanMapper mapper = new OtelSpanMapper(serviceName);
        List<Map<String, Object>> spans = new ArrayList<>();
        for (InspectorEvent event : events) {
            Map<String, Object> payload = metadataOnly ? RuntimeEventPrivacy.metadataOnly(event) : event.toMap();
            spans.addAll(mapper.consume(payload));
        }
        return spans;
    }

    private void write(String line) {
        if (writer != null) {
            writer.accept(line);
            return;
        }
        try {
            Files.writeString(
                    path,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write OTel span", e);
        }
    }

    private static final class OtelSpanMapper {
        private final String serviceName;
        private final Map<String, TraceState> traces = new LinkedHashMap<>();

        OtelSpanMapper(String serviceName) {
            this.serviceName = serviceName == null || serviceName.isBlank() ? "gavio" : serviceName;
        }

        List<Map<String, Object>> consume(Map<String, Object> event) {
            String traceId = string(event.get("traceId"));
            if (traceId == null) {
                return List.of();
            }
            String type = string(event.get("type"));
            if ("trace.start".equals(type)) {
                traces.put(traceId, new TraceState(event, serviceName));
                return List.of();
            }
            TraceState state = traces.get(traceId);
            if (state == null) {
                return List.of();
            }
            if (type != null && type.endsWith(".start")) {
                state.openSpan(event);
                return List.of();
            }
            if ("interceptor.before.end".equals(type) || "interceptor.after.end".equals(type)) {
                return state.closeInterceptor(event);
            }
            if ("provider.call.end".equals(type)) {
                return state.closeProvider(event);
            }
            if ("trace.error".equals(type)) {
                state.addException(event);
                return List.of();
            }
            if ("governance.event".equals(type)) {
                state.addEvent("gavio.governance", event, data(event));
                return List.of();
            }
            if ("trace.end".equals(type)) {
                Map<String, Object> span = state.closeRoot(event);
                traces.remove(traceId);
                return List.of(span);
            }
            return List.of();
        }
    }

    private static final class TraceState {
        private final Map<String, Object> startEvent;
        private final Map<String, Object> startData;
        private final String serviceName;
        private final String originalTraceId;
        private final String otelTraceId;
        private final String rootSpanId;
        private final long rootStartNs;
        private final Map<String, List<Map<String, Object>>> open = new LinkedHashMap<>();
        private final List<Map<String, Object>> rootEvents = new ArrayList<>();

        TraceState(Map<String, Object> startEvent, String serviceName) {
            this.startEvent = startEvent;
            this.startData = data(startEvent);
            this.serviceName = serviceName;
            this.originalTraceId = string(startEvent.get("traceId"));
            this.otelTraceId = hexId(originalTraceId, 32);
            this.rootSpanId = hexId(originalTraceId + ":root", 16);
            this.rootStartNs = wallTimeNs(startData.get("wallTimeUtc"));
        }

        void openSpan(Map<String, Object> event) {
            String key = openKey(event);
            open.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }

        List<Map<String, Object>> closeInterceptor(Map<String, Object> endEvent) {
            String type = string(endEvent.get("type"));
            String phase = "interceptor.before.end".equals(type) ? "before" : "after";
            Map<String, Object> endData = data(endEvent);
            String name = string(endData.get("name"));
            if (name == null) {
                name = "unknown";
            }
            Map<String, Object> start = popOpen("interceptor." + phase, name);
            if (start == null) {
                return List.of();
            }
            Map<String, Object> attrs = baseAttributes();
            attrs.put("gavio.interceptor.name", name);
            attrs.put("gavio.interceptor.phase", phase);
            attrs.put("gavio.interceptor.mutated", Boolean.TRUE.equals(endData.get("mutated")));
            copyIfPresent(attrs, endData, "durationUs", "gavio.duration_us");
            Object decision = endData.get("decision");
            if (decision instanceof Map<?, ?> map) {
                attrs.putAll(flatten("gavio.decision", map));
            }
            return List.of(span(
                    "gavio.interceptor." + phase + " " + name,
                    "interceptor." + phase + ":" + name + ":" + string(start.get("seq")),
                    start,
                    endEvent,
                    attrs,
                    false,
                    null,
                    rootSpanId,
                    null,
                    List.of()));
        }

        List<Map<String, Object>> closeProvider(Map<String, Object> endEvent) {
            Map<String, Object> endData = data(endEvent);
            Map<String, Object> start = popOpen("provider.call", endData.get("attempt"));
            if (start == null) {
                return List.of();
            }
            Map<String, Object> startData = data(start);
            String model = firstString(startData.get("model"), this.startData.get("model"), "unknown");
            String provider = firstString(startData.get("provider"), this.startData.get("provider"), "unknown");
            Map<String, Object> attrs = baseAttributes();
            attrs.put("gen_ai.system", provider);
            attrs.put("gen_ai.request.model", model);
            copyIfPresent(attrs, endData, "modelVersion", "gen_ai.response.model");
            copyIfPresent(attrs, endData, "attempt", "gavio.retry.attempt");
            copyIfPresent(attrs, endData, "costUsd", "gen_ai.usage.cost");
            copyIfPresent(attrs, endData, "durationUs", "gavio.duration_us");
            copyIfPresent(attrs, endData, "errorType", "error.type");
            Object usage = endData.get("usage");
            if (usage instanceof Map<?, ?> map) {
                copyIfPresent(attrs, map, "promptTokens", "gen_ai.usage.input_tokens");
                copyIfPresent(attrs, map, "completionTokens", "gen_ai.usage.output_tokens");
                copyIfPresent(attrs, map, "totalTokens", "gen_ai.usage.total_tokens");
            }
            boolean error = !"ok".equals(string(endData.get("status")));
            return List.of(span(
                    "chat " + model,
                    "provider:" + String.valueOf(endData.getOrDefault("attempt", start.get("seq"))),
                    start,
                    endEvent,
                    attrs,
                    error,
                    string(endData.get("errorType")),
                    rootSpanId,
                    null,
                    List.of()));
        }

        void addException(Map<String, Object> event) {
            Map<String, Object> d = data(event);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", d.getOrDefault("errorType", "Error"));
            attrs.put("exception.message", d.getOrDefault("message", ""));
            attrs.put("gavio.error.origin", d.getOrDefault("origin", "chain"));
            attrs.put("exception.escaped", !Boolean.TRUE.equals(d.get("handled")));
            copyIfPresent(attrs, d, "interceptorName", "gavio.interceptor.name");
            addEvent("exception", event, attrs);
        }

        void addEvent(String name, Map<String, Object> event, Map<String, Object> attributes) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("timeUnixNano", timeNs(event));
            out.put("attributes", clean(attributes));
            rootEvents.add(out);
        }

        Map<String, Object> closeRoot(Map<String, Object> endEvent) {
            Map<String, Object> endData = data(endEvent);
            Map<String, Object> attrs = baseAttributes();
            copyIfPresent(attrs, startData, "agentId", "gavio.agent_id");
            copyIfPresent(attrs, startData, "sessionId", "session.id");
            copyIfPresent(attrs, startData, "parentTraceId", "gavio.parent_trace_id");
            copyIfPresent(attrs, startData, "provider", "gen_ai.system");
            copyIfPresent(attrs, startData, "model", "gen_ai.request.model");
            copyIfPresent(attrs, endData, "latencyMs", "gavio.latency_ms");
            copyIfPresent(attrs, endData, "costUsd", "gen_ai.usage.cost");
            copyIfPresent(attrs, endData, "cacheHit", "gavio.cache.hit");
            copyIfPresent(attrs, endData, "cacheType", "gavio.cache.type");
            copyIfPresent(attrs, endData, "piiEntityTypes", "gavio.pii.entity_types");
            copyIfPresent(attrs, endData, "interceptorsFired", "gavio.interceptors");
            Object dimensions = startData.get("costDimensions");
            if (dimensions instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    attrs.put("gavio.cost.dimension." + entry.getKey(), entry.getValue());
                }
            }
            String status = string(endData.get("status"));
            boolean error = status != null && !"ok".equals(status);
            return span(
                    "gavio.request",
                    "root",
                    startEvent,
                    endEvent,
                    attrs,
                    error,
                    status,
                    null,
                    rootSpanId,
                    List.copyOf(rootEvents));
        }

        private Map<String, Object> span(
                String name,
                String logicalKey,
                Map<String, Object> start,
                Map<String, Object> end,
                Map<String, Object> attributes,
                boolean error,
                String statusMessage,
                String parentSpanId,
                String spanId,
                List<Map<String, Object>> events) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("traceId", otelTraceId);
            out.put("spanId", spanId != null ? spanId : hexId(originalTraceId + ":" + logicalKey, 16));
            out.put("parentSpanId", parentSpanId);
            out.put("name", name);
            out.put("kind", "INTERNAL");
            out.put("startTimeUnixNano", timeNs(start));
            out.put("endTimeUnixNano", timeNs(end));
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("code", error ? "ERROR" : "OK");
            if (error && statusMessage != null && !statusMessage.isBlank()) {
                status.put("message", statusMessage);
            }
            out.put("status", status);
            out.put("attributes", clean(attributes));
            out.put("events", events);
            return out;
        }

        private Map<String, Object> baseAttributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("service.name", serviceName);
            attrs.put("gavio.trace_id", originalTraceId);
            attrs.put("gavio.event.schema_version", startEvent.getOrDefault("schemaVersion", "1.0"));
            return attrs;
        }

        private Map<String, Object> popOpen(String family, Object discriminator) {
            String exact = family + ":" + String.valueOf(discriminator);
            List<Map<String, Object>> exactList = open.get(exact);
            if (exactList != null && !exactList.isEmpty()) {
                return exactList.remove(exactList.size() - 1);
            }
            for (Map.Entry<String, List<Map<String, Object>>> entry : open.entrySet()) {
                if (entry.getKey().startsWith(family + ":") && !entry.getValue().isEmpty()) {
                    return entry.getValue().remove(entry.getValue().size() - 1);
                }
            }
            return null;
        }

        private long timeNs(Map<String, Object> event) {
            Object tNs = event.get("tNs");
            return rootStartNs + (tNs instanceof Number n ? n.longValue() : 0L);
        }
    }

    private static String openKey(Map<String, Object> event) {
        String type = string(event.get("type"));
        Map<String, Object> data = data(event);
        if ("interceptor.before.start".equals(type)) {
            return "interceptor.before:" + String.valueOf(data.get("name"));
        }
        if ("interceptor.after.start".equals(type)) {
            return "interceptor.after:" + String.valueOf(data.get("name"));
        }
        if ("provider.call.start".equals(type)) {
            return "provider.call:" + String.valueOf(data.get("attempt"));
        }
        return type + ":" + String.valueOf(event.get("seq"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> event) {
        Object data = event.get("data");
        return data instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private static void copyIfPresent(
            Map<String, Object> target, Map<?, ?> source, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static Map<String, Object> flatten(String prefix, Map<?, ?> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = prefix + "." + String.valueOf(entry.getKey());
            Object raw = entry.getValue();
            if (raw instanceof Map<?, ?> map) {
                out.putAll(flatten(key, map));
            } else {
                out.put(key, raw);
            }
        }
        return out;
    }

    private static Map<String, Object> clean(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static long wallTimeNs(Object value) {
        String text = string(value);
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            Instant instant = Instant.parse(text);
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String firstString(Object first, Object second, String fallback) {
        String value = string(first);
        if (value != null) {
            return value;
        }
        value = string(second);
        return value != null ? value : fallback;
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String hexId(String seed, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
