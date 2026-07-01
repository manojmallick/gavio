package io.gavio.interceptors.governance;

import io.gavio.interceptors.InterceptorContext;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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

    static String windowBucket(String window) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return switch (window) {
            case "day" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "month" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> "total";
        };
    }
}
