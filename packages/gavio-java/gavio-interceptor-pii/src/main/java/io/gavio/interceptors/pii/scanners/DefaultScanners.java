package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiScanner;
import java.util.List;

/** The default scanner set wired into PiiGuard when none is supplied. */
public final class DefaultScanners {

    private DefaultScanners() {
    }

    public static List<PiiScanner> defaults() {
        return List.of(
                new SecretScanner(),
                new EmailScanner(),
                new IbanScanner(),
                new BsnScanner(),
                new CreditCardScanner(),
                new SsnScanner(),
                new PhoneScanner(),
                new IpAddressScanner());
    }

    /**
     * FinTech domain policy pack — SWIFT/BIC and US ABA routing numbers. Compose
     * with {@link #defaults()}. (IBAN is already in the default set.)
     */
    public static List<PiiScanner> fintech() {
        return List.of(new SwiftBicScanner(), new RoutingNumberScanner());
    }
}
