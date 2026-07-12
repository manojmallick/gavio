package io.gavio.trust;

import java.util.List;

/** Verification result for a Production Trust Bundle. */
public record ProductionTrustVerification(
        boolean valid,
        List<String> errors,
        String computedHash) {
}
