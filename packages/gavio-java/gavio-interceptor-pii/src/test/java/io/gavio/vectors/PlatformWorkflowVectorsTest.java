package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.gavio.json.Json;
import io.gavio.platform.PlatformWorkflow;
import io.gavio.platform.PlatformWorkflowResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs test-vectors/workflow/release.json end to end. The Policy Pack resolver ships in this
 * module (registered through META-INF/services), so every release case, including the ones that
 * list policy packs, runs here.
 */
class PlatformWorkflowVectorsTest {

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/workflow/release.json");
            if (Files.isRegularFile(candidate)) {
                return Json.parseObject(Files.readString(candidate));
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate workflow vectors from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Map<String, Object> vectors, String key) {
        return (List<Map<String, Object>>) vectors.get(key);
    }

    @TestFactory
    Iterable<DynamicTest> canonicalJsonMatchesSharedVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : cases(vectors(), "canonicalJsonCases")) {
            tests.add(DynamicTest.dynamicTest(
                    "canonical JSON: " + testCase.get("id"),
                    () -> assertEquals(testCase.get("expected"), PlatformWorkflow.canonicalJson(testCase.get("value")))));
        }
        return tests;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Iterable<DynamicTest> releaseHashMatchesSharedVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : cases(vectors(), "hashCases")) {
            tests.add(DynamicTest.dynamicTest(
                    "workflow hash: " + testCase.get("id"),
                    () -> assertEquals(
                            testCase.get("expectedHash"),
                            PlatformWorkflow.releaseHash((Map<String, Object>) testCase.get("artifact")))));
        }
        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> releasesMatchSharedVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> testCase : cases(vectors(), "releaseCases")) {
            Path dir = tmp.resolve("case-" + index++);
            tests.add(DynamicTest.dynamicTest("release vector: " + testCase.get("id"), () -> run(testCase, dir)));
        }
        return tests;
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> testCase, Path dir) throws IOException {
        Map<String, Object> files = (Map<String, Object>) testCase.get("files");
        for (Map.Entry<String, Object> file : files.entrySet()) {
            Path path = dir.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, Json.write(file.getValue()));
        }
        Files.createDirectories(dir);
        Path manifest = dir.resolve("workflow.json");
        Files.writeString(manifest, Json.write(testCase.get("manifest")));

        PlatformWorkflowResult result = PlatformWorkflow.runFile(manifest);

        Map<String, Object> expected = (Map<String, Object>) testCase.get("expected");
        assertEquals(expected.get("passed"), result.passed());
        assertEquals(expected.get("reasons"), result.reasons());
        Map<String, Object> artifact = stripEnvironment(result.artifact());
        assertEquals(
                PlatformWorkflow.canonicalJson(expected.get("artifact")),
                PlatformWorkflow.canonicalJson(artifact));
        assertEquals(artifact.get("workflowHash"), PlatformWorkflow.releaseHash(result.artifact()));
        String serialized = Json.write(result.artifact());
        for (Object forbidden : (List<Object>) testCase.get("forbiddenStrings")) {
            assertFalse(serialized.contains(String.valueOf(forbidden)), String.valueOf(forbidden));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripEnvironment(Map<String, Object> artifact) {
        Map<String, Object> copy = new LinkedHashMap<>(artifact);
        List<Object> evals = new ArrayList<>();
        for (Object item : (List<Object>) artifact.get("evals")) {
            Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) item);
            entry.remove("runner");
            entry.remove("source");
            evals.add(entry);
        }
        copy.put("evals", evals);
        return copy;
    }
}
