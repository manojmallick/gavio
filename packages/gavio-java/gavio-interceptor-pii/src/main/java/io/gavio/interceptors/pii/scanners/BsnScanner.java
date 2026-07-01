package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dutch BSN scanner — regex + 11-proef (eleven-test) checksum. */
public final class BsnScanner implements PiiScanner {

    private static final Pattern BSN = Pattern.compile("\\b\\d{9}\\b");
    private static final int[] WEIGHTS = {9, 8, 7, 6, 5, 4, 3, 2, -1};

    @Override
    public String entityType() {
        return "BSN";
    }

    static boolean validBsn(String digits) {
        if (digits.length() != 9) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += (digits.charAt(i) - '0') * WEIGHTS[i];
        }
        return total % 11 == 0;
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = BSN.matcher(text);
        while (m.find()) {
            if (!validBsn(m.group())) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[BSN_" + idx + "]")
                    .build());
        }
        return out;
    }
}
