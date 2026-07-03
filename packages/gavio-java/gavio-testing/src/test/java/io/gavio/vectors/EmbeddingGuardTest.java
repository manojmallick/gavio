package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.ProviderException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.inspector.CaptureMode;
import io.gavio.inspector.InspectorConfig;
import io.gavio.inspector.InspectorEvent;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.AuditRecord;
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
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Embedding call guard tests (F-SEC-10), mirroring the Python suite.
 *
 * <p>The same PII pipeline that protects completions must run on embedding
 * calls: inputs are scanned/redacted before the provider's embedding API,
 * governance and audit interceptors fire, and the inspector traces the call.
 * Redaction cases come from {@code //test-vectors/embedding/redaction.json},
 * shared with the other SDKs.
 *
 * <p>Lives in gavio-testing because it needs gavio-core (Gateway, Inspector),
 * the PII module (pii_guard) and the audit module (audit) at once.
 */
class EmbeddingGuardTest {

    /**
     * Python's MockProvider vector for "alpha" — sha256 bytes[0..8) / 255.0.
     * Hardcoded to enforce cross-SDK numeric parity, not recomputed.
     */
    private static final List<Double> PYTHON_ALPHA_VECTOR = List.of(
            0.5568627450980392, 0.8274509803921568, 0.9647058823529412, 0.6784313725490196,
            0.40784313725490196, 0.3568627450980392, 0.5843137254901961, 0.6196078431372549);

    /** Records the request as it reaches the embedding API (post-redaction). */
    private static final class CapturingProvider implements ProviderAdapter {
        private final MockProvider delegate = new MockProvider();
        private volatile GavioRequest embedded;

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            return delegate.complete(request);
        }

        @Override
        public CompletableFuture<GavioResponse> embed(GavioRequest request) {
            this.embedded = request;
            return delegate.embed(request);
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return delegate.healthCheck();
        }
    }

    /** Adapter that never overrides {@code embed} — exercises the default guard. */
    private static final class NoEmbedProvider implements ProviderAdapter {
        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("not used"));
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Walk up from the working dir to find the repo's test-vectors directory. */
    private static Path vectorsFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/embedding/redaction.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/embedding from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws IOException {
        List<Object> raw = (List<Object>) Json.parseObject(Files.readString(vectorsFile())).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : raw) {
            out.add((Map<String, Object>) c);
        }
        return out;
    }

    @Test
    void embedReturnsOneVectorPerText() {
        Gateway gw = Gateway.builder().adapter(new MockProvider()).model("mock").build();
        GavioResponse response = gw.embed(List.of("alpha", "beta", "gamma")).join();

        assertNotNull(response.embeddings());
        assertEquals(3, response.embeddings().size());
        for (List<Double> vector : response.embeddings()) {
            assertEquals(8, vector.size());
        }
        assertEquals("", response.content());
        assertTrue(response.usage().promptTokens() > 0);
        assertEquals(0, response.usage().completionTokens());
        // Deterministic and byte-identical to the Python SDK for the same text.
        assertEquals(PYTHON_ALPHA_VECTOR, response.embeddings().get(0));
        GavioResponse again = gw.embed(List.of("alpha")).join();
        assertEquals(response.embeddings().get(0), again.embeddings().get(0));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> embeddingRedactionVectors() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest("embedding:" + c.get("id"), () -> {
            CapturingProvider adapter = new CapturingProvider();
            Gateway gw = Gateway.builder()
                    .adapter(adapter)
                    .model("mock")
                    .use(new PiiGuard())
                    .build();
            List<String> texts = new ArrayList<>();
            for (Object text : (List<Object>) c.get("texts")) {
                texts.add(text.toString());
            }
            GavioResponse response = gw.embed(texts).join();

            StringBuilder reached = new StringBuilder();
            for (Message message : adapter.embedded.messages()) {
                reached.append(message.content()).append(" \n ");
            }
            String reachedProvider = reached.toString();
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            for (Object fragment : (List<Object>) expected.get("redactedContains")) {
                assertTrue(reachedProvider.contains(fragment.toString()),
                        c.get("id") + ": missing " + fragment);
            }
            for (Object raw : (List<Object>) expected.get("redactedNotContains")) {
                assertFalse(reachedProvider.contains(raw.toString()),
                        c.get("id") + ": leaked " + raw);
            }
            assertNotNull(response.embeddings());
            assertEquals(texts.size(), response.embeddings().size());
        }));
    }

    @Test
    void embedWritesAuditRecordWithPiiMetadata() {
        Gateway gw = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .use(new AuditInterceptor(
                        new StdoutSink(false, new PrintStream(OutputStream.nullOutputStream()))))
                .use(new PiiGuard())
                .build();
        GavioResponse response = gw.embed(List.of("reach me at jan.real@corp.com")).join();

        AuditRecord record = (AuditRecord) response.audit();
        assertNotNull(record);
        assertEquals(response.traceId(), record.traceId());
        assertFalse(record.promptHash().isEmpty());
        assertTrue(record.piiEntityTypes().contains("EMAIL"));
        assertTrue(record.interceptorsFired().contains("pii_guard"));
    }

    @Test
    void embedFailsForProvidersWithoutEmbeddings() {
        Gateway gw = Gateway.builder().adapter(new NoEmbedProvider()).model("mock").build();
        CompletionException error = assertThrows(
                CompletionException.class, () -> gw.embed(List.of("anything")).join());
        assertTrue(error.getCause() instanceof ProviderException,
                "expected ProviderException, got " + error.getCause());
        assertTrue(error.getCause().getMessage().contains("does not support embeddings"));
    }

    @Test
    void embedIsTracedByTheInspector() {
        Gateway gw = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .use(new PiiGuard())
                .inspect(InspectorConfig.builder()
                        .enabled(true)
                        .mode(CaptureMode.METADATA)
                        .startServer(false)
                        .build())
                .build();
        List<InspectorEvent> events = Collections.synchronizedList(new ArrayList<>());
        gw.inspector().bus().subscribe(events::add);

        gw.embed(List.of("mail jan@example.com please")).join();

        List<String> types = events.stream().map(InspectorEvent::type).toList();
        assertEquals("trace.start", types.get(0));
        assertTrue(types.contains("provider.call.start"));
        assertEquals("trace.end", types.get(types.size() - 1));
        Map<String, Object> end = events.get(events.size() - 1).data();
        assertEquals("ok", end.get("status"));
        Object piiTypes = end.get("piiEntityTypes");
        assertTrue(piiTypes instanceof List<?> list && list.contains("EMAIL"),
                "trace.end should carry piiEntityTypes with EMAIL, got " + piiTypes);
    }
}
