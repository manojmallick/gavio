package io.gavio.prompts;

import io.gavio.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-backed deterministic eval runner and CI gate.
 *
 * <p>Library port of {@code gavio.prompts.runner} (Python). Every case must provide a deterministic
 * output through a case-level output key or a top-level {@code outputs} map; provider calls are
 * left to application code that uses {@link EvalSuite} directly. The {@code gavio eval run} CLI
 * stays Python-only, and only JSON documents are read here (YAML suites are Python-only).
 */
public final class EvalRunner {

    public static final String SCHEMA_VERSION = "1.0";

    private static final List<String> OUTPUT_KEYS =
            List.of("mockOutput", "mock_output", "output", "expectedOutput", "expected_output");

    private EvalRunner() {
    }

    /** Optional gate and template inputs for {@link #run(Path, Options)}. */
    public record Options(List<Path> templatePaths, Double failUnder, Path baselinePath, double maxRegression) {

        public Options {
            templatePaths = List.copyOf(templatePaths == null ? List.of() : templatePaths);
        }

        public static Options defaults() {
            return new Options(List.of(), null, null, 0.0);
        }
    }

    public static EvalRunResult run(Path suitePath) {
        return run(suitePath, Options.defaults());
    }

    /** Run one deterministic eval suite file and evaluate its CI gate. */
    public static EvalRunResult run(Path suitePath, Options options) {
        Path suiteFile = expandUser(suitePath.toString()).toAbsolutePath().normalize();
        Map<String, Object> payload = loadDocument(suiteFile);
        List<Map<String, Object>> templates = loadTemplates(suiteFile, payload, options.templatePaths());
        List<PromptTemplate> parsed = new ArrayList<>();
        for (Map<String, Object> template : templates) {
            parsed.add(PromptTemplate.fromMap(template));
        }
        PromptRegistry registry = new PromptRegistry(parsed);
        EvalSuite suite = EvalSuite.fromMap(normalizeSuite(suitePayload(payload)));
        Map<String, String> outputs = outputMap(payload, suite);
        EvalReport report = suite.run(registry, (prompt, testCase) -> outputs.get(testCase.id()));
        Double baselineScore = options.baselinePath() == null ? null : loadBaselineScore(options.baselinePath());
        EvalGate gate = evaluateGate(report, options.failUnder(), baselineScore, options.maxRegression());
        List<PromptEvalLink> links = workflowLinks(payload, templates, suite.id());
        PromptWorkflowResult workflow = links.isEmpty() ? null : PromptEvalWorkflow.evaluate(report, links);
        return new EvalRunResult(report, gate, suiteFile.toString(), workflow);
    }

    public static EvalGate evaluateGate(
            EvalReport report, Double failUnder, Double baselineScore, double maxRegression) {
        List<String> reasons = new ArrayList<>();
        if (report.failedCases() > 0) {
            reasons.add(report.failedCases() + " eval case(s) failed");
        }
        if (failUnder != null && report.score() < failUnder) {
            reasons.add(String.format(
                    Locale.ROOT, "score %.8f is below fail-under %.8f", report.score(), failUnder));
        }
        Double scoreDelta = null;
        if (baselineScore != null) {
            scoreDelta = EvalReport.round8(report.score() - baselineScore);
            if (scoreDelta < -maxRegression) {
                reasons.add(String.format(
                        Locale.ROOT, "score regression %.8f exceeds allowed %.8f", scoreDelta, maxRegression));
            }
        }
        return new EvalGate(reasons.isEmpty(), reasons, failUnder, baselineScore, maxRegression, scoreDelta);
    }

