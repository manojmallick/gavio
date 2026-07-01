package io.gavio.interceptors.guardrails;

/** Validates a response's content string (F-QUA-01, F-QUA-02). */
public interface OutputValidator {
    String name();

    ValidationResult validate(String content);
}
