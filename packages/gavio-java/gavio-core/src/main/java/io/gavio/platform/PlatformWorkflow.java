package io.gavio.platform;

import io.gavio.json.Json;
import io.gavio.platform.PlatformWorkflowPolicyResolver.PolicyEvidence;
import io.gavio.prompts.EvalReport;
import io.gavio.prompts.EvalRunResult;
import io.gavio.prompts.EvalRunner;
import io.gavio.prompts.PromptEvalWorkflow;
import io.gavio.prompts.PromptRegistry;
import io.gavio.prompts.PromptTemplate;
import io.gavio.trust.ProductionTrust;
import io.gavio.trust.ProductionTrustVerification;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Platform workflow release artifacts (F-DX-13).
 *
 * <p>Joins prompts, evals, policy packs, production trust evidence, and platform runtime profiles
 * into one metadata-only release record. Library port of {@code gavio.workflow} (Python): same
 * manifest, same artifact, same {@code workflowHash}. The {@code gavio workflow release} CLI stays
 * Python-only, and only JSON documents are read here (YAML manifests are Python-only). The artifact
 * shape is {@code spec/PlatformWorkflowRelease.schema.json}; behaviour is pinned by
 * {@code test-vectors/workflow/release.json}.
 *
 * <p>Policy Pack evidence is read through {@link PlatformWorkflowPolicyResolver}, which the
 * {@code gavio-interceptor-pii} module provides; pass one explicitly or keep that module on the
 * classpath.
 */
public final class PlatformWorkflow {

    public static final String MANIFEST_SCHEMA_VERSION = "gavio.platform-workflow.v1";
    public static final String RELEASE_SCHEMA_VERSION = "gavio.platform-workflow-release.v1";

    /** Fields of an {@code evals[*]} entry that describe the machine that ran the suite. */
    private static final Set<String> ENVIRONMENT_EVAL_KEYS = Set.of("runner", "source");

    private static final Set<String> CONTENT_KEY_NAMES = Set.of(
            "messages",
            "content",
            "diff",
            "raw",
            "rawmessages",
            "rawprompt",
            "rawresponse",
            "prompt",
            "prompttext",
            "completion",
            "response",
            "responsetext",
            "input",
            "inputtext",
            "output",
            "outputtext",
            "rawinput",
            "rawoutput",
            "renderedprompt",
            "text");

    private PlatformWorkflow() {
    }

    /** Load a workflow manifest and build its release artifact. */
    public static PlatformWorkflowResult runFile(Path manifestPath) {
        return runFile(manifestPath, null);
    }

    public static PlatformWorkflowResult runFile(Path manifestPath, PlatformWorkflowPolicyResolver resolver) {
        Path path = EvalRunnerPaths.expandUser(manifestPath.toString()).toAbsolutePath().normalize();
        Map<String, Object> manifest = EvalRunner.loadDocument(path);
        return build(manifest, path.getParent(), resolver);
    }

    public static PlatformWorkflowResult build(Map<String, Object> manifest, Path basePath) {
        return build(manifest, basePath, null);
    }

