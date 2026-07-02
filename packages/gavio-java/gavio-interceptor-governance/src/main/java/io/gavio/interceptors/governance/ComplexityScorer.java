package io.gavio.interceptors.governance;

/** Scores prompt text in {@code [0, 1]} — higher means more complex (F-GOV-06). */
@FunctionalInterface
public interface ComplexityScorer {
    double score(String text);
}
