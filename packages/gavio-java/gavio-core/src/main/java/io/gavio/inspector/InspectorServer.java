package io.gavio.inspector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gavio.GavioResponse;
import io.gavio.json.Json;
import io.gavio.types.Message;
import io.gavio.types.TokenUsage;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
 * {@code /api/pipeline}, {@code /api/traces}, {@code /api/traces/{id}},
 * {@code /api/traces/{id}/export}, {@code /api/dag}, {@code /api/sessions},
 * {@code /api/stats}, {@code /api/simulate-cost}, {@code /api/chain/verify},
 * {@code POST /api/replay} and {@code /api/stream} (SSE). Every response
 * carries the {@code X-Gavio-Inspector-Mode} header; with an auth token
 * configured, all requests must send {@code Authorization: Bearer <token>}.
 */
public final class InspectorServer {

    private static final Logger LOG = Logger.getLogger("gavio.inspector");
    private static final String UI_RESOURCE = "/io/gavio/inspector/ui.html";
    private static final byte[] UI_FALLBACK =
            "<!doctype html><title>Gavio Inspector</title><h1>Gavio Inspector</h1><p>UI asset missing.</p>"
                    .getBytes(StandardCharsets.UTF_8);

    private final Inspector inspector;
    private final InspectorConfig config;
    private final CaptureMode mode;
    private final RingBuffer buffer;
    private final InspectorBus bus;
    private final PipelineInfo pipeline;
    private final HttpServer http;
    private final ExecutorService executor;
    private final byte[] ui;

