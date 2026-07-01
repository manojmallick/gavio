package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** US Social Security Number scanner. */
public final class SsnScanner implements PiiScanner {

    // AAA-GG-SSSS with hyphens or spaces. Requires a separator to avoid
    // colliding with bare 9-digit numbers (handled by BsnScanner / others).
    private static final Pattern SSN =
            Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}[ -](?!00)\\d{2}[ -](?!0000)\\d{4}\\b");

    @Override
    public String entityType() {
        return "SSN";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = SSN.matcher(text);
        while (m.find()) {
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[SSN_" + idx + "]")
                    .build());
        }
        return out;
    }
}
