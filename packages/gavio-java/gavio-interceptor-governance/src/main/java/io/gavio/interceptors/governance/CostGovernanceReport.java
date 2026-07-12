package io.gavio.interceptors.governance;

import io.gavio.inspector.InspectorAnalytics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cost Governance v2 report helpers. */
public final class CostGovernanceReport {
    private CostGovernanceReport() {
    }

    public static Map<String, Object> build(
            List<Map<String, Object>> summaries,
            List<BudgetPolicyV2> policies,
            String groupBy,
            String since,
            double usageElapsedRatio) {
        Map<String, Object> report = new LinkedHashMap<>(
                InspectorAnalytics.buildCostReport(summaries, groupBy, since));
        if (policies == null || policies.isEmpty()) {
            return report;
        }
        double ratio = usageElapsedRatio > 0.0 ? usageElapsedRatio : 1.0;
        List<Map<String, Object>> budgets = new ArrayList<>();
        for (BudgetPolicyV2 policy : policies) {
            double current = spendForPolicy(report, summaries, policy, groupBy);
            double forecast = BudgetPolicyEvaluator.round8(current / ratio);
            double remaining = BudgetPolicyEvaluator.round8(Math.max(policy.limitUsd() - current, 0.0));
            Map<String, Object> rollup = new LinkedHashMap<>();
            rollup.put("policyId", policy.id());
            rollup.put("scope", reportScope(policy));
            rollup.put("window", policy.window());
            rollup.put("limitUsd", policy.limitUsd());
            rollup.put("currentSpendUsd", current);
            rollup.put("remainingUsd", remaining);
            rollup.put("forecastWindowSpendUsd", forecast);
            rollup.put("status", budgetStatus(policy, current, forecast));
            budgets.add(rollup);
            attachGroupBudget(report, policy, groupBy, rollup);
        }
        report.put("budgets", budgets);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static double spendForPolicy(
            Map<String, Object> report,
            List<Map<String, Object>> summaries,
            BudgetPolicyV2 policy,
            String groupBy) {
        if ("global".equals(policy.scopeType())) {
            return BudgetPolicyEvaluator.round8(asDouble(((Map<String, Object>) report.get("total")).get("costUsd")));
        }
        if (policy.scopeValue() != null && groupByName(policy.scopeType()).equals(groupBy)) {
            Object groupsRaw = report.get("groups");
            if (groupsRaw instanceof Map<?, ?> groups
                    && groups.get(policy.scopeValue()) instanceof Map<?, ?> group) {
                return BudgetPolicyEvaluator.round8(asDouble(group.get("costUsd")));
            }
        }
        double total = 0.0;
        for (Map<String, Object> summary : summaries) {
            if (summaryMatchesPolicy(summary, policy)) {
                total += asDouble(summary.get("costUsd"));
            }
        }
        return BudgetPolicyEvaluator.round8(total);
    }

    @SuppressWarnings("unchecked")
    private static void attachGroupBudget(
            Map<String, Object> report,
            BudgetPolicyV2 policy,
            String groupBy,
            Map<String, Object> rollup) {
        if (policy.scopeValue() == null || !groupByName(policy.scopeType()).equals(groupBy)) {
            return;
        }
        Object groupsRaw = report.get("groups");
        if (!(groupsRaw instanceof Map<?, ?> groups)
                || !(groups.get(policy.scopeValue()) instanceof Map<?, ?> groupRaw)) {
            return;
        }
        Map<String, Object> group = (Map<String, Object>) groupRaw;
        group.put("budgetLimitUsd", rollup.get("limitUsd"));
        group.put("budgetRemainingUsd", rollup.get("remainingUsd"));
        group.put("forecastWindowSpendUsd", rollup.get("forecastWindowSpendUsd"));
    }

    @SuppressWarnings("unchecked")
    private static boolean summaryMatchesPolicy(Map<String, Object> summary, BudgetPolicyV2 policy) {
        if ("global".equals(policy.scopeType())) {
            return true;
        }
        if (policy.scopeValue() == null) {
            return false;
        }
        Object value = summary.get(summaryField(policy.scopeType()));
        if ((value == null || "".equals(value)) && summary.get("costDimensions") instanceof Map<?, ?> dimensions) {
            value = ((Map<String, Object>) dimensions).get(groupByName(policy.scopeType()));
        }
        return policy.scopeValue().equals(String.valueOf(value));
    }

    private static String budgetStatus(BudgetPolicyV2 policy, double current, double forecast) {
        double soft = policy.limitUsd() * policy.softLimitRatio();
        if (current >= policy.limitUsd()) {
            return "hard_limit";
        }
        if (current >= soft || forecast >= soft) {
            return "soft_limit";
        }
        return "ok";
    }

    private static String reportScope(BudgetPolicyV2 policy) {
        if ("global".equals(policy.scopeType())) {
            return "global";
        }
        return policy.scopeType() + ":" + (policy.scopeValue() == null ? "unknown" : policy.scopeValue());
    }

    private static String groupByName(String scopeType) {
        return switch (scopeType) {
            case "agent" -> "agent_id";
            case "session" -> "session_id";
            default -> scopeType;
        };
    }

    private static String summaryField(String scopeType) {
        return switch (scopeType) {
            case "agent" -> "agentId";
            case "session" -> "sessionId";
            case "middleware_chain" -> "middlewareChain";
            default -> scopeType;
        };
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
