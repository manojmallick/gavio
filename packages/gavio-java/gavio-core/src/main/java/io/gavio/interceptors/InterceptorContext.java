package io.gavio.interceptors;

import io.gavio.GavioRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable scratch space shared by all interceptors within one request.
 *
 * <p>One instance per request — never shared across requests or threads.
 * Interceptors stash signals here (PII findings, cache decisions, risk scores)
 * for the audit interceptor to collect at the end of the chain.
 */
public final class InterceptorContext {

    private static final List<String> COST_DIMENSION_KEYS = List.of(
            "tenant", "feature", "user", "endpoint", "environment", "workflow", "tool");

    private final String traceId;
    private String agentId;
    private String parentTraceId;
    private String sessionId;
    private boolean dryRun;

    private String tenant;
    private String feature;
    private final Map<String, Object> cost = new LinkedHashMap<>();
    private final Map<String, Object> retry = new LinkedHashMap<>();
    private final Map<String, Object> tools = new LinkedHashMap<>();
    private final Map<String, Object> policy = new LinkedHashMap<>();

    private final List<String> interceptorsFired = new ArrayList<>();
    private final List<String> piiEntityTypes = new ArrayList<>();
    private final Map<String, Integer> piiEntityCounts = new HashMap<>();
    private boolean cacheHit;
    private String cacheType;
    private Double riskScore;
    private String guardrailOutcome;

    /** Arbitrary inter-interceptor state (e.g. PII replacement map for restore). */
    private final Map<String, Object> state = new HashMap<>();

    /** Pending inspector decision records; drained per hook by the TraceEmitter (F-DX-09). */
    private final Map<String, Object> inspectPending = new LinkedHashMap<>();

    /** Pending governance events (e.g. drift alerts, F-GOV-07); drained per hook by the emitter. */
    private final List<Map<String, Object>> governancePending = new ArrayList<>();

    public InterceptorContext(String traceId) {
        this.traceId = traceId;
    }

    /** Create a context from a request, including first-class runtime metadata. */
    public static InterceptorContext fromRequest(GavioRequest request, boolean dryRun) {
        RuntimeFields runtime = runtimeFields(request.metadata());
        InterceptorContext ctx = new InterceptorContext(request.traceId())
                .agentId(request.agentId())
                .parentTraceId(request.parentTraceId())
                .sessionId(request.sessionId())
                .dryRun(dryRun)
                .tenant(runtime.tenant())
                .feature(runtime.feature());
        ctx.cost().putAll(runtime.cost());
        ctx.retry().putAll(runtime.retry());
        ctx.tools().putAll(runtime.tools());
        ctx.policy().putAll(runtime.policy());
        return ctx;
    }

    public String traceId() {
        return traceId;
    }

    public String agentId() {
        return agentId;
    }

    public InterceptorContext agentId(String agentId) {
        this.agentId = agentId;
        return this;
    }

    public String parentTraceId() {
        return parentTraceId;
    }

    public InterceptorContext parentTraceId(String parentTraceId) {
        this.parentTraceId = parentTraceId;
        return this;
    }

    public String sessionId() {
        return sessionId;
    }

    public InterceptorContext sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public InterceptorContext dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public String tenant() {
        return tenant;
    }

    public InterceptorContext tenant(String tenant) {
        this.tenant = tenant;
        return this;
    }

    public String feature() {
        return feature;
    }

    public InterceptorContext feature(String feature) {
        this.feature = feature;
        return this;
    }

    public Map<String, Object> cost() {
        return cost;
    }

    public Map<String, Object> retry() {
        return retry;
    }

    public Map<String, Object> tools() {
        return tools;
    }

    public Map<String, Object> policy() {
        return policy;
    }

    public List<String> interceptorsFired() {
        return interceptorsFired;
    }

    public List<String> piiEntityTypes() {
        return piiEntityTypes;
    }

    public Map<String, Integer> piiEntityCounts() {
        return piiEntityCounts;
    }

    public boolean cacheHit() {
        return cacheHit;
    }

    public void cacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String cacheType() {
        return cacheType;
    }

    public void cacheType(String cacheType) {
        this.cacheType = cacheType;
    }

    public Double riskScore() {
        return riskScore;
    }

