package io.gavio.prompts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prompt-to-eval workflow helpers. */
public final class PromptEvalWorkflow {
    private PromptEvalWorkflow() {
    }

    public static PromptVersionGate evaluate(EvalReport report, PromptEvalLink link) {
        List<EvalCaseResult> cases = report.suiteId().equals(link.suiteId())
                ? report.cases().stream()
                        .filter(c -> c.templateId().equals(link.promptId())
                                && c.templateVersion().equals(link.promptVersion()))
                        .toList()
                : List.of();
        List<String> failed = cases.stream().filter(c -> !c.passed()).map(EvalCaseResult::id).toList();
        double score = cases.isEmpty()
                ? 0.0
                : EvalReport.round8(cases.stream().mapToDouble(EvalCaseResult::score).sum() / cases.size());
        List<String> reasons = new ArrayList<>();
        if (cases.isEmpty()) {
            reasons.add("no eval cases found for "
                    + link.promptId() + "@" + link.promptVersion() + " in " + link.suiteId());
        }
        if (!failed.isEmpty()) {
            reasons.add(failed.size() + " linked eval case(s) failed");
        }
        if (link.failUnder() != null && score < link.failUnder()) {
            reasons.add(String.format("score %.8f is below fail-under %.8f", score, link.failUnder()));
        }
        Double scoreDelta = null;
        if (link.baselineScore() != null) {
            scoreDelta = EvalReport.round8(score - link.baselineScore());
            if (scoreDelta < -link.maxRegression()) {
                reasons.add(String.format(
                        "score regression %.8f exceeds allowed %.8f",
                        scoreDelta,
                        link.maxRegression()));
            }
        }
        return new PromptVersionGate(
                link.promptId(),
                link.promptVersion(),
                link.suiteId(),
                reasons.isEmpty(),
                score,
                cases.size(),
                (int) cases.stream().filter(EvalCaseResult::passed).count(),
                failed,
                reasons,
                link.baselineScore(),
                link.failUnder(),
                link.maxRegression(),
                scoreDelta);
    }

    public static PromptWorkflowResult evaluate(EvalReport report, List<PromptEvalLink> links) {
        List<PromptVersionGate> gates = new ArrayList<>();
        for (PromptEvalLink link : links) {
            gates.add(evaluate(report, link));
        }
        return new PromptWorkflowResult(links, gates);
    }

    @SuppressWarnings("unchecked")
    public static List<PromptEvalLink> linksFromManifest(Map<String, Object> manifest) {
        List<PromptEvalLink> links = new ArrayList<>();
        Object rawLinks = manifest.getOrDefault("promptEvalLinks", manifest.get("evalLinks"));
        if (rawLinks instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> m) {
                    links.add(PromptEvalLink.fromMap((Map<String, Object>) m));
                }
            }
        }
        Object rawTemplates = manifest.get("templates");
        if (rawTemplates instanceof List<?> templates) {
            for (Object rawTemplate : templates) {
                if (!(rawTemplate instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> template = (Map<String, Object>) rawMap;
                List<Object> candidates = new ArrayList<>();
                collectLinks(template, candidates);
                if (template.get("metadata") instanceof Map<?, ?> metadata) {
                    collectLinks((Map<String, Object>) metadata, candidates);
                }
                for (Object raw : candidates) {
                    if (!(raw instanceof Map<?, ?> m)) {
                        continue;
                    }
                    Map<String, Object> link = new LinkedHashMap<>((Map<String, Object>) m);
                    link.putIfAbsent("promptId", template.get("id"));
                    link.putIfAbsent("promptVersion", template.get("version"));
                    links.add(PromptEvalLink.fromMap(link));
                }
            }
        }
        return List.copyOf(links);
    }

    public static PromptReleaseBundle buildReleaseBundle(
            Map<String, Object> manifest,
            String promptId,
            String promptVersion,
            List<EvalReport> reports,
            List<PromptEvalLink> links,
            String fromVersion,
            String generatedAt,
            String bundleId,
            Map<String, Object> metadata) {
        PromptRegistry registry = PromptRegistry.fromManifest(manifest, null, false);
        PromptDiff diff = null;
        if (fromVersion != null) {
            diff = registry.diff(promptId, fromVersion, promptVersion);
        }
        List<PromptEvalLink> scopedLinks = links == null ? linksFromManifest(manifest) : links;
        scopedLinks = scopedLinks.stream()
                .filter(link -> link.promptId().equals(promptId) && link.promptVersion().equals(promptVersion))
                .toList();
        List<PromptVersionGate> gates = new ArrayList<>();
        for (EvalReport report : reports) {
            for (PromptEvalLink link : scopedLinks) {
                if (link.suiteId().equals(report.suiteId())) {
                    gates.add(evaluate(report, link));
                }
            }
        }
        return new PromptReleaseBundle(
                bundleId == null ? promptId + "@" + promptVersion : bundleId,
                promptId,
                promptVersion,
                generatedAt == null ? Instant.now().toString() : generatedAt,
                manifestIdentity(manifest),
                gates,
                reports,
                diff,
                metadata == null ? Map.of() : metadata);
    }

    @SuppressWarnings("unchecked")
    private static void collectLinks(Map<String, Object> data, List<Object> out) {
        for (String key : List.of("promptEvalLinks", "evalLinks", "evals")) {
            Object value = data.get(key);
            if (value instanceof List<?> list) {
                out.addAll((List<Object>) list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> manifestIdentity(Map<String, Object> manifest) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schemaVersion", manifest.get("schemaVersion"));
        identity.put("registryId", manifest.get("registryId"));
        identity.put("digest", PromptManifests.digest(manifest));
        if (manifest.get("signature") instanceof Map<?, ?> rawSignature) {
            Map<String, Object> signature = (Map<String, Object>) rawSignature;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String key : List.of("algorithm", "keyId", "value")) {
                if (signature.containsKey(key)) {
                    copy.put(key, signature.get(key));
                }
            }
            identity.put("signature", copy);
        }
        return identity;
    }
}
