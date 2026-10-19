package io.gavio.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.PiiBlockedException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.audit.AuditChain;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.audit.AuditRecord.ToolCallDigest;
import io.gavio.interceptors.audit.AuditSink;
import io.gavio.interceptors.cache.HashingEmbedder;
import io.gavio.interceptors.cache.SemanticCache;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.providers.anthropic.AnthropicAdapter;
import io.gavio.providers.azure.AzureOpenAiAdapter;
import io.gavio.providers.gemini.GeminiAdapter;
import io.gavio.providers.ollama.OllamaAdapter;
import io.gavio.providers.openai.OpenAiAdapter;
import io.gavio.types.FinishReason;
import io.gavio.types.Message;
import io.gavio.types.PiiMode;
import io.gavio.types.Provider;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Normalised tool calling (F-ADP-03) through the interceptor pipeline: PiiGuard on tool arguments and
 * tool-role messages, audit hashes, the cache, and the gateway. The shared provider vectors are in
 * {@code ToolCallingVectorsTest}.
 */
class ToolCallingPipelineTest {

    private static final String IBAN = "NL91ABNA0417164300"; // synthetic, checksum-valid

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    private static List<Map<String, Object>> transferTool() {
        return List.of(args(
                "name", "transfer",
                "description", "Send money",
                "inputSchema", args("type", "object", "properties", args("iban", args("type", "string")))));
    }

    /** user -> assistant calls transfer(arguments) -> tool result {@code toolContent}. */
    private static GavioRequest history(Map<String, Object> arguments, String toolContent) {
        return GavioRequest.builder()
                .model("m")
                .provider(Provider.MOCK)
                .message("user", "Pay the invoice")
                .message(Message.assistantToolCalls("", List.of(new ToolCall("call_1", "transfer", arguments))))
                .message(Message.toolResult("call_1", toolContent))
                .tools(transferTool())
                .build();
    }

    private static PiiGuard guard() {
        return PiiGuard.builder().logEntityTypes(false).build();
    }

    private static InterceptorContext ctx() {
        return new InterceptorContext("t-1");
    }

    private static ToolCall firstCall(GavioRequest request) {
        return request.messages().get(1).toolCalls().get(0);
    }

    // ---- PiiGuard ---------------------------------------------------------------------------

    @Test
    void ibanInAToolArgumentIsRedactedBeforeEveryPayloadIsBuilt() {
        Map<String, Function<GavioRequest, Map<String, Object>>> builders = Map.of(
                "openai", OpenAiAdapter::toProviderPayload,
                "azure_openai", AzureOpenAiAdapter::toProviderPayload,
                "anthropic", AnthropicAdapter::toProviderPayload,
                "gemini", GeminiAdapter::toProviderPayload,
                "ollama", OllamaAdapter::toProviderPayload);
        for (Map.Entry<String, Function<GavioRequest, Map<String, Object>>> builder : builders.entrySet()) {
            GavioRequest original = history(
                    args("iban", IBAN, "memo", "to " + IBAN + " please", "nested", args("l", List.of(IBAN))), "ok");
            InterceptorContext ctx = ctx();
            GavioRequest redacted = guard().before(original, ctx).join();
            String payload = Json.write(builder.getValue().apply(redacted));

            assertFalse(payload.contains(IBAN), builder.getKey());
            assertTrue(payload.contains("[IBAN_"), builder.getKey());
            assertTrue(ctx.piiEntityTypes().contains("IBAN"));
            // The caller's request is untouched.
            assertEquals(IBAN, firstCall(original).arguments().get("iban"));
        }
    }

    @Test
    void toolRoleMessageContentIsScanned() {
        GavioRequest redacted = guard().before(history(args("iban", "x"), "sent to jan@example.com"), ctx()).join();
        assertEquals("sent to [EMAIL_1]", redacted.messages().get(2).content());
        // The tool-calling members survive redaction.
        assertEquals("call_1", redacted.messages().get(2).toolCallId());
        assertEquals("call_1", firstCall(redacted).id());
        assertEquals("transfer", firstCall(redacted).name());
        assertEquals(redacted.tools(), history(args(), "").tools());
    }

