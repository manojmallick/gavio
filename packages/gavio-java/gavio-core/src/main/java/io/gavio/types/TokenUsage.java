package io.gavio.types;

/** Token accounting for a single completion. Immutable. */
public record TokenUsage(int promptTokens, int completionTokens) {

    public TokenUsage() {
        this(0, 0);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
