package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Credit card scanner — regex candidate + Luhn checksum validation. */
public final class CreditCardScanner implements PiiScanner {

    // 13–19 digits, optionally separated by single spaces or hyphens.
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    @Override
    public String entityType() {
        return "CREDIT_CARD";
    }

    static boolean luhnValid(String number) {
        List<Integer> digits = new ArrayList<>();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (Character.isDigit(c)) {
                digits.add(c - '0');
            }
        }
        int n = digits.size();
        if (n < 13 || n > 19) {
            return false;
        }
        int checksum = 0;
        int parity = n % 2;
        for (int i = 0; i < n; i++) {
            int d = digits.get(i);
            if (i % 2 == parity) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            checksum += d;
        }
        return checksum % 10 == 0;
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            if (!luhnValid(m.group())) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[CREDIT_CARD_" + idx + "]")
                    .build());
        }
        return out;
    }
}
