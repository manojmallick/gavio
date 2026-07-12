package io.gavio.interceptors.governance;

import io.gavio.interceptors.InterceptorContext;
import io.gavio.GavioRequest;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Shared scope + window helpers for governance interceptors. */
final class Scopes {

    private Scopes() {}

    static String scopeKey(String scope, InterceptorContext ctx) {
        return switch (scope) {
            case "agent" -> "agent:" + (ctx.agentId() != null ? ctx.agentId() : "unknown");
            case "session" -> "session:" + (ctx.sessionId() != null ? ctx.sessionId() : "unknown");
            default -> "global";
        };
    }

    static String scopeKey(String scope, GavioRequest request, InterceptorContext ctx) {
        return switch (scope) {
            case "agent" -> "agent:" + (ctx.agentId() != null ? ctx.agentId() : "unknown");
            case "session" -> "session:" + (ctx.sessionId() != null ? ctx.sessionId() : "unknown");
            case "model" -> "model:" + request.model();
            case "tenant", "feature", "user" -> scope + ":" + dimension(request, scope);
            default -> "global";
        };
    }

    static String windowBucket(String window) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return switch (window) {
            case "day" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "month" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> "total";
        };
    }

    @SuppressWarnings("unchecked")
    private static String dimension(GavioRequest request, String key) {
        Map<String, Object> metadata = request.metadata();
        Object nested = metadata.get("costDimensions");
        if (!(nested instanceof Map<?, ?>)) {
            nested = metadata.get("cost_dimensions");
        }
        String value = nested instanceof Map<?, ?> map
                ? readDimension((Map<String, Object>) map, key)
                : null;
        if (value == null) {
            value = readDimension(metadata, key);
        }
        return value == null ? "unknown" : value;
    }

    private static String readDimension(Map<String, Object> source, String key) {
        List<String> aliases = switch (key) {
            case "tenant" -> List.of("tenant", "tenantId", "tenant_id");
            case "feature" -> List.of("feature", "featureId", "feature_id");
            case "user" -> List.of("user", "userId", "user_id");
            default -> List.of(key);
        };
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
}
