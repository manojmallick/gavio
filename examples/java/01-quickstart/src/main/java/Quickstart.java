import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;

/**
 * Gavio quickstart — PII redaction in dev mode.
 *
 * <p>Runs with zero setup: no API key, no network. Dev mode wires a mock
 * provider and a stdout audit sink automatically.
 *
 * <pre>{@code mvn -q compile exec:java}</pre>
 */
public class Quickstart {

    public static void main(String[] args) {
        Gateway gw = Gateway.builder().devMode(true).use(new PiiGuard()).build();

        GavioResponse r = gw.complete(GavioRequest.builder()
                .message("user", "Email jan@example.com about IBAN NL91ABNA0417164300")
                .agentId("quickstart")
                .build()).join();

        // GavioResponse.audit() is typed Object (core avoids an audit-module
        // dependency); cast it to read the structured record.
        AuditRecord audit = (AuditRecord) r.audit();

        System.out.println();
        System.out.println("Reply    : " + r.content());              // PII restored
        System.out.println("PII found: " + audit.piiEntityTypes());
        System.out.println("Fired    : " + r.interceptorsFired());
        System.out.printf("Cost     : $%.6f   (mock = free)%n", r.costUsd());
        System.out.println("Trace    : " + r.traceId());
    }
}
