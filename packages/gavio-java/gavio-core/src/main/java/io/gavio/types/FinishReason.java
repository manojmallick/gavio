package io.gavio.types;

/**
 * Provider-agnostic stop reason (F-ADP-03), mapped from each provider's native value.
 *
 * <p>The wire spellings are the {@code GavioResponse.finishReason} enum in
 * {@code spec/GavioResponse.schema.json}.
 */
public enum FinishReason {
    STOP("stop"),
    LENGTH("length"),
    TOOL_CALLS("tool_calls"),
    CONTENT_FILTER("content_filter"),
    OTHER("other");

    private final String value;

    FinishReason(String value) {
        this.value = value;
    }

    /** The spec spelling, e.g. {@code "tool_calls"}. */
    public String value() {
        return value;
    }

    /** The reason with the given spec spelling, or {@code null} when {@code value} is null. */
    public static FinishReason fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (FinishReason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown finish reason: " + value);
    }
}
