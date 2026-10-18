package io.gavio.platform;

import java.nio.file.Path;
import java.util.Map;

/**
 * SPI that lets {@code gavio-core} read Policy Pack evidence for a workflow release without
 * depending on {@code gavio-interceptor-pii}.
 *
 * <p>The PII module registers an implementation via {@code META-INF/services}. If none is on the
 * classpath, a workflow manifest that lists policy packs fails with a clear error.
 */
public interface PlatformWorkflowPolicyResolver {

    /** Load a pack from a directory containing {@code manifest.json}, or from a manifest file. */
    PolicyEvidence loadPath(Path path);

    /** Load a pack from the shipped policy-pack catalog by name, e.g. {@code finance}. */
    PolicyEvidence loadCatalog(String name);

    /**
     * What a workflow release records about one pack. {@code manifest} is the pack's normalized
     * manifest ({@code PolicyPack.manifest()}), whose canonical digest becomes {@code manifestDigest}.
     */
    record PolicyEvidence(
            String id,
            String name,
            String version,
            String domain,
            boolean signatureValid,
            Map<String, Object> manifest,
            int detectorCount) {
    }
}
