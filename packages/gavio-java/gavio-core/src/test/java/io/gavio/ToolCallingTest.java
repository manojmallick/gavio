package io.gavio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.MalformedToolCallException;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor.ProviderCallOptions;
import io.gavio.json.CanonicalJson;
import io.gavio.json.Json;
import io.gavio.json.JsonValues;
import io.gavio.providers.ChatCompletionsWire;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.toolcalling.ProviderCompletion;
import io.gavio.toolcalling.ToolCalling;
import io.gavio.toolcalling.ToolCalling.ToolSpec;
import io.gavio.types.FinishReason;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import io.gavio.types.TokenUsage;
import io.gavio.types.ToolCall;
import io.gavio.types.ToolChoice;
import io.gavio.types.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Normalised tool calling (F-ADP-03), the parts that live in {@code gavio-core}: the value types,
 * spec conformance, the translation helpers and the Tool Runtime bridge. The shared vectors
 * ({@code test-vectors/tool-calling/*.json}) and the privacy / audit / cache behaviour run in
 * {@code gavio-testing}.
 */
class ToolCallingTest {

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate " + relative + " from working dir");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws IOException {
        return Json.parseObject(Files.readString(repoFile("spec/" + name + ".schema.json")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    // ---- arguments hash (the shared vectors run in gavio-testing: ToolCallingVectorsTest) ----

    @Test
    void argumentsHashIgnoresKeyOrder() {
        assertEquals(new ToolCall("c", "t", args("a", 2L, "b", 1L)).argumentsHash(),
                new ToolCall("c", "t", args("b", 1L, "a", 2L)).argumentsHash());
        assertNotEquals(new ToolCall("c", "t", args("a", 1L)).argumentsHash(),
                new ToolCall("c", "t", args("a", 2L)).argumentsHash());
    }

    @Test
    void canonicalJsonOrdersKeysByCodePointNotUtf16Unit() {
        // U+FF5E sorts before U+1F600 by code point; by UTF-16 unit the surrogate (0xD83D) is first.
        assertEquals("{\"～\":2,\"😀\":1}",
                CanonicalJson.write(args("😀", 1L, "～", 2L)));
    }

    @Test
    void jsonParsedArgumentsHashLikeBuiltOnes() {
        Map<String, Object> parsed = Json.parseObject("{\"b\": [1, 2], \"a\": {\"y\": \"x\", \"n\": null}}");
        assertEquals("{\"a\":{\"n\":null,\"y\":\"x\"},\"b\":[1,2]}", CanonicalJson.write(parsed));
        assertEquals(64, new ToolCall("c", "t", parsed).argumentsHash().length());
    }

    // ---- value types ----------------------------------------------------------------------

    @Test
    void toolCallValidatesAndCopies() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("", "t"));
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("c", ""));
        assertThrows(IllegalArgumentException.class, () -> new ToolCall(null, "t"));

        Map<String, Object> source = args("a", new ArrayList<>(List.of(1L)));
        ToolCall call = new ToolCall("c", "t", source);
        source.put("b", 2L);
        ((List<Object>) source.get("a")).add(9L);
        assertEquals(args("a", List.of(1L)), call.arguments(), "the call holds its own deep copy");
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("x", 1));
        assertEquals(Map.of(), new ToolCall("c", "t", null).arguments());
        // JSON null values are allowed in arguments.
        assertDoesNotThrow(() -> new ToolCall("c", "t", args("none", null)));
    }

    @Test
    void toolCallFromValidatesItsInput() {
        assertEquals(new ToolCall("c", "t"), ToolCall.from(Map.of("id", "c", "name", "t")));
        assertEquals(new ToolCall("c", "t", args("a", 1L)),
                ToolCall.from(Map.of("id", "c", "name", "t", "arguments", args("a", 1L))));
        assertThrows(IllegalArgumentException.class, () -> ToolCall.from(Map.of("id", "c", "name", "t", "arguments", "{}")));
        assertThrows(IllegalArgumentException.class, () -> ToolCall.from("not an object"));
        assertThrows(IllegalArgumentException.class, () -> ToolCall.from(Map.of("name", "t")));
        ToolCall call = new ToolCall("c", "t");
        assertSame(call, ToolCall.from(call));
    }

    @Test
    void toolCallToMapCarriesExactlyTheSpecProperties() throws IOException {
        Map<String, Object> spec = schema("ToolCall");
        Map<String, Object> map = new ToolCall("c1", "t", args("a", 1L)).toMap();
        assertEquals(new TreeSet<>(properties(spec).keySet()), new TreeSet<>(map.keySet()));
        assertEquals(List.of("id", "name", "arguments"), spec.get("required"));
        assertEquals(Boolean.FALSE, spec.get("additionalProperties"));
        // toMap returns a copy of the arguments.
        ((Map<String, Object>) map.get("arguments")).put("changed", true);
        assertEquals(args("a", 1L), new ToolCall("c1", "t", args("a", 1L)).toMap().get("arguments"));
    }

    @Test
    void toolResultRoundTripsThroughAMessage() throws IOException {
        ToolCall call = new ToolCall("c1", "get_weather", args("city", "Paris"));
        assertEquals(new Message("tool", "18C", List.of(), "c1", "get_weather", false),
                call.result("18C").toMessage());
        ToolResult failed = call.result("boom", true);
        assertEquals(new ToolResult("c1", "boom", "get_weather", true), failed);
        assertTrue(failed.toMessage().isError());

        Map<String, Object> spec = schema("ToolResult");
        Map<String, Object> wire = failed.toMessage().toMap();
        wire.remove("role");
        assertTrue(properties(spec).keySet().containsAll(wire.keySet()), wire.toString());
        assertEquals(List.of("toolCallId", "content"), spec.get("required"));
        assertEquals(Set.of("toolCallId", "content", "name", "isError"), properties(spec).keySet());
    }

    @Test
    void messageToMapUsesTheSpecSpelling() throws IOException {
        Message assistant = Message.assistantToolCalls("", List.of(new ToolCall("c", "t", args("a", 1L))));
        Map<String, Object> wire = assistant.toMap();
        assertEquals(List.of("role", "content", "toolCalls"), new ArrayList<>(wire.keySet()));
        // A plain message has exactly role and content.
        assertEquals(List.of("role", "content"), new ArrayList<>(Message.of("user", "hi").toMap().keySet()));

        Map<String, Object> messageSchema = (Map<String, Object>) ((Map<String, Object>) schema("GavioRequest")
                .get("$defs")).get("message");
        Set<String> allowed = properties(messageSchema).keySet();
        Map<String, Object> tool = new Message("tool", "x", List.of(), "c", "t", true).toMap();
        assertTrue(allowed.containsAll(tool.keySet()), tool.keySet().toString());
        assertTrue(allowed.containsAll(wire.keySet()));
    }

    @Test
    void messageFromReadsCamelAndSnakeCase() {
        List<Object> calls = List.of(Map.of("id", "c1", "name", "t", "arguments", Map.of()));
        Message camel = Message.from(Map.of("role", "assistant", "content", "", "toolCalls", calls));
        Message snake = Message.from(Map.of("role", "assistant", "content", "", "tool_calls", calls));
        assertEquals(camel, snake);
        assertEquals(List.of(new ToolCall("c1", "t")), camel.toolCalls());

        Message tool = Message.from(Map.of("role", "tool", "toolCallId", "c1", "isError", true, "content", "x"));
        assertEquals(tool, Message.from(Map.of("role", "tool", "tool_call_id", "c1", "is_error", true, "content", "x")));
        assertEquals("c1", tool.toolCallId());
        assertTrue(tool.isError());
        // OpenAI-style tool-call turns send null content.
        Map<String, Object> nullContent = new LinkedHashMap<>();
        nullContent.put("role", "assistant");
        nullContent.put("content", null);
        nullContent.put("toolCalls", calls);
        assertEquals("", Message.from(nullContent).content());
    }

    @Test
    void messageWithContentKeepsToolMembers() {
        Message tool = new Message("tool", "x", List.of(), "c", "t", true);
        assertEquals(new Message("tool", "y", List.of(), "c", "t", true), tool.withContent("y"));
        Message assistant = Message.assistantToolCalls("a", List.of(new ToolCall("c", "t")));
        assertEquals(assistant.toolCalls(), assistant.withContent("b").toolCalls());
        assertTrue(assistant.hasToolCalls());
        assertFalse(Message.of("user", "hi").hasToolFields());
        assertTrue(tool.hasToolFields());
    }

    @Test
    void finishReasonValuesMatchTheResponseSchemaEnum() throws IOException {
        Map<String, Object> property = (Map<String, Object>) properties(schema("GavioResponse")).get("finishReason");
        List<String> allowed = new ArrayList<>();
        for (Object value : (List<Object>) property.get("enum")) {
            if (value != null) {
                allowed.add((String) value);
            }
        }
        List<String> values = new ArrayList<>();
        for (FinishReason reason : FinishReason.values()) {
            values.add(reason.value());
            assertEquals(reason, FinishReason.fromValue(reason.value()));
        }
        assertEquals(new TreeSet<>(allowed), new TreeSet<>(values));
        assertNull(FinishReason.fromValue(null));
        assertThrows(IllegalArgumentException.class, () -> FinishReason.fromValue("nope"));
    }

    @Test
    void requestResponseAndAuditSchemasDefineTheToolKeys() throws IOException {
        assertTrue(properties(schema("GavioRequest")).keySet().containsAll(Set.of("tools", "toolChoice")));
        assertTrue(properties(schema("GavioResponse")).keySet().containsAll(Set.of("toolCalls", "finishReason")));
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) properties(schema("AuditRecord"))
                .get("toolCalls")).get("items");
        assertEquals(List.of("name", "argumentsHash"), items.get("required"));
        assertEquals(Boolean.FALSE, items.get("additionalProperties"));
    }

    // ---- request and response ------------------------------------------------------------

    @Test
    void promptTextIncludesToolCallsButIsUnchangedWithoutThem() {
        GavioRequest plain = GavioRequest.builder()
                .message("user", "a").message("assistant", "b").build();
        assertEquals("a\nb", plain.promptText());

        GavioRequest withCalls = GavioRequest.builder()
                .message("user", "a")
                .message(Message.assistantToolCalls("", List.of(new ToolCall("c", "t", args("k", "v")))))
                .build();
        assertEquals("a\n\nt({\"k\":\"v\"})", withCalls.promptText());
        // A tool result is ordinary text.
        assertEquals("a\n\nt({\"k\":\"v\"})\nok", GavioRequest.builder()
                .messages(withCalls.messages()).message(Message.toolResult("c", "ok")).build().promptText());
    }

    @Test
    void requestCopiesCarryEveryField() {
        GavioRequest original = GavioRequest.builder()
                .message("user", "hi")
                .model("m")
                .provider(Provider.OPENAI)
                .agentId("a")
                .parentTraceId("p")
                .sessionId("s")
                .option("temperature", 0.1)
                .metadata("k", 1)
                .tools(List.of(args("name", "t")))
                .toolChoice(ToolChoice.tool("t"))
                .build();
        GavioRequest copy = original.withMessages(List.of(Message.of("user", "changed")));
        assertEquals("changed", copy.messages().get(0).content());
        assertEquals(original.tools(), copy.tools());
        assertEquals(ToolChoice.tool("t"), copy.toolChoice());
        assertEquals(original.provider(), copy.provider());
        assertEquals(original.options(), copy.options());
        assertEquals(original.tools(), original.withModel("x").tools());
        assertEquals(original.toolChoice(), original.withProvider(Provider.MOCK).toolChoice());
        assertTrue(original.hasTools());
        assertFalse(GavioRequest.builder().message("user", "hi").build().hasTools());
    }

    @Test
    void requestKeepsTheOldConstructorAndDeepCopiesTools() {
        Map<String, Object> tool = args("name", "t", "inputSchema", args("type", "object"));
        GavioRequest request = GavioRequest.builder().message("user", "hi").tool(tool).build();
        tool.put("name", "changed");
        assertEquals("t", request.tools().get(0).get("name"));
        assertThrows(UnsupportedOperationException.class, () -> request.tools().add(Map.of()));

        GavioRequest old = new GavioRequest(List.of(Message.of("user", "hi")), "m", Provider.MOCK,
                null, null, null, null, Map.of(), Map.of(), List.of(), null);
        assertTrue(old.tools().isEmpty());
        assertNull(old.toolChoice());
    }

    @Test
    void responseCopiesCarryToolCallsAndFinishReason() {
        ToolCall call = new ToolCall("c", "t", args("a", 1L));
        GavioResponse original = GavioResponse.builder()
                .traceId("t").content("old").model("m").provider("openai")
                .usage(new TokenUsage(1, 2)).toolCalls(List.of(call)).finishReason(FinishReason.TOOL_CALLS)
                .build();
        for (GavioResponse copy : List.of(
                original.withContent("new"),
                original.withInterceptorsFired(List.of("a")),
                original.withAudit("record"),
                original.withEmbeddings(List.of(List.of(0.1))))) {
            assertEquals(List.of(call), copy.toolCalls());
            assertEquals(FinishReason.TOOL_CALLS, copy.finishReason());
        }
        assertEquals(List.of(), original.withToolCalls(List.of()).toolCalls());
        assertEquals(FinishReason.STOP, original.withFinishReason(FinishReason.STOP).finishReason());
        assertTrue(GavioResponse.builder().traceId("t").build().toolCalls().isEmpty());
    }

    @Test
    void aResponseFeedsTheNextRequestThroughToAssistantMessage() {
        ToolCall call = new ToolCall("call_1", "get_weather", args("city", "Paris"));
        GavioResponse response = GavioResponse.builder()
                .traceId("t").content("").model("m").provider("openai").toolCalls(List.of(call)).build();
        List<Message> messages = List.of(
                Message.of("user", "weather?"), response.toAssistantMessage(), call.result("{\"t\":18}").toMessage());

        Map<String, Object> payload = ChatCompletionsWire.toProviderPayload(
                GavioRequest.builder().messages(messages).model("gpt-4o").provider(Provider.OPENAI).build(), true);
        List<Map<String, Object>> sent = (List<Map<String, Object>>) payload.get("messages");
        Map<String, Object> function = (Map<String, Object>)
                ((Map<String, Object>) ((List<Object>) sent.get(1).get("tool_calls")).get(0)).get("function");
        assertEquals("{\"city\":\"Paris\"}", function.get("arguments"));
        assertEquals(args("role", "tool", "tool_call_id", "call_1", "content", "{\"t\":18}"), sent.get(2));
        assertEquals(Message.of("assistant", "hi"), GavioResponse.builder()
                .traceId("t").content("hi").build().toAssistantMessage());
    }

    // ---- translation helpers ----------------------------------------------------------------

    @Test
    void toolChoiceParsesItsWireShapes() {
        assertNull(ToolChoice.parse(null));
        assertEquals(ToolChoice.auto(), ToolChoice.parse("auto"));
        assertEquals(ToolChoice.none(), ToolChoice.parse("none"));
        assertEquals(ToolChoice.required(), ToolChoice.parse("required"));
        assertEquals(ToolChoice.tool("t"), ToolChoice.parse(Map.of("name", "t")));
        assertSame(ToolChoice.auto(), ToolChoice.parse(ToolChoice.auto()));
        assertThrows(ConfigurationException.class, () -> ToolChoice.parse("sometimes"));
        assertThrows(ConfigurationException.class, () -> ToolChoice.parse(Map.of("x", 1)));
        assertThrows(ConfigurationException.class, () -> ToolChoice.parse(Map.of("name", "")));
        assertThrows(ConfigurationException.class, () -> ToolChoice.parse(42));
        assertEquals("required", ToolChoice.required().toWire());
        assertEquals(Map.of("name", "t"), ToolChoice.tool("t").toWire());
    }

    @Test
    void toolChoiceWithoutToolsIsNotSent() {
        GavioRequest request = GavioRequest.builder().message("user", "hi").toolChoice("required").build();
        assertNull(ToolCalling.activeToolChoice(request));
        Map<String, Object> payload = ChatCompletionsWire.toProviderPayload(request, true);
        assertFalse(payload.containsKey("tools"));
        assertFalse(payload.containsKey("tool_choice"));
    }

    @Test
    void toolSpecsReadSnakeCaseAndDropGovernanceFields() {
        List<ToolSpec> specs = ToolCalling.toolSpecs(List.of(
                args("name", "t", "description", "d",
                        "input_schema", args("type", "object", "properties", args("a", args("type", "string"))),
                        "permissions", List.of("x"), "risk", "destructive", "requires_approval", true),
                args("name", "bare")));
        assertEquals(2, specs.size());
        assertEquals("d", specs.get(0).description());
        assertEquals(args("type", "object", "properties", args("a", args("type", "string"))),
                specs.get(0).inputSchema());
        assertNull(specs.get(1).description());
        assertNull(specs.get(1).inputSchema());

        Map<String, Object> function = (Map<String, Object>) ((Map<String, Object>) ((List<Object>)
                ChatCompletionsWire.toProviderPayload(GavioRequest.builder().message("user", "hi")
                        .tool(args("name", "t", "input_schema", args("type", "object"), "permissions", List.of("x")))
                        .build(), true).get("tools")).get(0)).get("function");
        assertEquals(args("name", "t", "parameters", args("type", "object")), function);
    }

    @Test
    void invalidDeclarationsAreRejected() {
        assertThrows(ConfigurationException.class, () -> ToolCalling.toolSpecs(List.of(args("description", "no name"))));
        assertThrows(ConfigurationException.class, () -> ToolCalling.toolSpecs(List.of(args("name", ""))));
        GavioRequest noName = GavioRequest.builder().message("user", "hi").tool(args("description", "x")).build();
        assertThrows(ConfigurationException.class, () -> ChatCompletionsWire.toProviderPayload(noName, true));
    }

    @Test
    void argumentsSerialiseCompactlyInInsertionOrder() {
        assertEquals("{\"z\":1,\"a\":{\"y\":\"é\",\"b\":[1,2]}}",
                ToolCalling.dumpArguments(args("z", 1L, "a", args("y", "é", "b", List.of(1L, 2L)))));
        assertEquals("{\"z\":1,\"a\":2}", ToolCalling.dumpArguments(args("z", 1L, "a", 2L)));
        assertEquals("call_2_get_weather", ToolCalling.syntheticCallId(2, "get_weather"));
    }

    @Test
    void parseArgumentsAcceptsObjectsStringsAndNothing() {
        assertEquals(Map.of(), ToolCalling.parseArguments(null, "t"));
        assertEquals(Map.of(), ToolCalling.parseArguments("", "t"));
        assertEquals(Map.of(), ToolCalling.parseArguments("  \n", "t"));
        assertEquals(args("a", 1L), ToolCalling.parseArguments("{\"a\": 1}", "t"));
        assertEquals(args("a", 1L), ToolCalling.parseArguments(args("a", 1L), "t"));
        // The result is a copy, not the provider's own object.
        Map<String, Object> source = args("a", args("b", 1L));
        ToolCalling.parseArguments(source, "t").put("x", 1);
        assertEquals(args("a", args("b", 1L)), source);
    }

    @Test
    void parseArgumentsRejectsWhatIsNotAnObjectWithoutLeakingIt() {
        for (Object bad : new Object[] {"{not json", "[1, 2]", "\"text\"", "42", "{\"city\": \"Paris\"} trailing",
                "{\"city\": \"Pa\\u00", 7L, List.of("Paris")}) {
            MalformedToolCallException thrown = assertThrows(MalformedToolCallException.class,
                    () -> ToolCalling.parseArguments(bad, "get_weather"), String.valueOf(bad));
            assertTrue(thrown.getMessage().contains("get_weather"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains("Paris"), thrown.getMessage());
            assertTrue(thrown instanceof GavioException.ProviderException);
        }
    }

    @Test
    void finishReasonNormalisation() {
        Map<String, FinishReason> mapping = Map.of("stop", FinishReason.STOP, "length", FinishReason.LENGTH);
        ToolCall call = new ToolCall("c", "t");
        assertEquals(FinishReason.STOP, ToolCalling.normaliseFinishReason("stop", mapping, List.of()));
        assertEquals(FinishReason.OTHER, ToolCalling.normaliseFinishReason("weird", mapping, List.of()));
        assertNull(ToolCalling.normaliseFinishReason(null, mapping, List.of()));
        // A plain stop (or none) that produced tool calls is tool_calls; other reasons stay.
        assertEquals(FinishReason.TOOL_CALLS, ToolCalling.normaliseFinishReason("stop", mapping, List.of(call)));
        assertEquals(FinishReason.TOOL_CALLS, ToolCalling.normaliseFinishReason(null, mapping, List.of(call)));
        assertEquals(FinishReason.LENGTH, ToolCalling.normaliseFinishReason("length", mapping, List.of(call)));
    }

    @Test
    void providerCompletionToWireIsTheSpecShape() {
        ProviderCompletion completion = new ProviderCompletion(
                null, List.of(new ToolCall("c", "t", args("a", 1L))), FinishReason.TOOL_CALLS,
                new TokenUsage(3, 4), null);
        Map<String, Object> wire = completion.toWire();
        assertEquals("", wire.get("content"));
        assertEquals("tool_calls", wire.get("finishReason"));
        assertEquals(args("promptTokens", 3, "completionTokens", 4, "totalTokens", 7), wire.get("usage"));
        assertNull(wire.get("modelVersion"));
        assertEquals(List.of(args("id", "c", "name", "t", "arguments", args("a", 1L))), wire.get("toolCalls"));
    }

    @Test
    void jsonValuesHelpers() {
        Map<String, Object> source = args("a", args("b", List.of(1L, "x")), "n", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = (Map<String, Object>) JsonValues.mapLeaves(source,
                leaf -> leaf instanceof String s ? s.toUpperCase() : leaf);
        assertEquals(args("a", args("b", List.of(1L, "X")), "n", null), mapped);
        assertEquals(args("a", args("b", List.of(1L, "x")), "n", null), source, "the input is not modified");
        mapped.put("new", 1); // the result is mutable
        assertThrows(UnsupportedOperationException.class,
                () -> JsonValues.immutableMap(source).put("new", 1));
        assertNotSame(source, JsonValues.mutableMap(source));
        assertEquals(Map.of(), JsonValues.immutableMap(null));
    }

    // ---- Gateway --------------------------------------------------------------------------

    @Test
    void streamRefusesToolsInsteadOfDroppingThem() {
        Gateway gateway = Gateway.builder().adapter(new MockProvider("x")).model("mock").build();
        GavioRequest request = GavioRequest.builder().message("user", "hi").tool(args("name", "t")).build();
        assertThrows(ConfigurationException.class, () -> gateway.stream(request));
    }

    @Test
    void completeWithToolsPassesToolsAndChoiceToTheAdapter() {
        AtomicReference<GavioRequest> seen = new AtomicReference<>();
        ProviderAdapter adapter = new ProviderAdapter() {
            private final MockProvider delegate = new MockProvider("ok");

            @Override
            public String providerName() {
                return "mock";
            }

            @Override
            public CompletableFuture<GavioResponse> complete(GavioRequest request) {
                seen.set(request);
                return delegate.complete(request);
            }

            @Override
            public CompletableFuture<Boolean> healthCheck() {
                return CompletableFuture.completedFuture(true);
            }
        };
        Gateway gateway = Gateway.builder().adapter(adapter).model("mock").build();
        List<Map<String, Object>> tools = List.of(args("name", "t"));
        gateway.completeWithTools(List.of(Message.of("user", "hi")), tools, ToolChoice.required()).join();
        assertEquals(tools, seen.get().tools());
        assertEquals(ToolChoice.required(), seen.get().toolChoice());

        gateway.completeWithTools(List.of(Message.of("user", "hi")), null, null).join();
        assertFalse(seen.get().hasTools());
    }

    // ---- Tool Runtime bridge -----------------------------------------------------------------

    @Test
    void bridgeMapsToolCallsToRuntimeCalls() {
        List<ToolCall> calls = List.of(new ToolCall("call_1", "lookup_invoice", args("invoice_id", "INV-1")));
        List<Map<String, Object>> mapped = ToolRuntimeInterceptor.toolCallsToRuntimeCalls(calls, null, "openai");
        assertEquals(List.of(args("id", "call_1", "name", "lookup_invoice",
                "arguments", args("invoice_id", "INV-1"), "source", "openai")), mapped);
        // The bridge copies the arguments.
        ((Map<String, Object>) mapped.get(0).get("arguments")).put("invoice_id", "changed");
        assertEquals("INV-1", calls.get(0).arguments().get("invoice_id"));

        List<Map<String, Object>> withResult = ToolRuntimeInterceptor.toolCallsToRuntimeCalls(
                calls, Map.of("call_1", args("total", 5L)), null);
        assertEquals(args("total", 5L), withResult.get(0).get("result"));
        assertFalse(withResult.get(0).containsKey("source"));

        Map<String, Object> decision = ToolRuntimeInterceptor.analyze(Map.of("calls", mapped));
        assertEquals(1, ((Number) decision.get("call_count")).intValue());
        assertEquals("lookup_invoice",
                ((Map<String, Object>) ((List<Object>) decision.get("decisions")).get(0)).get("tool_name"));
    }

    @Test
    void bridgeValidatesProviderCallsAgainstDefinitionsAndPermissions() {
        List<Map<String, Object>> definitions = List.of(
                args("name", "lookup_invoice", "permissions", List.of("read.billing")),
                args("name", "delete_account", "permissions", List.of("write.accounts"), "risk", "destructive"));
        List<ToolCall> calls = List.of(
                new ToolCall("call_1", "lookup_invoice", args("invoice_id", "INV-1")),
                new ToolCall("call_2", "delete_account", args("account_id", "A-9")));
        ProviderCallOptions options = ProviderCallOptions.builder().definitions(definitions);

        Map<String, Object> denied = ToolRuntimeInterceptor.analyzeProviderToolCalls(calls, null, options);
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) denied.get("decisions");
        assertEquals(List.of("block", "require_approval"), decisions.stream().map(d -> d.get("action")).toList());
        assertEquals(List.of("read.billing"), decisions.get(0).get("missing_permissions"));

        Map<String, Object> granted = ToolRuntimeInterceptor.analyzeProviderToolCalls(
                calls.subList(0, 1), Map.of("permissions", List.of("read.billing")), options);
        assertEquals("allow", ((List<Map<String, Object>>) granted.get("decisions")).get(0).get("action"));
        assertEquals(List.of(), granted.get("violations"));
    }

    @Test
    void bridgeKeepsCallsAlreadyInTheContextAndDoesNotModifyIt() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tool_calls", List.of(args("id", "old", "name", "earlier", "arguments", Map.of())));
        Map<String, Object> decision = ToolRuntimeInterceptor.analyzeProviderToolCalls(
                List.of(new ToolCall("new", "later")), context);
        assertEquals(List.of("old", "new"), ((List<Map<String, Object>>) decision.get("decisions")).stream()
                .map(d -> d.get("tool_id")).toList());
        assertFalse(context.containsKey("calls"));
        assertEquals(1, context.size());
    }

    @Test
    void bridgeUsesDefinitionsOnlyWhenTheContextHasNone() {
        List<Map<String, Object>> fallback = List.of(args("name", "t", "permissions", List.of("p")));
        Map<String, Object> ownDefinitions = Map.of("definitions", List.of(args("name", "t")));
        Map<String, Object> withOwn = ToolRuntimeInterceptor.analyzeProviderToolCalls(
                List.of(new ToolCall("c", "t")), ownDefinitions, ProviderCallOptions.builder().definitions(fallback));
        assertEquals("allow", ((List<Map<String, Object>>) withOwn.get("decisions")).get(0).get("action"));
        Map<String, Object> withFallback = ToolRuntimeInterceptor.analyzeProviderToolCalls(
                List.of(new ToolCall("c", "t")), null, ProviderCallOptions.builder().definitions(fallback));
        assertEquals("block", ((List<Map<String, Object>>) withFallback.get("decisions")).get(0).get("action"));
    }
}
