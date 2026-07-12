import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.policy.PolicyAction;
import io.gavio.interceptors.pii.policy.PolicyPack;
import io.gavio.interceptors.pii.policy.PolicyPacks;
import io.gavio.interceptors.pii.policy.RedactionStrategy;
import io.gavio.interceptors.pii.policy.RegexPolicyRule;
import java.util.List;
import java.util.TreeSet;

/**
 * Gavio Policy Packs - core PII, FinTech identifiers, and custom regex rules.
 *
 * <pre>{@code mvn -q compile exec:java}</pre>
 */
public class PolicyPacksDemo {

    public static void main(String[] args) {
        PolicyPack core = PolicyPacks.core();
        PolicyPack fintech = PolicyPacks.fintech();
        PolicyPack internal = PolicyPacks.custom(
                "acme.internal",
                "Acme Internal IDs",
                "1.0.0",
                "internal",
                List.of(new RegexPolicyRule(
                        "employee_id",
                        "EMPLOYEE_ID",
                        "\\bEMP-[0-9]{6}\\b",
                        0.92,
                        "EMPLOYEE_ID",
                        PolicyAction.FLAG,
                        RedactionStrategy.HASH,
                        "INTERNAL_IDENTIFIER")),
                PolicyAction.FLAG,
                RedactionStrategy.HASH,
                List.of("INTERNAL_IDENTIFIER"),
                "Acme internal employee identifiers.");

        System.out.println("packs: " + core.id() + " " + fintech.id() + " " + internal.id());
        System.out.println("fintech detectors: " + fintech.detectors().stream()
                .map(detector -> detector.entityType())
                .toList());

        Gateway gw = Gateway.builder()
                .devMode(true)
                .use(PiiGuard.builder().scanners(PolicyPacks.scanners(core, fintech, internal)).build())
                .build();

        GavioResponse r = gw.complete(GavioRequest.builder()
                .message("user", "Wire SWIFT DEUTDEFF500 routing 111000025 for EMP-123456 and email jan@example.com.")
                .build()).join();
        AuditRecord audit = (AuditRecord) r.audit();

        System.out.println("reply    : " + r.content());
        System.out.println("PII found: " + new TreeSet<>(audit.piiEntityTypes()));
        System.out.println("fired    : " + r.interceptorsFired());
    }
}
