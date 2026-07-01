package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** IBAN scanner — regex candidate + ISO 13616 mod-97 checksum validation. */
public final class IbanScanner implements PiiScanner {

    // 2 letters, 2 check digits, 11–30 alphanumerics (optionally spaced).
    private static final Pattern IBAN =
            Pattern.compile("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){11,30}\\b");

    @Override
    public String entityType() {
        return "IBAN";
    }

    static boolean validIban(String candidate) {
        String cleaned = candidate.replace(" ", "").toUpperCase();
        if (cleaned.length() < 15) {
            return false;
        }
        String rearranged = cleaned.substring(4) + cleaned.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < rearranged.length(); i++) {
            char ch = rearranged.charAt(i);
            if (Character.isLetter(ch)) {
                digits.append(ch - 55); // 'A' (65) -> 10
            } else if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                return false;
            }
        }
        try {
            return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = IBAN.matcher(text);
        while (m.find()) {
            if (!validIban(m.group())) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[IBAN_" + idx + "]")
                    .build());
        }
        return out;
    }
}
