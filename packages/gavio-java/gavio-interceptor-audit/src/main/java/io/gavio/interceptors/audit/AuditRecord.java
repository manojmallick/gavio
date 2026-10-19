package io.gavio.interceptors.audit;

import io.gavio.json.Json;
import io.gavio.types.PromptLineage;
import io.gavio.types.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One append-only audit entry. Carries metadata only — never raw content.
 *
 * <p>{@code promptHash}/{@code responseHash} are SHA-256 digests so the entry is
 * verifiable without storing sensitive text. {@code previousHash} is reserved
 * for the v0.2.0 hash-chain (F-OBS-02); empty in v0.1.0.
 *
 * <p>{@code toolCalls} lists the tool calls the model requested (F-ADP-03) as a name and an
 * arguments hash only — never the arguments, which may hold PII. It is left out of {@link #toMap()}
 * (and so out of the hash-chain input) when empty, so chains written before F-ADP-03 still verify.
 *
 * <p>{@code loopGuard} is the loop guard's verdict on this call (F-REL-09): {@code policy_id},
 * {@code run_id}, {@code action}, {@code breached}, {@code steps}, {@code cost_usd} and
 * {@code elapsed_seconds} — counts only, never tool arguments. It is left out of {@link #toMap()}
 * when {@code null} (no guard ran), so existing hash chains still verify.
 */
public record AuditRecord(
        String traceId,
        String provider,
        String model,
        String timestampUtc,
        String parentTraceId,
        String agentId,
        String sessionId,
        String subjectId,
        String modelVersion,
        String promptHash,
        String responseHash,
        TokenUsage tokenUsage,
        double costUsd,
        long latencyMs,
        List<String> piiEntityTypes,
        Map<String, Integer> piiEntityCounts,
        List<String> interceptorsFired,
        boolean cacheHit,
        String cacheType,
        String guardrailOutcome,
        Double riskScore,
        PromptLineage lineage,
        String previousHash,
        String schemaVersion,
        List<ToolCallDigest> toolCalls,
        Map<String, Object> loopGuard) {

    public static final String SCHEMA_VERSION = "1.0";

    /**
     * One tool call a response requested: its name and the lowercase hex SHA-256 of the canonical
     * JSON of its arguments ({@code ToolCall.argumentsHash()}).
     */
    public record ToolCallDigest(String name, String argumentsHash) {

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("arguments_hash", argumentsHash);
            return data;
        }
    }

    public AuditRecord {
        if (tokenUsage == null) {
            tokenUsage = new TokenUsage();
        }
        piiEntityTypes = piiEntityTypes == null ? List.of() : List.copyOf(piiEntityTypes);
        piiEntityCounts = piiEntityCounts == null ? Map.of() : Map.copyOf(piiEntityCounts);
        interceptorsFired = interceptorsFired == null ? List.of() : List.copyOf(interceptorsFired);
        if (modelVersion == null) {
            modelVersion = "";
        }
        if (promptHash == null) {
            promptHash = "";
        }
        if (responseHash == null) {
            responseHash = "";
        }
        if (previousHash == null) {
            previousHash = "";
        }
        if (schemaVersion == null) {
            schemaVersion = SCHEMA_VERSION;
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        loopGuard = loopGuard == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(loopGuard));
    }

    /** A record without tool calls — the shape before F-ADP-03. */
    public AuditRecord(
            String traceId,
            String provider,
            String model,
            String timestampUtc,
            String parentTraceId,
            String agentId,
            String sessionId,
            String subjectId,
            String modelVersion,
            String promptHash,
            String responseHash,
            TokenUsage tokenUsage,
            double costUsd,
            long latencyMs,
            List<String> piiEntityTypes,
            Map<String, Integer> piiEntityCounts,
            List<String> interceptorsFired,
            boolean cacheHit,
            String cacheType,
            String guardrailOutcome,
            Double riskScore,
            PromptLineage lineage,
            String previousHash,
            String schemaVersion) {
        this(traceId, provider, model, timestampUtc, parentTraceId, agentId, sessionId, subjectId,
                modelVersion, promptHash, responseHash, tokenUsage, costUsd, latencyMs, piiEntityTypes,
                piiEntityCounts, interceptorsFired, cacheHit, cacheType, guardrailOutcome, riskScore,
                lineage, previousHash, schemaVersion, null, null);
    }

    public static String nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    public static String hashText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trace_id", traceId);
        data.put("provider", provider);
        data.put("model", model);
        data.put("timestamp_utc", timestampUtc);
        data.put("parent_trace_id", parentTraceId);
        data.put("agent_id", agentId);
        data.put("session_id", sessionId);
        data.put("subject_id", subjectId);
        data.put("model_version", modelVersion);
        data.put("prompt_hash", promptHash);
        data.put("response_hash", responseHash);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", tokenUsage.promptTokens());
        usage.put("completion_tokens", tokenUsage.completionTokens());
        usage.put("total_tokens", tokenUsage.totalTokens());
        data.put("token_usage", usage);
        data.put("cost_usd", costUsd);
        data.put("latency_ms", latencyMs);
        data.put("pii_entity_types", new ArrayList<>(piiEntityTypes));
        data.put("pii_entity_counts", new LinkedHashMap<>(piiEntityCounts));
        data.put("interceptors_fired", new ArrayList<>(interceptorsFired));
        data.put("cache_hit", cacheHit);
        data.put("cache_type", cacheType);
        data.put("guardrail_outcome", guardrailOutcome);
        data.put("risk_score", riskScore);
        data.put("lineage", lineage == null ? null : lineage.toMap());
        if (!toolCalls.isEmpty()) {
            List<Object> digests = new ArrayList<>();
            for (ToolCallDigest digest : toolCalls) {
                digests.add(digest.toMap());
            }
            data.put("tool_calls", digests);
        }
        if (loopGuard != null) {
            data.put("loop_guard", new LinkedHashMap<>(loopGuard));
        }
        data.put("previous_hash", previousHash);
        data.put("schema_version", schemaVersion);
        return data;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** Hash of this record's content — used to build the v0.2.0 chain. */
    public String contentHash() {
        return hashText(toJson());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link AuditRecord}. */
    public static final class Builder {
        private String traceId;
        private String provider;
        private String model;
        private String timestampUtc = nowUtc();
        private String parentTraceId;
        private String agentId;
        private String sessionId;
        private String subjectId;
        private String modelVersion = "";
        private String promptHash = "";
        private String responseHash = "";
        private TokenUsage tokenUsage = new TokenUsage();
        private double costUsd;
        private long latencyMs;
        private List<String> piiEntityTypes = List.of();
        private Map<String, Integer> piiEntityCounts = Map.of();
        private List<String> interceptorsFired = List.of();
        private boolean cacheHit;
        private String cacheType;
        private String guardrailOutcome;
        private Double riskScore;
        private PromptLineage lineage;
        private String previousHash = "";
        private List<ToolCallDigest> toolCalls = List.of();
        private Map<String, Object> loopGuard;

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        public Builder provider(String v) {
            this.provider = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder timestampUtc(String v) {
            this.timestampUtc = v;
            return this;
        }

        public Builder parentTraceId(String v) {
            this.parentTraceId = v;
            return this;
        }

        public Builder agentId(String v) {
            this.agentId = v;
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder subjectId(String v) {
            this.subjectId = v;
            return this;
        }

        public Builder modelVersion(String v) {
            this.modelVersion = v;
            return this;
        }

        public Builder promptHash(String v) {
            this.promptHash = v;
            return this;
        }

        public Builder responseHash(String v) {
            this.responseHash = v;
            return this;
        }

        public Builder tokenUsage(TokenUsage v) {
            this.tokenUsage = v;
            return this;
        }

        public Builder costUsd(double v) {
            this.costUsd = v;
            return this;
        }

        public Builder latencyMs(long v) {
            this.latencyMs = v;
            return this;
        }

        public Builder piiEntityTypes(List<String> v) {
            this.piiEntityTypes = v;
            return this;
        }

        public Builder piiEntityCounts(Map<String, Integer> v) {
            this.piiEntityCounts = v;
            return this;
        }

        public Builder interceptorsFired(List<String> v) {
            this.interceptorsFired = v;
            return this;
        }

        public Builder cacheHit(boolean v) {
            this.cacheHit = v;
            return this;
        }

        public Builder cacheType(String v) {
            this.cacheType = v;
            return this;
        }

        public Builder guardrailOutcome(String v) {
            this.guardrailOutcome = v;
            return this;
        }

        public Builder riskScore(Double v) {
            this.riskScore = v;
            return this;
        }

        public Builder lineage(PromptLineage v) {
            this.lineage = v;
            return this;
        }

        public Builder previousHash(String v) {
            this.previousHash = v;
            return this;
        }

        public Builder toolCalls(List<ToolCallDigest> v) {
            this.toolCalls = v;
            return this;
        }

        /** The loop guard's verdict on this call (F-REL-09); {@code null} when no guard ran. */
        public Builder loopGuard(Map<String, Object> v) {
            this.loopGuard = v;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(traceId, provider, model, timestampUtc, parentTraceId,
                    agentId, sessionId, subjectId, modelVersion, promptHash, responseHash,
                    tokenUsage, costUsd, latencyMs, piiEntityTypes, piiEntityCounts,
                    interceptorsFired, cacheHit, cacheType, guardrailOutcome, riskScore, lineage,
                    previousHash, SCHEMA_VERSION, toolCalls, loopGuard);
        }
    }
}
