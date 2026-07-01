package io.gavio.testing;

import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.audit.AuditRecord;

/** The outcome of a {@link GavioTestKit} run: response, context and the captured request. */
public final class GavioTestResult {

    private final GavioResponse response;
    private final InterceptorContext context;
    private final GavioRequest capturedRequest;

    public GavioTestResult(GavioResponse response, InterceptorContext context, GavioRequest capturedRequest) {
        this.response = response;
        this.context = context;
        this.capturedRequest = capturedRequest;
    }

    public GavioResponse response() {
        return response;
    }

    public InterceptorContext context() {
        return context;
    }

    /** The request as it reached the provider (post-redaction). */
    public GavioRequest redactedRequest() {
        return capturedRequest;
    }

    /** Concatenated text of the request that reached the provider. */
    public String preRequestText() {
        return capturedRequest != null ? capturedRequest.promptText() : "";
    }

    public AuditRecord auditRecord() {
        return response != null && response.audit() instanceof AuditRecord ar ? ar : null;
    }

    public boolean piiDetected(String entityType) {
        if (context == null) {
            return false;
        }
        if (entityType == null) {
            return !context.piiEntityTypes().isEmpty();
        }
        return context.piiEntityTypes().contains(entityType);
    }
}
