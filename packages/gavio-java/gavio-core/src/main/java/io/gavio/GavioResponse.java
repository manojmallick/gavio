package io.gavio;

import io.gavio.types.CacheType;
import io.gavio.types.TokenUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical response returned to the caller, enriched by the post-interceptor
 * pipeline. Immutable record; mutations are expressed as {@code with*} copies.
 *
 * <p>The {@code audit} field is typed as {@link Object} so {@code gavio-core}
 * stays free of a dependency on {@code gavio-interceptor-audit}. Callers cast it
 * to {@code AuditRecord} when the audit interceptor is in the chain.
 */
public record GavioResponse(
        String traceId,
        String content,
        String model,
        String provider,
        String modelVersion,
        TokenUsage usage,
        double costUsd,
        long latencyMs,
        boolean cacheHit,
        CacheType cacheType,
        List<String> interceptorsFired,
        Object audit,
        Map<String, Object> metadata) {

    public GavioResponse {
        if (usage == null) {
            usage = new TokenUsage();
        }
        if (modelVersion == null) {
            modelVersion = "";
        }
        interceptorsFired = interceptorsFired == null ? List.of() : List.copyOf(interceptorsFired);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Return a copy with replaced content (PII restore, guardrails). */
    public GavioResponse withContent(String newContent) {
        return new GavioResponse(traceId, newContent, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, audit, metadata);
    }

    /** Return a copy with the interceptor-fired list set. */
    public GavioResponse withInterceptorsFired(List<String> fired) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                fired, audit, metadata);
    }

    /** Return a copy with the audit record attached. */
    public GavioResponse withAudit(Object auditRecord) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, auditRecord, metadata);
    }

    /** Fluent builder for {@link GavioResponse}. */
    public static final class Builder {
        private String traceId;
        private String content = "";
        private String model = "";
        private String provider = "";
        private String modelVersion = "";
        private TokenUsage usage = new TokenUsage();
        private double costUsd;
        private long latencyMs;
        private boolean cacheHit;
        private CacheType cacheType;
        private List<String> interceptorsFired = List.of();
        private Object audit;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder modelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public Builder usage(TokenUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder costUsd(double costUsd) {
            this.costUsd = costUsd;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder cacheHit(boolean cacheHit) {
            this.cacheHit = cacheHit;
            return this;
        }

        public Builder cacheType(CacheType cacheType) {
            this.cacheType = cacheType;
            return this;
        }

        public Builder interceptorsFired(List<String> fired) {
            this.interceptorsFired = fired;
            return this;
        }

        public Builder audit(Object audit) {
            this.audit = audit;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public GavioResponse build() {
            return new GavioResponse(traceId, content, model, provider, modelVersion,
                    usage, costUsd, latencyMs, cacheHit, cacheType,
                    interceptorsFired, audit, metadata);
        }
    }
}
