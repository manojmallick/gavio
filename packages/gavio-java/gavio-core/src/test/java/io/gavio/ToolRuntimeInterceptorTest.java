package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ToolRuntimeException;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor.OnFailure;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ToolRuntimeInterceptorTest {

    private static Path vectorsFile(String name) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/tool-runtime").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/tool-runtime/" + name + " from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String name) throws IOException {
        String text = Files.readString(vectorsFile(name));
        List<Object> raw = (List<Object>) Json.parseObject(text).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : raw) {
            out.add((Map<String, Object>) c);
        }
        return out;
    }

    private static List<Map<String, Object>> cases() throws IOException {
        return cases("cases.json");
    }

    @SuppressWarnings("unchecked")
    private static void assertDecision(Map<String, Object> decision, Map<String, Object> expected) {
        assertEquals(
                ((Number) expected.get("violation_count")).intValue(),
                ((List<?>) decision.get("violations")).size());
        assertEquals(
                ((Number) expected.getOrDefault("conflict_count", 0)).intValue(),
                ((List<?>) decision.get("conflicts")).size());
        if (expected.containsKey("confidence")) {
            assertEquals(
                    ((Number) expected.get("confidence")).doubleValue(),
                    ((Number) decision.get("confidence")).doubleValue(),
                    0.0001);
        }
        if (expected.containsKey("provenance_count")) {
            assertEquals(
                    ((Number) expected.get("provenance_count")).intValue(),
                    ((List<?>) decision.get("provenance")).size());
        }
        if (expected.containsKey("first_violation_kind")) {
            Map<String, Object> first = (Map<String, Object>)
                    ((List<?>) decision.get("violations")).get(0);
            assertEquals(expected.get("first_violation_kind"), first.get("kind"));
        }
        if (expected.containsKey("first_conflict_key")) {
            Map<String, Object> first = (Map<String, Object>)
                    ((List<?>) decision.get("conflicts")).get(0);
            assertEquals(expected.get("first_conflict_key"), first.get("key"));
        }
        if (expected.containsKey("decision_count")) {
            assertEquals(
                    ((Number) expected.get("decision_count")).intValue(),
                    ((List<?>) decision.get("decisions")).size());
        }
        if (expected.containsKey("first_action")) {
            Map<String, Object> first = (Map<String, Object>)
                    ((List<?>) decision.get("decisions")).get(0);
            assertEquals(expected.get("first_action"), first.get("action"));
        }
        if (expected.containsKey("first_approved")) {
            Map<String, Object> first = (Map<String, Object>)
                    ((List<?>) decision.get("decisions")).get(0);
            assertEquals(expected.get("first_approved"), first.get("approved"));
        }
        if (expected.containsKey("approval_required_count")) {
            assertEquals(
                    ((Number) expected.get("approval_required_count")).intValue(),
                    ((Number) decision.get("approvals_required")).intValue());
        }
        if (expected.containsKey("blocked_count")) {
            assertEquals(
                    ((Number) expected.get("blocked_count")).intValue(),
                    ((Number) decision.get("blocked")).intValue());
        }
        if (expected.containsKey("first_mcp_server")) {
            Map<String, Object> first = (Map<String, Object>)
                    ((List<?>) decision.get("provenance")).get(0);
            assertEquals(expected.get("first_mcp_server"), first.get("mcp_server"));
        }
        if (expected.containsKey("replayable")) {
            assertEquals(expected.get("replayable"), decision.get("replayable"));
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> sharedVectors() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest("tool-runtime:" + c.get("id"), () -> {
            Map<String, Object> decision =
                    ToolRuntimeInterceptor.analyze((Map<String, Object>) c.get("tools"));
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            assertDecision(decision, expected);
        }));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> permissionVectors() throws IOException {
        return cases("permissions.json").stream().map(c -> DynamicTest.dynamicTest(
                "tool-runtime-permissions:" + c.get("id"),
                () -> {
                    Map<String, Object> decision =
                            ToolRuntimeInterceptor.analyze((Map<String, Object>) c.get("tools"));
                    assertDecision(decision, (Map<String, Object>) c.get("expected"));
                }));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> replayVectors() throws IOException {
        return cases("replay.json").stream().map(c -> DynamicTest.dynamicTest(
                "tool-runtime-replay:" + c.get("id"),
                () -> {
                    Map<String, Object> decision =
                            ToolRuntimeInterceptor.replay((Map<String, Object>) c.get("record"));
                    assertDecision(decision, (Map<String, Object>) c.get("expected"));
                }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsRuntimeContextWithoutMutatingRequestMetadata() throws IOException {
        Map<String, Object> tools = (Map<String, Object>) cases().get(0).get("tools");
        AtomicReference<InterceptorContext> captured = new AtomicReference<>();
        Interceptor capture = new Interceptor() {
            @Override
            public String name() {
                return "tool_runtime_capture";
            }

            @Override
            public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
                captured.set(ctx);
                return CompletableFuture.completedFuture(request);
            }
        };
        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("ok"))
                .model("mock")
                .use(ToolRuntimeInterceptor.builder().build())
                .use(capture)
                .build();

        GavioRequest request = GavioRequest.builder()
                .message("user", "hi")
                .metadata("tools", tools)
                .build();
        gw.complete(request).join();

        assertFalse(tools.containsKey("runtime"));
        InterceptorContext ctx = captured.get();
        Map<String, Object> runtime = (Map<String, Object>) ctx.tools().get("runtime");
        assertEquals(1, runtime.get("call_count"));
        assertTrue(((List<?>) runtime.get("violations")).isEmpty());
        Map<String, Object> firstProvenance = (Map<String, Object>)
                ((List<?>) runtime.get("provenance")).get(0);
        assertEquals("warehouse-a", firstProvenance.get("source"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blocksInvalidToolContextWhenConfiguredForError() throws IOException {
        Map<String, Object> tools = (Map<String, Object>) cases().get(1).get("tools");
        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("ok"))
                .model("mock")
                .use(ToolRuntimeInterceptor.builder().onFailure(OnFailure.ERROR).build())
                .build();
        GavioRequest request = GavioRequest.builder()
                .message("user", "hi")
                .metadata("tools", tools)
                .build();

        CompletionException error = assertThrows(CompletionException.class, () -> gw.complete(request).join());
        assertInstanceOf(ToolRuntimeException.class, error.getCause());
    }
}
