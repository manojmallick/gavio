package io.gavio.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import io.gavio.prompts.EvalRunResult;
import io.gavio.prompts.EvalRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platform workflow release vectors that need no Policy Pack resolver (canonical JSON, release hash,
 * and the policy-free release case). The full vector, including policy packs, runs in
 * {@code gavio-interceptor-pii} where the resolver lives.
 */
class PlatformWorkflowTest {

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    static Map<String, Object> vectors() throws IOException {
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
    static List<Map<String, Object>> cases(Map<String, Object> vectors, String key) {
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

    @Test
    @SuppressWarnings("unchecked")
    void policyFreeReleaseMatchesSharedVector() throws IOException {
        Map<String, Object> testCase = cases(vectors(), "releaseCases").stream()
                .filter(c -> "minimal-release".equals(c.get("id")))
                .findFirst()
                .orElseThrow();
        Path manifest = tmp.resolve("workflow.json");
        Files.writeString(manifest, Json.write(testCase.get("manifest")));

        PlatformWorkflowResult result = PlatformWorkflow.runFile(manifest);

        Map<String, Object> expected = (Map<String, Object>) testCase.get("expected");
        assertEquals(expected.get("passed"), result.passed());
        assertEquals(expected.get("reasons"), result.reasons());
        assertEquals(
                PlatformWorkflow.canonicalJson(expected.get("artifact")),
                PlatformWorkflow.canonicalJson(result.artifact()));
        assertFalse(Json.write(result.artifact()).contains("raw prompt text"));
    }

    @Test
    void policiesWithoutAResolverFailClearly() throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", "2026-07-12T12:00:00Z");
        manifest.put("policies", List.of("core"));
        // No ServiceLoader provider is on gavio-core's own test classpath.
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> PlatformWorkflow.build(manifest, tmp));
        assertTrue(error.getMessage().contains("PlatformWorkflowPolicyResolver"));
    }

    @Test
    void evalRunnerGatesAndKeepsRawOutputOut() throws IOException {
        Path suite = tmp.resolve("suite.json");
        Files.writeString(suite, Json.write(Map.of(
                "id", "gate",
                "templates", List.of(Map.of(
                        "id", "hello",
                        "version", "1.0.0",
                        "messages", List.of(Map.of("role", "user", "content", "Hi")))),
                "cases", List.of(Map.of(
                        "id", "bad",
                        "templateId", "hello",
                        "mockOutput", "secret card number",
                        "assertions", List.of(Map.of("type", "not_contains", "value", "card number")))))));

        EvalRunResult result = EvalRunner.run(suite, new EvalRunner.Options(List.of(), 1.0, null, 0.0));

        assertFalse(result.passed());
        assertTrue(result.gate().reasons().contains("1 eval case(s) failed"));
        assertFalse(Json.write(result.toMap()).contains("secret card number"));
    }
}
