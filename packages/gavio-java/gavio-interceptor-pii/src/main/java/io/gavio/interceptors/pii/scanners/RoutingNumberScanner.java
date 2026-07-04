package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** US ABA routing-number scanner — 9 digits + mod-10 checksum (FinTech pack). */
public final class RoutingNumberScanner implements PiiScanner {

    private static final Pattern ROUTING = Pattern.compile("\\b\\d{9}\\b");
    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1};

    /** ABA checksum: weighted digit sum must be a non-zero multiple of 10. */
    static boolean validRoutingNumber(String candidate) {
        if (candidate.length() != 9) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += WEIGHTS[i] * (c - '0');
        }
        return sum > 0 && sum % 10 == 0;
    }

    @Override
    public String entityType() {
        return "ROUTING_NUMBER";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = ROUTING.matcher(text);
        while (m.find()) {
            if (!validRoutingNumber(m.group())) {
                continue;
            }
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[ROUTING_NUMBER_" + idx + "]")
                    .build());
        }
        return out;
    }
}
