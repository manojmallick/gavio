package io.gavio.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the shared v0.7.0 Inspector API cases from
 * {@code //test-vectors/inspector/api-cases.json} against the Java SDK —
 * DAG assembly (F-OBS-10) and replay-mode gating (F-DX-11). Same file the
 * Python and JavaScript suites consume.
 */
class InspectorApiVectorsTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Walk up from the working dir to find the repo's test-vectors directory. */
    private static Path vectorsFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/inspector/api-cases.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/inspector from working dir");
    }

    private static Map<String, Object> vectors() throws IOException {
        return Json.parseObject(Files.readString(vectorsFile()));
    }

    @SuppressWarnings("unchecked")
    static Stream<Map<String, Object>> dagCases() throws IOException {
        return ((List<Object>) vectors().get("dagCases")).stream()
                .map(c -> (Map<String, Object>) c);
    }

    @SuppressWarnings("unchecked")
    static Stream<Map<String, Object>> replayGatingCases() throws IOException {
        return ((List<Object>) vectors().get("replayGating")).stream()
                .map(c -> (Map<String, Object>) c);
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest(name = "{0}")
    @MethodSource("dagCases")
    void dagVectors(Map<String, Object> dagCase) {
        List<Map<String, Object>> summaries =
                (List<Map<String, Object>>) (List<?>) dagCase.get("summaries");
        Map<String, Object> dag = InspectorAnalytics.buildDag(
                summaries, (String) dagCase.get("root"), (String) dagCase.get("sessionId"));
        assertNotNull(dag, (String) dagCase.get("id"));

        Map<String, Object> expected = (Map<String, Object>) dagCase.get("expected");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) (List<?>) dag.get("nodes");
        List<?> edges = (List<?>) dag.get("edges");
        assertEquals(((Number) expected.get("nodes")).intValue(), nodes.size());
        assertEquals(((Number) expected.get("edges")).intValue(), edges.size());

        String rootId = (String) dagCase.get("root");
        if (rootId == null) {
            rootId = nodes.stream()
                    .filter(n -> n.get("parentTraceId") == null)
                    .findFirst()
                    .orElseThrow()
                    .get("traceId")
                    .toString();
        }
        final String root = rootId;
        Map<String, Object> rootNode = nodes.stream()
                .filter(n -> root.equals(n.get("traceId")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> subtree = (Map<String, Object>) rootNode.get("subtree");
        Map<String, Object> expectedSubtree = (Map<String, Object>) expected.get("rootSubtree");
        for (Map.Entry<String, Object> entry : expectedSubtree.entrySet()) {
            double want = ((Number) entry.getValue()).doubleValue();
            double got = ((Number) subtree.get(entry.getKey())).doubleValue();
            assertEquals(want, got, 1e-8, entry.getKey());
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @MethodSource("replayGatingCases")
    void replayGatingVectors(Map<String, Object> gating) throws Exception {
        CaptureMode mode = CaptureMode.valueOf(((String) gating.get("mode")).toUpperCase());
        int expectedStatus = ((Number) gating.get("expectedStatus")).intValue();

        Gateway gateway = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .port(0)
                        .mode(mode)
                        .unsafeContentCaptureAck(true)
                        .build())
                .build();
        try {
            String traceId = gateway.complete(GavioRequest.builder()
                            .messages(List.of(Message.of("user", "gate me")))
                            .model("mock")
                            .provider(Provider.MOCK)
                            .build())
                    .join()
                    .traceId();
            String base = "http://127.0.0.1:" + gateway.inspector().port();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/replay"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.write(Map.of("traceId", traceId))))
                    .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(expectedStatus, response.statusCode());
            if (expectedStatus == 200) {
                Map<String, Object> body = Json.parseObject(response.body());
                assertTrue(body.get("traceId") instanceof String);
                assertEquals(traceId, body.get("replayOf"));
            }
        } finally {
            gateway.inspector().stop();
        }
    }
}
