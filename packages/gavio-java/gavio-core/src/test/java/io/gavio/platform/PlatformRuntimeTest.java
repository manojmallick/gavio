package io.gavio.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformRuntimeTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildsSharedPlatformRuntimeProfile() throws Exception {
        Map<String, Object> vector = loadVector();
        Map<String, Object> profile = buildFromVector((Map<String, Object>) vector.get("readyProfileInput"));

        assertEquals(vector.get("readyProfile"), profile);
        PlatformRuntimeVerification result = PlatformRuntime.verify(profile);
        assertTrue(result.valid());
        assertEquals(List.of(), result.errors());
        assertEquals(profile.get("profileHash"), result.computedHash());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsPlatformRuntimeReadinessGaps() throws Exception {
        Map<String, Object> gapCase = (Map<String, Object>) loadVector().get("gapCase");
        Map<String, Object> profile = buildFromVector((Map<String, Object>) gapCase.get("input"));

        assertEquals(gapCase.get("expectedReadiness"), profile.get("readiness"));
        assertFalse((Boolean) ((Map<String, Object>) profile.get("readiness")).get("ready"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTamperedOrContentBearingProfile() throws Exception {
        Map<String, Object> profile = buildFromVector((Map<String, Object>) loadVector().get("readyProfileInput"));
        Map<String, Object> runtime = (Map<String, Object>) profile.get("runtime");
        runtime.put("eventExportMode", "full_local_debug");
        runtime.put("rawPrompt", "do not store me");

        PlatformRuntimeVerification result = PlatformRuntime.verify(profile);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("profileHash does not match profile content"));
        assertTrue(result.errors().contains("profile contains content-bearing keys"));
        assertTrue(result.errors().contains("readiness does not match profile content"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildFromVector(Map<String, Object> input) {
        return PlatformRuntime.builder((String) input.get("profileId"))
                .generatedAt((String) input.get("generatedAt"))
                .sdk(
                        (String) ((Map<String, Object>) input.get("sdk")).get("name"),
                        (String) ((Map<String, Object>) input.get("sdk")).get("version"))
                .runtime((Map<String, Object>) input.get("runtime"))
                .surfaces((List<String>) input.get("surfaces"))
                .exporters((List<String>) input.get("exporters"))
                .integrations((List<String>) input.get("integrations"))
                .controls((List<Map<String, Object>>) input.get("controls"))
                .evidence((Map<String, Object>) input.get("evidence"))
                .build();
    }

    private static Map<String, Object> loadVector() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/platform-runtime/profile.json")));
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("test-vectors"))) {
                return p;
            }
        }
        throw new AssertionError("repository root not found from " + cwd);
    }
}
