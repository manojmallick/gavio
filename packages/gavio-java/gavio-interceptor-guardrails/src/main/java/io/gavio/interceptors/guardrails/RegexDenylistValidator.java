package io.gavio.interceptors.guardrails;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fails if the content matches ANY denied pattern (F-QUA-02). */
public final class RegexDenylistValidator implements OutputValidator {

    private final List<Pattern> patterns = new ArrayList<>();

    public RegexDenylistValidator(List<String> patterns) {
        for (String p : patterns) {
            this.patterns.add(Pattern.compile(p));
        }
    }

    public static RegexDenylistValidator of(String... patterns) {
        return new RegexDenylistValidator(List.of(patterns));
    }

    @Override
    public String name() {
        return "regex_denylist";
    }

    @Override
    public ValidationResult validate(String content) {
        for (Pattern p : patterns) {
            if (p.matcher(content).find()) {
                return ValidationResult.failed("content matched denied pattern /" + p.pattern() + "/");
            }
        }
        return ValidationResult.passed();
    }
}