    /** Build a deterministic metadata-only platform workflow release. */
    @SuppressWarnings("unchecked")
    public static PlatformWorkflowResult build(
            Map<String, Object> manifest, Path basePath, PlatformWorkflowPolicyResolver resolver) {
        Path base = EvalRunnerPaths.expandUser((basePath == null ? Path.of(".") : basePath).toString())
                .toAbsolutePath()
                .normalize();
        String workflowId = pyStr(get(manifest, "workflowId", get(manifest, "workflow_id", "workflow-release")));
        Object configuredAt = truthy(manifest.get("generatedAt"));
        if (configuredAt == null) {
            configuredAt = truthy(manifest.get("generated_at"));
        }
        String generatedAt = pyStr(configuredAt == null ? utcNow() : configuredAt);

        Map<String, Object> promptSection = asMap(get(manifest, "prompts", get(manifest, "prompt")));
        Path promptManifestPath = optionalPath(base, promptSection.get("manifest"));
        Map<String, Object> promptManifest =
                promptManifestPath == null ? null : EvalRunner.loadDocument(promptManifestPath);

        List<EvalRunResult> evalResults = runEvalSections(manifest, base, promptManifestPath);
        List<EvalReport> reports = new ArrayList<>();
        for (EvalRunResult result : evalResults) {
            reports.add(result.report());
        }
        List<Map<String, Object>> promptBundles = buildPromptBundles(promptSection, promptManifest, reports, generatedAt);
        List<Map<String, Object>> policyEvidence = policyEvidence(manifest, base, resolver);
        Map<String, Object> trustEvidence = trustEvidence(manifest, base);
        Map<String, Object> profileEvidence = profileEvidence(manifest, base);

        List<String> reasons = new ArrayList<>();
        for (EvalRunResult result : evalResults) {
            if (!result.passed()) {
                for (String reason : result.gate().reasons()) {
                    reasons.add("eval:" + reason);
                }
                if (result.workflow() != null && !result.workflow().passed()) {
                    result.workflow().gates().forEach(gate -> {
                        for (String reason : gate.reasons()) {
                            reasons.add("prompt:" + reason);
                        }
                    });
                }
            }
        }
        for (Map<String, Object> bundle : promptBundles) {
            if (!Boolean.TRUE.equals(bundle.get("passed"))) {
                reasons.add("prompt release bundle " + bundle.get("bundleId") + " did not pass");
            }
        }
        for (Map<String, Object> policy : policyEvidence) {
            if (!Boolean.TRUE.equals(policy.get("signatureValid"))) {
                reasons.add("policy " + policy.get("id") + " signature is invalid");
            }
        }
        if (trustEvidence != null && !Boolean.TRUE.equals(trustEvidence.get("valid"))) {
            for (Object error : (List<Object>) trustEvidence.get("errors")) {
                reasons.add("trust:" + error);
            }
        }
        if (profileEvidence != null) {
            if (!Boolean.TRUE.equals(profileEvidence.get("valid"))) {
                for (Object error : (List<Object>) profileEvidence.get("errors")) {
                    reasons.add("profile:" + error);
                }
            }
            Map<String, Object> readiness = asMap(profileEvidence.get("readiness"));
            if (!Boolean.TRUE.equals(readiness.get("ready"))) {
                for (Object gap : asList(readiness.get("gaps"))) {
                    if (gap instanceof Map<?, ?> gapMap && gapMap.containsKey("code")) {
                        reasons.add("profile:" + gapMap.get("code"));
                    }
                }
            }
        }

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", RELEASE_SCHEMA_VERSION);
        artifact.put("workflowId", workflowId);
        artifact.put("generatedAt", generatedAt);
        artifact.put("release", sanitizeMetadata(new LinkedHashMap<>(asMap(manifest.get("release")))));
        artifact.put("passed", reasons.isEmpty());
        artifact.put("reasons", new ArrayList<>(reasons));
        Map<String, Object> prompts = new LinkedHashMap<>();
        prompts.put("manifest", promptManifest == null ? null : promptManifestIdentity(promptManifest));
        prompts.put("releaseBundles", new ArrayList<>(promptBundles));
        artifact.put("prompts", prompts);
        List<Object> evals = new ArrayList<>();
        for (EvalRunResult result : evalResults) {
            evals.add(result.toMap());
        }
        artifact.put("evals", evals);
        artifact.put("policies", new ArrayList<>(policyEvidence));
        artifact.put("trust", trustEvidence);
        artifact.put("runtimeProfile", profileEvidence);
        artifact.put("metadata", sanitizeMetadata(new LinkedHashMap<>(asMap(manifest.get("metadata")))));
        artifact.put("workflowHash", releaseHash(artifact));
        return new PlatformWorkflowResult(artifact, reasons.isEmpty(), reasons);
    }

