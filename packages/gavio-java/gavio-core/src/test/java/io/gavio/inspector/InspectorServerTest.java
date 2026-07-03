package io.gavio.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Embedded inspector HTTP server (F-DX-09/F-DX-10): JSON API, UI, auth. */
class InspectorServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Gateway gateway;

    @AfterEach
    void stopServer() {
        if (gateway != null && gateway.inspector() != null) {
            gateway.inspector().stop();
        }
    }

    private String serve(InspectorConfig.Builder config) {
        gateway = Gateway.builder()
                .adapter(new MockProvider())
                .model("mock")
                .inspect(config.enabled(true).port(0).build())
                .build();
        return "http://127.0.0.1:" + gateway.inspector().port();
    }

    private HttpResponse<String> get(String url, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            req.header(headers[i], headers[i + 1]);
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesHealthTracesDetailAndUi() throws Exception {
        String base = serve(InspectorConfig.builder().mode(CaptureMode.METADATA));
        gateway.complete(List.of(Message.of("user", "ping"))).join();

        HttpResponse<String> health = get(base + "/api/health");
        assertEquals(200, health.statusCode());
        assertEquals("metadata", health.headers().firstValue("X-Gavio-Inspector-Mode").orElse(null));
        Map<String, Object> h = Json.parseObject(health.body());
        assertEquals("ok", h.get("status"));
        assertEquals("java", h.get("sdk"));
        assertEquals("metadata", h.get("mode"));

        Map<String, Object> list = Json.parseObject(get(base + "/api/traces").body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) (List<?>) (List<Object>) list.get("traces");
        assertEquals(1, traces.size());
        String traceId = (String) traces.get(0).get("traceId");
        assertNotNull(traceId);

        HttpResponse<String> detail = get(base + "/api/traces/" + traceId);
        assertEquals(200, detail.statusCode());
        Map<String, Object> d = Json.parseObject(detail.body());
        assertTrue(((List<?>) d.get("events")).size() > 0, "trace detail must include events");

        assertEquals(404, get(base + "/api/traces/no-such-trace").statusCode());

        HttpResponse<String> ui = get(base + "/");
        assertEquals(200, ui.statusCode());
        assertTrue(ui.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(ui.body().contains("Gavio Inspector"), "UI page must be served at /");

        Map<String, Object> pipeline = Json.parseObject(get(base + "/api/pipeline").body());
        assertEquals("mock", pipeline.get("provider"));
        assertTrue(pipeline.containsKey("interceptors"));
        assertTrue(pipeline.containsKey("lints"));
    }

    @Test
    void requiresBearerTokenWhenConfigured() throws Exception {
        String base = serve(InspectorConfig.builder().mode(CaptureMode.METADATA).authToken("hunter2"));
        assertEquals(401, get(base + "/api/health").statusCode());
        assertEquals(200, get(base + "/api/health", "Authorization", "Bearer hunter2").statusCode());
        assertEquals(401, get(base + "/api/health", "Authorization", "Bearer wrong").statusCode());
    }
}
