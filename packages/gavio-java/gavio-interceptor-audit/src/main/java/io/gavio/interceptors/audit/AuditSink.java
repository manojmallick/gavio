package io.gavio.interceptors.audit;

import java.util.concurrent.CompletableFuture;

/** Where audit records go. Implement {@code write} to add a backend. */
public interface AuditSink {

    CompletableFuture<Void> write(AuditRecord record);

    /** Flush/close any resources. Default no-op. */
    default CompletableFuture<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
