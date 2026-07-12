package io.gavio.prompts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Prompt eval suite. */
public record EvalSuite(String id, List<EvalCase> cases) {

    public EvalSuite {
        cases = List.copyOf(cases);
    }

    @SuppressWarnings("unchecked")
    public static EvalSuite fromMap(Map<String, Object> data) {
        List<EvalCase> cases = new ArrayList<>();
        for (Object raw : (List<Object>) data.getOrDefault("cases", List.of())) {
            cases.add(EvalCase.fromMap((Map<String, Object>) raw));
        }
        return new EvalSuite(String.valueOf(data.get("id")), cases);
    }

    public EvalReport run(
            PromptRegistry registry,
            BiFunction<RenderedPrompt, EvalCase, String> complete) {
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            RenderedPrompt prompt = registry.render(c.templateId(), c.variables(), c.templateVersion());
            String output = complete.apply(prompt, c);
            List<EvalAssertionResult> assertions = new ArrayList<>();
            for (EvalAssertion assertion : c.assertions()) {
                assertions.add(assertion.check(output));
            }
            int passedCount = 0;
            for (EvalAssertionResult assertion : assertions) {
                if (assertion.passed()) {
                    passedCount++;
                }
            }
            boolean passed = passedCount == assertions.size();
            double score = assertions.isEmpty()
                    ? 0.0
                    : EvalReport.round8((double) passedCount / assertions.size());
            results.add(new EvalCaseResult(
                    c.id(),
                    c.templateId(),
                    prompt.lineage().templateVersion(),
                    passed,
                    score,
                    sha256(output),
                    assertions,
                    prompt.lineage()));
        }
        return new EvalReport(id, results);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
