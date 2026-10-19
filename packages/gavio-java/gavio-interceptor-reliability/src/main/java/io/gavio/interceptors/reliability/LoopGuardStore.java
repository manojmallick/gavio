package io.gavio.interceptors.reliability;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.RootRunResolver;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Bounded, idle-expiring in-memory store of per-run state (F-REL-09).
 *
 * <p>Keeps at most {@code maxRuns} runs (least recently used evicted first) and forgets a run that
 * has been idle for {@code ttlSeconds}, so a long-lived process cannot grow without bound. A run
 * that expires starts again from zero if it comes back. Safe to share between threads; callers
 * that mutate a returned {@link LoopGuardState} must serialise those writes themselves (as
 * {@link LoopGuard} does).
 */
public final class LoopGuardStore {

    private record Entry(LoopGuardState state, double lastSeen) {
    }

    private final int maxRuns;
    private final double ttlSeconds;
    private final DoubleSupplier clock;
    // Insertion order is recency order: the first entry is the least recently used.
    private final LinkedHashMap<String, Entry> runs = new LinkedHashMap<>();

    public LoopGuardStore() {
        this(RootRunResolver.DEFAULT_MAX_ENTRIES, 3600.0, LoopGuard.monotonicSeconds());
    }

    public LoopGuardStore(DoubleSupplier clock) {
        this(RootRunResolver.DEFAULT_MAX_ENTRIES, 3600.0, clock);
    }

    /**
     * @param maxRuns most runs kept, at least 1
     * @param ttlSeconds seconds a run may idle before it is forgotten, greater than 0
     * @param clock seconds from any monotonic clock
     */
    public LoopGuardStore(int maxRuns, double ttlSeconds, DoubleSupplier clock) {
        if (maxRuns < 1) {
            throw new ConfigurationException("maxRuns must be an integer of at least 1");
        }
        if (!(ttlSeconds > 0)) {
            throw new ConfigurationException("ttlSeconds must be positive");
        }
        this.maxRuns = maxRuns;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /** The state of {@code runId}, created (and started now) if it is new or has expired. */
    public synchronized LoopGuardState acquire(String runId) {
        double now = clock.getAsDouble();
        expire(now);
        Entry existing = runs.remove(runId);
        LoopGuardState state = existing != null ? existing.state() : new LoopGuardState();
        if (existing == null) {
            state.startedAt(now);
        }
        runs.put(runId, new Entry(state, now));
        while (runs.size() > maxRuns) {
            runs.remove(runs.keySet().iterator().next());
        }
        return state;
    }

    /** The state of {@code runId} if it is still remembered (refreshing it), else {@code null}. */
    public synchronized LoopGuardState get(String runId) {
        double now = clock.getAsDouble();
        expire(now);
        Entry entry = runs.remove(runId);
        if (entry == null) {
            return null;
        }
        runs.put(runId, new Entry(entry.state(), now));
        return entry.state();
    }

    public synchronized int size() {
        return runs.size();
    }

    private void expire(double now) {
        // Entries are ordered by recency, so the idle ones are all at the front.
        Iterator<Map.Entry<String, Entry>> it = runs.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeen() < ttlSeconds) {
                break;
            }
            it.remove();
        }
    }
}
