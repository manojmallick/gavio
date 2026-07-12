package io.gavio.interceptors.pii.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import io.gavio.interceptors.pii.scanners.DefaultScanners;
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

class PolicyPackTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws IOException {
        return Json.parseObject(Files.readString(vectorsPath("manifest.json")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> catalogVectors() throws IOException {
        return Json.parseObject(Files.readString(vectorsPath("catalog.json")));
    }

    private static Path vectorsPath(String file) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/policy-packs/" + file);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate policy-pack vectors from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<String> detectorEntityTypes(Map<String, Object> manifest) {
        return ((List<Object>) manifest.get("detectors"))
                .stream()
                .map(obj -> ((Map<String, Object>) obj).get("entityType").toString())
                .toList();
    }

    private static List<String> scannerEntityTypes(List<PiiScanner> scanners) {
        return scanners.stream().map(PiiScanner::entityType).toList();
    }

    private static List<String> strings(Object value) {
        return ((List<?>) value).stream().map(Object::toString).toList();
    }

    private static List<String> detect(String text, List<PiiScanner> scanners) {
        ScanContext ctx = new ScanContext();
        TreeSet<String> found = new TreeSet<>();
        for (PiiScanner scanner : scanners) {
            if (!scanner.scan(text, ctx).isEmpty()) {
                found.add(scanner.entityType());
            }
        }
        return new ArrayList<>(found);
    }

    @SuppressWarnings("unchecked")
    @Test
    void builtinPolicyPackManifests() throws IOException {
        Map<String, PolicyPack> packs = Map.of(
                "gavio.core-pii", PolicyPacks.core(),
                "gavio.fintech", PolicyPacks.fintech());
        for (Object obj : (List<Object>) vectors().get("builtinPacks")) {
            Map<String, Object> expected = (Map<String, Object>) obj;
            Map<String, Object> manifest = packs.get(expected.get("id")).manifest();
            assertEquals(expected.get("id"), manifest.get("id"));
            assertEquals(expected.get("name"), manifest.get("name"));
            assertEquals(expected.get("version"), manifest.get("version"));
            assertEquals(expected.get("domain"), manifest.get("domain"));
            assertEquals(expected.get("defaultAction"), manifest.get("defaultAction"));
            assertEquals(expected.get("redactionStrategy"), manifest.get("redactionStrategy"));
            assertEquals(expected.get("auditLabels"), manifest.get("auditLabels"));
            assertEquals(expected.get("detectorEntityTypes"), detectorEntityTypes(manifest));
        }
    }

    @Test
    void fintechScannersAreBackedByPolicyPack() {
        assertEquals(
                PolicyPacks.fintech().detectors().stream().map(PolicyDetector::entityType).toList(),
                scannerEntityTypes(DefaultScanners.fintech()));
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> customRegexRulePolicyPackVectors() throws IOException {
        Map<String, Object> vector = (Map<String, Object>) vectors().get("customRulePack");
        List<Object> rules = (List<Object>) vector.get("rules");
        List<RegexPolicyRule> parsedRules = new ArrayList<>();
        for (Object obj : rules) {
            Map<String, Object> rule = (Map<String, Object>) obj;
            parsedRules.add(new RegexPolicyRule(
                    rule.get("name").toString(),
                    rule.get("entityType").toString(),
                    rule.get("pattern").toString(),
                    ((Number) rule.get("confidence")).doubleValue(),
                    rule.get("replacementPrefix").toString(),
                    PolicyAction.FLAG,
                    RedactionStrategy.HASH,
                    rule.get("label").toString()));
        }
        PolicyPack pack = PolicyPacks.custom(
                vector.get("id").toString(),
                vector.get("name").toString(),
                vector.get("version").toString(),
                vector.get("domain").toString(),
                parsedRules,
                PolicyAction.FLAG,
                RedactionStrategy.HASH,
                ((List<Object>) vector.get("auditLabels")).stream().map(Object::toString).toList(),
                "Custom organization policy pack.");
        Map<String, Object> manifest = pack.manifest();
        assertEquals(vector.get("id"), manifest.get("id"));
        assertEquals(vector.get("defaultAction"), manifest.get("defaultAction"));
        assertEquals(vector.get("redactionStrategy"), manifest.get("redactionStrategy"));
        assertEquals(vector.get("auditLabels"), manifest.get("auditLabels"));

        List<DynamicTest> tests = new ArrayList<>();
        for (Object obj : (List<Object>) vector.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) obj;
            tests.add(DynamicTest.dynamicTest("policy-pack:" + c.get("id"), () ->
                    assertEquals(
                            ((List<Object>) c.get("expectedTypes")).stream().map(Object::toString).toList(),
                            detect(c.get("text").toString(), PolicyPacks.scanners(pack)))));
        }
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogPolicyPackListAndManifests() throws IOException {
        Map<String, Object> vectors = catalogVectors();
        assertEquals(strings(vectors.get("catalogNames")), PolicyPacks.listCatalog());
        Map<String, Object> signature = (Map<String, Object>) vectors.get("signature");
        for (Object obj : (List<Object>) vectors.get("catalogPacks")) {
            Map<String, Object> expected = (Map<String, Object>) obj;
            PolicyPack pack = PolicyPacks.load(expected.get("name").toString());
            Map<String, Object> manifest = pack.manifest();
            assertEquals(expected.get("id"), manifest.get("id"));
            assertEquals(expected.get("domain"), manifest.get("domain"));
            assertEquals(expected.get("auditLabels"), manifest.get("auditLabels"));
            assertEquals(expected.get("detectorEntityTypes"), detectorEntityTypes(manifest));
            assertEquals(signature.get("algorithm"), ((Map<String, Object>) manifest.get("signature")).get("algorithm"));
            assertTrue(pack.verifySignature());
            assertTrue(PiiGuard.fromPolicyPack(pack).dryRunSafe());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogSignatureFailsClosedForMutatedManifest() throws IOException {
        Map<String, Object> vectors = catalogVectors();
        Map<String, Object> signature = (Map<String, Object>) vectors.get("signature");
        Map<String, Object> manifest = PolicyPacks.load("finance").manifest();
        ((Map<String, Object>) manifest.get("signature")).put("value", signature.get("badValue"));
        Path path = Files.createTempFile("gavio-policy-pack-", ".json");
        Files.writeString(path, Json.write(manifest));
        assertFalse(PolicyPacks.loadPath(path).verifySignature());
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogOverridesUpdateDetectorMetadata() throws IOException {
        Map<String, Object> c = (Map<String, Object>) catalogVectors().get("overrideCase");
        PolicyPack pack = PolicyPacks.load(c.get("pack").toString())
                .withOverrides((Map<String, Object>) c.get("overrides"));
        Map<String, Object> detector = ((List<Map<String, Object>>) (Object) pack.manifest().get("detectors")).stream()
                .filter(item -> item.get("name").equals(c.get("detector")))
                .findFirst()
                .orElseThrow();
        assertEquals(c.get("expectedAction"), detector.get("action"));
        assertEquals(c.get("expectedSeverity"), detector.get("severity"));
        assertEquals(c.get("expectedRedactionStrategy"), detector.get("redactionStrategy"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogSuppressionRulesAreApplied() throws IOException {
        Map<String, Object> c = (Map<String, Object>) catalogVectors().get("suppressionCase");
        PolicyPack pack = PolicyPacks.load(c.get("pack").toString());
        assertEquals(strings(c.get("expectedTypes")), detect(c.get("text").toString(), PolicyPacks.scanners(pack)));
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> catalogDomainPolicyPackVectors() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object obj : (List<Object>) catalogVectors().get("domainCases")) {
            Map<String, Object> c = (Map<String, Object>) obj;
            tests.add(DynamicTest.dynamicTest("catalog-policy-pack:" + c.get("pack"), () -> {
                PolicyPack pack = PolicyPacks.load(c.get("pack").toString());
                assertEquals(strings(c.get("expectedTypes")), detect(c.get("text").toString(), PolicyPacks.scanners(pack)));
            }));
        }
        return tests.stream();
    }
}
