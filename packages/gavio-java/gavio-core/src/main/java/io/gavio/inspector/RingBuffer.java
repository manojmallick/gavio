package io.gavio.inspector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bounded, in-memory trace store fed by the {@link InspectorBus} (F-DX-09).
 *
 * <p>Assembles events by traceId into traces (summary + ordered events).
 * Oldest traces are evicted beyond {@code maxTraces}; each trace keeps at most
 * {@value #MAX_EVENTS_PER_TRACE} events. All access is synchronized — the
 * gateway emits from arbitrary threads and the HTTP server reads concurrently.
 */
public final class RingBuffer implements Consumer<InspectorEvent> {

    static final int MAX_EVENTS_PER_TRACE = 500;

    private final int maxTraces;
    /** Insertion-ordered: iteration order == chronological (trace.start) order. */
    private final LinkedHashMap<String, Entry> traces = new LinkedHashMap<>();

    private static final class Entry {
        final Map<String, Object> summary = new LinkedHashMap<>();
        final List<InspectorEvent> events = new ArrayList<>();
    }

    public RingBuffer(int maxTraces) {
        this.maxTraces = Math.max(1, maxTraces);
    }

    @Override
    public synchronized void accept(InspectorEvent event) {
        Entry entry = traces.get(event.traceId());
        if (entry == null) {
            entry = new Entry();
            entry.summary.put("traceId", event.traceId());
            entry.summary.put("status", "pending");
            traces.put(event.traceId(), entry);
            while (traces.size() > maxTraces) {
                Iterator<String> oldest = traces.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        if (entry.events.size() < MAX_EVENTS_PER_TRACE) {
            entry.events.add(event);
        }
        updateSummary(entry.summary, event);
    }

    private static void updateSummary(Map<String, Object> summary, InspectorEvent event) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case "trace.start" -> {
                summary.put("parentTraceId", data.get("parentTraceId"));
                summary.put("agentId", data.get("agentId"));
                summary.put("sessionId", data.get("sessionId"));
                summary.put("provider", data.get("provider"));
                summary.put("model", data.get("model"));
                summary.put("wallTimeUtc", data.get("wallTimeUtc"));
            }
            case "trace.end" -> {
                summary.put("status", data.getOrDefault("status", "ok"));
                summary.put("latencyMs", data.get("latencyMs"));
                summary.put("costUsd", data.get("costUsd"));
                summary.put("cacheHit", data.get("cacheHit"));
                summary.put("cacheType", data.get("cacheType"));
                summary.put("piiEntityTypes", data.getOrDefault("piiEntityTypes", List.of()));
                summary.put("interceptorsFired", data.getOrDefault("interceptorsFired", List.of()));
            }
            default -> {
                // Other event types only contribute to the event list.
            }
        }
    }

    /** Number of traces currently held. */
    public synchronized int size() {
        return traces.size();
    }

    public synchronized boolean contains(String traceId) {
        return traces.containsKey(traceId);
    }

    /**
     * Trace summaries in chronological (ascending) order. A positive
     * {@code limit} keeps only the most recent N, still ascending.
     */
    public synchronized List<Map<String, Object>> summaries(int limit) {
        List<Map<String, Object>> all = new ArrayList<>(traces.size());
        for (Entry entry : traces.values()) {
            all.add(new LinkedHashMap<>(entry.summary));
        }
        if (limit > 0 && all.size() > limit) {
            return new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
        return all;
    }

    /** One trace as {@code {summary, events}}, or null when unknown/evicted. */
    public synchronized Map<String, Object> trace(String traceId) {
        Entry entry = traces.get(traceId);
        if (entry == null) {
            return null;
        }
        List<Map<String, Object>> events = new ArrayList<>(entry.events.size());
        for (InspectorEvent e : entry.events) {
            events.add(e.toMap());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", new LinkedHashMap<>(entry.summary));
        out.put("events", events);
        return out;
    }
}
