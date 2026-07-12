package io.gavio.interceptors.pii;

import io.gavio.GavioException.PiiBlockedException;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.interceptors.pii.policy.PolicyPack;
import io.gavio.interceptors.pii.policy.PolicyPacks;
import io.gavio.interceptors.pii.scanners.DefaultScanners;
import io.gavio.types.Message;
import io.gavio.types.PiiMode;
import io.gavio.types.Sensitivity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * The pre/post interceptor that detects and redacts PII.
 *
 * <p>PII is scanned on every request before it reaches the provider. Detected
 * entities are redacted/masked/tagged or blocked. In REDACT mode the original
 * values are restored in the response. Direct port of the Python {@code PiiGuard}.
 */
public final class PiiGuard implements Interceptor {

    private static final Logger LOG = Logger.getLogger("gavio.pii");
    private static final String STATE_KEY = "pii_replacements";

    // Confidence floor per sensitivity level — matches below the floor are ignored.
    private static final Map<Sensitivity, Double> CONFIDENCE_FLOOR = Map.of(
            Sensitivity.STRICT, 0.0,
            Sensitivity.BALANCED, 0.6,
            Sensitivity.PERMISSIVE, 0.9);

    private final List<PiiScanner> scanners;
    private final Sensitivity sensitivity;
    private final PiiMode mode;
    private final boolean restoreOnResponse;
    private final boolean logEntityTypes;
    private final boolean dryRun;
    private final String locale;
    private final String language;

    public PiiGuard() {
        this(DefaultScanners.defaults(), Sensitivity.STRICT, PiiMode.REDACT,
                true, true, false, "NL", "en");
    }

    public PiiGuard(List<PiiScanner> scanners, Sensitivity sensitivity, PiiMode mode,
                    boolean restoreOnResponse, boolean logEntityTypes, boolean dryRun,
                    String locale, String language) {
        this.scanners = scanners != null ? List.copyOf(scanners) : DefaultScanners.defaults();
        this.sensitivity = sensitivity;
        this.mode = mode;
        this.restoreOnResponse = restoreOnResponse;
        this.logEntityTypes = logEntityTypes;
        this.dryRun = dryRun;
        this.locale = locale;
        this.language = language;
    }

    @Override
    public String name() {
        return "pii_guard";
    }

    @Override
    public boolean dryRunSafe() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        ScanContext scanCtx = new ScanContext(language, locale);
        double floor = CONFIDENCE_FLOOR.get(sensitivity);

        List<Message> newMessages = new ArrayList<>();
        List<String> allTypes = new ArrayList<>();
        Map<String, String> replacements =
                (Map<String, String>) ctx.state().computeIfAbsent(STATE_KEY, k -> new HashMap<String, String>());

        boolean effectiveDryRun = this.dryRun || ctx.dryRun();

        for (Message message : request.messages()) {
            String content = message.content();
            List<PiiMatch> matches = scanText(content, scanCtx, floor);
            for (PiiMatch m : matches) {
                allTypes.add(m.entityType());
            }

            if (!matches.isEmpty() && mode == PiiMode.BLOCK) {
                List<String> types = new ArrayList<>();
                for (PiiMatch m : matches) {
                    types.add(m.entityType());
                }
                LOG.warning("pii_guard BLOCK: " + new TreeSet<>(types));
                return CompletableFuture.failedFuture(new PiiBlockedException(types));
            }

            String redacted = content;
            if (!matches.isEmpty() && !effectiveDryRun) {
                redacted = apply(content, matches, replacements);
            }
            newMessages.add(message.withContent(redacted));
        }

        if (!allTypes.isEmpty()) {
            ctx.recordPii(allTypes);
            if (logEntityTypes) {
                LOG.info("pii_guard detected entity types: " + new TreeSet<>(allTypes));
            }
        }

        if (restoreOnResponse && !replacements.isEmpty()) {
            ctx.state().put(STATE_KEY, replacements);
        }

