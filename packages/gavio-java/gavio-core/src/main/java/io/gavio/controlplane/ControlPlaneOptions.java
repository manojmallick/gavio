package io.gavio.controlplane;

import java.nio.file.Path;

/** Options for loading runtime config from a self-hosted Gavio control plane. */
public final class ControlPlaneOptions {

    private final String url;
    private final String runtimeKey;
    private final String policySource;
    private final Path cachePath;
    private final String failMode;
    private final int timeoutMillis;

    private ControlPlaneOptions(Builder b) {
        this.url = b.url;
        this.runtimeKey = b.runtimeKey;
        this.policySource = b.policySource;
        this.cachePath = b.cachePath;
        this.failMode = b.failMode;
        this.timeoutMillis = b.timeoutMillis;
    }

    public String url() {
        return url;
    }

    public String runtimeKey() {
        return runtimeKey;
    }

    public String policySource() {
        return policySource;
    }

    public Path cachePath() {
        return cachePath;
    }

    public String failMode() {
        return failMode;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public static Builder builder(String url, String runtimeKey, String policySource) {
        return new Builder(url, runtimeKey, policySource);
    }

    public static final class Builder {
        private final String url;
        private final String runtimeKey;
        private final String policySource;
        private Path cachePath;
        private String failMode = "open";
        private int timeoutMillis = 2000;

        private Builder(String url, String runtimeKey, String policySource) {
            this.url = url;
            this.runtimeKey = runtimeKey;
            this.policySource = policySource;
        }

        public Builder cachePath(Path cachePath) {
            this.cachePath = cachePath;
            return this;
        }

        public Builder failMode(String failMode) {
            if (!"open".equals(failMode) && !"closed".equals(failMode)) {
                throw new IllegalArgumentException("failMode must be open or closed");
            }
            this.failMode = failMode;
            return this;
        }

        public Builder timeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public ControlPlaneOptions build() {
            return new ControlPlaneOptions(this);
        }
    }
}
