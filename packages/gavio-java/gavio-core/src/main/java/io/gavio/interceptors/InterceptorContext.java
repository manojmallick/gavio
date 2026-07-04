package io.gavio.interceptors;

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

    private final String traceId;
    private String agentId;
    private String parentTraceId;
    private String sessionId;
    private boolean dryRun;

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
}
