package io.gavio;

import io.gavio.GavioException.ConfigurationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root run resolution (F-REL-09, shared with the planned F-AGT-01 task records).
 *
 * <p>A <em>root run</em> is every provider call that belongs to one agent task. Each call is
 * counted under a root run id, resolved per request in this order:
 *
 * <ol>
 *   <li>{@code metadata.runId} (or {@code metadata.run_id}) when it is a non-empty string;
 *   <li>else the root of the {@code parentTraceId} chain;
 *   <li>else the request's own {@code traceId}.
 * </ol>
 *
 * <p>The chain root comes from a bounded least-recently-used map of {@code traceId -> root run
 * id}. A parent trace id the map does not know — evicted, or from another process — is taken for
 * the root itself, so pass {@code metadata.runId} when a run spans processes or outlives the map.
 * Pure: no clock, no I/O; safe to share between threads. Behaviour is pinned by
 * {@code test-vectors/loop-guard/decisions.json} ({@code rootRunCases}) and described in
 * {@code spec/README.md} ('Loop guard'). Port of {@code gavio.root_run} (Python).
 */
public final class RootRunResolver {

    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final int maxEntries;
    // Insertion order is recency order: the first entry is the least recently used.
    private final LinkedHashMap<String, String> roots = new LinkedHashMap<>();

    public RootRunResolver() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public RootRunResolver(int maxEntries) {
        if (maxEntries < 1) {
            throw new ConfigurationException("maxEntries must be an integer of at least 1");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * The run id a caller set in request metadata, or {@code null}. Blank and non-string values
     * are ignored, so a stray {@code runId: 42} cannot merge runs.
     */
    public static String explicitRunId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        for (String key : new String[] {"runId", "run_id"}) {
            if (metadata.get(key) instanceof String value && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** The root run id of this call; also records {@code traceId} so children resolve to it. */
    public synchronized String resolve(
            String traceId, String parentTraceId, Map<String, Object> metadata) {
        String explicit = explicitRunId(metadata);
        String root;
        if (explicit != null) {
            root = explicit;
        } else if (parentTraceId != null && !parentTraceId.isEmpty()) {
            String known = roots.remove(parentTraceId); // reading a parent refreshes it
            if (known != null) {
                roots.put(parentTraceId, known);
            }
            root = known != null ? known : parentTraceId;
        } else {
            root = traceId;
        }
        roots.remove(traceId);
        roots.put(traceId, root);
        while (roots.size() > maxEntries) {
            String oldest = roots.keySet().iterator().next();
            roots.remove(oldest);
        }
        return root;
    }

    public synchronized int size() {
        return roots.size();
    }
}
