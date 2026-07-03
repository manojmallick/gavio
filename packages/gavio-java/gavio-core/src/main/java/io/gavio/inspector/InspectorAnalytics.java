package io.gavio.inspector;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregations over trace summaries — DAG, sessions, stats (F-OBS-10 / F-DX-08).
 *
 * <p>Pure static functions over the summary maps produced by {@link RingBuffer}.
 * Ported from the Python reference ({@code gavio/inspector/analytics.py}) so the
 * JSON shapes stay identical across SDKs: nearest-rank percentiles, cycle-safe
 * DAG walks, 4dp rate rounding and 8dp cost rounding. All returned structures
 * are {@link LinkedHashMap}/{@link ArrayList} compositions ready for
 * {@link io.gavio.json.Json#write}.
 */
public final class InspectorAnalytics {

    /** group_by query values mapped to the summary field they group on. */
    private static final Map<String, String> GROUP_BY_FIELDS = new LinkedHashMap<>();

    static {
        GROUP_BY_FIELDS.put("provider", "provider");
        GROUP_BY_FIELDS.put("model", "model");
        GROUP_BY_FIELDS.put("agent_id", "agentId");
    }

    private InspectorAnalytics() {
    }

    /** Group summaries by sessionId — trace counts, cost, duration, agents. */
    public static List<Map<String, Object>> buildSessions(List<Map<String, Object>> summaries) {
        Map<String, Map<String, Object>> sessions = new LinkedHashMap<>();
        for (Map<String, Object> s : summaries) {
            Object sessionIdObj = s.get("sessionId");
            if (!(sessionIdObj instanceof String sessionId) || sessionId.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = sessions.computeIfAbsent(sessionId, id -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("sessionId", id);
                e.put("traces", 0L);
                e.put("errors", 0L);
                e.put("totalCostUsd", 0.0);
                e.put("totalLatencyMs", 0L);
                e.put("agents", new ArrayList<String>());
                e.put("firstWallTimeUtc", s.get("wallTimeUtc"));
                e.put("lastWallTimeUtc", s.get("wallTimeUtc"));
                return e;
            });
            entry.put("traces", asLong(entry.get("traces")) + 1);
            if (isErrorStatus(s.get("status"))) {
                entry.put("errors", asLong(entry.get("errors")) + 1);
            }
            entry.put("totalCostUsd", round8(asDouble(entry.get("totalCostUsd")) + asDouble(s.get("costUsd"))));
            entry.put("totalLatencyMs", asLong(entry.get("totalLatencyMs")) + asLong(s.get("latencyMs")));
            @SuppressWarnings("unchecked")
            List<String> agents = (List<String>) entry.get("agents");
            if (s.get("agentId") instanceof String agent && !agent.isEmpty() && !agents.contains(agent)) {
                agents.add(agent);
            }
            if (s.get("wallTimeUtc") != null) {
                entry.put("lastWallTimeUtc", s.get("wallTimeUtc"));
            }
        }
        return new ArrayList<>(sessions.values());
    }

    /**
     * Agent call graph from parentTraceId links, with subtree rollups.
     *
     * <p>Select nodes by {@code sessionId} or by {@code root} trace id (the root
     * plus every descendant, cycle-safe). Returns null when {@code root} is given
     * but unknown.
     */
    public static Map<String, Object> buildDag(
            List<Map<String, Object>> summaries, String root, String sessionId) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (Map<String, Object> s : summaries) {
            byId.put((String) s.get("traceId"), s);
            if (s.get("parentTraceId") instanceof String parent && !parent.isEmpty()) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add((String) s.get("traceId"));
            }
        }

        List<String> selected = new ArrayList<>();
        if (sessionId != null) {
            for (Map<String, Object> s : summaries) {
                if (sessionId.equals(s.get("sessionId"))) {
                    selected.add((String) s.get("traceId"));
                }
            }
        } else {
            if (!byId.containsKey(root)) {
                return null;
            }
            Deque<String> stack = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                String traceId = stack.pop();
                if (!seen.add(traceId)) {
                    continue; // defensive: a parentTraceId cycle must not hang us
                }
                selected.add(traceId);
                for (String child : children.getOrDefault(traceId, List.of())) {
                    stack.push(child);
                }
            }
        }

        Set<String> nodeSet = new HashSet<>(selected);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String traceId : selected) {
            Map<String, Object> s = byId.get(traceId);
            if (s == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("traceId", traceId);
            node.put("parentTraceId", s.get("parentTraceId"));
            node.put("agentId", s.get("agentId"));
            node.put("sessionId", s.get("sessionId"));
            node.put("provider", s.get("provider"));
            node.put("model", s.get("model"));
            node.put("status", s.get("status"));
            node.put("latencyMs", s.get("latencyMs"));
            node.put("costUsd", s.get("costUsd"));
            node.put("cacheHit", s.get("cacheHit"));
            node.put("wallTimeUtc", s.get("wallTimeUtc"));
            node.put("subtree", subtree(traceId, byId, children, nodeSet, new HashSet<>()));
            nodes.add(node);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (String traceId : selected) {
            Map<String, Object> s = byId.get(traceId);
            if (s != null && s.get("parentTraceId") instanceof String parent && nodeSet.contains(parent)) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", parent);
                edge.put("to", traceId);
                edges.add(edge);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** Rollup of one node plus all descendants inside the node set (cycle-safe). */
    private static Map<String, Object> subtree(
            String traceId,
            Map<String, Map<String, Object>> byId,
            Map<String, List<String>> children,
            Set<String> nodeSet,
            Set<String> seen) {
        seen.add(traceId);
        Map<String, Object> s = byId.get(traceId);
        long traces = 1;
        long errors = isErrorStatus(s.get("status")) ? 1 : 0;
        double costUsd = asDouble(s.get("costUsd"));
        long latencyMs = asLong(s.get("latencyMs"));
        for (String child : children.getOrDefault(traceId, List.of())) {
            if (nodeSet.contains(child) && !seen.contains(child)) {
                Map<String, Object> childRollup = subtree(child, byId, children, nodeSet, seen);
                traces += asLong(childRollup.get("traces"));
                errors += asLong(childRollup.get("errors"));
                costUsd += asDouble(childRollup.get("costUsd"));
                latencyMs += asLong(childRollup.get("latencyMs"));
            }
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("traces", traces);
        rollup.put("errors", errors);
        rollup.put("costUsd", round8(costUsd));
        rollup.put("latencyMs", latencyMs);
        return rollup;
    }

    /**
     * RED aggregates: rate, errors, latency percentiles, tokens, cost, cache, PII.
     *
     * @throws IllegalArgumentException for an unknown {@code groupBy} or an
     *     unparsable {@code since} timestamp.
     */
    public static Map<String, Object> buildStats(
            List<Map<String, Object>> summaries, String groupBy, String since) {
        if (groupBy != null && !GROUP_BY_FIELDS.containsKey(groupBy)) {
            throw new IllegalArgumentException(
                    "group_by must be one of ['agent_id', 'model', 'provider']");
        }
        List<Map<String, Object>> filtered = summaries;
        if (since != null) {
            OffsetDateTime sinceAt = parseIso(since);
            filtered = new ArrayList<>();
            for (Map<String, Object> s : summaries) {
                if (s.get("wallTimeUtc") instanceof String wall
                        && !parseIso(wall).isBefore(sinceAt)) {
                    filtered.add(s);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", aggregate(filtered));
        if (groupBy != null) {
            String field = GROUP_BY_FIELDS.get(groupBy);
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> s : filtered) {
                groups.computeIfAbsent(String.valueOf(s.get(field)), k -> new ArrayList<>()).add(s);
            }
            Map<String, Object> aggregated = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
                aggregated.put(e.getKey(), aggregate(e.getValue()));
            }
            out.put("groups", aggregated);
        }
        return out;
    }

    private static Map<String, Object> aggregate(List<Map<String, Object>> summaries) {
        List<Long> latencies = new ArrayList<>();
        long errors = 0;
        long cacheHits = 0;
        long prompt = 0;
        long completion = 0;
        double costUsd = 0.0;
        Map<String, Long> pii = new LinkedHashMap<>();
        for (Map<String, Object> s : summaries) {
            if (s.get("latencyMs") instanceof Number latency) {
                latencies.add(latency.longValue());
            }
            if (isErrorStatus(s.get("status"))) {
                errors++;
            }
            if (Boolean.TRUE.equals(s.get("cacheHit"))) {
                cacheHits++;
            }
            if (s.get("usage") instanceof Map<?, ?> usage) {
                prompt += asLong(usage.get("promptTokens"));
                completion += asLong(usage.get("completionTokens"));
            }
            if (s.get("piiEntityTypes") instanceof List<?> types) {
                for (Object type : types) {
                    pii.merge(String.valueOf(type), 1L, Long::sum);
                }
            }
            costUsd += asDouble(s.get("costUsd"));
        }
        latencies.sort(Comparator.naturalOrder());
        int n = summaries.size();

        Map<String, Object> latencyMs = new LinkedHashMap<>();
        latencyMs.put("p50", percentile(latencies, 50));
        latencyMs.put("p95", percentile(latencies, 95));
        latencyMs.put("p99", percentile(latencies, 99));

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("completion", completion);
        tokens.put("total", prompt + completion);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", (long) n);
        out.put("errors", errors);
        out.put("errorRate", n == 0 ? 0.0 : round4(errors / (double) n));
        out.put("latencyMs", latencyMs);
        out.put("tokens", tokens);
        out.put("costUsd", round8(costUsd));
        out.put("cacheHits", cacheHits);
        out.put("cacheHitRate", n == 0 ? 0.0 : round4(cacheHits / (double) n));
        out.put("piiDetections", pii);
        return out;
    }

    /** Nearest-rank percentile over an ascending list; null when empty. */
    static Long percentile(List<Long> sortedValues, int pct) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int rank = Math.max(1, (int) Math.ceil(pct / 100.0 * sortedValues.size()));
        return sortedValues.get(rank - 1);
    }

    /** Parse an ISO timestamp with or without a zone offset (naive = UTC). */
    private static OffsetDateTime parseIso(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException withOffset) {
            try {
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("invalid ISO timestamp: " + value);
            }
        }
    }

    private static boolean isErrorStatus(Object status) {
        return "error".equals(status) || "blocked".equals(status);
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    private static double round8(double v) {
        return Math.round(v * 1e8) / 1e8;
    }
}