        if (effectiveDryRun) {
            return CompletableFuture.completedFuture(request);
        }
        return CompletableFuture.completedFuture(request.withMessages(newMessages));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<GavioResponse> after(GavioResponse response, InterceptorContext ctx) {
        if (!restoreOnResponse || mode != PiiMode.REDACT) {
            return CompletableFuture.completedFuture(response);
        }
        Map<String, String> replacements = (Map<String, String>) ctx.state().get(STATE_KEY);
        if (replacements == null || replacements.isEmpty()) {
            return CompletableFuture.completedFuture(response);
        }
        String content = response.content();
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            content = content.replace(e.getKey(), e.getValue());
        }
        if (content.equals(response.content())) {
            return CompletableFuture.completedFuture(response);
        }
        return CompletableFuture.completedFuture(response.withContent(content));
    }

    private List<PiiMatch> scanText(String text, ScanContext scanCtx, double floor) {
        List<PiiScanner> ordered = new ArrayList<>(scanners);
        ordered.sort(Comparator.comparingInt(PiiScanner::tier));
        List<PiiMatch> raw = new ArrayList<>();
        for (PiiScanner scanner : ordered) {
            for (PiiMatch match : scanner.scan(text, scanCtx)) {
                if (match.confidence() >= floor) {
                    raw.add(match);
                }
            }
        }
        return resolveOverlaps(raw);
    }

    private String apply(String text, List<PiiMatch> matches, Map<String, String> replacements) {
        // Replace right-to-left so earlier offsets stay valid.
        List<PiiMatch> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt(PiiMatch::start).reversed());
        for (PiiMatch match : sorted) {
            String token = tokenFor(match);
            if (mode == PiiMode.REDACT) {
                replacements.put(token, match.value());
            }
            text = text.substring(0, match.start()) + token + text.substring(match.end());
        }
        return text;
    }

    private String tokenFor(PiiMatch match) {
        if (mode == PiiMode.MASK) {
            return "*".repeat(Math.max(match.length(), 1));
        }
        if (mode == PiiMode.TAG) {
            return "<" + match.entityType() + ">" + match.value() + "</" + match.entityType() + ">";
        }
        // REDACT (default)
        return match.replacement() != null ? match.replacement() : "[" + match.entityType() + "]";
    }

    /**
     * Drop lower-priority matches that overlap a kept one.
     *
     * <p>Sort by start, then descending span length (prefer the longer match),
     * then confidence; greedily keep non-overlapping matches.
     */
    static List<PiiMatch> resolveOverlaps(List<PiiMatch> matches) {
        List<PiiMatch> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator
                .comparingInt(PiiMatch::start)
                .thenComparingInt((PiiMatch m) -> -m.length())
                .thenComparingDouble(m -> -m.confidence()));
        List<PiiMatch> kept = new ArrayList<>();
        int occupiedEnd = -1;
        for (PiiMatch match : ordered) {
            if (match.start() >= occupiedEnd) {
                kept.add(match);
                occupiedEnd = match.end();
            }
        }
        return kept;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PiiGuard fromPolicyPack(PolicyPack... packs) {
        return builder().scanners(PolicyPacks.scanners(packs)).build();
    }

    /** Fluent builder for {@link PiiGuard}. */
    public static final class Builder {
        private List<PiiScanner> scanners;
        private Sensitivity sensitivity = Sensitivity.STRICT;
        private PiiMode mode = PiiMode.REDACT;
        private boolean restoreOnResponse = true;
        private boolean logEntityTypes = true;
        private boolean dryRun = false;
        private String locale = "NL";
        private String language = "en";

        public Builder scanners(PiiScanner... scanners) {
            this.scanners = List.of(scanners);
            return this;
        }

        public Builder scanners(List<PiiScanner> scanners) {
            this.scanners = List.copyOf(scanners);
            return this;
        }

        public Builder sensitivity(Sensitivity sensitivity) {
            this.sensitivity = sensitivity;
            return this;
        }

        public Builder mode(PiiMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder restoreOnResponse(boolean restoreOnResponse) {
            this.restoreOnResponse = restoreOnResponse;
            return this;
        }

        public Builder logEntityTypes(boolean logEntityTypes) {
            this.logEntityTypes = logEntityTypes;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder locale(String locale) {
            this.locale = locale;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public PiiGuard build() {
            return new PiiGuard(scanners, sensitivity, mode, restoreOnResponse,
                    logEntityTypes, dryRun, locale, language);
        }
    }
}
