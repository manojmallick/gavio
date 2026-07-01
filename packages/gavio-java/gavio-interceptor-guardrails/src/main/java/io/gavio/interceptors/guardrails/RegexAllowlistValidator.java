package io.gavio.interceptors.guardrails;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fails unless the content matches at least ONE allowed pattern (F-QUA-02). */
public final class RegexAllowlistValidator implements OutputValidator {

    private final List<Pattern> patterns = new ArrayList<>();

    public RegexAllowlistValidator(List<String> patterns) {
        for (String p : patterns) {
            this.patterns.add(Pattern.compile(p));
        }
    }

    public static RegexAllowlistValidator of(String... patterns) {
        return new RegexAllowlistValidator(List.of(patterns));
    }

    @Override
    public String name() {
        return "regex_allowlist";
    }

    @Override
    public ValidationResult validate(String content) {
        for (Pattern p : patterns) {
            if (p.matcher(content).find()) {
                return ValidationResult.passed();
            }
        }
        return ValidationResult.failed("content matched no allowed pattern");
    }
}
