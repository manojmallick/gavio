package io.gavio.interceptors.pii;

/**
 * One detected PII entity within a span of text.
 *
 * <p>{@code start}/{@code end} are half-open character offsets. {@code replacement}
 * is the placeholder used in REDACT mode; {@code value} is the original text
 * (never logged — used only for restore).
 */
public record PiiMatch(
        String entityType,
        int start,
        int end,
        String value,
        double confidence,
        String replacement) {

    public PiiMatch {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException(
                    "Invalid PiiMatch span: start=" + start + ", end=" + end);
        }
    }

    public int length() {
        return end - start;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder mirroring the SDK plan. */
    public static final class Builder {
        private String entityType;
        private int start;
        private int end;
        private String value;
        private double confidence = 1.0;
        private String replacement;

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder start(int start) {
            this.start = start;
            return this;
        }

        public Builder end(int end) {
            this.end = end;
            return this;
        }

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder replacement(String replacement) {
            this.replacement = replacement;
            return this;
        }

        public PiiMatch build() {
            return new PiiMatch(entityType, start, end, value, confidence, replacement);
        }
    }
}
