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
}