    InspectorServer(Inspector inspector) throws IOException {
        this.inspector = inspector;
        InspectorConfig config = inspector.config();
        this.config = config;
        this.mode = inspector.mode();
        this.buffer = inspector.buffer();
        this.bus = inspector.bus();
        this.pipeline = inspector.pipeline();
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
                case "/api/dag" -> handleDag(exchange);
                case "/api/sessions" -> sendJson(exchange, 200,
                        Map.of("sessions", InspectorAnalytics.buildSessions(buffer.summaries(0))));
                case "/api/stats" -> handleStats(exchange);
                case "/api/simulate-cost" -> handleSimulateCost(exchange);
                case "/api/chain/verify" -> handleChainVerify(exchange);
                case "/api/replay" -> handleReplay(exchange);
                case "/api/stream" -> handleStream(exchange);
                default -> {
                    if (path.startsWith("/api/traces/") && path.endsWith("/export")) {
                        handleExport(exchange, path.substring(
                                "/api/traces/".length(), path.length() - "/export".length()));
                    } else if (path.startsWith("/api/traces/")) {
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
        String query = exchange.getRequestURI().getQuery();
        int limit = queryInt(query, "limit", 0);
        List<Map<String, Object>> summaries = buffer.summaries(limit);
        String q = queryParam(query, "q");
        if (q != null && !q.isEmpty()) {
            summaries.removeIf(s -> !hashPrefixMatch(s, q));
        }
        sendJson(exchange, 200, Map.of("traces", summaries));
    }

    /** True when traceId, promptHash or responseHash (when present) starts with q. */
    private static boolean hashPrefixMatch(Map<String, Object> summary, String q) {
        for (String field : List.of("traceId", "promptHash", "responseHash")) {
            if (summary.get(field) instanceof String value && value.startsWith(q)) {
                return true;
            }
        }
        return false;
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

    // ---- agentic + production endpoints (v0.7.0) --------------------------------

    private void handleDag(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String root = queryParam(query, "root");
        String sessionId = queryParam(query, "session_id");
        if (root == null && sessionId == null) {
            sendJson(exchange, 400, Map.of("error", "pass ?root=<trace_id> or ?session_id=<id>"));
            return;
        }
        Map<String, Object> dag = InspectorAnalytics.buildDag(buffer.summaries(0), root, sessionId);
        if (dag == null) {
            sendJson(exchange, 404, Map.of("error", "not found"));
        } else {
            sendJson(exchange, 200, dag);
        }
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, Object> stats;
        try {
            stats = InspectorAnalytics.buildStats(
                    buffer.summaries(0), queryParam(query, "group_by"), queryParam(query, "since"));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(error.getMessage())));
            return;
        }
        sendJson(exchange, 200, stats);
    }

    private void handleSimulateCost(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String traceId = queryParam(query, "trace_id");
        String model = queryParam(query, "model");
        if (traceId == null || traceId.isEmpty() || model == null || model.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "pass ?trace_id=<id>&model=<model>"));
            return;
        }
        Map<String, Object> trace = buffer.trace(traceId);
        if (trace == null) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        if (!(summary.get("usage") instanceof Map<?, ?> usage) || usage.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "trace has no token usage"));
            return;
        }
        TokenUsage tokenUsage = new TokenUsage(
                usageInt(usage.get("promptTokens")), usageInt(usage.get("completionTokens")));
        double simulated = inspector.pricing().estimate(model, tokenUsage);
        double original = summary.get("costUsd") instanceof Number cost ? cost.doubleValue() : 0.0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("model", summary.get("model"));
        body.put("costUsd", original);
        body.put("simulatedModel", model);
        body.put("simulatedCostUsd", simulated);
        body.put("deltaUsd", Math.round((simulated - original) * 1e8) / 1e8);
        body.put("usage", usage);
        sendJson(exchange, 200, body);
    }

    private void handleChainVerify(HttpExchange exchange) throws IOException {
        // The live server has no audit store; store-backed inspection is
        // Python-only in v0.7.0 (gavio inspect --store).
        sendJson(exchange, 400, Map.of(
                "error", "chain verification requires an audit store; "
                        + "run: gavio inspect --store <audit.jsonl>"));
    }

    private void handleExport(HttpExchange exchange, String rawId) throws IOException {
        if (mode == CaptureMode.METADATA) {
            sendJson(exchange, 403, Map.of("error", "export requires full or redacted capture mode"));
            return;
        }
        String format = queryParam(exchange.getRequestURI().getQuery(), "format");
        if (format == null || !TraceExporter.EXPORT_FORMATS.contains(format)) {
            sendJson(exchange, 400, Map.of("error",
                    "format must be one of ['test-vector', 'testkit-py', 'testkit-java', 'testkit-js']"));
            return;
        }
        String id = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
        Map<String, Object> trace = buffer.trace(id);
        if (trace == null) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        TraceExporter.Export export;
        try {
            export = TraceExporter.exportTrace(trace, format);
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(error.getMessage())));
            return;
        }
        sendBytes(exchange, 200, export.contentType(),
                export.body().getBytes(StandardCharsets.UTF_8));
    }

    private void handleReplay(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        if (mode != CaptureMode.FULL) {
            sendJson(exchange, 403, Map.of("error", "replay requires full capture mode"));
            return;
        }
        Inspector.ReplayHandler handler = inspector.replayHandler();
        if (handler == null) {
            sendJson(exchange, 403, Map.of("error", "no live gateway attached; replay unavailable"));
            return;
        }
        Map<String, Object> body;
        try {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            body = raw.isBlank() ? new LinkedHashMap<>() : Json.parseObject(raw);
        } catch (Json.JsonException error) {
            sendJson(exchange, 400, Map.of("error", "invalid JSON body"));
            return;
        }
        if (!(body.get("traceId") instanceof String traceId) || traceId.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "body must include traceId"));
            return;
        }
        Map<String, Object> trace = buffer.trace(traceId);
        if (trace == null) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        Map<String, Object> overrides = asMap(body.get("overrides"));
        List<?> rawMessages = overrides.get("messages") instanceof List<?> edited
                ? edited : capturedMessages(trace);
        if (rawMessages == null || rawMessages.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "trace has no captured messages to replay"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        String model = overrides.get("model") instanceof String m && !m.isEmpty()
                ? m : (String) summary.get("model");
        Map<String, Object> options = asMap(overrides.get("options"));
        GavioResponse response;
        try {
            // The replayed call runs the full interceptor chain — PII guard
            // included, never bypassed.
            response = handler.replay(
                    toMessages(rawMessages), model, Map.of("replay_of", traceId), options).join();
        } catch (Exception error) {
            Throwable cause = error;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            failure.put("replayOf", traceId);
            sendJson(exchange, 502, failure);
            return;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("traceId", response.traceId());
        ok.put("replayOf", traceId);
        sendJson(exchange, 200, ok);
    }

    /** The messages list captured in the trace.start event, or null. */
    private static List<?> capturedMessages(Map<String, Object> trace) {
        if (!(trace.get("events") instanceof List<?> events)) {
            return null;
        }
        for (Object item : events) {
            if (item instanceof Map<?, ?> event && "trace.start".equals(event.get("type"))
                    && event.get("data") instanceof Map<?, ?> data
                    && data.get("messages") instanceof List<?> messages) {
                return messages;
            }
        }
        return null;
    }

    /** Convert {@code {role, content}} maps (JSON body or event data) to messages. */
    private static List<Message> toMessages(List<?> rawMessages) {
        List<Message> out = new ArrayList<>(rawMessages.size());
        for (Object item : rawMessages) {
            Map<?, ?> m = item instanceof Map<?, ?> map ? map : Map.of();
            out.add(Message.of(
                    m.get("role") instanceof String role ? role : "user",
                    m.get("content") instanceof String content ? content : ""));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int usageInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
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

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

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
