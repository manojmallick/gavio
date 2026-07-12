package io.gavio.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import io.gavio.types.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptRegistryEvalTest {

    @Test
    @SuppressWarnings("unchecked")
    void promptRegistryRendersSharedVectorsWithoutRawLineageContent() throws Exception {
        Map<String, Object> vectors = loadVector();
        Map<String, Object> templateVector = ((List<Map<String, Object>>) vectors.get("templates")).get(0);
        PromptRegistry registry = new PromptRegistry();
        registry.register(PromptTemplate.fromMap(templateVector));

        RenderedPrompt rendered =
                registry.render((String) templateVector.get("id"), (Map<String, Object>) templateVector.get("variables"));

        assertEquals(messages(templateVector.get("expectedMessages")), rendered.messages());
        Map<String, Object> expectedLineage = (Map<String, Object>) templateVector.get("expectedLineage");
        assertEquals(expectedLineage.get("templateId"), rendered.lineage().templateId());
        assertEquals(expectedLineage.get("templateVersion"), rendered.lineage().templateVersion());
        assertEquals(expectedLineage.get("variables"), rendered.lineage().variables());
        assertFalse(rendered.lineage().toMap().containsKey("renderedPrompt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptRegistryReportsMissingRequiredVariables() throws Exception {
        Map<String, Object> templateVector = ((List<Map<String, Object>>) loadVector().get("templates")).get(0);
        PromptRegistry registry = new PromptRegistry();
        registry.register(PromptTemplate.fromMap(templateVector));
        Map<String, Object> variables = new java.util.LinkedHashMap<>((Map<String, Object>) templateVector.get("variables"));
        variables.remove("topic");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.render((String) templateVector.get("id"), variables));

        assertTrue(error.getMessage().contains("topic"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evalSuiteRunsSharedVectorsWithoutRawOutputs() throws Exception {
        Map<String, Object> vectors = loadVector();
        PromptRegistry registry = new PromptRegistry();
        for (Map<String, Object> rawTemplate : (List<Map<String, Object>>) vectors.get("templates")) {
            registry.register(PromptTemplate.fromMap(rawTemplate));
        }
        Map<String, Object> suiteVector = (Map<String, Object>) vectors.get("suite");
        EvalSuite suite = EvalSuite.fromMap(suiteVector);
        Map<String, String> outputs = new java.util.HashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) suiteVector.get("cases")) {
            outputs.put((String) c.get("id"), (String) c.get("mockOutput"));
        }

        EvalReport report = suite.run(registry, (_prompt, c) -> outputs.get(c.id()));
        Map<String, Object> data = report.toMap();
        Map<String, Object> expected = (Map<String, Object>) suiteVector.get("expectedReport");

        assertEquals(expected.get("suiteId"), data.get("suiteId"));
        assertEquals(((Number) expected.get("totalCases")).intValue(), data.get("totalCases"));
        assertEquals(((Number) expected.get("passedCases")).intValue(), data.get("passedCases"));
        assertEquals(((Number) expected.get("failedCases")).intValue(), data.get("failedCases"));
        assertEquals(((Number) expected.get("score")).doubleValue(), ((Number) data.get("score")).doubleValue(), 1e-9);

        List<Map<String, Object>> cases = (List<Map<String, Object>>) data.get("cases");
        List<Map<String, Object>> expectedCases = (List<Map<String, Object>>) suiteVector.get("cases");
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> expectedCase = (Map<String, Object>) expectedCases.get(i).get("expected");
            assertEquals(expectedCase.get("passed"), cases.get(i).get("passed"));
            assertEquals(((Number) expectedCase.get("score")).doubleValue(),
                    ((Number) cases.get(i).get("score")).doubleValue(), 1e-9);
            assertEquals(64, String.valueOf(cases.get(i).get("outputHash")).length());
        }
        String serialized = Json.write(data);
        for (String contentKey : (List<String>) vectors.get("contentKeys")) {
            assertFalse(serialized.contains("\"" + contentKey + "\""));
        }
        for (Map<String, Object> c : expectedCases) {
            assertFalse(serialized.contains((String) c.get("mockOutput")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptRegistryV2LoadsSignedFileAndResolvesSemverRanges() throws Exception {
        Map<String, Object> vector = loadV2Vector();
        Map<String, Object> manifest = (Map<String, Object>) vector.get("manifest");
        Map<String, Object> expected = (Map<String, Object>) vector.get("expected");
        String secret = (String) vector.get("signatureSecret");
        Path manifestPath = Files.createTempFile("gavio-prompts", ".json");
        Files.writeString(manifestPath, Json.write(manifest));

        assertTrue(PromptManifests.verifySignature(manifest, secret));

        PromptRegistry registry = PromptRegistry.fromFile(manifestPath, secret);

        assertEquals(expected.get("latestVersion"), registry.get("support.reply").version());
        assertEquals(expected.get("caretVersion"), registry.get("support.reply", "^1.0.0").version());
        assertEquals(expected.get("tildeVersion"), registry.get("support.reply", "~1.1.0").version());
        assertEquals(expected.get("rangeVersion"), registry.get("support.reply", ">=1.0.0 <2.0.0").version());
        assertEquals("pending", registry.get("support.reply").approval().status());

        RenderedPrompt rendered = registry.render("support.reply", Map.of(
                "customerName", "Avery",
                "topic", "refund status",
                "orderId", "A-100"));
        assertEquals(expected.get("renderedMessage"), rendered.messages().get(rendered.messages().size() - 1).content());

        Map<String, Object> signedRoundTrip = registry.toSignedManifest(
                (String) vector.get("registryId"),
                (Map<String, Object>) vector.get("metadata"),
                secret,
                (String) ((Map<String, Object>) manifest.get("signature")).get("keyId"));
        assertEquals(
                ((Map<String, Object>) manifest.get("signature")).get("value"),
                ((Map<String, Object>) signedRoundTrip.get("signature")).get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptRegistryV2DiffIsMetadataSafe() throws Exception {
        Map<String, Object> vector = loadV2Vector();
        Map<String, Object> manifest = (Map<String, Object>) vector.get("manifest");
        Map<String, Object> expected = (Map<String, Object>) vector.get("expected");
        List<Map<String, Object>> rawTemplates = (List<Map<String, Object>>) manifest.get("templates");

        PromptDiff diff = PromptDiff.between(
                PromptTemplate.fromMap(rawTemplates.get(0)),
                PromptTemplate.fromMap(rawTemplates.get(1)));
        List<Map<String, Object>> changes = (List<Map<String, Object>>) diff.toMap().get("changes");

        assertEquals(expected.get("diffPaths"), changes.stream().map(change -> change.get("path")).toList());
        assertTrue(changes.stream()
                .filter(change -> String.valueOf(change.get("path")).startsWith("messages["))
                .allMatch(change -> change.containsKey("beforeHash") || change.containsKey("afterHash")));
        String serialized = Json.write(diff.toMap());
        for (String content : (List<String>) expected.get("contentKeys")) {
            assertFalse(serialized.contains(content));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptRegistryV2RejectsBadSemverAndSignsDeterministically() throws Exception {
        Map<String, Object> vector = loadV2Vector();
        Map<String, Object> manifest = (Map<String, Object>) vector.get("manifest");
        Map<String, Object> badManifest = new java.util.LinkedHashMap<>(manifest);
        Map<String, Object> badTemplate =
                new java.util.LinkedHashMap<>(((List<Map<String, Object>>) manifest.get("templates")).get(0));
        badTemplate.put("version", "2026-07-12");
        badManifest.put("templates", List.of(badTemplate));

        assertThrows(
                IllegalArgumentException.class,
                () -> PromptRegistry.fromManifest(badManifest, null, true));

        Map<String, Object> unsigned = new java.util.LinkedHashMap<>(manifest);
        unsigned.remove("signature");
        Map<String, Object> signed = PromptManifests.sign(
                unsigned,
                (String) vector.get("signatureSecret"),
                (String) ((Map<String, Object>) manifest.get("signature")).get("keyId"));

        assertEquals(manifest.get("signature"), signed.get("signature"));
    }

    @SuppressWarnings("unchecked")
    private static List<Message> messages(Object raw) {
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> message : (List<Map<String, Object>>) raw) {
            messages.add(Message.of((String) message.get("role"), (String) message.get("content")));
        }
        return messages;
    }

    private static Map<String, Object> loadVector() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/prompts/registry-evals.json")));
    }

    private static Map<String, Object> loadV2Vector() throws Exception {
        return Json.parseObject(Files.readString(repoRoot().resolve("test-vectors/prompts/registry-v2.json")));
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