    /** Load a JSON eval document; the top level must be an object. */
    public static Map<String, Object> loadDocument(Path path) {
        try {
            return Json.parseObject(Files.readString(expandUser(path.toString())));
        } catch (IOException e) {
            throw new IllegalStateException("could not read eval document: " + path, e);
        } catch (Json.JsonException e) {
            throw new IllegalArgumentException(path + ": expected a JSON object", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> suitePayload(Map<String, Object> payload) {
        Object suite = payload.get("suite");
        if (suite != null) {
            if (!(suite instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("suite must be an object");
            }
            return (Map<String, Object>) map;
        }
        if (!payload.containsKey("id") || !payload.containsKey("cases")) {
            throw new IllegalArgumentException("eval document must contain either suite or id/cases");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", payload.get("id"));
        out.put("cases", payload.get("cases"));
        return out;
    }

    /** Accept the {@code case_sensitive} spelling Python accepts for assertions. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSuite(Map<String, Object> suite) {
        Map<String, Object> out = new LinkedHashMap<>(suite);
        List<Object> cases = new ArrayList<>();
        for (Object rawCase : asList(suite.get("cases"))) {
            if (!(rawCase instanceof Map<?, ?> caseMap)) {
                cases.add(rawCase);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) caseMap);
            List<Object> assertions = new ArrayList<>();
            for (Object rawAssertion : asList(copy.get("assertions"))) {
                if (rawAssertion instanceof Map<?, ?> assertionMap) {
                    Map<String, Object> assertion = new LinkedHashMap<>((Map<String, Object>) assertionMap);
                    if (!assertion.containsKey("caseSensitive") && assertion.containsKey("case_sensitive")) {
                        assertion.put("caseSensitive", assertion.get("case_sensitive"));
                    }
                    assertions.add(assertion);
                } else {
                    assertions.add(rawAssertion);
                }
            }
            if (copy.containsKey("assertions")) {
                copy.put("assertions", assertions);
            }
            cases.add(copy);
        }
        out.put("cases", cases);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadTemplates(
            Path suiteFile, Map<String, Object> payload, List<Path> templatePaths) {
        List<Map<String, Object>> templates = new ArrayList<>();
        Object inline = payload.get("templates");
        if (inline != null) {
            if (!(inline instanceof List<?> list)) {
                throw new IllegalArgumentException("templates must be a list");
            }
            for (Object item : list) {
                templates.add((Map<String, Object>) item);
            }
        }

        List<String> paths = new ArrayList<>();
        for (Path path : templatePaths) {
            paths.add(path.toString());
        }
        for (String key : List.of("templatesFile", "templates_file", "templateFile", "template_file")) {
            if (payload.get(key) != null) {
                paths.add(String.valueOf(payload.get(key)));
            }
        }
        for (String key : List.of("templateFiles", "template_files")) {
            if (payload.get(key) instanceof List<?> list) {
                for (Object item : list) {
                    paths.add(String.valueOf(item));
                }
            }
        }

        for (String path : paths) {
            Map<String, Object> templatePayload = loadDocument(resolveRelative(suiteFile, path));
            Object items = templatePayload.containsKey("templates") ? templatePayload.get("templates") : templatePayload;
            if (items instanceof Map<?, ?> single) {
                items = List.of(single);
            }
            if (!(items instanceof List<?> list)) {
                throw new IllegalArgumentException(path + ": expected template object/list or templates list");
            }
            for (Object item : list) {
                templates.add((Map<String, Object>) item);
            }
        }

        if (templates.isEmpty()) {
            throw new IllegalArgumentException("eval document must provide templates or template files");
        }
        return templates;
    }

    @SuppressWarnings("unchecked")
    private static List<PromptEvalLink> workflowLinks(
            Map<String, Object> payload, List<Map<String, Object>> templates, String suiteId) {
        List<PromptEvalLink> links = new ArrayList<>();
        if (payload.get("promptWorkflow") instanceof Map<?, ?> workflow) {
            Object rawLinks = workflow.get("links") != null ? workflow.get("links") : workflow.get("promptEvalLinks");
            for (Object raw : asList(rawLinks)) {
                addLink(links, raw, null, null, suiteId);
            }
        }
        for (String key : List.of("promptEvalLinks", "evalLinks")) {
            for (Object raw : asList(payload.get(key))) {
                addLink(links, raw, null, null, suiteId);
            }
        }
        for (Map<String, Object> template : templates) {
            String promptId = String.valueOf(template.get("id"));
            String promptVersion = String.valueOf(template.get("version"));
            List<Object> candidates = new ArrayList<>();
            for (String key : List.of("promptEvalLinks", "evalLinks", "evals")) {
                candidates.addAll(asList(template.get(key)));
            }
            if (template.get("metadata") instanceof Map<?, ?> metadata) {
                for (String key : List.of("promptEvalLinks", "evalLinks", "evals")) {
                    candidates.addAll(asList(((Map<String, Object>) metadata).get(key)));
                }
            }
            for (Object raw : candidates) {
                addLink(links, raw, promptId, promptVersion, suiteId);
            }
        }

        // Later links win but keep the position of the first one, like a dict.
        Map<String, PromptEvalLink> deduped = new LinkedHashMap<>();
        for (PromptEvalLink link : links) {
            deduped.put(link.promptId() + "\u0000" + link.promptVersion() + "\u0000" + link.suiteId(), link);
        }
        return List.copyOf(deduped.values());
    }

    @SuppressWarnings("unchecked")
    private static void addLink(
            List<PromptEvalLink> links, Object raw, String promptId, String promptVersion, String suiteId) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> data = (Map<String, Object>) rawMap;
        Map<String, Object> link = new LinkedHashMap<>(data);
        putIfMissing(link, "promptId", promptId);
        putIfMissing(link, "promptVersion", promptVersion);
        if (link.get("suiteId") == null && link.get("evalSuiteId") == null && link.get("id") == null) {
            link.put("suiteId", suiteId);
        }
        alias(link, "baselineScore", "baseline_score");
        alias(link, "failUnder", "fail_under");
        alias(link, "maxRegression", "max_regression");
        links.add(PromptEvalLink.fromMap(link));
    }

    private static void putIfMissing(Map<String, Object> map, String key, String value) {
        if (value != null && map.get(key) == null && map.get(altKey(key)) == null) {
            map.put(key, value);
        }
    }

    private static String altKey(String key) {
        return key.equals("promptId") ? "templateId" : "templateVersion";
    }

    private static void alias(Map<String, Object> map, String key, String snake) {
        if (map.get(key) == null && map.get(snake) != null) {
            map.put(key, map.get(snake));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> outputMap(Map<String, Object> payload, EvalSuite suite) {
        Map<String, String> outputs = new LinkedHashMap<>();
        Object rawOutputs = truthy(payload.get("outputs"));
        if (rawOutputs == null) {
            rawOutputs = truthy(payload.get("mockOutputs"));
        }
        if (rawOutputs == null) {
            rawOutputs = truthy(payload.get("mock_outputs"));
        }
        if (rawOutputs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                outputs.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        for (Object raw : asList(suitePayload(payload).get("cases"))) {
            if (!(raw instanceof Map<?, ?> rawCase)) {
                continue;
            }
            Map<String, Object> data = (Map<String, Object>) rawCase;
            String caseId = String.valueOf(data.getOrDefault("id", ""));
            for (String key : OUTPUT_KEYS) {
                if (data.containsKey(key)) {
                    outputs.put(caseId, String.valueOf(data.get(key)));
                    break;
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (EvalCase testCase : suite.cases()) {
            if (!outputs.containsKey(testCase.id())) {
                missing.add(testCase.id());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "deterministic eval runner requires outputs for case(s): " + String.join(", ", missing));
        }
        return outputs;
    }

    @SuppressWarnings("unchecked")
    private static double loadBaselineScore(Path path) {
        Map<String, Object> data = loadDocument(path);
        Object score = data.get("score");
        if (score == null && data.get("report") instanceof Map<?, ?> report) {
            score = ((Map<String, Object>) report).get("score");
        }
        if (score == null) {
            throw new IllegalArgumentException(path + ": baseline report missing score");
        }
        return score instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(score));
    }

    private static Path resolveRelative(Path baseFile, String value) {
        Path path = expandUser(value);
        return path.isAbsolute() ? path : baseFile.getParent().resolve(path);
    }

    static Path expandUser(String value) {
        if (value.equals("~") || value.startsWith("~/")) {
            String home = System.getProperty("user.home", "");
            return Path.of(home).resolve(value.length() > 2 ? value.substring(2) : "");
        }
        return Path.of(value);
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    /** Python truthiness for the {@code a or b or c} chains the eval document accepts. */
    private static Object truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == 0.0) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        return value;
    }
}
