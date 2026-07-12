package io.gavio.interceptors.pii.policy;

import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.scanners.BsnScanner;
import io.gavio.interceptors.pii.scanners.CreditCardScanner;
import io.gavio.interceptors.pii.scanners.EmailScanner;
import io.gavio.interceptors.pii.scanners.IbanScanner;
import io.gavio.interceptors.pii.scanners.IpAddressScanner;
import io.gavio.interceptors.pii.scanners.PhoneScanner;
import io.gavio.interceptors.pii.scanners.RoutingNumberScanner;
import io.gavio.interceptors.pii.scanners.SecretScanner;
import io.gavio.interceptors.pii.scanners.SsnScanner;
import io.gavio.interceptors.pii.scanners.SwiftBicScanner;
import java.util.ArrayList;
import java.util.List;

/** Built-in and custom policy-pack factories. */
public final class PolicyPacks {

    private static final String VERSION = "0.12.0";

    private PolicyPacks() {
    }

    public static PolicyPack core() {
        return new PolicyPack(
                "gavio.core-pii",
                "Core PII",
                VERSION,
                "core",
                "Built-in deterministic PII scanners.",
                List.of(
                        PolicyDetector.scanner("secret", "SECRET", "PII"),
                        PolicyDetector.scanner("email", "EMAIL", "PII"),
                        PolicyDetector.scanner("iban", "IBAN", "PII"),
                        PolicyDetector.scanner("bsn", "BSN", "PII"),
                        PolicyDetector.scanner("credit_card", "CREDIT_CARD", "PII"),
                        PolicyDetector.scanner("ssn", "SSN", "PII"),
                        PolicyDetector.scanner("phone", "PHONE", "PII"),
                        PolicyDetector.scanner("ip_address", "IP_ADDRESS", "PII")),
                List.of(
                        new SecretScanner(),
                        new EmailScanner(),
                        new IbanScanner(),
                        new BsnScanner(),
                        new CreditCardScanner(),
                        new SsnScanner(),
                        new PhoneScanner(),
                        new IpAddressScanner()),
                PolicyAction.REDACT,
                RedactionStrategy.TOKENIZE,
                List.of("PII"));
    }

    public static PolicyPack fintech() {
        return new PolicyPack(
                "gavio.fintech",
                "FinTech",
                VERSION,
                "fintech",
                "Financial identifiers beyond the core PII pack.",
                List.of(
                        PolicyDetector.scanner("swift_bic", "SWIFT_BIC", "FINANCIAL_IDENTIFIER"),
                        PolicyDetector.scanner("routing_number", "ROUTING_NUMBER", "FINANCIAL_IDENTIFIER")),
                List.of(new SwiftBicScanner(), new RoutingNumberScanner()),
                PolicyAction.REDACT,
                RedactionStrategy.TOKENIZE,
                List.of("FINANCIAL_IDENTIFIER"));
    }

    public static PolicyPack custom(
            String id,
            String name,
            String version,
            String domain,
            List<RegexPolicyRule> rules,
            PolicyAction defaultAction,
            RedactionStrategy redactionStrategy,
            List<String> auditLabels,
            String description) {
        List<PolicyDetector> detectors = new ArrayList<>();
        List<PiiScanner> scanners = new ArrayList<>();
        for (RegexPolicyRule rule : rules) {
            detectors.add(PolicyDetector.regex(rule, defaultAction, redactionStrategy));
            scanners.add(new RegexRuleScanner(rule));
        }
        return new PolicyPack(
                id,
                name,
                version,
                domain,
                description,
                detectors,
                scanners,
                defaultAction,
                redactionStrategy,
                auditLabels);
    }

    public static PolicyPack custom(String id, String name, List<RegexPolicyRule> rules) {
        return custom(
                id,
                name,
                "1.0.0",
                "custom",
                rules,
                PolicyAction.REDACT,
                RedactionStrategy.TOKENIZE,
                List.of(),
                "Custom organization policy pack.");
    }

    public static List<PiiScanner> scanners(PolicyPack... packs) {
        List<PiiScanner> scanners = new ArrayList<>();
        for (PolicyPack pack : packs) {
            scanners.addAll(pack.scanners());
        }
        return scanners;
    }
}
