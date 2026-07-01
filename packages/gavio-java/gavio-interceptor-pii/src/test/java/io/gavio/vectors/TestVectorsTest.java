package io.gavio.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gavio.GavioRequest;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import io.gavio.interceptors.pii.scanners.BsnScanner;
import io.gavio.interceptors.pii.scanners.CreditCardScanner;
import io.gavio.interceptors.pii.scanners.EmailScanner;
import io.gavio.interceptors.pii.scanners.IbanScanner;
import io.gavio.interceptors.pii.scanners.IpAddressScanner;
import io.gavio.interceptors.pii.scanners.PhoneScanner;
import io.gavio.interceptors.pii.scanners.SecretScanner;
import io.gavio.interceptors.pii.scanners.SsnScanner;
import io.gavio.json.Json;
import io.gavio.types.Provider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared cross-SDK test vectors from //test-vectors against the Java SDK.
 * Same JSON files the Python and JavaScript SDKs run — parity is enforced.
 */
class TestVectorsTest {

    private static final Map<String, Supplier<PiiScanner>> SCANNERS = Map.of(
            "EMAIL", EmailScanner::new,
            "IBAN", IbanScanner::new,
            "BSN", BsnScanner::new,
            "CREDIT_CARD", CreditCardScanner::new,
            "PHONE", PhoneScanner::new,
            "IP_ADDRESS", IpAddressScanner::new,
            "SSN", SsnScanner::new,
            "SECRET", SecretScanner::new);

    /** Walk up from the working dir to find the repo's test-vectors directory. */
    private static Path vectorsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-vectors/pii");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate test-vectors/pii from working dir");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String file) throws IOException {
        String text = Files.readString(vectorsDir().resolve(file));
        Object casesObj = Json.parseObject(text).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : (List<Object>) casesObj) {
            out.add((Map<String, Object>) c);
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> checksumVectors() throws IOException {
        return cases("checksums.json").stream().map(c -> {
            String id = (String) c.get("id");
            String scannerName = (String) c.get("scanner");
            String textIn = (String) c.get("text");
            boolean shouldMatch = (Boolean) c.get("shouldMatch");
            return DynamicTest.dynamicTest("checksum:" + id, () -> {
                PiiScanner scanner = SCANNERS.get(scannerName).get();
                boolean matched = !scanner.scan(textIn, new ScanContext()).isEmpty();
                assertEquals(shouldMatch, matched,
                        id + ": " + scannerName + " on \"" + textIn + "\"");
            });
        });
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> detectionVectors() throws IOException {
        return cases("detection.json").stream().map(c -> {
            String id = (String) c.get("id");
            String textIn = (String) c.get("text");
            List<String> expected = ((List<Object>) c.get("expectedTypes")).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            return DynamicTest.dynamicTest("detection:" + id, () -> {
                PiiGuard guard = new PiiGuard();
                InterceptorContext ctx = new InterceptorContext("t");
                GavioRequest req = GavioRequest.builder()
                        .message("user", textIn)
                        .model("mock")
                        .provider(Provider.MOCK)
                        .build();
                guard.before(req, ctx).join();
                List<String> detected = new ArrayList<>(new TreeSet<>(ctx.piiEntityTypes()));
                assertEquals(expected, detected, id);
            });
        });
    }
}
