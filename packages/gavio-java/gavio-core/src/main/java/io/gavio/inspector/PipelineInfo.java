package io.gavio.inspector;

import java.util.List;

/**
 * Static description of the gateway's pipeline, served by the inspector's
 * {@code /api/pipeline} endpoint (F-DX-10). Captured once at build time.
 */
public record PipelineInfo(
        String provider,
        String model,
        boolean devMode,
        boolean dryRun,
        List<String> interceptors) {

    public PipelineInfo {
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
    }
}
