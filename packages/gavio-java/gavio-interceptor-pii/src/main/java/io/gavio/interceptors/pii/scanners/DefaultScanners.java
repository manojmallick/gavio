package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.policy.PolicyPacks;
import java.util.List;

/** The default scanner set wired into PiiGuard when none is supplied. */
public final class DefaultScanners {

    private DefaultScanners() {
    }

    public static List<PiiScanner> defaults() {
        return PolicyPacks.core().scanners();
    }

    /**
     * FinTech domain policy pack — SWIFT/BIC and US ABA routing numbers. Compose
     * with {@link #defaults()}. (IBAN is already in the default set.)
     */
    public static List<PiiScanner> fintech() {
        return PolicyPacks.fintech().scanners();
    }
}
