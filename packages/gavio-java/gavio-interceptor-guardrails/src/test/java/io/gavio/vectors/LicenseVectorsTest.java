package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gavio.interceptors.guardrails.LicenseDetectorValidator;
import io.gavio.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared cross-SDK license test vectors from //test-vectors against the
 * Java SDK. Same JSON file the Python and JavaScript SDKs run — parity enforced.
 */
class LicenseVectorsTest {

    /** Walk up from the working dir to find the repo's test-vectors directory. */
    private static Path vectorsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/license");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/license from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String file) throws IOException {
        String text = Files.readString(vectorsDir().resolve(file));
        Object casesObj = Json.parseObject(text).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : (List<Object>) casesObj) {
            out.add((Map<String, Object>) c);
        }
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> licenseDetectionVectors() throws IOException {
        LicenseDetectorValidator validator = new LicenseDetectorValidator();
        return cases("detection.json").stream().map(c -> {
            String id = (String) c.get("id");
            String textIn = (String) c.get("text");
            List<String> expected = ((List<Object>) c.get("expectedLicenses"))
                    .stream().map(Object::toString).toList();
            return DynamicTest.dynamicTest("license:" + id, () ->
                    assertEquals(expected, validator.detect(textIn), id));
        });
    }
}
