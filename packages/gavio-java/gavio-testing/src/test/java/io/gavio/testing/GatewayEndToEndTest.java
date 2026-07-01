package io.gavio.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.Gateway;
import io.gavio.GavioException.ConfigurationException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.reliability.RetryInterceptor;
import io.gavio.interceptors.reliability.TimeoutPolicy;
import io.gavio.providers.MockProvider;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayEndToEndTest {

    @Test
    void devModeRoundtripWithPiiRestore() {
        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(new PiiGuard())
                .build();
        GavioResponse resp = gw.complete(
                GavioRequest.builder()
                        .message("user", "mail jan@example.com please")
                        .model("mock")
                        .provider(Provider.MOCK)
                        .agentId("demo")
                        .build()).join();

        // MockProvider echoes the (redacted) prompt; PII restore puts the email back.
        assertTrue(resp.content().contains("jan@example.com"));
        AuditRecord audit = (AuditRecord) resp.audit();
        assertNotNull(audit, "dev mode should auto-wire an audit interceptor via SPI");
        assertTrue(audit.piiEntityTypes().contains("EMAIL"));
        assertEquals("demo", audit.agentId());
        assertEquals("mock", resp.provider());
        assertEquals(resp.traceId(), audit.traceId());
    }

    @Test
    void auditRecordHasHashesNotContent() {
        Gateway gw = Gateway.builder().devMode(true).build();
        GavioResponse resp = gw.complete(List.of(Message.of("user", "secret stuff"))).join();
        AuditRecord record = (AuditRecord) resp.audit();
        assertNotNull(record);
        assertEquals(64, record.promptHash().length());
        assertEquals(64, record.responseHash().length());
        assertEquals("1.0", record.schemaVersion());
    }

    @Test
    void interceptorsFiredRecorded() {
        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(new PiiGuard())
                .use(TimeoutPolicy.builder().timeoutSeconds(5).build())
                .use(RetryInterceptor.builder().maxAttempts(2).baseDelayMs(1).build())
                .build();
        GavioResponse resp = gw.complete(List.of(Message.of("user", "hi"))).join();
        List<String> fired = resp.interceptorsFired();
        assertTrue(fired.contains("pii_guard"));
        assertTrue(fired.contains("audit"));
        assertTrue(fired.contains("timeout"));
        assertTrue(fired.contains("retry"));
    }

    @Test
    void builderRequiresProviderWithoutDevMode() {
        assertThrows(ConfigurationException.class, () -> Gateway.builder().build());
    }

    @Test
    void explicitAdapterAndModel() {
        Gateway gw = Gateway.builder()
                .adapter(MockProvider.withResponse("fixed"))
                .model("mock")
                .build();
        GavioResponse resp = gw.complete(List.of(Message.of("user", "anything"))).join();
        assertEquals("fixed", resp.content());
    }

    @Test
    void dryRunDoesNotRedact() {
        Gateway gw = Gateway.builder()
                .devMode(true)
                .dryRun(true)
                .use(new PiiGuard())
                .build();
        GavioResponse resp = gw.complete(
                List.of(Message.of("user", "mail jan@example.com"))).join();
        // In dry-run the request is never modified, so the echo keeps the raw email.
        assertTrue(resp.content().contains("jan@example.com"));
    }
}
