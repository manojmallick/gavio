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
    private static final List<String> COST_DIMENSION_KEYS =
            List.of("feature", "tenant", "user", "endpoint", "environment", "workflow", "tool");

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
            applyCostDefaults(entry.summary);
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
                Map<String, Object> dimensions = costDimensions(data.get("costDimensions"));
                summary.put("costDimensions", dimensions);
                for (String key : COST_DIMENSION_KEYS) {
                    summary.put(key, dimensions.get(key));
                }
            }
            case "trace.end" -> {
                summary.put("status", data.getOrDefault("status", "ok"));
                summary.put("latencyMs", data.get("latencyMs"));
                summary.put("costUsd", data.get("costUsd"));
                summary.put("cacheHit", data.get("cacheHit"));
                summary.put("cacheType", data.get("cacheType"));
                summary.put("piiEntityTypes", data.getOrDefault("piiEntityTypes", List.of()));
                summary.put("interceptorsFired", data.getOrDefault("interceptorsFired", List.of()));
                if (summary.get("interceptorsFired") instanceof List<?> fired && !fired.isEmpty()) {
                    summary.put("middlewareChain", String.join(">", fired.stream().map(String::valueOf).toList()));
                } else {
                    summary.put("middlewareChain", null);
                }
                if (data.containsKey("cacheSavingsUsd")) {
                    summary.put("cacheSavingsUsd", data.get("cacheSavingsUsd"));
                }
            }
            case "provider.call.start" -> {
                long attempt = asLong(data.get("attempt"));
                if (attempt <= 0) {
                    attempt = asLong(summary.get("providerCallCount")) + 1;
                }
                summary.put("providerCallCount", Math.max(asLong(summary.get("providerCallCount")), attempt));
                summary.put("retryCount", Math.max(0L, asLong(summary.get("providerCallCount")) - 1L));
            }
            case "provider.call.end" -> {
                // Token usage feeds /api/stats and /api/simulate-cost.
                if (data.containsKey("usage")) {
                    summary.put("usage", data.get("usage"));
                }
                long attempt = asLong(data.get("attempt"));
                double costUsd = asDouble(data.get("costUsd"));
                if (attempt > 1 && costUsd > 0.0) {
                    summary.put("retryOverheadUsd", round8(asDouble(summary.get("retryOverheadUsd")) + costUsd));
                }
            }
            case "governance.event" -> {
                // Drift alerts (F-GOV-07) feed /api/stats.
                if ("drift".equals(data.get("kind")) && data.get("metric") instanceof String metric) {
                    @SuppressWarnings("unchecked")
                    List<String> alerts = (List<String>)
                            summary.computeIfAbsent("driftAlerts", k -> new ArrayList<String>());
                    alerts.add(metric);
                }
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
            all.add(applyCostDefaults(new LinkedHashMap<>(entry.summary)));
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

    private static Map<String, Object> applyCostDefaults(Map<String, Object> summary) {
        summary.putIfAbsent("costDimensions", Map.of());
        if (summary.get("costDimensions") instanceof Map<?, ?> dimensions) {
            for (String key : COST_DIMENSION_KEYS) {
                summary.putIfAbsent(key, dimensions.get(key));
            }
        }
        summary.putIfAbsent("middlewareChain", null);
        summary.putIfAbsent("providerCallCount", 0L);
        summary.putIfAbsent("retryCount", 0L);
        summary.putIfAbsent("retryOverheadUsd", 0.0);
        summary.putIfAbsent("cacheSavingsUsd", 0.0);
        return summary;
    }

    private static Map<String, Object> costDimensions(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : COST_DIMENSION_KEYS) {
            Object v = map.get(key);
            if (v instanceof String s && !s.isEmpty()) {
                out.put(key, s);
            }
        }
        return out;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round8(double value) {
        return Math.round(value * 1e8) / 1e8;
    }
}
