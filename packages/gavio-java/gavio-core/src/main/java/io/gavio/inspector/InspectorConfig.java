package io.gavio.inspector;

import io.gavio.GavioException.ConfigurationException;

/**
 * Inspector configuration (F-DX-09/F-DX-10). Immutable; build via {@link #builder()}.
 *
 * <p>The inspector is OFF by default and dev mode does not auto-enable it —
 * enable explicitly via {@code GavioBuilder.inspect(...)} or {@code GAVIO_INSPECT=1}.
 */
public final class InspectorConfig {

    private final boolean enabled;
    private final CaptureMode mode; // null = auto: FULL in dev mode, else METADATA
    private final int port;
    private final String bind;
    private final String authToken;
    private final int maxTraces;
    private final boolean unsafeContentCaptureAck;
    private final boolean startServer;

    private InspectorConfig(Builder b) {
        this.enabled = b.enabled;
        this.mode = b.mode;
        this.port = b.port;
        this.bind = b.bind;
        this.authToken = b.authToken;
        this.maxTraces = b.maxTraces;
        this.unsafeContentCaptureAck = b.unsafeContentCaptureAck;
        this.startServer = b.startServer;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Configured mode, or null when left to the dev-mode default. */
    public CaptureMode mode() {
        return mode;
    }

    public int port() {
        return port;
    }

    public String bind() {
        return bind;
    }

    public String authToken() {
        return authToken;
    }

    public int maxTraces() {
        return maxTraces;
    }

    public boolean unsafeContentCaptureAck() {
        return unsafeContentCaptureAck;
    }

    public boolean startServer() {
        return startServer;
    }

    /** Resolve the capture mode: explicit setting, else FULL in dev mode, else METADATA. */
    public CaptureMode effectiveMode(boolean devMode) {
        if (mode != null) {
            return mode;
        }
        return devMode ? CaptureMode.FULL : CaptureMode.METADATA;
    }

    /**
     * Validate at gateway build time. Full content capture outside dev mode
     * requires an explicit acknowledgement; a non-loopback bind requires auth.
     */
    public void validate(boolean devMode) {
        if (!enabled) {
            return;
        }
        if (effectiveMode(devMode) == CaptureMode.FULL && !devMode && !unsafeContentCaptureAck) {
            throw new ConfigurationException(
                    "Inspector FULL capture mode outside dev mode holds raw prompts/responses in memory "
                            + "and serves them over HTTP. Acknowledge with "
                            + ".unsafeContentCaptureAck(true), or use REDACTED/METADATA mode.");
        }
        if (startServer && !isLoopback(bind) && (authToken == null || authToken.isEmpty())) {
            throw new ConfigurationException(
                    "Inspector bind '" + bind + "' is not loopback; set .authToken(...) before exposing "
                            + "the inspector beyond localhost.");
        }
    }

    private static boolean isLoopback(String bind) {
        return bind != null
                && (bind.equals("localhost") || bind.equals("::1") || bind.startsWith("127."));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: defaults with {@code enabled(true)}. */
    public static InspectorConfig defaults() {
        return builder().enabled(true).build();
    }

    /** Fluent builder for {@link InspectorConfig}. */
    public static final class Builder {
        private boolean enabled = false;
        private CaptureMode mode;
        private int port = 7411;
        private String bind = "127.0.0.1";
        private String authToken;
        private int maxTraces = 1000;
        private boolean unsafeContentCaptureAck = false;
        private boolean startServer = true;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder mode(CaptureMode mode) {
            this.mode = mode;
            return this;
        }

        /** Port for the inspector HTTP server; 0 picks an ephemeral port. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder bind(String bind) {
            this.bind = bind;
            return this;
        }

        /** When set, every HTTP request must carry {@code Authorization: Bearer <token>}. */
        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder maxTraces(int maxTraces) {
            this.maxTraces = maxTraces;
            return this;
        }

        /** Acknowledge FULL content capture outside dev mode. */
        public Builder unsafeContentCaptureAck(boolean ack) {
            this.unsafeContentCaptureAck = ack;
            return this;
        }

        public Builder startServer(boolean startServer) {
            this.startServer = startServer;
            return this;
        }

        public InspectorConfig build() {
            return new InspectorConfig(this);
        }
    }
}
