package io.gavio.interceptors.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Auditable Cost Governance v2 budget decision. */
public record BudgetDecision(
        String policyId,
        String scope,
        String window,
        boolean allowed,
        String action,
        double currentSpendUsd,
        double projectedSpendUsd,
        double remainingUsd,
        String thresholdStatus,
        String reason,
        String targetModel,
        List<Double> alertThresholdsCrossed,
        Map<String, Object> metadata) {

    public BudgetDecision {
        alertThresholdsCrossed = List.copyOf(
                alertThresholdsCrossed == null ? List.of() : alertThresholdsCrossed);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policyId", policyId);
        out.put("scope", scope);
        out.put("window", window);
        out.put("allowed", allowed);
        out.put("action", action);
        out.put("currentSpendUsd", currentSpendUsd);
        out.put("projectedSpendUsd", projectedSpendUsd);
        out.put("remainingUsd", remainingUsd);
        out.put("thresholdStatus", thresholdStatus);
        out.put("reason", reason);
        if (targetModel != null) {
            out.put("targetModel", targetModel);
        }
        out.put("alertThresholdsCrossed", alertThresholdsCrossed);
        out.put("metadata", metadata);
        return out;
    }
}
