package io.gavio.interceptors.governance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured Cost Governance v2 policy. */
public record BudgetPolicyV2(
        String id,
        String scopeType,
        String scopeValue,
        String window,
        double limitUsd,
        double softLimitRatio,
        String hardLimitAction,
        List<Double> alertThresholds,
        String fallbackModel,
        Map<String, Object> metadata) {

    public BudgetPolicyV2 {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("budget policy id is required");
        }
        scopeType = scopeType == null || scopeType.isBlank() ? "global" : scopeType;
        window = window == null || window.isBlank() ? "daily" : window;
        hardLimitAction = hardLimitAction == null || hardLimitAction.isBlank()
                ? "block" : hardLimitAction;
        alertThresholds = List.copyOf(alertThresholds == null ? List.of() : alertThresholds);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    @SuppressWarnings("unchecked")
    public static BudgetPolicyV2 fromMap(Map<String, Object> data) {
        List<Double> thresholds = new ArrayList<>();
        Object rawThresholds = data.getOrDefault("alertThresholds", data.get("alert_thresholds"));
        if (rawThresholds instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Number n) {
                    thresholds.add(n.doubleValue());
                }
            }
        }
        Object rawMetadata = data.get("metadata");
        Map<String, Object> metadata = rawMetadata instanceof Map<?, ?> map
                ? copyMap(map) : Map.of();
        return new BudgetPolicyV2(
                String.valueOf(data.get("id")),
                str(data.getOrDefault("scopeType", data.get("scope_type"))),
                str(data.getOrDefault("scopeValue", data.get("scope_value"))),
                str(data.getOrDefault("window", "daily")),
                num(data.getOrDefault("limitUsd", data.get("limit_usd"))),
                num(data.getOrDefault("softLimitRatio", data.getOrDefault("soft_limit_ratio", 0.8))),
                str(data.getOrDefault("hardLimitAction", data.getOrDefault("hard_limit_action", "block"))),
                thresholds,
                str(data.getOrDefault("fallbackModel", data.get("fallback_model"))),
                metadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("scopeType", scopeType);
        if (scopeValue != null) {
            out.put("scopeValue", scopeValue);
        }
        out.put("window", window);
        out.put("limitUsd", limitUsd);
        out.put("softLimitRatio", softLimitRatio);
        out.put("hardLimitAction", hardLimitAction);
        out.put("alertThresholds", alertThresholds);
        if (fallbackModel != null) {
            out.put("fallbackModel", fallbackModel);
        }
        out.put("metadata", metadata);
        return out;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Map<String, Object> copyMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
