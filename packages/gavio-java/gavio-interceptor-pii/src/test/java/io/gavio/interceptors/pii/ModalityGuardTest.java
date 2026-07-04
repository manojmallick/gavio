package io.gavio.interceptors.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioException.PiiBlockedException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ModalityGuardTest {

    private static final byte[] IMG = {1, 2, 3};

    private static ModalityScanner stub(String text, List<String> entityTypes) {
        return new ModalityScanner() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public ModalityScanResult scan(byte[] image) {
                return new ModalityScanResult(text, entityTypes);
            }
        };
    }

    private static GavioRequest reqWithImage() {
        return GavioRequest.builder().message("user", "q").model("mock").image(IMG).build();
    }

    private static List<String> detect(ModalityScanner scanner) {
        InterceptorContext ctx = new InterceptorContext("t");
        new ModalityGuard(List.of(scanner)).before(reqWithImage(), ctx).join();
        return ctx.piiEntityTypes().stream().sorted().toList();
    }

    @Test
    void recordsOcrTextPii() {
        assertEquals(List.of("EMAIL"), detect(stub("contact jan.devries@example.com", List.of())));
    }

    @Test
    void recordsDirectFaceDetection() {
        assertEquals(List.of("FACE"), detect(stub("", List.of("FACE"))));
    }

    @Test
    void unionsTextAndDirect() {
        assertEquals(List.of("EMAIL", "FACE"), detect(stub("mail a@b.com", List.of("FACE"))));
    }

    @Test
    void cleanImageRecordsNothing() {
        assertEquals(List.of(), detect(stub("a sunset over the mountains", List.of())));
    }

    @Test
    void noopWithoutImages() {
        InterceptorContext ctx = new InterceptorContext("t");
        GavioRequest req = GavioRequest.builder().message("user", "q").model("mock").build();
        new ModalityGuard(List.of(stub("", List.of("FACE")))).before(req, ctx).join();
        assertTrue(ctx.piiEntityTypes().isEmpty());
    }

    @Test
    void blockRaisesPiiBlockedException() {
        InterceptorContext ctx = new InterceptorContext("t");
        ModalityGuard guard = new ModalityGuard(List.of(stub("", List.of("FACE"))), null, "block");
        CompletionException ex =
                assertThrows(CompletionException.class, () -> guard.before(reqWithImage(), ctx).join());
        assertInstanceOf(PiiBlockedException.class, ex.getCause());
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
    Stream<DynamicTest> imageDetectionVectors() throws IOException {
        String text = Files.readString(vectorsDir().resolve("image-detection.json"));
        List<Object> cases = (List<Object>) Json.parseObject(text).get("cases");
        List<DynamicTest> tests = new ArrayList<>();
        for (Object obj : cases) {
            Map<String, Object> c = (Map<String, Object>) obj;
            String id = (String) c.get("id");
            String ocrText = (String) c.get("ocrText");
            List<String> entityTypes = ((List<Object>) c.get("entityTypes"))
                    .stream().map(Object::toString).toList();
            List<String> expected = ((List<Object>) c.get("expectedTypes"))
                    .stream().map(Object::toString).toList();
            tests.add(DynamicTest.dynamicTest("image:" + id, () ->
                    assertEquals(expected, detect(stub(ocrText, entityTypes)))));
        }
        return tests.stream();
    }
}
