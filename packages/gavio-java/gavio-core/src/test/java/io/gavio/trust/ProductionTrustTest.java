package io.gavio.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionTrustTest {

    @Test
    void buildsAndVerifiesMetadataOnlyTrustBundle() {
        Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
                .generatedAt("2026-07-12T12:00:00Z")
                .sdk("gavio-java", "2.2.0")
                .release("2.2.0", "v2.2.0", "b1ff1be")
                .runtime("production", "project:prod-support", true, "metadata_only")
                .auditChain(2, true, "abc", "def")
                .runtimeEvents(2, true, List.of("trace.start", "provider.call.end"))
                .addControl("policy_pack", "support", "pass", "test-vectors/policy-packs/catalog.json")
                .addDocument("Threat model", "docs/trust-package.md#threat-model", "")
                .build();

        ProductionTrustVerification result = ProductionTrust.verify(bundle);

        assertTrue(result.valid());
        assertEquals(List.of(), result.errors());
        assertEquals(bundle.get("bundleHash"), result.computedHash());
    }

    @Test
    void verifiesSharedProductionTrustVector() throws Exception {
        Path vectorPath = repoRoot().resolve("test-vectors/trust/production-trust-bundle.json");
        Map<String, Object> vector = Json.parseObject(Files.readString(vectorPath));

        ProductionTrustVerification result = ProductionTrust.verify(vector);

        assertTrue(result.valid());
        assertEquals(vector.get("bundleHash"), result.computedHash());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTamperedOrContentBearingBundle() {
        Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
                .generatedAt("2026-07-12T12:00:00Z")
                .sdk("gavio-java", "2.2.0")
                .release("2.2.0", "v2.2.0", "b1ff1be")
                .runtime("production", "project:prod-support", true, "metadata_only")
                .auditChain(2, true, "abc", "def")
                .runtimeEvents(2, true, List.of("trace.start", "provider.call.end"))
                .build();

        Map<String, Object> release = (Map<String, Object>) bundle.get("release");
        release.put("version", "1.7.1");
        Map<String, Object> evidence = (Map<String, Object>) bundle.get("evidence");
        Map<String, Object> runtimeEvents = (Map<String, Object>) evidence.get("runtimeEvents");
        runtimeEvents.put("contentFree", false);
        runtimeEvents.put("content", "raw prompt text");

        ProductionTrustVerification result = ProductionTrust.verify(bundle);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("bundleHash does not match bundle content"));
        assertTrue(result.errors().contains("bundle contains content-bearing keys"));
        assertTrue(result.errors().contains("evidence.runtimeEvents.contentFree must be true"));
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
