package io.gavio.interceptors.pii;

import io.gavio.GavioException.PromptInjectionException;
import io.gavio.GavioRequest;
import io.gavio.interceptors.Interceptor;
import io.gavio.interceptors.InterceptorContext;
import io.gavio.types.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * PromptInjectionGuard (F-SEC-05) — pattern-based prompt-injection defense.
 *
 * <p>Scans user/tool messages against a curated attack-pattern corpus; blocks or
 * flags. (The Python/JS SDKs add an optional semantic tier via an embedder; the
 * Java guard is pattern-only to keep the module dependency-free.)
 */
public final class PromptInjectionGuard implements Interceptor {

    public enum Action {
        BLOCK,
        FLAG
    }

    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
            Pattern.compile("ignore (all |the )?(previous|prior|above) (instructions|prompts?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard (all |the )?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget (everything|all|your) (above|previous|instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reveal (your |the )?(system )?prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(print|show|repeat) (your |the )?(system )?prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you are now (a |an )?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do anything now|\\bDAN\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("override (your |the )?(safety|guidelines|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend (to be|you are)", Pattern.CASE_INSENSITIVE));

    private final List<Pattern> patterns;
    private final Action action;
    private final Set<String> scanRoles;

    private PromptInjectionGuard(Builder b) {
        this.patterns = b.patterns != null ? b.patterns : DEFAULT_PATTERNS;
        this.action = b.action;
        this.scanRoles = b.scanRoles;
    }

    public PromptInjectionGuard() {
        this(new Builder());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "prompt_injection_guard";
    }

    @Override
    public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
        Set<String> hits = new TreeSet<>();
        for (Message m : request.messages()) {
            if (!scanRoles.contains(m.role())) {
                continue;
            }
            for (Pattern p : patterns) {
                if (p.matcher(m.content()).find()) {
                    hits.add(p.pattern());
                }
            }
        }
        if (!hits.isEmpty()) {
            Double current = ctx.riskScore();
            ctx.riskScore(Math.max(current != null ? current : 0.0, 0.9));
            if (action == Action.BLOCK) {
                return CompletableFuture.failedFuture(
                        new PromptInjectionException("prompt injection detected: " + hits));
            }
        }
        return CompletableFuture.completedFuture(request);
    }

    /** Builder for {@link PromptInjectionGuard}. */
    public static final class Builder {
        private List<Pattern> patterns;
        private Action action = Action.BLOCK;
        private Set<String> scanRoles = Set.of("user", "tool");

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder patterns(List<String> regexes) {
            List<Pattern> compiled = new ArrayList<>();
            for (String r : regexes) {
                compiled.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE));
            }
            this.patterns = compiled;
            return this;
        }

        public Builder scanRoles(Set<String> roles) {
            this.scanRoles = Set.copyOf(roles);
            return this;
        }

        public PromptInjectionGuard build() {
            return new PromptInjectionGuard(this);
        }
    }
}
