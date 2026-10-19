package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioException.MalformedToolCallException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.json.CanonicalJson;
import io.gavio.json.Json;
import io.gavio.providers.ProviderAdapter;
import io.gavio.providers.anthropic.AnthropicAdapter;
import io.gavio.providers.azure.AzureOpenAiAdapter;
import io.gavio.providers.gemini.GeminiAdapter;
import io.gavio.providers.ollama.OllamaAdapter;
import io.gavio.providers.openai.OpenAiAdapter;
import io.gavio.providers.openrouter.OpenRouterAdapter;
import io.gavio.toolcalling.ProviderCompletion;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import io.gavio.types.ToolCall;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Normalised tool calling (F-ADP-03): runs the shared {@code test-vectors/tool-calling/*.json} files
 * against every provider module — the same files the Python and JavaScript SDKs run.
 *
 * <p>Messages in the vectors use the spec's camelCase keys ({@code toolCalls}, {@code toolCallId},
 * {@code isError}), which is how the Java types spell them. Payloads and parsed responses are
 * compared as canonical JSON, so number types ({@code 256} vs {@code 256L}) and key order do not
 * matter; every value, including the compact {@code arguments} string OpenAI-style bodies carry,
 * must match exactly.
 */
class ToolCallingVectorsTest {

    /** The two pure functions a provider module exposes, plus a way to build its adapter for a URL. */
    private record Wire(
            String name,
            Function<GavioRequest, Map<String, Object>> toProviderPayload,
            Function<Map<String, Object>, ProviderCompletion> fromProviderResponse,
            Function<String, ProviderAdapter> adapterFor) {
    }

    private static final Wire OPENAI = new Wire("openai",
            OpenAiAdapter::toProviderPayload,
            OpenAiAdapter::fromProviderResponse,
            url -> OpenAiAdapter.builder().apiKey("k").baseUrl(url).build());
    private static final Wire OPENROUTER = new Wire("openrouter",
            OpenRouterAdapter::toProviderPayload,
            OpenRouterAdapter::fromProviderResponse,
            url -> OpenRouterAdapter.builder().apiKey("k").baseUrl(url).build());
    private static final Wire AZURE = new Wire("azure_openai",
            AzureOpenAiAdapter::toProviderPayload,
            AzureOpenAiAdapter::fromProviderResponse,
            url -> AzureOpenAiAdapter.builder().apiKey("k").endpoint(url).deployment("d").build());
    private static final Wire ANTHROPIC = new Wire("anthropic",
            AnthropicAdapter::toProviderPayload,
            AnthropicAdapter::fromProviderResponse,
            url -> AnthropicAdapter.builder().apiKey("k").baseUrl(url).build());
    private static final Wire GEMINI = new Wire("gemini",
            GeminiAdapter::toProviderPayload,
            GeminiAdapter::fromProviderResponse,
            url -> GeminiAdapter.builder().apiKey("k").baseUrl(url).build());
    private static final Wire OLLAMA = new Wire("ollama",
            OllamaAdapter::toProviderPayload,
            OllamaAdapter::fromProviderResponse,
            url -> OllamaAdapter.builder().baseUrl(url).build());

    /** Vector file -> the providers that must pass it (OpenRouter reuses openai.json unchanged). */
    private static final Map<String, List<Wire>> FILES = Map.of(
            "openai.json", List.of(OPENAI, OPENROUTER),
            "azure.json", List.of(AZURE),
            "anthropic.json", List.of(ANTHROPIC),
            "gemini.json", List.of(GEMINI),
            "ollama.json", List.of(OLLAMA));

    private static Path vectorsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/tool-calling");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/tool-calling from working dir");
    }

    private static Map<String, Object> doc(String file) throws IOException {
        return Json.parseObject(Files.readString(vectorsDir().resolve(file)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Map<String, Object> doc, String key) {
        Object raw = doc.get(key);
        return raw == null ? List.of() : (List<Map<String, Object>>) raw;
    }

    /** Build a request from a spec-shaped (camelCase) vector request. */
    @SuppressWarnings("unchecked")
    static GavioRequest vectorRequest(Map<String, Object> data) {
        GavioRequest.Builder builder = GavioRequest.builder()
                .model((String) data.get("model"))
                .provider(Provider.coerce(data.get("provider")));
        for (Object raw : (List<Object>) data.get("messages")) {
            builder.message(Message.from((Map<String, Object>) raw));
        }
        Map<String, Object> options = (Map<String, Object>) data.getOrDefault("options", Map.of());
        options.forEach(builder::option);
        if (data.get("tools") != null) {
            builder.tools((List<Map<String, Object>>) data.get("tools"));
        }
        if (data.get("toolChoice") != null) {
            builder.toolChoice(data.get("toolChoice"));
        }
        return builder.build();
    }

    private static String canonical(Object value) {
        return CanonicalJson.write(value);
    }

    private static Class<? extends RuntimeException> errorClass(String name) {
        return switch (name) {
            case "MalformedToolCall" -> MalformedToolCallException.class;
            case "Configuration" -> ConfigurationException.class;
            default -> throw new IllegalArgumentException("unknown expectedError " + name);
        };
    }

    @TestFactory
    Iterable<DynamicTest> sharedVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, List<Wire>> file : FILES.entrySet()) {
            Map<String, Object> vectors = doc(file.getKey());
            for (Wire wire : file.getValue()) {
                String prefix = wire.name() + " (" + file.getKey() + ") ";
                for (Map<String, Object> c : cases(vectors, "requestCases")) {
                    tests.add(DynamicTest.dynamicTest(prefix + "request: " + c.get("id"), () -> {
                        Map<String, Object> actual = wire.toProviderPayload().apply(vectorRequest(map(c.get("request"))));
                        assertEquals(canonical(c.get("expectedPayload")), canonical(actual));
                    }));
                }
                for (Map<String, Object> c : cases(vectors, "requestErrorCases")) {
                    tests.add(DynamicTest.dynamicTest(prefix + "request error: " + c.get("id"), () ->
                            assertThrows(errorClass((String) c.get("expectedError")),
                                    () -> wire.toProviderPayload().apply(vectorRequest(map(c.get("request")))))));
                }
                for (Map<String, Object> c : cases(vectors, "responseCases")) {
                    tests.add(DynamicTest.dynamicTest(prefix + "response: " + c.get("id"), () -> {
                        ProviderCompletion actual =
                                wire.fromProviderResponse().apply(map(c.get("providerResponse")));
                        assertEquals(canonical(c.get("expected")), canonical(actual.toWire()));
                    }));
                }
                for (Map<String, Object> c : cases(vectors, "errorCases")) {
                    tests.add(DynamicTest.dynamicTest(prefix + "malformed response: " + c.get("id"), () -> {
                        RuntimeException thrown = assertThrows(errorClass((String) c.get("expectedError")),
                                () -> wire.fromProviderResponse().apply(map(c.get("providerResponse"))));
                        // The message names the tool at most — never the arguments, which may hold PII.
                        assertFalse(thrown.getMessage().contains("{\"city\""), thrown.getMessage());
                        assertFalse(thrown.getMessage().contains("Paris"), thrown.getMessage());
                    }));
                }
            }
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @TestFactory
    Iterable<DynamicTest> argumentsHashVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map<String, Object> c : cases(doc("arguments-hash.json"), "cases")) {
            hashes.put((String) c.get("id"), (String) c.get("expectedHash"));
            tests.add(DynamicTest.dynamicTest("arguments hash: " + c.get("id"), () -> {
                ToolCall call = new ToolCall("c", "t", map(c.get("arguments")));
                assertEquals(c.get("expectedCanonical"), CanonicalJson.write(call.arguments()));
                assertEquals(c.get("expectedHash"), call.argumentsHash());
            }));
        }
        tests.add(DynamicTest.dynamicTest("arguments hash: key order does not matter", () ->
                assertEquals(hashes.get("key-order-does-not-matter-a"), hashes.get("key-order-does-not-matter-b"))));
        assertFalse(hashes.isEmpty());
        return tests;
    }

    @Test
    void everyVectorFileIsExercised() throws IOException {
        try (var listing = Files.list(vectorsDir())) {
            List<String> files = listing.map(p -> p.getFileName().toString()).sorted().toList();
            List<String> expected = new ArrayList<>(FILES.keySet());
            expected.add("arguments-hash.json");
            expected.sort(null);
            assertEquals(expected, files, "a new tool-calling vector file needs a runner in this class");
        }
    }

    // ---- complete() wiring --------------------------------------------------------------

    /**
     * Serves {@code providerResponse} for any POST and records the request body, so the real
     * {@code complete()} path (payload build, HTTP, parse, response build) runs end to end.
     */
    private static final class Fake implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> body = new AtomicReference<>();

        Fake(Object providerResponse) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] response = Json.write(providerResponse).getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @TestFactory
    Iterable<DynamicTest> completeReturnsToolCalls() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, List<Wire>> file : FILES.entrySet()) {
            Map<String, Object> vectors = doc(file.getKey());
            Map<String, Object> requestCase = cases(vectors, "requestCases").stream()
                    .filter(c -> "declares-tools-auto".equals(c.get("id"))).findFirst().orElseThrow();
            Map<String, Object> responseCase = cases(vectors, "responseCases").stream()
                    .filter(c -> !((List<?>) map(c.get("expected")).get("toolCalls")).isEmpty())
                    .findFirst().orElseThrow();
            for (Wire wire : file.getValue()) {
                tests.add(DynamicTest.dynamicTest("complete(): " + wire.name(), () -> {
                    try (Fake fake = new Fake(responseCase.get("providerResponse"))) {
                        GavioRequest request = vectorRequest(map(requestCase.get("request")));
                        GavioResponse response = wire.adapterFor().apply(fake.url()).complete(request).join();

                        // What went over the wire is the vector's native payload.
                        assertEquals(canonical(requestCase.get("expectedPayload")),
                                canonical(Json.parse(fake.body.get())));
                        // And what came back is normalised.
                        Map<String, Object> expected = map(responseCase.get("expected"));
                        List<Object> toolCalls = new ArrayList<>();
                        response.toolCalls().forEach(call -> toolCalls.add(call.toMap()));
                        assertEquals(canonical(expected.get("toolCalls")), canonical(toolCalls));
                        assertEquals(expected.get("finishReason"), response.finishReason().value());
                        assertEquals(expected.get("content"), response.content());
                        Map<String, Object> usage = map(expected.get("usage"));
                        assertEquals(((Number) usage.get("promptTokens")).intValue(),
                                response.usage().promptTokens());
                        assertEquals(expected.get("modelVersion") != null
                                ? expected.get("modelVersion") : request.model(), response.modelVersion());
                        assertNotNull(response.traceId());
                    }
                }));
            }
        }
        return tests;
    }

    @Test
    void tooledRequestIsOnlyAPayloadConcernForPlainResponses() throws IOException {
        // A provider that answers with text only yields a response without tool calls.
        Map<String, Object> vectors = doc("openai.json");
        Map<String, Object> textOnly = cases(vectors, "responseCases").stream()
                .filter(c -> "text-only".equals(c.get("id"))).findFirst().orElseThrow();
        try (Fake fake = new Fake(textOnly.get("providerResponse"))) {
            GavioResponse response = OPENAI.adapterFor().apply(fake.url())
                    .complete(GavioRequest.builder().message("user", "hi").model("gpt-4o").build()).join();
            assertTrue(response.toolCalls().isEmpty());
            assertEquals("stop", response.finishReason().value());
        }
    }
}
