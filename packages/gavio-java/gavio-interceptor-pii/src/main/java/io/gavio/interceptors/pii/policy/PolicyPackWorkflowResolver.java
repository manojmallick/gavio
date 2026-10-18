package io.gavio.interceptors.pii.policy;

import io.gavio.platform.PlatformWorkflowPolicyResolver;
import java.nio.file.Path;

/**
 * Supplies Policy Pack evidence to {@code io.gavio.platform.PlatformWorkflow}. Registered through
 * {@code META-INF/services} so {@code gavio-core} can read packs without depending on this module.
 */
public final class PolicyPackWorkflowResolver implements PlatformWorkflowPolicyResolver {

    @Override
    public PolicyEvidence loadPath(Path path) {
        return evidence(PolicyPacks.loadPath(path));
    }

    @Override
    public PolicyEvidence loadCatalog(String name) {
        return evidence(PolicyPacks.load(name));
    }

    private static PolicyEvidence evidence(PolicyPack pack) {
        return new PolicyEvidence(
                pack.id(),
                pack.name(),
                pack.version(),
                pack.domain(),
                pack.verifySignature(),
                pack.manifest(),
                pack.detectors().size());
    }
}
