package io.gavio.testing;

import static io.gavio.testing.GavioAssertions.assertAuditEntityType;
import static io.gavio.testing.GavioAssertions.assertNotContains;
import static io.gavio.testing.GavioAssertions.assertPiiDetected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.GavioRequest;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.scanners.IbanScanner;
import io.gavio.types.Sensitivity;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import org.junit.jupiter.api.Test;

class GavioTestKitTest {

    @Test
    void detectsPiiAndRedactedRequest() {
        GavioTestKit kit = GavioTestKit.builder()
                .interceptor(new PiiGuard())
                .provider(MockProvider.withResponse("done [EMAIL_1]"))
                .build();
        GavioTestResult result = kit.run(java.util.List.of(Message.of("user", "to jan@example.com"))).join();
        assertTrue(result.piiDetected("EMAIL"));
        assertNotContains(result.redactedRequest().messages().get(0).content(), "jan@example.com");
        // restore-on-response puts it back in the final content
        assertEquals("done jan@example.com", result.response().content());
    }

    @Test
    void shouldRedactIbanWithAuditEntityType() {
        // Audit registered first so its `after` runs last and sees the final PII types.
        GavioTestKit kit = GavioTestKit.builder()
                .interceptor(AuditInterceptor.builder().build())
                .interceptor(PiiGuard.builder()
                        .scanners(new IbanScanner())
                        .sensitivity(Sensitivity.STRICT)
                        .build())
                .provider(MockProvider.withResponse("I processed [IBAN_1]"))
                .build();

        GavioTestResult result = kit.run(GavioRequest.builder()
                .message("user", "Transfer from NL91ABNA0417164300")
                .model("mock")
                .build()).join();

        assertPiiDetected(result, "IBAN");
        assertNotContains(result.preRequestText(), "NL91ABNA0417164300");
        assertAuditEntityType(result, "IBAN");
    }
}
