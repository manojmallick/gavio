package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret / credential scanner (F-SEC-04).
 *
 * <p>Detects API keys, tokens, JWTs, PEM private keys and DB connection
 * strings. Tier 1 and on by default — these must never leave the device.
 */
public final class SecretScanner implements PiiScanner {

    private record NamedPattern(String label, Pattern pattern) {
    }

    // Ordered most-specific first.
    private static final List<NamedPattern> PATTERNS = List.of(
            new NamedPattern("ANTHROPIC_KEY", Pattern.compile("\\bsk-ant-[A-Za-z0-9_\\-]{20,}\\b")),
            new NamedPattern("OPENAI_KEY", Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_\\-]{20,}\\b")),
            new NamedPattern("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new NamedPattern("GITHUB_TOKEN", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            new NamedPattern("JWT", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\b")),
            new NamedPattern("PRIVATE_KEY", Pattern.compile(
                    "-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
            new NamedPattern("DB_CONNECTION_STRING", Pattern.compile(
                    "\\b(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis)://[^\\s\"']+")));

    @Override
    public String entityType() {
        return "SECRET";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        for (NamedPattern np : PATTERNS) {
            Matcher m = np.pattern().matcher(text);
            while (m.find()) {
                int idx = ctx.nextIndex(entityType());
                out.add(PiiMatch.builder()
                        .entityType(entityType())
                        .start(m.start())
                        .end(m.end())
                        .value(m.group())
                        .replacement("[SECRET_" + idx + "]")
                        .build());
            }
        }
        return out;
    }
}
