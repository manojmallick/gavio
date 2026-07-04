package io.gavio.interceptors.pii.scanners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import io.gavio.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class FintechScannersTest {

    private static List<String> detect(String text) {
        ScanContext ctx = new ScanContext();
        TreeSet<String> found = new TreeSet<>();
        for (PiiScanner s : DefaultScanners.fintech()) {
            if (!s.scan(text, ctx).isEmpty()) {
                found.add(s.entityType());
            }
        }
        return new ArrayList<>(found);
    }

    @Test
    void routingChecksum() {
        assertTrue(RoutingNumberScanner.validRoutingNumber("021000021"));
        assertTrue(RoutingNumberScanner.validRoutingNumber("111000025"));
        assertFalse(RoutingNumberScanner.validRoutingNumber("123456789"));
        assertFalse(RoutingNumberScanner.validRoutingNumber("000000000"));
        assertFalse(RoutingNumberScanner.validRoutingNumber("12345"));
    }

    @Test
    void swiftContextGated() {
        var matches = new SwiftBicScanner().scan("SWIFT: DEUTDEFF500 now", new ScanContext());
        assertEquals(1, matches.size());
        assertEquals("DEUTDEFF500", matches.get(0).value());
        assertTrue(new SwiftBicScanner().scan("the DATABASE was updated", new ScanContext()).isEmpty());
    }

    @Test
    void routingScanner() {
        assertEquals(1, new RoutingNumberScanner().scan("021000021", new ScanContext()).size());
        assertTrue(new RoutingNumberScanner().scan("123456789", new ScanContext()).isEmpty());
    }

    @Test
    void composition() {
        assertEquals(List.of("ROUTING_NUMBER", "SWIFT_BIC"),
                detect("SWIFT DEUTDEFF500 and routing 111000025"));
    }

    // ── shared test-vectors ──────────────────────────────────────────────────

    private static Path vectorsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/pii");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/pii from working dir");
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> fintechDetectionVectors() throws IOException {
        String text = Files.readString(vectorsDir().resolve("fintech-detection.json"));
        List<Object> cases = (List<Object>) Json.parseObject(text).get("cases");
        List<DynamicTest> tests = new ArrayList<>();
        for (Object obj : cases) {
            Map<String, Object> c = (Map<String, Object>) obj;
            String id = (String) c.get("id");
            String caseText = (String) c.get("text");
            List<String> expected = ((List<Object>) c.get("expectedTypes"))
                    .stream().map(Object::toString).toList();
            tests.add(DynamicTest.dynamicTest("fintech:" + id, () ->
                    assertEquals(expected, detect(caseText), id)));
        }
        return tests.stream();
    }
}
