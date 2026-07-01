package io.gavio.interceptors.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.interceptors.pii.scanners.BsnScanner;
import io.gavio.interceptors.pii.scanners.CreditCardScanner;
import io.gavio.interceptors.pii.scanners.EmailScanner;
import io.gavio.interceptors.pii.scanners.IbanScanner;
import io.gavio.interceptors.pii.scanners.IpAddressScanner;
import io.gavio.interceptors.pii.scanners.PhoneScanner;
import io.gavio.interceptors.pii.scanners.SecretScanner;
import io.gavio.interceptors.pii.scanners.SsnScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class PiiScannersTest {

    private static List<PiiMatch> scan(PiiScanner scanner, String text) {
        return scanner.scan(text, new ScanContext());
    }

    @Test
    void emailDetected() {
        List<PiiMatch> matches = scan(new EmailScanner(), "ping jan.devries@example.com please");
        assertEquals(1, matches.size());
        assertEquals("EMAIL", matches.get(0).entityType());
        assertEquals("jan.devries@example.com", matches.get(0).value());
        assertEquals("[EMAIL_1]", matches.get(0).replacement());
    }

    @Test
    void ibanValidChecksumDetected() {
        List<PiiMatch> matches = scan(new IbanScanner(), "Transfer to NL91ABNA0417164300 today");
        assertEquals(1, matches.size());
        assertEquals("IBAN", matches.get(0).entityType());
    }

    @Test
    void ibanInvalidChecksumIgnored() {
        List<PiiMatch> matches = scan(new IbanScanner(), "Transfer to NL00ABNA0417164300 today");
        assertTrue(matches.isEmpty());
    }

    @Test
    void bsnElevenProef() {
        assertFalse(scan(new BsnScanner(), "bsn 111222333").isEmpty()); // valid
        assertTrue(scan(new BsnScanner(), "bsn 111222334").isEmpty()); // invalid
    }

    @Test
    void creditCardLuhn() {
        assertFalse(scan(new CreditCardScanner(), "card 4111111111111111").isEmpty()); // valid
        assertTrue(scan(new CreditCardScanner(), "card 4111111111111112").isEmpty()); // invalid
    }

    @Test
    void phoneDetected() {
        List<PiiMatch> matches = scan(new PhoneScanner(), "call +31 6 12345678 tomorrow");
        assertFalse(matches.isEmpty());
        assertEquals("PHONE", matches.get(0).entityType());
    }

    @Test
    void phoneIgnoresShortNumbers() {
        assertTrue(scan(new PhoneScanner(), "in the year 2026").isEmpty());
    }

    @Test
    void ipv4AndIpv6() {
        assertFalse(scan(new IpAddressScanner(), "host 192.168.1.42").isEmpty());
        assertFalse(scan(new IpAddressScanner(), "host 2001:db8::1").isEmpty());
        assertTrue(scan(new IpAddressScanner(), "host 999.999.999.999").isEmpty());
    }

    @Test
    void ssnDetected() {
        List<PiiMatch> matches = scan(new SsnScanner(), "ssn 123-45-6789");
        assertFalse(matches.isEmpty());
        assertEquals("SSN", matches.get(0).entityType());
    }

    @Test
    void secretScannerKeysAndJwt() {
        String text = "key sk-ant-abcdef0123456789ABCDEF0123 and "
                + "token eyJhbGc.eyJzdWIi.SflKxwRJ and AKIAIOSFODNN7EXAMPLE";
        List<PiiMatch> matches = scan(new SecretScanner(), text);
        assertTrue(matches.size() >= 3);
        assertTrue(matches.stream().allMatch(m -> m.entityType().equals("SECRET")));
    }
}
