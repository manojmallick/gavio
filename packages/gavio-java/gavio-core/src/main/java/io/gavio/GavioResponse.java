package io.gavio;

import io.gavio.types.CacheType;
import io.gavio.types.FinishReason;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
import io.gavio.types.ToolCall;
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
 *
 * <p>{@code embeddings} is set on embedding calls only (F-SEC-10): one vector per
 * input text, null for completions.
 *
 * <p>{@code toolCalls} are the tool calls the model requested, in provider order, and
 * {@code finishReason} the normalised stop reason (F-ADP-03); {@code content} is empty when the
 * model only called tools. Feed {@link #toAssistantMessage()} and the tool results back into the
 * next request.
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
        Map<String, Object> metadata,
        List<List<Double>> embeddings,
        List<ToolCall> toolCalls,
        FinishReason finishReason) {

    public GavioResponse {
        if (usage == null) {
            usage = new TokenUsage();
        }
        if (modelVersion == null) {
            modelVersion = "";
        }
        interceptorsFired = interceptorsFired == null ? List.of() : List.copyOf(interceptorsFired);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A response without tool calls — the shape before F-ADP-03. */
    public GavioResponse(
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
            Map<String, Object> metadata,
            List<List<Double>> embeddings) {
        this(traceId, content, model, provider, modelVersion, usage, costUsd, latencyMs,
                cacheHit, cacheType, interceptorsFired, audit, metadata, embeddings, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Return a copy with replaced content (PII restore, guardrails). */
    public GavioResponse withContent(String newContent) {
        return new GavioResponse(traceId, newContent, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, audit, metadata, embeddings, toolCalls, finishReason);
    }

    /** Return a copy with the interceptor-fired list set. */
    public GavioResponse withInterceptorsFired(List<String> fired) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                fired, audit, metadata, embeddings, toolCalls, finishReason);
    }

    /** Return a copy with the audit record attached. */
    public GavioResponse withAudit(Object auditRecord) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, auditRecord, metadata, embeddings, toolCalls, finishReason);
    }

    /** Return a copy with embedding vectors attached (F-SEC-10): one per input text. */
    public GavioResponse withEmbeddings(List<List<Double>> vectors) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, audit, metadata, vectors, toolCalls, finishReason);
    }

    /** Return a copy with replaced tool calls (PII restore). */
    public GavioResponse withToolCalls(List<ToolCall> newToolCalls) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, audit, metadata, embeddings, newToolCalls, finishReason);
    }

    /** Return a copy with a different normalised stop reason. */
    public GavioResponse withFinishReason(FinishReason newFinishReason) {
        return new GavioResponse(traceId, content, model, provider, modelVersion,
                usage, costUsd, latencyMs, cacheHit, cacheType,
                interceptorsFired, audit, metadata, embeddings, toolCalls, newFinishReason);
    }

    /**
     * The assistant message to append to the conversation before the tool results: the content
     * and, when the model called tools, its {@code toolCalls}.
     */
    public Message toAssistantMessage() {
        return new Message("assistant", content, toolCalls, null, null, false);
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
        private List<List<Double>> embeddings;
        private List<ToolCall> toolCalls = List.of();
        private FinishReason finishReason;

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

        public Builder embeddings(List<List<Double>> embeddings) {
            this.embeddings = embeddings;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public GavioResponse build() {
            return new GavioResponse(traceId, content, model, provider, modelVersion,
                    usage, costUsd, latencyMs, cacheHit, cacheType,
                    interceptorsFired, audit, metadata, embeddings, toolCalls, finishReason);
        }
    }
}
