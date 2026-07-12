package io.gavio.controlplane;

/** Raised when control-plane config cannot be loaded in fail-closed mode. */
public final class ControlPlaneException extends RuntimeException {
    public ControlPlaneException(String message) {
        super(message);
    }

    public ControlPlaneException(String message, Throwable cause) {
        super(message, cause);
    }
}
