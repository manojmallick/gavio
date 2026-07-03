package io.gavio.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Inspector v0.7.0 endpoints (F-OBS-10 / F-DX-08 / F-DX-11 / F-DX-12): DAG,
 * sessions, stats, replay, simulate-cost, export and chain verification.
 * Mirrors the Python reference tests in {@code test_inspector_agentic.py}.
 */
class InspectorAgenticTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Gateway gateway;

    @AfterEach
    void stopServer() {
        if (gateway != null && gateway.inspector() != null) {
            gateway.inspector().stop();
        }
    }

    private String serve(CaptureMode mode) {
        gateway = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .port(0)
                        .mode(mode)
                        .unsafeContentCaptureAck(true)
                        .build())
                .build();
        return "http://127.0.0.1:" + gateway.inspector().port();
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String complete(String content, String agentId, String parentTraceId, String sessionId) {
        GavioRequest request = GavioRequest.builder()
                .messages(List.of(Message.of("user", content)))
                .model("mock")
                .provider(Provider.MOCK)
                .agentId(agentId)
                .parentTraceId(parentTraceId)
                .sessionId(sessionId)
                .build();
        return gateway.complete(request).join().traceId();
    }

    /** One orchestrator trace with two children in session s1. */
    private String[] runFamily() {
        String root = complete("orchestrate", "orchestrator", null, "s1");
        String childA = complete("sub-task a", "worker-a", root, "s1");
        String childB = complete("sub-task b", "worker-b", root, "s1");
        return new String[] {root, childA, childB};
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    // ---- F-OBS-10: DAG + sessions --------------------------------------------

    @Test
    void dagEndpointBuildsCallGraphWithRollups() throws Exception {
        String base = serve(CaptureMode.FULL);
        String[] family = runFamily();
        String rootId = family[0];

        Map<String, Object> dag = Json.parseObject(get(base + "/api/dag?root=" + rootId).body());
        List<Map<String, Object>> nodes = listOfMaps(dag.get("nodes"));
        Set<String> nodeIds = new TreeSet<>();
        for (Map<String, Object> node : nodes) {
            nodeIds.add((String) node.get("traceId"));
        }
        assertEquals(new TreeSet<>(Set.of(family[0], family[1], family[2])), nodeIds);

        List<Map<String, Object>> edges = listOfMaps(dag.get("edges"));
        assertEquals(2, edges.size());
        Set<String> edgePairs = new TreeSet<>();
        for (Map<String, Object> edge : edges) {
            edgePairs.add(edge.get("from") + ">" + edge.get("to"));
        }
        assertEquals(new TreeSet<>(Set.of(rootId + ">" + family[1], rootId + ">" + family[2])),
                edgePairs);

        Map<String, Object> rootNode = nodes.stream()
                .filter(n -> rootId.equals(n.get("traceId"))).findFirst().orElseThrow();
        assertEquals("orchestrator", rootNode.get("agentId"));
        Map<String, Object> subtree = map(rootNode.get("subtree"));
        assertEquals(3L, asLong(subtree.get("traces")));
        assertEquals(0L, asLong(subtree.get("errors")));
        Map<String, Object> leaf = nodes.stream()
                .filter(n -> family[1].equals(n.get("traceId"))).findFirst().orElseThrow();
        assertEquals(1L, asLong(map(leaf.get("subtree")).get("traces")));

        Map<String, Object> bySession = Json.parseObject(get(base + "/api/dag?session_id=s1").body());
        assertEquals(3, listOfMaps(bySession.get("nodes")).size());

        assertEquals(400, get(base + "/api/dag").statusCode());
        assertEquals(404, get(base + "/api/dag?root=no-such-trace").statusCode());
    }

    @Test
    void sessionsEndpointAggregatesBySession() throws Exception {
        String base = serve(CaptureMode.FULL);
        runFamily();
        complete("no session", null, null, null);

        Map<String, Object> body = Json.parseObject(get(base + "/api/sessions").body());
        List<Map<String, Object>> sessions = listOfMaps(body.get("sessions"));
        assertEquals(1, sessions.size());
        Map<String, Object> s1 = sessions.get(0);
        assertEquals("s1", s1.get("sessionId"));
        assertEquals(3L, asLong(s1.get("traces")));
        assertEquals(0L, asLong(s1.get("errors")));
        Set<String> agents = new TreeSet<>();
        for (Object agent : (List<?>) s1.get("agents")) {
            agents.add((String) agent);
        }
        assertEquals(new TreeSet<>(Set.of("orchestrator", "worker-a", "worker-b")), agents);
        String first = (String) s1.get("firstWallTimeUtc");
        String last = (String) s1.get("lastWallTimeUtc");
        assertTrue(first.compareTo(last) <= 0, "firstWallTimeUtc must not exceed lastWallTimeUtc");
    }

    // ---- F-DX-08: stats --------------------------------------------------------

    @Test
    void statsEndpointRedAggregatesAndGrouping() throws Exception {
        String base = serve(CaptureMode.FULL);
        runFamily();

        Map<String, Object> stats = Json.parseObject(get(base + "/api/stats").body());
        Map<String, Object> total = map(stats.get("total"));
        assertEquals(3L, asLong(total.get("requests")));
        assertEquals(0L, asLong(total.get("errors")));
        assertEquals(0.0, ((Number) total.get("errorRate")).doubleValue());
        assertNotNull(map(total.get("latencyMs")).get("p50"));
        assertTrue(asLong(map(total.get("tokens")).get("total")) > 0, "usage must be captured");
        assertEquals(0.0, ((Number) total.get("cacheHitRate")).doubleValue());

        Map<String, Object> grouped =
                Json.parseObject(get(base + "/api/stats?group_by=agent_id").body());
        Map<String, Object> groups = map(grouped.get("groups"));
        assertEquals(new TreeSet<>(Set.of("orchestrator", "worker-a", "worker-b")),
                new TreeSet<>(groups.keySet()));
        assertEquals(1L, asLong(map(groups.get("worker-a")).get("requests")));

        assertEquals(400, get(base + "/api/stats?group_by=nope").statusCode());
    }

    @Test
    void statsCountsPiiAndErrorsFromSummaries() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("traceId", "a");
        a.put("status", "ok");
        a.put("latencyMs", 10);
        a.put("piiEntityTypes", List.of("EMAIL"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("traceId", "b");
        b.put("status", "error");
        b.put("latencyMs", 30);
        b.put("piiEntityTypes", List.of("EMAIL", "IBAN"));

        Map<String, Object> total = map(
                InspectorAnalytics.buildStats(List.of(a, b), null, null).get("total"));
        assertEquals(1L, asLong(total.get("errors")));
        assertEquals(0.5, ((Number) total.get("errorRate")).doubleValue());
        assertEquals(Map.of("EMAIL", 2L, "IBAN", 1L), total.get("piiDetections"));
        assertEquals(10L, asLong(map(total.get("latencyMs")).get("p50")));
        assertEquals(30L, asLong(map(total.get("latencyMs")).get("p99")));
    }

    // ---- F-DX-11: replay --------------------------------------------------------

    @Test
    void replayRefiresTraceAndReturnsNewTraceId() throws Exception {
        String base = serve(CaptureMode.FULL);
        String traceId = complete("replay me", null, null, null);

        HttpResponse<String> replayResponse =
                post(base + "/api/replay", "{\"traceId\": \"" + traceId + "\"}");
        assertEquals(200, replayResponse.statusCode());
        Map<String, Object> replayed = Json.parseObject(replayResponse.body());
        assertEquals(traceId, replayed.get("replayOf"));
        assertNotEquals(traceId, replayed.get("traceId"));

        // The replayed call went through the live pipeline into the buffer.
        Map<String, Object> newTrace =
                Json.parseObject(get(base + "/api/traces/" + replayed.get("traceId")).body());
        assertEquals("ok", map(newTrace.get("summary")).get("status"));

        HttpResponse<String> editedResponse = post(base + "/api/replay",
                "{\"traceId\": \"" + traceId + "\", \"overrides\": "
                        + "{\"messages\": [{\"role\": \"user\", \"content\": \"edited\"}]}}");
        assertEquals(200, editedResponse.statusCode());
        Map<String, Object> edited = Json.parseObject(editedResponse.body());
        Map<String, Object> editedTrace =
                Json.parseObject(get(base + "/api/traces/" + edited.get("traceId")).body());
        Map<String, Object> start = listOfMaps(editedTrace.get("events")).stream()
                .filter(e -> "trace.start".equals(e.get("type"))).findFirst().orElseThrow();
        List<Map<String, Object>> messages = listOfMaps(map(start.get("data")).get("messages"));
        assertEquals("edited", messages.get(0).get("content"));

        assertEquals(404,
                post(base + "/api/replay", "{\"traceId\": \"no-such-trace\"}").statusCode());
        assertEquals(400, post(base + "/api/replay", "{}").statusCode());
    }

    @Test
    void replayIs403OutsideFullMode() throws Exception {
        String base = serve(CaptureMode.REDACTED);
        String traceId = complete("hi", null, null, null);
        assertEquals(403,
                post(base + "/api/replay", "{\"traceId\": \"" + traceId + "\"}").statusCode());
    }

    // ---- cost simulator -----------------------------------------------------------

    @Test
    void simulateCostRecostsTraceUnderOtherModel() throws Exception {
        String base = serve(CaptureMode.FULL);
        String traceId = complete("price this call", null, null, null);

        HttpResponse<String> response =
                get(base + "/api/simulate-cost?trace_id=" + traceId + "&model=gpt-4o");
        assertEquals(200, response.statusCode());
        Map<String, Object> simulated = Json.parseObject(response.body());
        assertEquals(traceId, simulated.get("traceId"));
        assertEquals("gpt-4o", simulated.get("simulatedModel"));
        double simulatedCost = ((Number) simulated.get("simulatedCostUsd")).doubleValue();
        assertTrue(simulatedCost > 0.0, "mock is free, gpt-4o is not");
        double delta = ((Number) simulated.get("deltaUsd")).doubleValue();
        double original = ((Number) simulated.get("costUsd")).doubleValue();
        assertEquals(simulatedCost - original, delta, 1e-9);
        assertTrue(asLong(map(simulated.get("usage")).get("totalTokens")) > 0);

        assertEquals(400, get(base + "/api/simulate-cost?trace_id=missing-both").statusCode());
    }

    // ---- F-DX-12: export -------------------------------------------------------------

    @Test
    void exportTestVectorSanitizesPii() throws Exception {
        String base = serve(CaptureMode.FULL);
        String traceId = complete("mail bob.real@corp.com about the invoice", null, null, null);

        HttpResponse<String> vectorResponse =
                get(base + "/api/traces/" + traceId + "/export?format=test-vector");
        assertEquals(200, vectorResponse.statusCode());
        Map<String, Object> vector = Json.parseObject(vectorResponse.body());
        assertTrue(((String) vector.get("id")).startsWith("exported-"));
        assertEquals("full", vector.get("mode"));
        List<Map<String, Object>> messages =
                listOfMaps(map(vector.get("request")).get("messages"));
        String content = (String) messages.get(0).get("content");
        assertFalse(content.contains("bob.real@corp.com"), "real email must be sanitized");
        assertTrue(content.contains("jan@example.com"), "synthetic fixture must replace PII");
        List<Map<String, Object>> expectedEvents = listOfMaps(vector.get("expectedEvents"));
        assertEquals("trace.start", expectedEvents.get(0).get("type"));
        assertEquals("trace.end", expectedEvents.get(expectedEvents.size() - 1).get("type"));

        HttpResponse<String> testkit =
                get(base + "/api/traces/" + traceId + "/export?format=testkit-py");
        assertEquals(200, testkit.statusCode());
        assertTrue(testkit.body().contains("GavioTestKit"));
        assertFalse(testkit.body().contains("bob.real@corp.com"));

        HttpResponse<String> testkitJava =
                get(base + "/api/traces/" + traceId + "/export?format=testkit-java");
        assertEquals(200, testkitJava.statusCode());
        assertTrue(testkitJava.body().contains("GavioTestKit"));

        assertEquals(400,
                get(base + "/api/traces/" + traceId + "/export?format=nope").statusCode());
    }

    @Test
    void exportIs403InMetadataMode() throws Exception {
        String base = serve(CaptureMode.METADATA);
        String traceId = complete("hi", null, null, null);
        assertEquals(403,
                get(base + "/api/traces/" + traceId + "/export?format=test-vector").statusCode());
    }

    // ---- F-DX-08: chain verify --------------------------------------------------------

    @Test
    void chainVerifyIs400OnLiveServer() throws Exception {
        String base = serve(CaptureMode.FULL);
        assertEquals(400, get(base + "/api/chain/verify").statusCode());
    }

    // ---- analytics unit coverage --------------------------------------------------------

    @Test
    void buildDagToleratesParentCycles() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("traceId", "a");
        a.put("parentTraceId", "b");
        a.put("status", "ok");
        a.put("latencyMs", 1);
        a.put("costUsd", 0.0);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("traceId", "b");
        b.put("parentTraceId", "a");
        b.put("status", "ok");
        b.put("latencyMs", 1);
        b.put("costUsd", 0.0);

        Map<String, Object> dag = InspectorAnalytics.buildDag(List.of(a, b), "a", null);
        assertNotNull(dag);
        Set<String> nodeIds = new TreeSet<>();
        for (Map<String, Object> node : listOfMaps(dag.get("nodes"))) {
            nodeIds.add((String) node.get("traceId"));
        }
        assertEquals(new TreeSet<>(Set.of("a", "b")), nodeIds);
    }

    @Test
    void buildSessionsSkipsSessionlessTraces() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", "a");
        summary.put("sessionId", null);
        assertEquals(List.of(), InspectorAnalytics.buildSessions(List.of(summary)));
    }
}
