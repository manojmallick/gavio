package io.gavio.interceptors.audit;

import java.util.concurrent.CompletableFuture;

/** Where audit records go. Implement {@code write} to add a backend. */
public interface AuditSink {

    CompletableFuture<Void> write(AuditRecord record);

    /**
     * Erase records for a data subject (GDPR Art. 17, F-QUA-09). Remove every
     * persisted record whose {@code subjectId} matches and return the number
     * removed. The default is a no-op returning 0 — appropriate for
     * non-persistent sinks (e.g. stdout). Persistent sinks override this.
     */
    default CompletableFuture<Integer> purge(String subjectId) {
        return CompletableFuture.completedFuture(0);
    }

    /** Flush/close any resources. Default no-op. */
    default CompletableFuture<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
