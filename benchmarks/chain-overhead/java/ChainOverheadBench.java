import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.inspector.Inspector;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.audit.AuditSink;
import io.gavio.interceptors.governance.RateLimiter;
import io.gavio.interceptors.guardrails.GuardrailsInterceptor;
import io.gavio.interceptors.guardrails.RegexDenylistValidator;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.json.Json;
import io.gavio.providers.MockProvider;
import io.gavio.providers.ProviderAdapter;
import io.gavio.types.Message;
import io.gavio.types.Provider;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interceptor-chain overhead benchmark — Java SDK (F-TRUST-02).
 *
 * <p>Same methodology as {@code bench.py} / {@code bench.mjs}: a MockProvider padded with a fixed
 * 5 ms delay, a raw-provider baseline versus realistic default interceptor stacks, seeded payloads
 * (identical across SDKs), interleaved round-robin measurement. Configurations, thresholds, seed
 * and payload recipe come from {@code spec.json}.
 *
 * <p>Prints the run as JSON on stdout (and to {@code --out}). Exits non-zero when a CI threshold
 * is breached; those are deliberately loose, so a breach means a structural regression rather
 * than noise.
 *
 * <p>Single-file launch from the repo root, after {@code mvn -f packages/gavio-java/pom.xml
 * -DskipTests compile} (see REPRODUCE.md for the full command).
 */
public final class ChainOverheadBench {

    private static final int SCHEMA_VERSION = 1;
    private static final String SDK = "java";

    private static Map<String, Object> spec;
    private static double simulatedDelayMs;

    // ── Seeded payload generator (recipe shared by all three harnesses) ─────────

    /** mulberry32 — a 32-bit PRNG that is trivial to port exactly. */
    private static final class Mulberry32 {
        private int a;

        Mulberry32(long seed) {
            this.a = (int) seed;
        }

        long next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return (t ^ (t >>> 14)) & 0xFFFFFFFFL;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> buildPayloads(long seed, int count) {
        Map<String, Object> payload = (Map<String, Object>) spec.get("payload");
        List<String> vocab = (List<String>) payload.get("vocabulary");
        int wordCountMin = ((Number) payload.get("wordCountMin")).intValue();
        int wordCountSpan = ((Number) payload.get("wordCountSpan")).intValue();
        int emailModulus = ((Number) payload.get("emailModulus")).intValue();
        Mulberry32 rng = new Mulberry32(seed);
        List<String> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nWords = wordCountMin + (int) (rng.next() % wordCountSpan);
            List<String> words = new ArrayList<>(nWords + 3);
            for (int w = 0; w < nWords; w++) {
                words.add(vocab.get((int) (rng.next() % vocab.size())));
            }
            long kind = rng.next() % 4; // 0,1 clean · 2 email · 3 email + IBAN + phone
            List<String> entities = new ArrayList<>(3);
            if (kind >= 2) {
                long n = rng.next() % emailModulus;
                entities.add(payload.get("emailPrefix") + String.valueOf(n) + "@" + payload.get("emailDomain"));
            }
            if (kind == 3) {
                entities.add((String) payload.get("iban"));
                entities.add((String) payload.get("phone"));
            }
            for (String entity : entities) {
                words.add((int) (rng.next() % (words.size() + 1)), entity);
            }
            payloads.add(String.join(" ", words));
        }
        return payloads;
    }

    private static String payloadDigest(List<String> payloads) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", payloads).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder("sha256:");
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ── Provider, sink, and per-configuration subjects ──────────────────────────

    /** MockProvider padded with a fixed delay to emulate a real provider call. */
    private static final class DelayedMockProvider implements ProviderAdapter {
        private final MockProvider delegate = new MockProvider();

        @Override
        public String providerName() {
            return delegate.providerName();
        }

        @Override
        public CompletableFuture<GavioResponse> complete(GavioRequest request) {
            return CompletableFuture
                    .runAsync(() -> { },
                            CompletableFuture.delayedExecutor((long) simulatedDelayMs, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> delegate.complete(request));
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return delegate.healthCheck();
        }
    }

    /** Discards records (no I/O noise) but proves the audit path really ran. */
    private static final class CountingSink implements AuditSink {
        final AtomicLong records = new AtomicLong();
        final AtomicLong piiEntities = new AtomicLong();

