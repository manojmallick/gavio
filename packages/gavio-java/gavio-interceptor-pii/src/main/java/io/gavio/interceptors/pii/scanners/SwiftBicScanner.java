package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SWIFT/BIC scanner — context-gated (FinTech pack).
 *
 * <p>Matches an 8- or 11-character BIC only when explicitly labelled
 * {@code SWIFT}/{@code BIC}, so ordinary 8-letter uppercase words never trigger
 * a false positive. Group 1 is the code.
 */
public final class SwiftBicScanner implements PiiScanner {

    private static final Pattern SWIFT = Pattern.compile(
            "\\b(?:[Ss][Ww][Ii][Ff][Tt]|[Bb][Ii][Cc])(?:\\s+[Cc]ode)?\\s*[:#]?\\s*"
                    + "([A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b");

    @Override
    public String entityType() {
        return "SWIFT_BIC";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = SWIFT.matcher(text);
        while (m.find()) {
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start(1))
                    .end(m.end(1))
                    .value(m.group(1))
                    .replacement("[SWIFT_BIC_" + idx + "]")
                    .build());
        }
        return out;
    }
}
