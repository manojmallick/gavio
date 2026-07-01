package io.gavio.interceptors.pii;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request state shared across PII scanners.
 *
 * <p>Tracks a monotonic per-entity-type index so repeated entities get stable,
 * distinct placeholders ({@code [EMAIL_1]}, {@code [EMAIL_2]}).
 */
public final class ScanContext {

    private final String language;
    private final String locale;
    private final Map<String, Integer> counters = new HashMap<>();

    public ScanContext() {
        this("en", "NL");
    }

    public ScanContext(String language, String locale) {
        this.language = language;
        this.locale = locale;
    }

    public String language() {
        return language;
    }

    public String locale() {
        return locale;
    }

    /** Return the next 1-based index for an entity type. */
    public int nextIndex(String entityType) {
        int next = counters.getOrDefault(entityType, 0) + 1;
        counters.put(entityType, next);
        return next;
    }
}
