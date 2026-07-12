package io.gavio.interceptors.governance;

import io.gavio.GavioRequest;
import io.gavio.interceptors.InterceptorContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure Cost Governance v2 policy evaluation helpers. */
public final class BudgetPolicyEvaluator {
    private BudgetPolicyEvaluator() {
    }

    public static BudgetDecision evaluate(
            BudgetPolicyV2 policy,
            String scope,
            double currentSpendUsd,
            double requestCostUsd) {
        double current = round8(Math.max(currentSpendUsd, 0.0));
        double projected = round8(Math.max(currentSpendUsd + requestCostUsd, 0.0));
        double remaining = round8(Math.max(policy.limitUsd() - projected, 0.0));
        double ratioBefore = ratio(current, policy.limitUsd());
        double ratioAfter = ratio(projected, policy.limitUsd());
        List<Double> crossed = new ArrayList<>();
        policy.alertThresholds().stream().distinct().sorted().forEach(threshold -> {
            if (ratioBefore < threshold && threshold <= ratioAfter) {
                crossed.add(threshold);
            }
        });

        if (projected > policy.limitUsd()) {
            return switch (policy.hardLimitAction()) {
                case "fallback" -> decision(policy, scope, true, "fallback", current, projected,
                        remaining, "hard_limit", "fallback_after_hard_limit", policy.fallbackModel(), crossed);
                case "downgrade_model" -> decision(policy, scope, true, "downgrade_model", current,
                        projected, remaining, "hard_limit", "downgrade_after_hard_limit",
                        policy.fallbackModel(), crossed);
                case "dry_run" -> decision(policy, scope, true, "dry_run", current, projected,
                        remaining, "hard_limit", "hard_limit_dry_run", null, crossed);
                default -> decision(policy, scope, false, "block", current, projected,
                        remaining, "hard_limit", "hard_limit_exceeded", null, crossed);
            };
        }

        if (ratioAfter >= policy.softLimitRatio()) {
            return decision(policy, scope, true, "warn", current, projected, remaining,
                    "soft_limit", "soft_limit_exceeded", null, crossed);
        }
        return decision(policy, scope, true, "allow", current, projected, remaining,
                "ok", "under_budget", null, crossed);
    }

    public static String resolvePolicyScope(
            BudgetPolicyV2 policy, GavioRequest request, InterceptorContext ctx) {
        return resolvePolicyScope(policy, request, ctx, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public static String resolvePolicyScope(
            BudgetPolicyV2 policy, GavioRequest request, InterceptorContext ctx, OffsetDateTime now) {
        String value = policy.scopeValue() != null
                ? policy.scopeValue()
                : requestScopeValue(policy.scopeType(), request, ctx);
        String prefix = "global".equals(policy.scopeType())
                ? "global" : policy.scopeType() + ":" + value;
        return prefix + "|" + windowBucket(policy.window(), now);
    }

    public static String windowBucket(String window, OffsetDateTime now) {
        OffsetDateTime at = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        return switch (window) {
            case "daily", "day" -> at.toLocalDate().toString();
            case "weekly", "week" -> {
                WeekFields fields = WeekFields.ISO;
                int week = at.get(fields.weekOfWeekBasedYear());
                int year = at.get(fields.weekBasedYear());
                yield String.format(Locale.ROOT, "%d-W%02d", year, week);
            }
            case "monthly", "month" -> String.format(Locale.ROOT, "%04d-%02d", at.getYear(), at.getMonthValue());
            case "rolling", "total" -> window;
            default -> "total";
        };
    }

    private static BudgetDecision decision(
            BudgetPolicyV2 policy,
            String scope,
            boolean allowed,
            String action,
            double current,
            double projected,
            double remaining,
            String thresholdStatus,
            String reason,
            String targetModel,
            List<Double> crossed) {
        return new BudgetDecision(
                policy.id(),
                scope,
                policy.window(),
                allowed,
                action,
                current,
                projected,
                remaining,
                thresholdStatus,
                reason,
                targetModel,
                crossed,
                Map.of());
    }

    private static String requestScopeValue(
            String scopeType, GavioRequest request, InterceptorContext ctx) {
        return switch (scopeType) {
            case "global" -> "global";
            case "agent" -> first(ctx == null ? null : ctx.agentId(), request.agentId(), "unknown");
            case "session" -> first(ctx == null ? null : ctx.sessionId(), request.sessionId(), "unknown");
            case "model" -> request.model();
            case "request" -> request.traceId();
            default -> first(dimension(request.metadata(), scopeType), "unknown");
        };
    }

    @SuppressWarnings("unchecked")
    private static String dimension(Map<String, Object> metadata, String key) {
        Object nested = metadata.get("costDimensions");
        Object nestedSnake = metadata.get("cost_dimensions");
        return first(
                readDimension(nested instanceof Map<?, ?> map ? (Map<String, Object>) map : null, key),
                readDimension(nestedSnake instanceof Map<?, ?> map ? (Map<String, Object>) map : null, key),
                readDimension(metadata, key));
    }

    private static String readDimension(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        List<String> aliases = switch (key) {
            case "tenant" -> List.of("tenant", "tenantId", "tenant_id");
            case "team" -> List.of("team", "teamId", "team_id");
            case "feature" -> List.of("feature", "featureId", "feature_id");
            case "user" -> List.of("user", "userId", "user_id");
            default -> List.of(key);
        };
        for (String alias : aliases) {
            Object value = source.get(alias);
            if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static double ratio(double spend, double limit) {
        if (limit <= 0.0) {
            return spend > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
        }
        return spend / limit;
    }

    static double round8(double value) {
        return Math.round(value * 1e8) / 1e8;
    }
}
