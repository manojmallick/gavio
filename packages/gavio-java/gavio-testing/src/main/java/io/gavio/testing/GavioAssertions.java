package io.gavio.testing;

import io.gavio.interceptors.audit.AuditRecord;

/** Static assertions for Gavio test results (no JUnit dependency). */
public final class GavioAssertions {

    private GavioAssertions() {
    }

    public static void assertPiiDetected(GavioTestResult result, String entityType) {
        if (!result.piiDetected(entityType)) {
            throw new AssertionError("Expected PII entity '" + entityType
                    + "' to be detected, but it was not. Detected: " + result.context().piiEntityTypes());
        }
    }

    public static void assertNotContains(String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError("Expected text not to contain '" + needle + "', but it did.");
        }
    }

    public static void assertContains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("Expected text to contain '" + needle + "', but it did not.");
        }
    }

    public static void assertAuditEntityType(GavioTestResult result, String entityType) {
        AuditRecord record = result.auditRecord();
        if (record == null) {
            throw new AssertionError("Expected an audit record, but none was attached "
                    + "(add an AuditInterceptor to the chain).");
        }
        if (!record.piiEntityTypes().contains(entityType)) {
            throw new AssertionError("Expected audit to record PII entity '" + entityType
                    + "', but recorded: " + record.piiEntityTypes());
        }
    }
}
