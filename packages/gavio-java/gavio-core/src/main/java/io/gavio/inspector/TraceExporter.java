package io.gavio.inspector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Export a captured trace as a test case (F-DX-12).
 *
 * <p>Renders a trace from the ring buffer as either a shared
 * {@code test-vectors/} JSON case or a GavioTestKit unit test (Python, Java, or
 * TypeScript source). Detected PII values are replaced with the repo's
 * synthetic fixtures before anything leaves the server, so real data never
 * lands in a test file.
 *
 * <p>The PII patterns are replicated locally (like {@link SecretMasker})
 * because gavio-core must stay dependency-free and the module dependency
 * points the other way (pii → core). Secrets are masked via
 * {@link SecretMasker} — its {@code ***} mask equals the SECRET fixture.
 */
public final class TraceExporter {

    /** Supported values for the {@code format} query parameter. */
    public static final List<String> EXPORT_FORMATS =
            List.of("test-vector", "testkit-py", "testkit-java", "testkit-js");

    /** entityType → synthetic stand-in — same fixtures used across test-vectors/. */
    static final Map<String, String> SYNTHETIC_FIXTURES = Map.of(
            "EMAIL", "jan@example.com",
            "IBAN", "NL91ABNA0417164300",
            "BSN", "123456782",
            "CREDIT_CARD", "4111111111111111",
            "SSN", "078-05-1120",
            "PHONE", "+31612345678",
            "IP_ADDRESS", "192.0.2.1",
            "SECRET", "***");

    /** (pattern, fixture) in the reference scanner order — earlier wins on overlap. */
    private static final List<Map.Entry<Pattern, String>> ENTITY_PATTERNS = List.of(
            Map.entry(Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"),
                    SYNTHETIC_FIXTURES.get("EMAIL")),
            Map.entry(Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b"),
                    SYNTHETIC_FIXTURES.get("IBAN")),
            Map.entry(Pattern.compile("\\b\\d{9}\\b"),
                    SYNTHETIC_FIXTURES.get("BSN")),
            Map.entry(Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"),
                    SYNTHETIC_FIXTURES.get("CREDIT_CARD")),
            Map.entry(Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
                    SYNTHETIC_FIXTURES.get("SSN")),
            Map.entry(Pattern.compile("(?<![\\w.])\\+\\d{9,15}\\b"),
                    SYNTHETIC_FIXTURES.get("PHONE")),
            Map.entry(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
                    SYNTHETIC_FIXTURES.get("IP_ADDRESS")));

    /** One rendered export: HTTP content type plus the response body. */
    public record Export(String contentType, String body) {
    }

    private record Span(int start, int end, String fixture) {
    }

    private TraceExporter() {
    }

