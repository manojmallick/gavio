package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.inspector.CaptureMode;
import io.gavio.inspector.InspectorConfig;
import io.gavio.inspector.InspectorEvent;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.sinks.StdoutSink;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Message;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared cross-SDK inspector event-sequence vectors from
 * //test-vectors/inspector against the Java SDK. Same JSON file the Python and
 * JavaScript SDKs run — event-stream parity is enforced, not assumed.
 *
 * <p>Lives in gavio-testing because it needs gavio-core (Inspector), the PII
 * module (pii_guard) and the audit module (audit) on the classpath at once.
 *
 * <p>Gateways use an explicit {@link MockProvider} adapter rather than dev
 * mode: dev mode auto-wires an audit interceptor, which would pollute the
 * expected sequences. 'full' capture is therefore acknowledged explicitly.
 */
class InspectorVectorsTest {

    private static final Map<String, Supplier<Interceptor>> FACTORIES = Map.of(
            "pii_guard", PiiGuard::new,
            "audit", () -> new AuditInterceptor(
                    new StdoutSink(false, new PrintStream(OutputStream.nullOutputStream()))));

    /** Provider that always fails — used by requireError cases. */
    private static final class FailingProvider implements ProviderAdapter {
        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("provider unavailable (vector requireError)"));
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Walk up from the working dir to find the repo's test-vectors directory. */
    private static Path vectorsFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/inspector/event-sequences.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/inspector from working dir");
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

    @SuppressWarnings("unchecked")
    private static Gateway buildGateway(Map<String, Object> c) {
        boolean requireError = Boolean.TRUE.equals(c.get("requireError"));
        CaptureMode mode = CaptureMode.valueOf(((String) c.get("mode")).toUpperCase());
        var builder = Gateway.builder()
                .adapter(requireError ? new FailingProvider() : new MockProvider())
                .model("mock")
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(mode)
                        .startServer(false)
                        .unsafeContentCaptureAck(true)
                        .build());
        for (Object name : (List<Object>) c.get("interceptors")) {
            builder.use(FACTORIES.get(name.toString()).get());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<Message> messages(Map<String, Object> c) {
        Map<String, Object> request = (Map<String, Object>) c.get("request");
        List<Message> out = new ArrayList<>();
        for (Object m : (List<Object>) request.get("messages")) {
            Map<String, Object> msg = (Map<String, Object>) m;
            out.add(Message.of((String) msg.get("role"), (String) msg.get("content")));
        }
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> eventSequenceVectors() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest("inspector:" + c.get("id"), () -> {
            Gateway gw = buildGateway(c);
            List<InspectorEvent> events = Collections.synchronizedList(new ArrayList<>());
            gw.inspector().bus().subscribe(events::add);

            boolean requireError = Boolean.TRUE.equals(c.get("requireError"));
            if (requireError) {
                assertThrows(Exception.class, () -> gw.complete(messages(c)).join());
            } else {
                gw.complete(messages(c)).join();
            }

            List<Map<String, Object>> expected = new ArrayList<>();
            for (Object e : (List<Object>) c.get("expectedEvents")) {
                expected.add((Map<String, Object>) e);
            }

            assertEquals(
                    expected.stream().map(e -> e.get("type")).toList(),
                    events.stream().map(InspectorEvent::type).toList(),
                    "event type sequence for " + c.get("id"));

            for (int i = 0; i < expected.size(); i++) {
                Map<String, Object> exp = expected.get(i);
                Map<String, Object> data = events.get(i).data();
                if (exp.containsKey("name")) {
                    assertEquals(exp.get("name"), data.get("name"), "name at event " + i);
                }
                if (exp.containsKey("status")) {
                    assertEquals(exp.get("status"), data.get("status"), "status at event " + i);
                }
                if (exp.containsKey("mutated")) {
                    assertEquals(exp.get("mutated"), data.get("mutated"), "mutated at event " + i);
                }
            }

            List<Object> forbidden = (List<Object>) c.getOrDefault("forbiddenDataKeys", List.of());
            for (InspectorEvent event : events) {
                for (Object key : forbidden) {
                    assertFalse(event.data().containsKey(key.toString()),
                            event.type() + " leaked forbidden key " + key);
                }
            }

            long prevSeq = -1;
            for (InspectorEvent event : events) {
                assertTrue(event.tNs() >= 0, "tNs must be non-negative");
                assertTrue(event.seq() > prevSeq, "seq must be strictly increasing");
                prevSeq = event.seq();
                if (event.type().endsWith(".end") && !event.type().equals("trace.end")) {
                    Object dur = event.data().get("durationUs");
                    assertTrue(dur instanceof Number && ((Number) dur).longValue() >= 0,
                            event.type() + " must carry non-negative durationUs");
                }
            }
        }));
    }
}
