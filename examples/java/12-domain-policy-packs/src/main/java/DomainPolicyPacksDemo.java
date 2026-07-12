import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.policy.PolicyPack;
import io.gavio.interceptors.pii.policy.PolicyPacks;
import java.util.Map;
import java.util.TreeSet;

/** Gavio Domain Policy Pack Catalog. */
public class DomainPolicyPacksDemo {

    public static void main(String[] args) {
        PolicyPack healthcare = PolicyPacks.load("healthcare");
        PolicyPack india = PolicyPacks.load("regional/india");
        PolicyPack hr = PolicyPacks.load("hr").withOverrides(Map.of(
                "detectors", Map.of(
                        "employee_id", Map.of(
                                "action", "flag",
                                "severity", "critical",
                                "redactionStrategy", "hash"))));

        System.out.println("catalog : " + String.join(", ", PolicyPacks.listCatalog()));
        System.out.println("signed  : " + healthcare.id() + " " + healthcare.verifySignature());
        System.out.println("override: " + hr.manifest().get("detectors"));

        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(PiiGuard.fromPolicyPack(healthcare, india, hr))
                .build();

        GavioResponse r = gw.complete(GavioRequest.builder()
                .message(
                        "user",
                        "Patient MRN-123456 has member MEM-AB12CD34. "
                                + "PAN ABCDE1234F and Aadhaar 1234 5678 9012 are present. "
                                + "Template EMP-000000 is allowed, but EMP-123456 is real.")
                .build()).join();
        AuditRecord audit = (AuditRecord) r.audit();

        System.out.println("reply    : " + r.content());
        System.out.println("PII found: " + new TreeSet<>(audit.piiEntityTypes()));
    }
}
