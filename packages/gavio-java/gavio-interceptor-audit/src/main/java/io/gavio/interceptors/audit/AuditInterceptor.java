package io.gavio.interceptors.audit;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.audit.sinks.StdoutSink;
import io.gavio.types.CacheType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures a full record of every call (F-OBS-01).
 *
 * <p>Register this as the outermost interceptor so its {@code after} runs last
 * and sees the final, fully-processed response. It hashes the (already PII-
 * redacted) prompt in {@code before} and the response in {@code after} — content
 * is never stored, only digests and metadata.
 */
public final class AuditInterceptor implements Interceptor {

    private static final Logger LOG = Logger.getLogger("gavio.audit");
    private static final String PROMPT_HASH_KEY = "audit_prompt_hash";

    private final AuditSink sink;

    public AuditInterceptor() {
        this(new StdoutSink());
    }

    public AuditInterceptor(AuditSink sink) {
        this.sink = sink != null ? sink : new StdoutSink();
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public boolean dryRunSafe() {
        return true;
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        ctx.state().put(PROMPT_HASH_KEY, AuditRecord.hashText(request.promptText()));
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        Object promptHash = ctx.state().get(PROMPT_HASH_KEY);
        CacheType ct = response.cacheType();
        AuditRecord record = AuditRecord.builder()
                .traceId(response.traceId())
                .parentTraceId(ctx.parentTraceId())
                .agentId(ctx.agentId())
                .sessionId(ctx.sessionId())
                .timestampUtc(AuditRecord.nowUtc())
                .provider(response.provider())
                .model(response.model())
                .modelVersion(response.modelVersion())
                .promptHash(promptHash != null ? promptHash.toString() : "")
                .responseHash(AuditRecord.hashText(response.content()))
                .tokenUsage(response.usage())
                .costUsd(response.costUsd())
                .latencyMs(response.latencyMs())
                .piiEntityTypes(new ArrayList<>(ctx.piiEntityTypes()))
                .piiEntityCounts(new HashMap<>(ctx.piiEntityCounts()))
                .interceptorsFired(new ArrayList<>(ctx.interceptorsFired()))
                .cacheHit(response.cacheHit())
                .cacheType(ct != null ? ct.value() : null)
                .guardrailOutcome(ctx.guardrailOutcome())
                .riskScore(ctx.riskScore())
                .build();

        GavioResponse withAudit = response.withAudit(record);
        try {
            sink.write(record).join();
        } catch (Exception e) {
            // Auditing must never break the call.
            LOG.log(Level.WARNING, "audit sink write failed for trace " + record.traceId(), e);
        }
        return CompletableFuture.completedFuture(withAudit);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link AuditInterceptor}. */
    public static final class Builder {
        private AuditSink sink = new StdoutSink();

        public Builder sink(AuditSink sink) {
            this.sink = sink;
            return this;
        }

        public AuditInterceptor build() {
            return new AuditInterceptor(sink);
        }
    }
}