    /** Replace every detected PII span with its synthetic fixture. Null-safe. */
    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = SecretMasker.mask(text);
        List<Span> spans = new ArrayList<>();
        for (Map.Entry<Pattern, String> entry : ENTITY_PATTERNS) {
            Matcher m = entry.getKey().matcher(masked);
            while (m.find()) {
                spans.add(new Span(m.start(), m.end(), entry.getValue()));
            }
        }
        // Replace right-to-left so earlier offsets stay valid; skip overlaps.
        spans.sort(Comparator.comparingInt(Span::start).reversed());
        StringBuilder out = new StringBuilder(masked);
        int lastStart = masked.length() + 1;
        for (Span span : spans) {
            if (span.end() > lastStart) {
                continue;
            }
            out.replace(span.start(), span.end(), span.fixture());
            lastStart = span.start();
        }
        return out.toString();
    }

    /** Sanitize a list of {@code {role, content}} message maps. */
    public static List<Map<String, Object>> sanitizeMessages(List<?> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : messages) {
            Map<?, ?> m = item instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.get("role") instanceof String role ? role : "");
            msg.put("content", sanitizeText(m.get("content") instanceof String c ? c : ""));
            out.add(msg);
        }
        return out;
    }

    /**
     * Render one assembled trace ({@code {summary, events}} as produced by
     * {@link RingBuffer#trace}).
     *
     * @throws IllegalArgumentException for an unknown format or a trace without
     *     captured messages (metadata-mode traces cannot be exported).
     */
    public static Export exportTrace(Map<String, Object> trace, String format) {
        if (!EXPORT_FORMATS.contains(format)) {
            throw new IllegalArgumentException(
                    "format must be one of ['test-vector', 'testkit-py', 'testkit-java', 'testkit-js']");
        }
        List<Map<String, Object>> events = eventList(trace);
        Map<String, Object> start = findEvent(events, "trace.start");
        List<?> rawMessages = start != null && eventData(start).get("messages") instanceof List<?> l
                ? l : null;
        if (rawMessages == null || rawMessages.isEmpty()) {
            throw new IllegalArgumentException(
                    "trace has no captured messages (metadata mode traces cannot be exported)");
        }
        List<Map<String, Object>> messages = sanitizeMessages(rawMessages);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        if ("test-vector".equals(format)) {
            return new Export("application/json",
                    io.gavio.json.Json.write(testVector(summary, events, messages)));
        }
        return new Export("text/plain; charset=utf-8", testkit(format, summary, messages));
    }

    // ---- test-vector -----------------------------------------------------------

    private static Map<String, Object> testVector(
            Map<String, Object> summary, List<Map<String, Object>> events,
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> expected = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String type = (String) event.get("type");
            Map<String, Object> data = eventData(event);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            if (type.startsWith("interceptor.") && data.get("name") instanceof String name) {
                entry.put("name", name);
            }
            if (data.containsKey("status")
                    && ("provider.call.end".equals(type) || "trace.end".equals(type))) {
                entry.put("status", data.get("status"));
            }
            expected.add(entry);
        }
        String mode = "full";
        for (Map<String, Object> event : events) {
            if ("trace.start".equals(event.get("type"))
                    && eventData(event).get("mode") instanceof String m) {
                mode = m;
                break;
            }
        }
        String traceId = (String) summary.get("traceId");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "exported-" + shortId(traceId));
        out.put("mode", mode);
        out.put("interceptors", publicInterceptors(summary));
        out.put("request", Map.of("messages", messages));
        out.put("expectedEvents", expected);
        return out;
    }

    // ---- testkit source templates -------------------------------------------------

    private static String testkit(
            String format, Map<String, Object> summary, List<Map<String, Object>> messages) {
        List<String> fired = publicInterceptors(summary);
        boolean pii = fired.contains("pii_guard");
        boolean audit = fired.contains("audit");
        List<String> other = new ArrayList<>(fired);
        other.remove("pii_guard");
        other.remove("audit");
        String traceId = (String) summary.get("traceId");
        String slug = shortId(traceId).replace("-", "");
        if ("testkit-py".equals(format)) {
            return testkitPy(traceId, slug, pii, audit, other, messages);
        }
        if ("testkit-js".equals(format)) {
            return testkitJs(traceId, slug, pii, audit, other, messages);
        }
        return testkitJava(traceId, slug, pii, audit, other, messages);
    }

    private static String testkitPy(String traceId, String slug, boolean pii, boolean audit,
            List<String> other, List<Map<String, Object>> messages) {
        List<String> uses = new ArrayList<>();
        if (pii) {
            uses.add("PiiGuard()");
        }
        if (audit) {
            uses.add("AuditInterceptor()");
        }
        StringBuilder imports = new StringBuilder();
        if (pii) {
            imports.append("from gavio.interceptors.pii import PiiGuard\n");
        }
        if (audit) {
            imports.append("from gavio.interceptors.audit import AuditInterceptor\n");
        }
        String note = other.isEmpty() ? ""
                : "# also fired in the original trace: " + String.join(", ", other) + "\n    ";
        String assertion = pii ? "\n    assert kit.pii_detected()" : "";
        return "\"\"\"Exported from the Gavio Inspector — trace " + traceId + ".\"\"\"\n"
                + "from gavio.testing import GavioTestKit\n" + imports + "\n\n"
                + "async def test_exported_trace_" + slug + "() -> None:\n"
                + "    " + note + "kit = GavioTestKit(interceptors=[" + String.join(", ", uses) + "])\n"
                + "    messages = " + jsonDumps(messages) + "\n"
                + "    response = await kit.run(messages)\n"
                + "    assert response.content" + assertion + "\n";
    }

    private static String testkitJs(String traceId, String slug, boolean pii, boolean audit,
            List<String> other, List<Map<String, Object>> messages) {
        List<String> uses = new ArrayList<>();
        if (pii) {
            uses.add("new PiiGuard()");
        }
        if (audit) {
            uses.add("new AuditInterceptor()");
        }
        StringBuilder imports = new StringBuilder("import { GavioTestKit } from 'gavio/testing'\n");
        if (pii) {
            imports.append("import { PiiGuard } from 'gavio/interceptors/pii'\n");
        }
        if (audit) {
            imports.append("import { AuditInterceptor } from 'gavio/interceptors/audit'\n");
        }
        String note = other.isEmpty() ? ""
                : "// also fired in the original trace: " + String.join(", ", other) + "\n  ";
        String assertion = pii ? "\n  expect(result.piiDetected()).toBe(true)" : "";
        return "// Exported from the Gavio Inspector — trace " + traceId + "\n"
                + "import { expect, test } from 'vitest'\n" + imports + "\n"
                + "test('exported trace " + slug + "', async () => {\n"
                + "  " + note + "const kit = new GavioTestKit({ interceptors: ["
                + String.join(", ", uses) + "] })\n"
                + "  const messages = " + jsonDumps(messages) + "\n"
                + "  const result = await kit.run({ messages })\n"
                + "  expect(result.response.content).toBeTruthy()" + assertion + "\n"
                + "})\n";
    }

    private static String testkitJava(String traceId, String slug, boolean pii, boolean audit,
            List<String> other, List<Map<String, Object>> messages) {
        StringBuilder builderUses = new StringBuilder();
        if (pii) {
            builderUses.append(".interceptor(new PiiGuard())");
        }
        if (audit) {
            builderUses.append(".interceptor(new AuditInterceptor())");
        }
        String note = other.isEmpty() ? ""
                : "// also fired in the original trace: " + String.join(", ", other) + "\n        ";
        List<String> rendered = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            rendered.add("Message.of(" + jsonString((String) m.get("role")) + ", "
                    + jsonString((String) m.get("content")) + ")");
        }
        String javaMessages = String.join(",\n                ", rendered);
        String assertion = pii ? "\n        assertTrue(result.piiDetected(null));" : "";
        return "// Exported from the Gavio Inspector — trace " + traceId + "\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n\n"
                + "import io.gavio.testing.GavioTestKit;\n"
                + "import io.gavio.testing.GavioTestResult;\n"
                + "import io.gavio.types.Message;\n"
                + "import java.util.List;\n"
                + "import org.junit.jupiter.api.Test;\n\n"
                + "class ExportedTrace" + slug + "Test {\n\n"
                + "    @Test\n"
                + "    void exportedTrace() {\n"
                + "        " + note + "GavioTestKit kit = GavioTestKit.builder()" + builderUses
                + ".build();\n"
                + "        GavioTestResult result = kit.run(List.of(\n                "
                + javaMessages + ")).join();\n"
                + "        assertNotNull(result.response().content());" + assertion + "\n"
                + "    }\n"
                + "}\n";
    }

    // ---- helpers -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventList(Map<String, Object> trace) {
        Object events = trace.get("events");
        return events instanceof List<?> ? (List<Map<String, Object>>) events : List.of();
    }

    private static Map<String, Object> findEvent(List<Map<String, Object>> events, String type) {
        for (Map<String, Object> event : events) {
            if (type.equals(event.get("type"))) {
                return event;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventData(Map<String, Object> event) {
        Object data = event.get("data");
        return data instanceof Map<?, ?> ? (Map<String, Object>) data : Map.of();
    }

    /** interceptorsFired minus internal names (leading underscore). */
    private static List<String> publicInterceptors(Map<String, Object> summary) {
        List<String> out = new ArrayList<>();
        if (summary.get("interceptorsFired") instanceof List<?> fired) {
            for (Object name : fired) {
                if (name instanceof String s && !s.startsWith("_")) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String shortId(String traceId) {
        return traceId.length() > 8 ? traceId.substring(0, 8) : traceId;
    }

    /** Python {@code json.dumps} rendering of message maps — same template text as export.py. */
    private static String jsonDumps(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Map<String, Object> m = messages.get(i);
            sb.append("{\"role\": ").append(jsonString((String) m.get("role")))
                    .append(", \"content\": ").append(jsonString((String) m.get("content")))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /** A JSON string literal escaped like Python's json.dumps (ensure_ascii). */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