    public void riskScore(Double riskScore) {
        this.riskScore = riskScore;
    }

    public String guardrailOutcome() {
        return guardrailOutcome;
    }

    public void guardrailOutcome(String guardrailOutcome) {
        this.guardrailOutcome = guardrailOutcome;
    }

    public Map<String, Object> state() {
        return state;
    }

    /**
     * Record a decision for the inspector (F-DX-09). Entries are attached to the
     * current hook's {@code interceptor.*.end} event as its {@code decision}
     * object. A harmless no-op when the inspector is disabled.
     */
    public void inspect(String key, Object value) {
        inspectPending.put(key, value);
    }

    /** Drain (return and clear) the pending inspector decision entries. */
    public Map<String, Object> drainInspections() {
        if (inspectPending.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(inspectPending);
        inspectPending.clear();
        return out;
    }

    /**
     * Queue a governance event (e.g. a drift alert, F-GOV-07) to surface on the
     * inspector as a standalone {@code governance.event}. A harmless no-op when
     * the inspector is disabled — the emitter drains these per hook.
     */
    public void recordGovernanceEvent(Map<String, Object> data) {
        governancePending.add(data);
    }

    /** Drain (return and clear) the pending governance events. */
    public List<Map<String, Object>> drainGovernance() {
        if (governancePending.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(governancePending);
        governancePending.clear();
        return out;
    }

    public void markFired(String name) {
        if (!interceptorsFired.contains(name)) {
            interceptorsFired.add(name);
        }
    }

    public void recordPii(List<String> entityTypes) {
        for (String et : entityTypes) {
            piiEntityCounts.merge(et, 1, Integer::sum);
            if (!piiEntityTypes.contains(et)) {
                piiEntityTypes.add(et);
            }
        }
    }

    private static RuntimeFields runtimeFields(Map<String, Object> metadata) {
        Map<String, Object> source = metadata == null ? Map.of() : metadata;
        Map<String, Object> cost = section(source, "cost", "costContext", "cost_context");
        Map<String, Object> dimensions = dimensions(source, cost);

        String tenant = firstScalar(source, "tenant", "tenantId", "tenant_id");
        if (tenant == null) {
            tenant = firstScalar(cost, "tenant", "tenantId", "tenant_id");
        }
        if (tenant == null) {
            tenant = firstScalar(dimensions, "tenant", "tenantId", "tenant_id");
        }

        String feature = firstScalar(source, "feature", "featureId", "feature_id");
        if (feature == null) {
            feature = firstScalar(cost, "feature", "featureId", "feature_id");
        }
        if (feature == null) {
            feature = firstScalar(dimensions, "feature", "featureId", "feature_id");
        }

        if (!dimensions.isEmpty()) {
            cost.put("dimensions", dimensions);
        }
        if (tenant != null) {
            cost.putIfAbsent("tenant", tenant);
        }
        if (feature != null) {
            cost.putIfAbsent("feature", feature);
        }

        return new RuntimeFields(
                tenant,
                feature,
                cost,
                section(source, "retry", "retryContext", "retry_context"),
                section(source, "tools", "toolContext", "tool_context"),
                section(source, "policy", "policyContext", "policy_context"));
    }

    private static Map<String, Object> section(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof Map<?, ?> map) {
                return copyMap(map);
            }
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> dimensions(
            Map<String, Object> metadata, Map<String, Object> cost) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object existing = cost.get("dimensions");
        if (existing instanceof Map<?, ?> map) {
            out.putAll(copyMap(map));
        }
        for (String key : COST_DIMENSION_KEYS) {
            if (metadata.containsKey(key)) {
                out.put(key, metadata.get(key));
            }
        }
        for (String key : List.of("costDimensions", "cost_dimensions")) {
            Object value = metadata.get(key);
            if (value instanceof Map<?, ?> map) {
                out.putAll(copyMap(map));
            }
        }
        return out;
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

    private static String firstScalar(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null
                    && !(value instanceof Map<?, ?>)
                    && !(value instanceof Iterable<?>)
                    && !value.getClass().isArray()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private record RuntimeFields(
            String tenant,
            String feature,
            Map<String, Object> cost,
            Map<String, Object> retry,
            Map<String, Object> tools,
            Map<String, Object> policy) {
    }
}
