package io.gavio.inspector;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-request event emitter (F-DX-09). Created by the gateway for each call
 * when the inspector is enabled; the interceptor chain invokes the emit hooks
 * at every stage. One instance per request — never shared.
 *
 * <p>Content gating is structural: in {@link CaptureMode#METADATA} the
 * content-carrying branches are never taken, so {@code messages}, {@code content}
 * and {@code diff} keys cannot exist in any emitted event. In FULL/REDACTED
 * modes captured content is passed through {@link SecretMasker}.
 */
public final class TraceEmitter {

    private static final List<String> COST_DIMENSION_KEYS =
            List.of("feature", "tenant", "user", "endpoint", "environment", "workflow", "tool");

    private final InspectorBus bus;
    private final CaptureMode mode;

    private String traceId;
    private long startNanos;
    private int seq;
    private boolean started;

    public TraceEmitter(InspectorBus bus, CaptureMode mode) {
        this.bus = bus;
        this.mode = mode;
    }

    private boolean captureContent() {
        return mode != CaptureMode.METADATA;
    }

    private boolean captureFrom() {
        return mode == CaptureMode.FULL;
    }

    private synchronized void emit(String type, Map<String, Object> data) {
        long tNs = started ? Math.max(0, System.nanoTime() - startNanos) : 0;
        bus.emit(InspectorEvent.of(traceId, type, tNs, seq++, data));
    }

    // ---- trace lifecycle ---------------------------------------------------

    public void traceStart(GavioRequest request) {
        this.traceId = request.traceId();
        this.startNanos = System.nanoTime();
        this.started = true;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentTraceId", request.parentTraceId());
        data.put("agentId", request.agentId());
        data.put("sessionId", request.sessionId());
        data.put("provider", request.provider().value());
        data.put("model", request.model());
        data.put("wallTimeUtc", OffsetDateTime.now(ZoneOffset.UTC).toString());
        data.put("mode", mode.wireValue());
        Map<String, String> costDimensions = costDimensions(request.metadata());
        if (!costDimensions.isEmpty()) {
            data.put("costDimensions", costDimensions);
        }
        if (captureContent()) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Message m : request.messages()) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", m.role());
                msg.put("content", SecretMasker.mask(m.content()));
                messages.add(msg);
            }
            data.put("messages", messages);
        }
        emit("trace.start", data);
    }

    public void traceEnd(GavioResponse response, InterceptorContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("latencyMs", elapsedMs());
        data.put("costUsd", response.costUsd());
        Double cacheSavingsUsd = metadataNumber(response.metadata(), "cacheSavingsUsd", "cache_savings_usd");
        if (cacheSavingsUsd != null) {
            data.put("cacheSavingsUsd", cacheSavingsUsd);
        }
        data.put("cacheHit", response.cacheHit());
        data.put("cacheType", response.cacheType() == null ? null : response.cacheType().toString());
        data.put("interceptorsFired", List.copyOf(response.interceptorsFired()));
        if (!ctx.piiEntityTypes().isEmpty()) {
            data.put("piiEntityTypes", List.copyOf(ctx.piiEntityTypes()));
        }
        if (captureContent()) {
            data.put("content", SecretMasker.mask(response.content()));
        }
        emit("trace.end", data);
    }

    public void traceEndError(InterceptorContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "error");
        data.put("latencyMs", elapsedMs());
        data.put("interceptorsFired", List.copyOf(ctx.interceptorsFired()));
        if (!ctx.piiEntityTypes().isEmpty()) {
            data.put("piiEntityTypes", List.copyOf(ctx.piiEntityTypes()));
        }
        emit("trace.end", data);
    }

    public void traceError(String origin, String interceptorName, Throwable cause) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("origin", origin);
        if (interceptorName != null) {
            data.put("interceptorName", interceptorName);
        }
        data.put("errorType", cause.getClass().getSimpleName());
        data.put("message", String.valueOf(cause.getMessage()));
        data.put("handled", false);
        emit("trace.error", data);
    }

    // ---- interceptor hooks ---------------------------------------------------

    public void interceptorBeforeStart(String name) {
        emit("interceptor.before.start", Map.of("name", name));
    }

    public void interceptorBeforeEnd(
            String name, long durationNs, GavioRequest before, GavioRequest after, InterceptorContext ctx) {
        boolean mutated = !before.messages().equals(after.messages())
                || !Objects.equals(before.model(), after.model());
        Map<String, Object> diff = mutated && captureContent() ? requestDiff(before, after) : null;
        emit("interceptor.before.end", hookEnd(name, durationNs, mutated, diff, ctx));
        emitGovernance(ctx);
    }

    public void interceptorAfterStart(String name) {
        emit("interceptor.after.start", Map.of("name", name));
    }

    public void interceptorAfterEnd(
            String name, long durationNs, GavioResponse before, GavioResponse after, InterceptorContext ctx) {
        boolean mutated = !Objects.equals(before.content(), after.content());
        Map<String, Object> diff = null;
        if (mutated && captureContent()) {
            Map<String, Object> content = new LinkedHashMap<>();
            if (captureFrom()) {
                content.put("from", SecretMasker.mask(before.content()));
            }
            content.put("to", SecretMasker.mask(after.content()));
            diff = new LinkedHashMap<>();
            diff.put("content", content);
        }
        emit("interceptor.after.end", hookEnd(name, durationNs, mutated, diff, ctx));
        emitGovernance(ctx);
    }

    /** Emit a standalone governance.event for each queued alert (F-GOV-07). */
    public void emitGovernance(InterceptorContext ctx) {
        for (Map<String, Object> data : ctx.drainGovernance()) {
            emit("governance.event", data);
        }
    }

    private Map<String, Object> hookEnd(
            String name, long durationNs, boolean mutated, Map<String, Object> diff, InterceptorContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("durationUs", Math.max(0, durationNs / 1_000L));
        data.put("mutated", mutated);
        Map<String, Object> decision = resolveDecision(name, ctx);
        if (!decision.isEmpty()) {
            data.put("decision", decision);
        }
        if (diff != null && !diff.isEmpty()) {
            data.put("diff", diff);
        }
        return data;
    }

    /**
     * Decision entries recorded via {@code ctx.inspect(...)} during this hook;
     * falls back to a context-state entry keyed by the interceptor's name.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveDecision(String name, InterceptorContext ctx) {
        Map<String, Object> decision = ctx.drainInspections();
        if (!decision.isEmpty()) {
            return decision;
        }
        Object fromState = ctx.state().get(name);
        if (fromState instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (fromState != null) {
            return Map.of(name, fromState);
        }
        return Map.of();
    }

    private Map<String, Object> requestDiff(GavioRequest before, GavioRequest after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        List<Message> a = before.messages();
        List<Message> b = after.messages();
        List<Map<String, Object>> messageDiffs = new ArrayList<>();
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            String from = i < a.size() ? a.get(i).content() : null;
            String to = i < b.size() ? b.get(i).content() : null;
            if (Objects.equals(from, to)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            if (captureFrom() && from != null) {
                entry.put("from", SecretMasker.mask(from));
            }
            if (to != null) {
                entry.put("to", SecretMasker.mask(to));
            }
            messageDiffs.add(entry);
        }
        if (!messageDiffs.isEmpty()) {
            diff.put("messages", messageDiffs);
        }
        if (!Objects.equals(before.model(), after.model())) {
            Map<String, Object> model = new LinkedHashMap<>();
            if (captureFrom()) {
                model.put("from", before.model());
            }
            model.put("to", after.model());
            diff.put("model", model);
        }
        return diff;
    }

    // ---- provider call ---------------------------------------------------

    public void providerCallStart(GavioRequest request, int attempt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", request.provider().value());
        data.put("model", request.model());
        data.put("attempt", attempt);
        emit("provider.call.start", data);
    }

    public void providerCallEnd(long durationNs, GavioResponse response, int attempt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("durationUs", Math.max(0, durationNs / 1_000L));
        data.put("status", "ok");
        data.put("attempt", attempt);
        if (response.modelVersion() != null && !response.modelVersion().isEmpty()) {
            data.put("modelVersion", response.modelVersion());
        }
        TokenUsage usage = response.usage();
        if (usage != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("promptTokens", usage.promptTokens());
            u.put("completionTokens", usage.completionTokens());
            u.put("totalTokens", usage.totalTokens());
            data.put("usage", u);
        }
        data.put("costUsd", response.costUsd());
        emit("provider.call.end", data);
    }

    public void providerCallError(long durationNs, Throwable cause, int attempt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("durationUs", Math.max(0, durationNs / 1_000L));
        data.put("status", "error");
        data.put("attempt", attempt);
        data.put("errorType", cause.getClass().getSimpleName());
        emit("provider.call.end", data);
    }

    private long elapsedMs() {
        return started ? Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L) : 0;
    }

    private static Map<String, String> costDimensions(Map<String, Object> metadata) {
        Map<String, Object> nested = objectMap(metadata.get("costDimensions"));
        if (nested == null) {
            nested = objectMap(metadata.get("cost_dimensions"));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : COST_DIMENSION_KEYS) {
            String value = firstScalar(nested, aliases(key));
            if (value == null) {
                value = firstScalar(metadata, aliases(key));
            }
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : null;
    }

    private static String firstScalar(Map<String, Object> source, List<String> aliases) {
        if (source == null) {
            return null;
        }
        for (String alias : aliases) {
            Object value = source.get(alias);
            if (value instanceof String s && !s.isBlank()) {
                return s.trim();
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static List<String> aliases(String key) {
        return switch (key) {
            case "feature" -> List.of("feature", "featureId", "feature_id");
            case "tenant" -> List.of("tenant", "tenantId", "tenant_id");
            case "user" -> List.of("user", "userId", "user_id");
            case "endpoint" -> List.of("endpoint", "route", "path");
            case "environment" -> List.of("environment", "env");
            case "workflow" -> List.of("workflow", "workflowId", "workflow_id");
            case "tool" -> List.of("tool", "toolName", "tool_name");
            default -> List.of(key);
        };
    }

    private static Double metadataNumber(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof Number n && n.doubleValue() >= 0.0) {
                return n.doubleValue();
            }
        }
        return null;
    }
}
