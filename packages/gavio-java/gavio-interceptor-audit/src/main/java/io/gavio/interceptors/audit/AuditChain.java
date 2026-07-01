package io.gavio.interceptors.audit;

import java.util.List;

/** Hash-chain verification (F-OBS-02). */
public final class AuditChain {

    private AuditChain() {}

    /**
     * Return true if the records form an intact hash chain. Each record's
     * previousHash must equal the content hash of the record before it; the
     * first must be empty. Any edit, reorder, or deletion breaks the chain.
     */
    public static boolean verifyChain(List<AuditRecord> records) {
        String prevHash = "";
        for (AuditRecord rec : records) {
            if (!rec.previousHash().equals(prevHash)) {
                return false;
            }
            prevHash = rec.contentHash();
        }
        return true;
    }
}
