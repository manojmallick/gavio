import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import io.gavio.interceptors.pii.scanners.EmailScanner;
import io.gavio.providers.MockProvider;
import io.gavio.testing.GavioTestKit;
import io.gavio.testing.GavioTestResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gavio custom PII scanner — detect a domain-specific identifier (an ING
 * account) by implementing PiiScanner, then unit-test it with GavioTestKit.
 *
 * <pre>{@code mvn -q compile exec:java}</pre>
 */
public class CustomScanner {

    /** Detects ING account numbers of the form NL##INGB##########. */
    static final class IngAccountScanner implements PiiScanner {
        private static final Pattern PATTERN = Pattern.compile("\\bNL\\d{2}INGB\\d{10}\\b");

        @Override
        public String entityType() {
            return "ING_ACCOUNT";
        }

        @Override
        public List<PiiMatch> scan(String text, ScanContext ctx) {
            List<PiiMatch> out = new ArrayList<>();
            Matcher m = PATTERN.matcher(text);
            while (m.find()) {
                out.add(PiiMatch.builder()
                        .entityType(entityType())
                        .start(m.start())
                        .end(m.end())
                        .value(m.group())
                        .confidence(1.0)
                        .replacement("[ING_ACCOUNT_" + ctx.nextIndex(entityType()) + "]")
                        .build());
            }
            return out;
        }
    }

    public static void main(String[] args) {
        // 1) Compose the custom scanner with a built-in one.
        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(PiiGuard.builder()
                        .scanners(new EmailScanner(), new IngAccountScanner())
                        .build())
                .build();

        GavioResponse r = gw.complete(GavioRequest.builder()
                .message("user", "email jan@example.com, pay ING NL20INGB0001234567")
                .build()).join();

        AuditRecord audit = (AuditRecord) r.audit();
        System.out.println("Reply    : " + r.content());
        System.out.println("PII found: " + audit.piiEntityTypes());   // [EMAIL, ING_ACCOUNT]

        // 2) Test the scanner in isolation with GavioTestKit — no network.
        GavioTestKit kit = GavioTestKit.builder()
                .interceptor(PiiGuard.builder().scanners(new IngAccountScanner()).build())
                .provider(MockProvider.withResponse("processed [ING_ACCOUNT_1]"))
                .build();

        GavioTestResult result = kit.run(GavioRequest.builder()
                .message("user", "account NL20INGB0001234567 on file")
                .build()).join();

        if (result.preRequestText().contains("NL20INGB0001234567")) {
            throw new AssertionError("expected the account to be redacted before the provider");
        }
        System.out.println();
        System.out.println("✓ custom scanner test passed → " + result.response().content());
    }
}
