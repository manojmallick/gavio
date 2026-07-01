package io.gavio.interceptors.guardrails;

/** Result of an {@link OutputValidator}. */
public record ValidationResult(boolean ok, String reason) {
    public static ValidationResult passed() {
        return new ValidationResult(true, "");
    }

    public static ValidationResult failed(String reason) {
        return new ValidationResult(false, reason);
    }
}