        @Override
        public CompletableFuture<Void> write(AuditRecord record) {
            records.incrementAndGet();
            for (int n : record.piiEntityCounts().values()) {
                piiEntities.addAndGet(n);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /** One benchmarked configuration: a callable plus its audit sink. */
    private static final class Subject {
        final java.util.function.Function<List<Message>, GavioResponse> call;
        final CountingSink sink;

        Subject(java.util.function.Function<List<Message>, GavioResponse> call, CountingSink sink) {
            this.call = call;
            this.sink = sink;
        }
    }

    @SuppressWarnings("unchecked")
    private static Interceptor buildInterceptor(String name, CountingSink sink) {
        switch (name) {
            case "audit":
                return new AuditInterceptor(sink);
            case "pii_guard": {
                Map<String, Object> pii = (Map<String, Object>) spec.get("piiGuard");
                return PiiGuard.builder().logEntityTypes((Boolean) pii.get("logEntityTypes")).build();
            }
            case "rate_limiter": {
                Map<String, Object> limits = (Map<String, Object>) spec.get("rateLimiter");
                return RateLimiter.builder()
                        .maxRequestsPerMinute(((Number) limits.get("maxRequestsPerMinute")).intValue())
                        .maxTokensPerMinute(((Number) limits.get("maxTokensPerMinute")).longValue())
                        .build();
            }
            case "guardrails": {
                List<String> denylist = (List<String>) spec.get("guardrailsDenylist");
                return GuardrailsInterceptor.builder()
                        .validator(new RegexDenylistValidator(denylist))
                        .build();
            }
            default:
                throw new IllegalArgumentException("unknown interceptor in spec.json: " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Subject buildSubject(Map<String, Object> config) {
        DelayedMockProvider adapter = new DelayedMockProvider();
        if (!(Boolean) config.get("gateway")) {
            return new Subject(messages -> adapter.complete(request(messages)).join(), null);
        }
        CountingSink sink = new CountingSink();
        var builder = Gateway.builder().adapter(adapter).model("mock");
        List<String> interceptors = (List<String>) config.get("interceptors");
        for (String name : interceptors) {
            builder.use(buildInterceptor(name, sink));
        }
        Gateway gateway = builder.build();
        return new Subject(
                messages -> gateway.complete(request(messages)).join(),
                interceptors.contains("audit") ? sink : null);
    }

    private static GavioRequest request(List<Message> messages) {
        return GavioRequest.builder().messages(messages).model("mock").provider(Provider.MOCK).build();
    }

    // ── Measurement ─────────────────────────────────────────────────────────────

    /**
     * Interleaved round-robin: every iteration times each configuration once. The same payload is
     * used for all configurations in an iteration and the starting configuration rotates, so clock
     * drift, GC and JIT effects hit every configuration equally instead of biasing whichever ran
     * last.
     */
    private static Map<String, double[]> measure(
            Map<String, Subject> subjects, List<String> payloads, int warmup) {
        List<String> names = new ArrayList<>(subjects.keySet());
        Map<String, double[]> samples = new LinkedHashMap<>();
        for (String name : names) {
            samples.put(name, new double[payloads.size() - warmup]);
        }
        for (int i = 0; i < payloads.size(); i++) {
            List<Message> messages = List.of(Message.of("user", payloads.get(i)));
            for (int j = 0; j < names.size(); j++) {
                String name = names.get((i + j) % names.size());
                long started = System.nanoTime();
                subjects.get(name).call.apply(messages);
                double elapsedUs = (System.nanoTime() - started) / 1000.0;
                if (i >= warmup) {
                    samples.get(name)[i - warmup] = elapsedUs;
                }
            }
        }
        return samples;
    }

    /** Nearest-rank percentile: ordered[ceil(q * n) - 1]. */
    private static double percentile(double[] samples, double q) {
        double[] ordered = samples.clone();
        Arrays.sort(ordered);
        return ordered[Math.max(0, (int) Math.ceil(q * ordered.length) - 1)];
    }

    private static double round(double value, int places) {
        double f = Math.pow(10, places);
        return Math.round(value * f) / f;
    }

    // ── Runner / hardware info ──────────────────────────────────────────────────

    private static String command(String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(5, TimeUnit.SECONDS) && !out.isEmpty() ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String cpuModel(String os) {
        if (os.equals("macos")) {
            String brand = command("sysctl", "-n", "machdep.cpu.brand_string");
            return brand != null ? brand : "unknown";
        }
        if (os.equals("linux")) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("model name") || lower.startsWith("hardware")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        return "unknown";
    }

    private static Map<String, Object> runnerInfo() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "macos" : osName.contains("win") ? "windows" : "linux";
        String rawArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String arch = rawArch.equals("aarch64") || rawArch.equals("arm64") ? "arm64"
                : rawArch.equals("amd64") || rawArch.equals("x86_64") ? "x86_64" : rawArch;
        boolean inCi = "true".equals(System.getenv("GITHUB_ACTIONS"));
        String imageOs = System.getenv("ImageOS");
        String imageVersion = System.getenv("ImageVersion");
        String image = String.join("/",
                java.util.stream.Stream.of(imageOs, imageVersion).filter(s -> s != null && !s.isEmpty()).toList());
        Object memory = ManagementFactory.getOperatingSystemMXBean();
        Double memoryGb = memory instanceof com.sun.management.OperatingSystemMXBean mx
                ? round(mx.getTotalMemorySize() / (1024.0 * 1024.0 * 1024.0), 1) : null;

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("name", System.getProperty("java.vm.name"));
        runtime.put("version", System.getProperty("java.runtime.version"));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("os", os);
        info.put("osRelease", System.getProperty("os.version"));
        info.put("arch", arch);
        info.put("cpuModel", cpuModel(os));
        info.put("cpuCount", Runtime.getRuntime().availableProcessors());
        info.put("memoryGb", memoryGb);
        info.put("runtime", runtime);
        info.put("ci", inCi);
        info.put("ciProvider", inCi ? "github-actions" : null);
        info.put("ciImage", inCi && !image.isEmpty() ? image : null);
        return info;
    }

    // ── Entry point ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(long seed, int warmup, int iterations) throws Exception {
        List<String> payloads = buildPayloads(seed, warmup + iterations);
        List<Map<String, Object>> specs = (List<Map<String, Object>>) spec.get("configurations");
        Map<String, Subject> subjects = new LinkedHashMap<>();
        for (Map<String, Object> config : specs) {
            subjects.put((String) config.get("id"), buildSubject(config));
        }
        Map<String, double[]> samples = measure(subjects, payloads, warmup);

        double delayUs = simulatedDelayMs * 1000.0;
        double baselineP50 = round(percentile(samples.get("baseline"), 0.5), 1);
        List<Object> configurations = new ArrayList<>();
        boolean ok = true;
        for (Map<String, Object> config : specs) {
            String name = (String) config.get("id");
            Number budget = (Number) config.get("budgetPct");
            double p50 = round(percentile(samples.get(name), 0.5), 1);
            double p95 = round(percentile(samples.get(name), 0.95), 1);
            double overheadUs = round(p50 - baselineP50, 1);
            double overheadPct = round(overheadUs / delayUs * 100.0, 2);
            boolean pass = budget == null || overheadPct < budget.doubleValue();

            // Prove the stack really ran: every call audited, PII really detected.
            CountingSink sink = subjects.get(name).sink;
            Map<String, Object> checks = new LinkedHashMap<>();
            if (sink != null) {
                checks.put("auditRecords", sink.records.get());
                checks.put("piiEntities", sink.piiEntities.get());
                pass = pass && sink.records.get() == payloads.size();
                if (((List<String>) config.get("interceptors")).contains("pii_guard")) {
                    pass = pass && sink.piiEntities.get() > 0;
                }
            }

            ok = ok && pass;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configuration", name);
            row.put("description", config.get("description"));
            row.put("interceptors", config.get("interceptors"));
            row.put("p50Us", p50);
            row.put("p95Us", p95);
            row.put("overheadP50Us", overheadUs);
            row.put("overheadPct", overheadPct);
            row.put("budgetPct", budget);
            row.put("checks", checks);
            row.put("pass", pass);
            configurations.add(row);
        }

        Map<String, Object> methodology = new LinkedHashMap<>();
        methodology.put("simulatedDelayMs", simulatedDelayMs);
        methodology.put("warmup", warmup);
        methodology.put("iterations", iterations);
        methodology.put("percentile", "nearest-rank");
        methodology.put("interleaved", true);
        methodology.put("payloadCount", payloads.size());
        methodology.put("payloadDigest", payloadDigest(payloads));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("benchmark", "chain-overhead");
        result.put("sdk", SDK);
        result.put("gavioVersion", Inspector.SDK_VERSION);
        result.put("date", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        result.put("seed", seed);
        result.put("methodology", methodology);
        result.put("runner", runnerInfo());
        result.put("configurations", configurations);
        result.put("pass", ok);
        return result;
    }

    /** Indents compact JSON (string-aware) so stdout matches the other harnesses' layout. */
    private static String pretty(String compact) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    out.append(compact.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> { inString = true; out.append(c); }
                case '{', '[' -> {
                    boolean empty = compact.charAt(i + 1) == (c == '{' ? '}' : ']');
                    out.append(c);
                    if (!empty) {
                        depth++;
                        out.append('\n').append("  ".repeat(depth));
                    }
                }
                case '}', ']' -> {
                    boolean empty = compact.charAt(i - 1) == (c == '}' ? '{' : '[');
                    if (!empty) {
                        depth--;
                        out.append('\n').append("  ".repeat(depth));
                    }
                    out.append(c);
                }
                case ',' -> out.append(",\n").append("  ".repeat(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path specPath = Path.of(System.getProperty("chainBench.spec",
                Path.of("benchmarks", "chain-overhead", "spec.json").toString()));
        spec = Json.parseObject(Files.readString(specPath));
        simulatedDelayMs = ((Number) spec.get("simulatedDelayMs")).doubleValue();

        long seed = ((Number) spec.get("seed")).longValue();
        int warmup = ((Number) spec.get("warmup")).intValue();
        int iterations = ((Number) spec.get("iterations")).intValue();
        Path out = null;
        for (int i = 0; i < args.length; i += 2) {
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--seed" -> seed = Long.parseLong(value);
                case "--warmup" -> warmup = Integer.parseInt(value);
                case "--iterations" -> iterations = Integer.parseInt(value);
                case "--out" -> out = Path.of(value);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        Map<String, Object> result = run(seed, warmup, iterations);
        String text = pretty(Json.write(result));
        System.out.println(text);
        if (out != null) {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            Files.writeString(out, text + "\n");
        }
        System.exit((Boolean) result.get("pass") ? 0 : 1);
    }
}
