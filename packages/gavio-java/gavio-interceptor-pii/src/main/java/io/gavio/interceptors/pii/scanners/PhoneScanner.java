package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phone number scanner — E.164 and common national formats. */
public final class PhoneScanner implements PiiScanner {

    // E.164 (+CC...) or national groupings with separators. Java has no
    // lookbehind for \w-class shorthand inside the original Python pattern, so
    // we use explicit character classes.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.])(?:\\+?\\d{1,3}[ .\\-]?)?(?:\\(\\d{1,4}\\)[ .\\-]?)?"
                    + "\\d{2,4}(?:[ .\\-]?\\d{2,4}){2,4}(?![\\w])");

    private final List<String> locales;

    public PhoneScanner() {
        this(null);
    }

    public PhoneScanner(List<String> locales) {
        this.locales = locales != null ? List.copyOf(locales) : Arrays.asList("NL", "DE", "GB", "US");
    }

    @Override
    public String entityType() {
        return "PHONE";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String g = m.group();
            long digitCount = g.chars().filter(Character::isDigit).count();
            if (digitCount < 7 || digitCount > 15) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(g)
                    .confidence(0.85)
                    .replacement("[PHONE_" + idx + "]")
                    .build());
        }
        return out;
    }

    @Override
    public boolean supportsLocale(String locale) {
        return locales.contains(locale.toUpperCase());
    }
}
