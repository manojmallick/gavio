package io.gavio.providers.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.types.Provider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenRouterAdapterTest {
    @Test
    void providerNameAndHealth() {
        OpenRouterAdapter a = OpenRouterAdapter.builder().apiKey("k").build();
        assertEquals("openrouter", a.providerName());
        assertTrue(a.healthCheck().join());
        assertFalse(OpenRouterAdapter.builder().apiKey("").build().healthCheck().join());
    }

    @Test
    void buildsUrlAndAttributionHeaders() {
        OpenRouterAdapter a = OpenRouterAdapter.builder()
                .apiKey("k")
                .baseUrl("https://router.example/v1/")
                .httpReferer("https://app.example")
                .appTitle("Gavio")
                .build();
        Map<String, String> headers = a.headers();
        assertEquals("https://router.example/v1/chat/completions", a.url());
        assertEquals("Bearer k", headers.get("Authorization"));
        assertEquals("https://app.example", headers.get("HTTP-Referer"));
        assertEquals("Gavio", headers.get("X-OpenRouter-Title"));
    }

    @Test
    void providerRegistryBuildsThroughCoreBuilder() {
        Gateway gw = Gateway.builder().provider(Provider.OPENROUTER).build();
        assertEquals("openrouter", gw.providerName());
        assertEquals("openai/gpt-4o", gw.model());
    }

    @Test
    void postsChatCompletionsAndPreservesResponseMetadata() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> referer = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            referer.set(exchange.getRequestHeaders().getFirst("HTTP-Referer"));
            byte[] response = """
                    {"choices":[{"message":{"content":"ok"}}],
                     "usage":{"prompt_tokens":1000,"completion_tokens":500},
                     "model":"openai/gpt-4o"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenRouterAdapter adapter = OpenRouterAdapter.builder()
                    .apiKey("k")
                    .baseUrl(baseUrl)
                    .httpReferer("https://app.example")
                    .appTitle("Gavio")
                    .build();
            GavioRequest request = GavioRequest.builder()
                    .provider(Provider.OPENROUTER)
                    .model("openai/gpt-4o")
                    .message("user", "hi")
                    .build();

            GavioResponse response = adapter.complete(request).join();

            assertTrue(body.get().contains("\"model\":\"openai/gpt-4o\""));
            assertTrue(body.get().contains("\"max_tokens\":1024"));
            assertEquals("Bearer k", auth.get());
            assertEquals("https://app.example", referer.get());
            assertEquals("openrouter", response.provider());
            assertEquals("openai/gpt-4o", response.model());
            assertEquals("openai/gpt-4o", response.modelVersion());
            assertTrue(response.costUsd() > 0);
        } finally {
            server.stop(0);
        }
    }
}
