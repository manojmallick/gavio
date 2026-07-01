package io.gavio;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Base class for every error raised by Gavio. All Gavio errors derive from this
 * so callers can catch the whole family with one {@code catch}.
 */
public class GavioException extends RuntimeException {

    public GavioException(String message) {
        super(message);
    }

    public GavioException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Raised when the gateway is misconfigured (e.g. no provider set). */
    public static class ConfigurationException extends GavioException {
        public ConfigurationException(String message) {
            super(message);
        }
    }

    /** Base class for provider-adapter failures. */
    public static class ProviderException extends GavioException {
        public ProviderException(String message) {
            super(message);
        }

        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The provider could not be reached (network / health-check failure). */
    public static class ProviderUnavailableException extends ProviderException {
        public ProviderUnavailableException(String message) {
            super(message);
        }

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The provider returned a rate-limit (HTTP 429) signal. */
    public static class RateLimitException extends ProviderException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    /** The provider returned a 5xx server error. */
    public static class ServerException extends ProviderException {
        public ServerException(String message) {
            super(message);
        }
    }

    /** A request exceeded its configured timeout. */
    public static class TimeoutException extends ProviderException {
        public TimeoutException(String message) {
            super(message);
        }

        public TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** PiiGuard is in BLOCK mode and detected PII in the request. */
    public static class PiiBlockedException extends GavioException {
        private final List<String> entityTypes;

        public PiiBlockedException(List<String> entityTypes) {
            super("Request blocked: PII detected (" + String.join(", ", new TreeSet<>(entityTypes)) + ")");
            this.entityTypes = new ArrayList<>(entityTypes);
        }

        public List<String> entityTypes() {
            return List.copyOf(entityTypes);
        }
    }

    /** A hard budget cap was exceeded. Never swallow this — surface to user. */
    public static class BudgetExceededException extends GavioException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    /** The circuit breaker is open; the call was rejected without hitting the provider. */
    public static class CircuitOpenException extends ProviderUnavailableException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }

    /** A local rate limit (requests/tokens per minute) was exceeded. */
    public static class RateLimitExceededException extends GavioException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    /** The caller's role is not permitted to use the requested model (RBAC). */
    public static class ModelNotAllowedException extends GavioException {
        public ModelNotAllowedException(String role, String model) {
            super("role '" + role + "' may not use model '" + model + "'");
        }
    }

    /** Output failed a guardrail validator with onFailure=ERROR. */
    public static class GuardrailViolationException extends GavioException {
        public GuardrailViolationException(String message) {
            super(message);
        }
    }

    /** A prompt-injection attempt was detected and the guard is in block mode. */
    public static class PromptInjectionException extends GavioException {
        public PromptInjectionException(String message) {
            super(message);
        }
    }
}