    @Test
    void aSecretAndANumericIdentifierInToolArgumentsAreRedacted() {
        String key = "sk-ant-" + "a1B2c3D4e5F6g7H8i9J0k1L2"; // synthetic, matches the key pattern
        Map<String, Object> arguments = args("api_key", key, "bsn", 111222333L, "count", 3L, "flag", true);
        arguments.put("none", null);
        GavioRequest redacted = guard().before(history(arguments, "ok"), ctx()).join();
        Map<String, Object> result = firstCall(redacted).arguments();
        assertFalse(Json.write(result).contains(key));
        assertEquals("[BSN_1]", result.get("bsn"));
        assertEquals(3L, result.get("count"));
        assertEquals(true, result.get("flag"));
        assertTrue(result.containsKey("none"));
        assertEquals(null, result.get("none"));
    }

    @Test
    void blockModeRefusesPiiInToolArguments() {
        PiiGuard guard = PiiGuard.builder().mode(PiiMode.BLOCK).logEntityTypes(false).build();
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> guard.before(history(args("iban", IBAN), "ok"), ctx()).join());
        assertTrue(thrown.getCause() instanceof PiiBlockedException);
        assertEquals(List.of("IBAN"), ((PiiBlockedException) thrown.getCause()).entityTypes());
    }

    @Test
    void dryRunReportsButDoesNotRewriteToolArguments() {
        GavioRequest original = history(args("iban", IBAN), "ok");
        InterceptorContext ctx = ctx().dryRun(true);
        assertSame(original, guard().before(original, ctx).join());
        assertTrue(ctx.piiEntityTypes().contains("IBAN"));
    }

    @Test
    void placeholdersAreRestoredInResponseToolCallArguments() {
        PiiGuard guard = guard();
        InterceptorContext ctx = ctx();
        guard.before(history(args("iban", IBAN), "ok"), ctx).join();
        GavioResponse response = GavioResponse.builder()
                .traceId("t").content("Sending to [IBAN_1]").model("m").provider("mock")
                .toolCalls(List.of(new ToolCall("call_2", "transfer", args("iban", "[IBAN_1]", "amount", 5L))))
                .finishReason(FinishReason.TOOL_CALLS)
                .build();

        GavioResponse restored = guard.after(response, ctx).join();

        assertEquals("Sending to " + IBAN, restored.content());
        assertEquals(List.of(new ToolCall("call_2", "transfer", args("iban", IBAN, "amount", 5L))),
                restored.toolCalls());
        assertEquals(FinishReason.TOOL_CALLS, restored.finishReason());
        // The original response is not mutated.
        assertEquals("[IBAN_1]", response.toolCalls().get(0).arguments().get("iban"));
    }

    @Test
    void argumentsAreRestoredEvenWhenTheContentHasNothingToRestore() {
        PiiGuard guard = guard();
        InterceptorContext ctx = ctx();
        guard.before(history(args("iban", IBAN), "ok"), ctx).join();
        GavioResponse response = GavioResponse.builder()
                .traceId("t").content("").model("m").provider("mock")
                .toolCalls(List.of(new ToolCall("c", "transfer", args("iban", "[IBAN_1]")))).build();
        assertEquals(IBAN, guard.after(response, ctx).join().toolCalls().get(0).arguments().get("iban"));
    }

    @Test
    void maskModeDoesNotRestoreToolArguments() {
        PiiGuard guard = PiiGuard.builder().mode(PiiMode.MASK).logEntityTypes(false).build();
        InterceptorContext ctx = ctx();
        guard.before(history(args("iban", IBAN), "ok"), ctx).join();
        GavioResponse response = GavioResponse.builder()
                .traceId("t").content("").model("m").provider("mock")
                .toolCalls(List.of(new ToolCall("c", "transfer", args("iban", "*".repeat(18))))).build();
        assertSame(response, guard.after(response, ctx).join());
    }

    // ---- gateway end to end ---------------------------------------------------------------------

    /** Answers every call with the given tool calls and records what it was sent. */
    private static final class ToolCallingProvider implements ProviderAdapter {
        final List<GavioRequest> seen = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        private final MockProvider delegate = new MockProvider("");
        private final List<ToolCall> toolCalls;

        ToolCallingProvider(ToolCall... toolCalls) {
            this.toolCalls = List.of(toolCalls);
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            calls.incrementAndGet();
            seen.add(request);
            return delegate.complete(request).thenApply(
                    r -> r.withToolCalls(toolCalls).withFinishReason(FinishReason.TOOL_CALLS));
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class CapturingSink implements AuditSink {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.add(record);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void gatewayRedactsToolArgumentsRestoresThemAndAuditsHashesOnly() {
        ToolCallingProvider provider = new ToolCallingProvider(
                new ToolCall("call_2", "transfer", args("iban", "[IBAN_1]")));
        Gateway gateway = Gateway.builder()
                .adapter(provider).model("mock")
                .use(new AuditInterceptor(new CapturingSink()))
                .use(guard())
                .build();
        GavioRequest h = history(args("iban", IBAN), "ok");

        GavioResponse response = gateway.completeWithTools(h.messages(), h.tools(), ToolChoice.auto()).join();

        GavioRequest sent = provider.seen.get(0);
        assertEquals(h.tools(), sent.tools());
        assertEquals(ToolChoice.auto(), sent.toolChoice());
        assertFalse(Json.write(OpenAiAdapter.toProviderPayload(sent)).contains(IBAN));
        // The caller sees the real value again...
        assertEquals(List.of(new ToolCall("call_2", "transfer", args("iban", IBAN))), response.toolCalls());
        // ...but the audit record keeps only the tool name and the arguments hash.
        AuditRecord audit = (AuditRecord) response.audit();
        assertNotNull(audit);
        assertEquals(List.of(new ToolCallDigest("transfer", response.toolCalls().get(0).argumentsHash())),
                audit.toolCalls());
        String json = audit.toJson();
        assertFalse(json.contains(IBAN));
        assertFalse(json.contains("[IBAN_1]"));
        assertFalse(json.contains("\"arguments\""));
        assertTrue(json.contains("\"arguments_hash\""));
    }

    @Test
    void streamRefusesToolsInsteadOfSilentlyDroppingThem() {
        Gateway gateway = Gateway.builder().adapter(new MockProvider("x")).model("mock").build();
        GavioRequest request = GavioRequest.builder().message("user", "hi").tools(transferTool()).build();
        assertThrows(ConfigurationException.class, () -> gateway.stream(request));
        // A request without tools still streams.
        assertNotNull(gateway.stream(List.of(Message.of("user", "hi"))));
    }

    // ---- audit ----------------------------------------------------------------------------------

    private static AuditRecord.Builder record() {
        return AuditRecord.builder()
                .traceId("t").provider("p").model("m").timestampUtc("2026-01-01T00:00:00+00:00");
    }

    @Test
    void auditRecordOmitsToolCallsWhenEmptySoExistingChainsStillVerify() {
        AuditRecord plain = record().build();
        assertFalse(plain.toMap().containsKey("tool_calls"));
        assertFalse(plain.toJson().contains("tool_calls"));
        AuditRecord withCalls = record().toolCalls(List.of(new ToolCallDigest("t", "h"))).build();
        assertEquals(List.of(args("name", "t", "arguments_hash", "h")), withCalls.toMap().get("tool_calls"));
        assertNotEquals(plain.contentHash(), withCalls.contentHash());

        AuditRecord first = record().build();
        AuditRecord second = record().previousHash(first.contentHash())
                .toolCalls(List.of(new ToolCallDigest("t", "h"))).build();
        AuditRecord third = record().previousHash(second.contentHash()).build();
        assertTrue(AuditChain.verifyChain(List.of(first, second, third)));
        AuditRecord tampered = record().previousHash(first.contentHash())
                .toolCalls(List.of(new ToolCallDigest("t", "tampered"))).build();
        assertFalse(AuditChain.verifyChain(List.of(first, tampered, third)));
    }

    @Test
    void auditRecordKeepsItsOldConstructor() {
        AuditRecord record = new AuditRecord("t", "p", "m", "2026-01-01T00:00:00+00:00", null, null, null, null,
                "", "", "", null, 0.0, 0L, null, null, null, false, null, null, null, null, "", "1.0");
        assertTrue(record.toolCalls().isEmpty());
    }

    @Test
    void aCallWithoutToolCallsRecordsNone() {
        CapturingSink sink = new CapturingSink();
        Gateway gateway = Gateway.builder().adapter(new MockProvider("x")).model("mock")
                .use(new AuditInterceptor(sink)).build();
        GavioResponse response = gateway.complete(List.of(Message.of("user", "hi"))).join();
        assertTrue(((AuditRecord) response.audit()).toolCalls().isEmpty());
        assertFalse(sink.records.get(0).toMap().containsKey("tool_calls"));
    }

    @Test
    void theArgumentsHashChangesWithTheArgumentsButNotTheirKeyOrder() {
        CapturingSink sink = new CapturingSink();
        AuditInterceptor audit = new AuditInterceptor(sink);
        for (Map<String, Object> arguments : List.of(
                args("a", 1L, "b", 2L), args("b", 2L, "a", 1L), args("a", 1L, "b", 3L))) {
            GavioResponse response = GavioResponse.builder().traceId("t").content("").model("m").provider("p")
                    .toolCalls(List.of(new ToolCall("c", "t", arguments))).build();
            audit.after(response, ctx()).join();
        }
        List<String> hashes = sink.records.stream().map(r -> r.toolCalls().get(0).argumentsHash()).toList();
        assertEquals(hashes.get(0), hashes.get(1));
        assertNotEquals(hashes.get(0), hashes.get(2));
    }

    // ---- cache ------------------------------------------------------------------------------------

    /** Counts provider calls and answers with plain text. */
    private static final class Counting implements ProviderAdapter {
        final AtomicInteger calls = new AtomicInteger();
        private final MockProvider delegate = new MockProvider("x");

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            calls.incrementAndGet();
            return delegate.complete(request);
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
    }

    @Test
    void theCacheNeverReplaysAToolCallResponse() {
        ToolCallingProvider provider = new ToolCallingProvider(new ToolCall("c", "t", args("a", 1L)));
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        List<Message> messages = List.of(Message.of("user", "do it"));

        GavioResponse first = gateway.complete(messages).join();
        GavioResponse second = gateway.complete(messages).join();

        assertEquals(2, provider.calls.get());
        assertFalse(first.cacheHit());
        assertFalse(second.cacheHit());
        assertEquals(List.of(new ToolCall("c", "t", args("a", 1L))), second.toolCalls());
    }

    @Test
    void theCacheKeySeparatesDifferentToolSets() {
        Counting provider = new Counting();
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        List<Message> messages = List.of(Message.of("user", "hello"));
        List<Map<String, Object>> a = List.of(args("name", "a"));
        List<Map<String, Object>> b = List.of(args("name", "b"));

        gateway.completeWithTools(messages, a, null).join();
        gateway.completeWithTools(messages, a, null).join(); // same tools -> cached
        gateway.completeWithTools(messages, b, null).join(); // different tools -> miss
        gateway.completeWithTools(messages, a, ToolChoice.required()).join(); // different choice -> miss
        gateway.complete(messages).join(); // no tools -> miss
        assertEquals(4, provider.calls.get());
    }

    @Test
    void theCacheKeyCoversToolCallsAndResultsInTheHistory() {
        Counting provider = new Counting();
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        gateway.complete(history(args("iban", "A"), "ok")).join();
        gateway.complete(history(args("iban", "A"), "ok")).join(); // identical -> cached
        gateway.complete(history(args("iban", "B"), "ok")).join(); // other arguments -> miss
        assertEquals(2, provider.calls.get());
    }

    @Test
    void aPlainRequestStillHitsTheCache() {
        Counting provider = new Counting();
        Gateway gateway = Gateway.builder().adapter(provider).model("mock").use(new SemanticCache()).build();
        gateway.complete(List.of(Message.of("user", "hello"))).join();
        GavioResponse second = gateway.complete(List.of(Message.of("user", "hello"))).join();
        assertTrue(second.cacheHit());
        assertEquals(1, provider.calls.get());
    }

    @Test
    void requestsWithToolsSkipTheSemanticLevel() {
        Counting provider = new Counting();
        Gateway gateway = Gateway.builder().adapter(provider).model("mock")
                .use(SemanticCache.builder().embedder(new HashingEmbedder()).build()).build();
        List<Message> messages = List.of(Message.of("user", "hello there"));
        gateway.completeWithTools(messages, List.of(args("name", "a")), null).join();
        // Identical text, so a semantic hit would be certain — but the tools differ.
        GavioResponse second = gateway.completeWithTools(messages, List.of(args("name", "b")), null).join();
        assertFalse(second.cacheHit());
        assertEquals(2, provider.calls.get());

        // Without tools the semantic level is still used.
        gateway.complete(messages).join();
        GavioResponse plain = gateway.complete(List.of(Message.of("user", "hello there"))).join();
        assertTrue(plain.cacheHit());
    }
}
