package io.gavio.inspector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gavio.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded HTTP server for the Gavio Inspector (F-DX-10). JDK-built-in
 * {@code com.sun.net.httpserver} — zero dependencies, loopback by default.
 *
 * <p>Endpoints: {@code /} (vendored UI), {@code /api/health},
 * {@code /api/pipeline}, {@code /api/traces}, {@code /api/traces/{id}} and
 * {@code /api/stream} (SSE). Every response carries the
 * {@code X-Gavio-Inspector-Mode} header; with an auth token configured, all
 * requests must send {@code Authorization: Bearer <token>}.
 */
public final class InspectorServer {

    private static final Logger LOG = Logger.getLogger("gavio.inspector");
    private static final String UI_RESOURCE = "/io/gavio/inspector/ui.html";
    private static final byte[] UI_FALLBACK =
            "<!doctype html><title>Gavio Inspector</title><h1>Gavio Inspector</h1><p>UI asset missing.</p>"
                    .getBytes(StandardCharsets.UTF_8);

    private final InspectorConfig config;
    private final CaptureMode mode;
    private final RingBuffer buffer;
    private final InspectorBus bus;
    private final PipelineInfo pipeline;
    private final HttpServer http;
    private final ExecutorService executor;
    private final byte[] ui;

    InspectorServer(InspectorConfig config, CaptureMode mode, RingBuffer buffer,
                    InspectorBus bus, PipelineInfo pipeline) throws IOException {
        this.config = config;
        this.mode = mode;
        this.buffer = buffer;
        this.bus = bus;
        this.pipeline = pipeline;
        this.ui = loadUi();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "gavio-inspector");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        this.http.createContext("/", this::handle);
        this.http.setExecutor(executor);
    }

    void start() {
        http.start();
        LOG.info("Gavio Inspector listening on http://" + config.bind() + ":" + getPort()
                + " (mode=" + mode.wireValue() + ")");
    }

    /** The actual bound port (resolves port 0 to the ephemeral port). */
    public int getPort() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
        executor.shutdownNow();
    }

    // ---- request handling ----------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Gavio-Inspector-Mode", mode.wireValue());
            if (!authorized(exchange)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> sendBytes(exchange, 200, "text/html; charset=utf-8", ui);
                case "/api/health" -> handleHealth(exchange);
                case "/api/pipeline" -> handlePipeline(exchange);
                case "/api/traces" -> handleTraces(exchange);
                case "/api/stream" -> handleStream(exchange);
                default -> {
                    if (path.startsWith("/api/traces/")) {
                        handleTrace(exchange, path.substring("/api/traces/".length()));
                    } else {
                        sendJson(exchange, 404, Map.of("error", "not found"));
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "inspector request failed", e);
            try {
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            } catch (IOException ignored) {
                // Connection already gone.
            }
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String token = config.authToken();
        if (token == null || token.isEmpty()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return ("Bearer " + token).equals(header);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", Inspector.SDK_VERSION);
        body.put("mode", mode.wireValue());
        body.put("sdk", "java");
        body.put("traces", buffer.size());
        body.put("drops", bus.dropped());
        sendJson(exchange, 200, body);
    }

    private void handlePipeline(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", pipeline.provider());
        body.put("model", pipeline.model());
        body.put("devMode", pipeline.devMode());
        body.put("dryRun", pipeline.dryRun());
        List<Map<String, Object>> interceptors = new ArrayList<>();
        for (String name : pipeline.interceptors()) {
            interceptors.add(Map.of("name", name));
        }
        body.put("interceptors", interceptors);
        body.put("lints", lints(pipeline.interceptors()));
        sendJson(exchange, 200, body);
    }

    /** Ordering lints: interceptors that see raw PII when placed before pii_guard. */
    static List<Map<String, Object>> lints(List<String> names) {
        List<Map<String, Object>> lints = new ArrayList<>();
        int pii = names.indexOf("pii_guard");
        if (pii < 0) {
            return lints;
        }
        int audit = names.indexOf("audit");
        if (audit >= 0 && audit < pii) {
            lints.add(Map.of(
                    "level", "warning",
                    "message", "audit registered before pii_guard — audit will hash unredacted prompts"));
        }
        for (int i = 0; i < pii; i++) {
            if (names.get(i).contains("cache")) {
                lints.add(Map.of(
                        "level", "warning",
                        "message", "cache registered before pii_guard — raw PII used as cache key"));
                break;
            }
        }
        return lints;
    }

    private void handleTraces(HttpExchange exchange) throws IOException {
        int limit = queryInt(exchange.getRequestURI().getQuery(), "limit", 0);
        sendJson(exchange, 200, Map.of("traces", buffer.summaries(limit)));
    }

    private void handleTrace(HttpExchange exchange, String rawId) throws IOException {
        String id = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
        Map<String, Object> trace = buffer.trace(id);
        if (trace == null) {
            sendJson(exchange, 404, Map.of("error", "not found"));
        } else {
            sendJson(exchange, 200, trace);
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        Consumer<InspectorEvent> subscriber = new Consumer<>() {
            @Override
            public void accept(InspectorEvent event) {
                synchronized (out) {
                    try {
                        out.write(("data: " + event.toJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException dead) {
                        bus.unsubscribe(this);
                        exchange.close();
                    }
                }
            }
        };
        // Flush headers so clients see the stream immediately, then hand the
        // connection to the bus. The exchange stays open until the client
        // disconnects (detected on the next failed write).
        synchronized (out) {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        bus.subscribe(subscriber);
    }

    // ---- helpers ----------------------------------------------------

    private static int queryInt(String query, String key, int fallback) {
        if (query == null || query.isEmpty()) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                try {
                    return Integer.parseInt(pair.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] loadUi() {
        try (InputStream in = InspectorServer.class.getResourceAsStream(UI_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
            // Fall through to the placeholder page.
        }
        return UI_FALLBACK;
    }
}
