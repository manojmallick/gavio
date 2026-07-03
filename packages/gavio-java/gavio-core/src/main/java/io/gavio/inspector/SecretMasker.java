package io.gavio.inspector;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks obvious credentials in inspector-captured content (F-DX-09).
 *
 * <p>These patterns are replicated from the pii module's {@code SecretScanner}
 * (gavio-interceptor-pii) because gavio-core must stay dependency-free and the
 * module dependency points the other way (pii → core). Kept to the core three:
 * API keys ({@code sk-...}), JWTs ({@code eyJ...}) and PEM private-key headers.
 * Keep in sync with {@code io.gavio.interceptors.pii.scanners.SecretScanner}.
 */
public final class SecretMasker {

    private static final String MASK = "***";

    private static final List<Pattern> PATTERNS = List.of(
            // API keys: sk-..., sk-ant-..., sk-proj-... (covers Anthropic + OpenAI shapes).
            Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{16,}\\b"),
            // JWTs: three base64url segments starting with eyJ.
            Pattern.compile("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\b"),
            // PEM private-key headers.
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"));

    private SecretMasker() {
    }

    /** Replace any matched secret span with {@code ***}. Null-safe. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }
}
