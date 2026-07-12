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

    private static Path vectorsFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/tool-runtime/cases.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/tool-runtime from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws IOException {
        String text = Files.readString(vectorsFile());
        List<Object> raw = (List<Object>) Json.parseObject(text).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : raw) {
            out.add((Map<String, Object>) c);
        }
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> sharedVectors() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest("tool-runtime:" + c.get("id"), () -> {
            Map<String, Object> decision =
                    ToolRuntimeInterceptor.analyze((Map<String, Object>) c.get("tools"));
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");

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
