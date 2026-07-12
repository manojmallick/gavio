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
import io.gavio.json.Json;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    public static List<String> listCatalog() {
        Path root = catalogRoot();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals("manifest.json"))
                    .map(path -> root.relativize(path.getParent()).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("could not list policy-packs catalog", ex);
        }
    }

    public static PolicyPack load(String name) {
        Path manifest = catalogRoot().resolve(name).resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("unknown policy pack: " + name);
        }
        return loadPath(manifest);
    }

    public static PolicyPack loadPath(String path) {
        return loadPath(Path.of(path));
    }

    public static PolicyPack loadPath(Path path) {
        Path manifest = Files.isDirectory(path) ? path.resolve("manifest.json") : path;
        try {
            return fromManifest(Json.parseObject(Files.readString(manifest)));
        } catch (IOException ex) {
            throw new IllegalStateException("could not load policy pack manifest: " + manifest, ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static PolicyPack fromManifest(Map<String, Object> manifest) {
        PolicyAction defaultAction = policyAction(manifest.get("defaultAction"), PolicyAction.REDACT);
        RedactionStrategy redactionStrategy =
                redactionStrategy(manifest.get("redactionStrategy"), RedactionStrategy.TOKENIZE);
        List<PolicyDetector> detectors = new ArrayList<>();
        List<PiiScanner> scanners = new ArrayList<>();
        for (Object obj : (List<Object>) manifest.getOrDefault("detectors", List.of())) {
            PolicyDetector detector = detectorFromManifest((Map<String, Object>) obj, defaultAction, redactionStrategy);
            detectors.add(detector);
            scanners.addAll(scannersFromDetector(detector));
        }
        return new PolicyPack(
                manifest.get("id").toString(),
                manifest.get("name").toString(),
                manifest.get("version").toString(),
                manifest.get("domain").toString(),
                manifest.getOrDefault("description", "").toString(),
                detectors,
                scanners,
                defaultAction,
                redactionStrategy,
                stringList(manifest.get("auditLabels")),
                stringMap(manifest.get("compatibility")),
                signatureFromManifest(manifest.get("signature")),
                manifest.containsKey("$schema") ? manifest.get("$schema").toString() : null,
                manifest.containsKey("schemaVersion") ? manifest.get("schemaVersion").toString() : null);
    }

    static String canonicalManifestDigest(Map<String, Object> manifest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) copyJson(manifest);
        Object signature = payload.get("signature");
        if (signature instanceof Map<?, ?> rawSignature) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutableSignature = (Map<String, Object>) rawSignature;
            mutableSignature.put("value", null);
        }
        byte[] encoded = Json.write(canonicalize(payload)).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(encoded));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static PolicyDetector detectorFromManifest(
            Map<String, Object> item,
            PolicyAction defaultAction,
            RedactionStrategy defaultStrategy) {
        return new PolicyDetector(
                item.get("name").toString(),
                item.get("entityType").toString(),
                item.getOrDefault("type", "scanner").toString(),
                policyAction(item.get("action"), defaultAction),
                item.containsKey("label") ? item.get("label").toString() : null,
                item.containsKey("severity") ? item.get("severity").toString() : null,
                number(item.getOrDefault("confidence", 1.0)),
                redactionStrategy(item.get("redactionStrategy"), defaultStrategy),
                item.containsKey("pattern") ? item.get("pattern").toString() : null,
                item.containsKey("replacementPrefix") ? item.get("replacementPrefix").toString() : null,
                stringList(item.get("suppressionPatterns")));
    }

    private static List<PiiScanner> scannersFromDetector(PolicyDetector detector) {
        if ("regex".equals(detector.type())) {
            if (detector.pattern() == null) {
                throw new IllegalArgumentException("regex policy detector " + detector.name() + " is missing pattern");
            }
            return List.of(new RegexRuleScanner(new RegexPolicyRule(
                    detector.name(),
                    detector.entityType(),
                    detector.pattern(),
                    detector.confidence(),
                    detector.replacementPrefix(),
                    detector.action(),
                    detector.redactionStrategy(),
                    detector.label(),
                    detector.severity(),
                    detector.suppressionPatterns())));
        }
        PiiScanner scanner = scannerFor(detector.name());
        if (scanner == null) {
            scanner = scannerFor(detector.entityType());
        }
        if (scanner == null) {
            throw new IllegalArgumentException("unknown policy-pack scanner detector: " + detector.name());
        }
        return List.of(scanner);
    }

    private static PiiScanner scannerFor(String name) {
        return switch (name) {
            case "secret", "SECRET" -> new SecretScanner();
            case "email", "EMAIL" -> new EmailScanner();
            case "iban", "IBAN" -> new IbanScanner();
            case "bsn", "BSN" -> new BsnScanner();
            case "credit_card", "CREDIT_CARD" -> new CreditCardScanner();
            case "ssn", "SSN" -> new SsnScanner();
            case "phone", "PHONE" -> new PhoneScanner();
            case "ip_address", "IP_ADDRESS" -> new IpAddressScanner();
            case "swift_bic", "SWIFT_BIC" -> new SwiftBicScanner();
            case "routing_number", "ROUTING_NUMBER" -> new RoutingNumberScanner();
            default -> null;
        };
    }

    private static PolicyPackSignature signatureFromManifest(Object value) {
        if (!(value instanceof Map<?, ?> signature)) {
            return null;
        }
        Object rawValue = signature.get("value");
        Object algorithm = signature.get("algorithm");
        return new PolicyPackSignature(
                algorithm != null ? algorithm.toString() : "sha256",
                rawValue != null ? rawValue.toString() : null,
                signature.containsKey("keyId") ? signature.get("keyId").toString() : null,
                signature.containsKey("signedAt") ? signature.get("signedAt").toString() : null);
    }

    private static PolicyAction policyAction(Object value, PolicyAction fallback) {
        return value == null ? fallback : PolicyAction.fromWire(value.toString());
    }

    private static RedactionStrategy redactionStrategy(Object value, RedactionStrategy fallback) {
        return value == null ? fallback : RedactionStrategy.fromWire(value.toString());
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return out;
    }

    private static Object copyJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(entry.getKey().toString(), copyJson(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(copyJson(item));
            }
            return out;
        }
        return value;
    }

    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> out.put(entry.getKey().toString(), canonicalize(entry.getValue())));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(canonicalize(item));
            }
            return out;
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return d.longValue();
        }
        if (value instanceof Float f && f == Math.rint(f) && !Float.isInfinite(f)) {
            return f.longValue();
        }
        return value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static Path catalogRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path found = findCatalogFrom(cwd);
        if (found != null) {
            return found;
        }
        try {
            Path code = Path.of(PolicyPacks.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath();
            found = findCatalogFrom(code);
            if (found != null) {
                return found;
            }
        } catch (URISyntaxException ignored) {
            // Fall through to the error below.
        }
        throw new IllegalStateException("could not locate policy-packs catalog");
    }

    private static Path findCatalogFrom(Path start) {
        Path current = Files.isRegularFile(start) ? start.getParent() : start;
        while (current != null) {
            Path candidate = current.resolve("policy-packs");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }
}
