import io.gavio.interceptors.audit.AuditChain;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.trust.ProductionTrust;
import io.gavio.trust.ProductionTrustVerification;
import java.util.List;
import java.util.Map;

public class ProductionTrustDemo {
  public static void main(String[] args) {
    AuditRecord first = AuditRecord.builder()
        .traceId("trace-a")
        .provider("mock")
        .model("mock")
        .timestampUtc("2026-07-12T12:00:00Z")
        .promptHash(AuditRecord.hashText("support question"))
        .responseHash(AuditRecord.hashText("support answer"))
        .build();
    AuditRecord second = AuditRecord.builder()
        .traceId("trace-b")
        .provider("mock")
        .model("mock")
        .timestampUtc("2026-07-12T12:00:01Z")
        .previousHash(first.contentHash())
        .promptHash(AuditRecord.hashText("handoff question"))
        .responseHash(AuditRecord.hashText("handoff answer"))
        .build();

    boolean chainOk = AuditChain.verifyChain(List.of(first, second));
    Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
        .generatedAt("2026-07-12T12:00:00Z")
        .release("1.8.0", "v1.8.0", "b1ff1be")
        .runtime("production", "project:prod-support", true, "metadata_only")
        .auditChain(2, chainOk, first.contentHash(), second.contentHash())
        .runtimeEvents(3, true, List.of("trace.start", "provider.call.end", "trace.end"))
        .addControl("policy_pack", "support", "pass", "test-vectors/policy-packs/catalog.json")
        .addControl("benchmark", "inspector-overhead", "pass", "docs/gavio-1x-gap-closure-roadmap.md")
        .addDocument("Threat model", "docs/trust-package.md#threat-model", "")
        .build();
    ProductionTrustVerification result = ProductionTrust.verify(bundle);

    System.out.println("bundle: " + bundle.get("bundleId"));
    System.out.println("hash  : " + bundle.get("bundleHash"));
    System.out.println("valid : " + result.valid());
    @SuppressWarnings("unchecked")
    Map<String, Object> evidence = (Map<String, Object>) bundle.get("evidence");
    @SuppressWarnings("unchecked")
    Map<String, Object> runtimeEvents = (Map<String, Object>) evidence.get("runtimeEvents");
    System.out.println("events: " + runtimeEvents.get("eventTypes"));
  }
}
