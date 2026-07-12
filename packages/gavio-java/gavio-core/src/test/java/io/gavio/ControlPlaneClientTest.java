package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.gavio.controlplane.ControlPlaneClient;
import io.gavio.controlplane.ControlPlaneException;
import io.gavio.controlplane.ControlPlaneOptions;
import io.gavio.providers.MockProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlPlaneClientTest {

    private static final String CONFIG_JSON = """
            {
              "schemaVersion": "1.0",
              "configVersion": "cfg_test",
              "projectId": "proj_support",
              "environment": "prod",
              "policySource": "project:prod-support",
              "policy": {"id": "pol_support", "name": "Support", "policyPack": "support", "rules": []},
              "budgets": [{"id": "budget_support", "scopeType": "project", "limitUsd": 25}],
              "rollout": {"id": "rollout_support", "policyId": "pol_support", "status": "active"},
              "cache": {"ttlSeconds": 120, "failMode": "open"}
            }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void fetchesRuntimeConfigAndFallsBackToCache() throws Exception {
        TestServer server = TestServer.start();
        Path cache = Files.createTempDirectory("gavio-control-plane").resolve("cache.json");
        ControlPlaneClient client = new ControlPlaneClient(ControlPlaneOptions
                .builder(server.url(), "gav_rt_test", "project:prod-support")
                .cachePath(cache)
                .build());
        try {
            Map<String, Object> first = client.loadConfig();
            assertEquals("control_plane", ((Map<String, Object>) first.get("cache")).get("loadedFrom"));
            assertEquals("proj_support", first.get("projectId"));
        } finally {
            server.close();
        }

        Map<String, Object> cached = client.loadConfig();
        assertEquals("cache", ((Map<String, Object>) cached.get("cache")).get("loadedFrom"));
        assertEquals("project:prod-support", cached.get("policySource"));
    }

    @Test
    void failsClosedWithoutCache() throws Exception {
        Path cache = Files.createTempDirectory("gavio-control-plane").resolve("missing.json");
        ControlPlaneClient client = new ControlPlaneClient(ControlPlaneOptions
                .builder("http://127.0.0.1:1", "gav_rt_test", "project:prod-support")
                .cachePath(cache)
                .failMode("closed")
                .timeoutMillis(50)
                .build());
        assertThrows(ControlPlaneException.class, client::loadConfig);
    }

    @Test
    void gatewayBuilderLoadsControlPlaneConfig() throws Exception {
        TestServer server = TestServer.start();
        Path cache = Files.createTempDirectory("gavio-control-plane").resolve("gateway.json");
        try {
            Gateway gateway = Gateway.builder()
                    .adapter(new MockProvider())
                    .model("mock")
                    .controlPlane(ControlPlaneOptions
                            .builder(server.url(), "gav_rt_test", "project:prod-support")
                            .cachePath(cache)
                            .build())
                    .build();
            assertEquals("proj_support", gateway.controlPlaneConfig().get("projectId"));
        } finally {
            server.close();
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/runtime/config", exchange -> {
                if (!"Bearer gav_rt_test".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] body = CONFIG_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new TestServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