    /**
     * {@code sha256:<hex>} over the canonical JSON of a workflow release without
     * {@code workflowHash}, {@code evals[*].runner}, and {@code evals[*].source}.
     */
    public static String releaseHash(Map<String, Object> artifact) {
        Map<String, Object> data = new LinkedHashMap<>(artifact);
        data.remove("workflowHash");
        if (data.get("evals") instanceof List<?> evals) {
            List<Object> portable = new ArrayList<>();
            for (Object item : evals) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!ENVIRONMENT_EVAL_KEYS.contains(String.valueOf(entry.getKey()))) {
                            copy.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    portable.add(copy);
                } else {
                    portable.add(item);
                }
            }
            data.put("evals", portable);
        }
        return "sha256:" + sha256(canonicalJson(data));
    }

    /**
     * Cross-SDK canonical JSON: compact separators, object keys sorted by Unicode code point,
     * non-ASCII emitted as-is, control characters as lowercase four-digit unicode escapes, integral
     * numbers written as integers ({@code 1.0} becomes {@code 1}), and every other number as the
     * shortest round-trip decimal without an exponent.
     */
    public static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(out, value);
        return out.toString();
    }

    private static void writeCanonical(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof CharSequence) {
            out.append(value instanceof Boolean ? value.toString() : Json.write(value.toString()));
        } else if (value instanceof Number number) {
            out.append(canonicalNumber(number));
        } else if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((left, right) ->
                    compareCodePoints(String.valueOf(left.getKey()), String.valueOf(right.getKey())));
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : entries) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(Json.write(String.valueOf(entry.getKey()))).append(':');
                writeCanonical(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeCanonical(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot canonicalize " + value.getClass().getName());
        }
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte || number instanceof BigInteger) {
            return number.toString();
        }
        BigDecimal decimal;
        if (number instanceof BigDecimal big) {
            decimal = big;
        } else {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("canonical JSON cannot represent NaN or Infinity");
            }
            if (d == Math.rint(d)) {
                return new BigDecimal(d).toBigInteger().toString();
            }
            decimal = new BigDecimal(number instanceof Float ? Float.toString((float) d) : Double.toString(d));
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    @SuppressWarnings("unchecked")
    private static List<EvalRunResult> runEvalSections(
            Map<String, Object> manifest, Path base, Path promptManifestPath) {
        List<EvalRunResult> results = new ArrayList<>();
        for (Object rawEval : asList(get(manifest, "evals", get(manifest, "eval")))) {
            Map<String, Object> config = new LinkedHashMap<>();
            if (rawEval instanceof String suite) {
                config.put("suite", suite);
            } else {
                config.putAll(asMap(rawEval));
            }
            Path suitePath = requiredPath(base, config.get("suite"), "eval suite");
            List<String> templates = stringList(get(config, "templates", get(config, "templateFiles")));
            Object templatePath = get(config, "template", get(config, "templatesFile"));
            if (templatePath != null) {
                templates.add(String.valueOf(templatePath));
            }
            if (promptManifestPath != null && templates.isEmpty()) {
                templates.add(promptManifestPath.toString());
            }
            Object baseline = config.get("baseline");
            List<Path> templatePaths = new ArrayList<>();
            for (String value : templates) {
                templatePaths.add(resolvePath(base, value));
            }
            Double failUnder = optionalFloat(get(config, "failUnder", get(config, "fail_under")));
            Object maxRegression = get(config, "maxRegression", get(config, "max_regression", 0));
            results.add(EvalRunner.run(
                    suitePath,
                    new EvalRunner.Options(
                            templatePaths,
                            failUnder,
                            baseline == null ? null : resolvePath(base, String.valueOf(baseline)),
                            toDouble(maxRegression))));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildPromptBundles(
            Map<String, Object> promptSection,
            Map<String, Object> promptManifest,
            List<EvalReport> reports,
            String generatedAt) {
        List<Map<String, Object>> bundles = new ArrayList<>();
        if (promptManifest == null) {
            return bundles;
        }
        List<Object> releases = asList(get(promptSection, "releases", get(promptSection, "release")));
        if (releases.isEmpty() && truthy(get(promptSection, "promptId", get(promptSection, "prompt_id"))) != null) {
            releases = List.of(promptSection);
        }
        for (Object raw : releases) {
            Map<String, Object> config = asMap(raw);
            String promptId = pyStr(get(config, "promptId", get(config, "prompt_id")));
            String promptVersion = pyStr(get(config, "promptVersion", get(config, "prompt_version")));
            Object from = get(config, "fromVersion", get(config, "from_version"));
            String fromVersion = from == null
                    ? previousVersion(promptManifest, promptId, promptVersion)
                    : String.valueOf(from);
            Object bundleId = get(config, "bundleId", get(config, "bundle_id"));
            bundles.add(PromptEvalWorkflow.buildReleaseBundle(
                            promptManifest,
                            promptId,
                            promptVersion,
                            reports,
                            null,
                            fromVersion,
                            pyStr(get(config, "generatedAt", generatedAt)),
                            bundleId == null ? null : String.valueOf(bundleId),
                            new LinkedHashMap<>(asMap(config.get("metadata"))))
                    .toMap());
        }
        return bundles;
    }

    /** Python defaults {@code fromVersion} to the highest other registered version. */
    private static String previousVersion(Map<String, Object> manifest, String promptId, String promptVersion) {
        PromptRegistry registry = PromptRegistry.fromManifest(manifest, null, false);
        PromptTemplate target = registry.get(promptId, promptVersion);
        List<String> previous = registry.versions(promptId).stream()
                .filter(version -> !version.equals(target.version()))
                .toList();
        return previous.isEmpty() ? null : previous.get(previous.size() - 1);
    }

    private static List<Map<String, Object>> policyEvidence(
            Map<String, Object> manifest, Path base, PlatformWorkflowPolicyResolver resolver) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Object rawPolicy : asList(get(manifest, "policies", get(manifest, "policyPacks")))) {
            Map<String, Object> config = new LinkedHashMap<>();
            if (rawPolicy instanceof String name) {
                config.put("pathOrName", name);
            } else {
                config.putAll(asMap(rawPolicy));
            }
            Object chosen = truthy(config.get("pathOrName"));
            for (String key : List.of("path_or_name", "path", "name", "id")) {
                if (chosen == null) {
                    chosen = truthy(config.get(key));
                }
            }
            String pathOrName = pyStr(chosen);
            PolicyEvidence pack = loadPolicy(pathOrName, base, resolver);
            Object configuredId = truthy(config.get("id"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", pyStr(configuredId == null ? pack.id() : configuredId));
            entry.put("name", pack.name());
            entry.put("version", pack.version());
            entry.put("domain", pack.domain());
            entry.put("source", pathOrName);
            entry.put("signatureValid", pack.signatureValid());
            entry.put("manifestDigest", sha256(canonicalJson(pack.manifest())));
            entry.put("detectorCount", pack.detectorCount());
            entry.put("metadata", sanitizeMetadata(new LinkedHashMap<>(asMap(config.get("metadata")))));
            evidence.add(entry);
        }
        return evidence;
    }

    private static PolicyEvidence loadPolicy(String pathOrName, Path base, PlatformWorkflowPolicyResolver resolver) {
        PlatformWorkflowPolicyResolver active = resolver != null ? resolver : discoverResolver();
        Path path = EvalRunnerPaths.expandUser(pathOrName);
        List<Path> candidates = path.isAbsolute()
                ? List.of(path)
                : List.of(base.resolve(path), path.toAbsolutePath().normalize());
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return active.loadPath(candidate);
            }
        }
        return active.loadCatalog(pathOrName);
    }

    private static PlatformWorkflowPolicyResolver discoverResolver() {
        for (PlatformWorkflowPolicyResolver found : ServiceLoader.load(PlatformWorkflowPolicyResolver.class)) {
            return found;
        }
        throw new IllegalStateException(
                "no PlatformWorkflowPolicyResolver available; add gavio-interceptor-pii to the classpath "
                        + "or pass a resolver to PlatformWorkflow");
    }

    private static Map<String, Object> trustEvidence(Map<String, Object> manifest, Path base) {
        Object raw = get(manifest, "trustBundle", get(manifest, "trust"));
        if (raw == null) {
            return null;
        }
        Map<String, Object> bundle = loadSectionPayload(raw, base);
        ProductionTrustVerification verification = ProductionTrust.verify(bundle);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", verification.valid());
        out.put("errors", new ArrayList<>(verification.errors()));
        out.put("computedHash", verification.computedHash());
        out.put("bundle", sanitizeMetadata(new LinkedHashMap<>(bundle)));
        return out;
    }

    private static Map<String, Object> profileEvidence(Map<String, Object> manifest, Path base) {
        Object raw = get(manifest, "runtimeProfile", get(manifest, "platformRuntimeProfile"));
        if (raw == null) {
            return null;
        }
        Map<String, Object> profile = loadSectionPayload(raw, base);
        PlatformRuntimeVerification verification = PlatformRuntime.verify(profile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", verification.valid());
        out.put("errors", new ArrayList<>(verification.errors()));
        out.put("computedHash", verification.computedHash());
        out.put("readiness", new LinkedHashMap<>(verification.readiness()));
        out.put("profile", sanitizeMetadata(new LinkedHashMap<>(profile)));
        return out;
    }

    private static Map<String, Object> loadSectionPayload(Object raw, Path base) {
        if (raw instanceof String path) {
            return EvalRunner.loadDocument(resolvePath(base, path));
        }
        Map<String, Object> config = asMap(raw);
        Object path = config.get("path");
        if (path != null) {
            return EvalRunner.loadDocument(resolvePath(base, String.valueOf(path)));
        }
        Object data = get(config, "data", get(config, "bundle", get(config, "profile")));
        if (data instanceof Map<?, ?>) {
            return new LinkedHashMap<>(asMap(data));
        }
        return new LinkedHashMap<>(config);
    }

    private static Map<String, Object> promptManifestIdentity(Map<String, Object> manifest) {
        Map<String, Object> unsigned = new LinkedHashMap<>(manifest);
        unsigned.remove("signature");
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schemaVersion", manifest.get("schemaVersion"));
        identity.put("registryId", manifest.get("registryId"));
        identity.put("digest", sha256(canonicalJson(unsigned)));
        if (manifest.get("signature") instanceof Map<?, ?> signature) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String key : List.of("algorithm", "keyId", "value")) {
                if (signature.containsKey(key)) {
                    copy.put(key, signature.get(key));
                }
            }
            identity.put("signature", copy);
        }
        return identity;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (CONTENT_KEY_NAMES.contains(normalizeKey(key))) {
                    out.put(key + "Hash", sha256(canonicalJson(entry.getValue())));
                } else {
                    out.put(key, sanitizeMetadata(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeMetadata(item));
            }
            return out;
        }
        return value;
    }

    private static String normalizeKey(String value) {
        return value.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Object get(Map<String, Object> map, String key) {
        return map.get(key);
    }

    /** {@code dict.get(key, default)}: a present key wins even when its value is null. */
    private static Object get(Map<String, Object> map, String key, Object fallback) {
        return map.containsKey(key) ? map.get(key) : fallback;
    }

    /** Python truthiness for the {@code a or b or c} chains the manifest accepts. */
    private static Object truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == 0.0) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        return value;
    }

    private static String pyStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    private static String utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
    }

    private static Path resolvePath(Path base, String value) {
        Path path = EvalRunnerPaths.expandUser(value);
        return path.isAbsolute() ? path : base.resolve(path).normalize();
    }

    private static Path optionalPath(Path base, Object value) {
        return value == null ? null : resolvePath(base, String.valueOf(value));
    }

    private static Path requiredPath(Path base, Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("platform workflow release requires " + label);
        }
        return resolvePath(base, String.valueOf(value));
    }

    private static Double optionalFloat(Object value) {
        return value == null ? null : toDouble(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        List<Object> single = new ArrayList<>();
        single.add(value);
        return single;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /** Path helpers shared with the eval runner. */
    private static final class EvalRunnerPaths {
        private EvalRunnerPaths() {
        }

        static Path expandUser(String value) {
            if (value.equals("~") || value.startsWith("~/")) {
                String home = System.getProperty("user.home", "");
                return Path.of(home).resolve(value.length() > 2 ? value.substring(2) : "");
            }
            return Path.of(value);
        }
    }
}
