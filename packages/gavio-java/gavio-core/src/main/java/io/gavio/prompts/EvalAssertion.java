package io.gavio.prompts;

import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/** Simple built-in output assertion. */
public record EvalAssertion(String type, Object value, boolean caseSensitive) {

    public static EvalAssertion fromMap(Map<String, Object> data) {
        return new EvalAssertion(
                String.valueOf(data.get("type")),
                data.get("value"),
                Boolean.TRUE.equals(data.get("caseSensitive")));
    }

    public EvalAssertionResult check(String output) {
        String expected = String.valueOf(value);
        boolean passed;
        switch (type) {
            case "regex" -> passed = Pattern.compile(expected).matcher(output).find();
            case "equals" -> passed = cmp(output, expected, caseSensitive);
            case "not_contains" -> passed = !haystack(output, caseSensitive).contains(needle(expected, caseSensitive));
            case "contains" -> passed = haystack(output, caseSensitive).contains(needle(expected, caseSensitive));
            default -> throw new IllegalArgumentException("unsupported eval assertion type: " + type);
        }
        return new EvalAssertionResult(type, passed, value, passed ? "passed" : type + " assertion failed");
    }

    private static boolean cmp(String left, String right, boolean caseSensitive) {
        return caseSensitive ? left.equals(right) : left.equalsIgnoreCase(right);
    }

    private static String haystack(String value, boolean caseSensitive) {
        return caseSensitive ? value : value.toLowerCase(Locale.ROOT);
    }

    private static String needle(String value, boolean caseSensitive) {
        return caseSensitive ? value : value.toLowerCase(Locale.ROOT);
    }
}
