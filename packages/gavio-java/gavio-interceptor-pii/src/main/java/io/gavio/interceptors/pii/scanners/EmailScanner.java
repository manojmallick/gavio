package io.gavio.interceptors.pii.scanners;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Email address scanner (RFC 5322 pragmatic subset). */
public final class EmailScanner implements PiiScanner {

    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

    @Override
    public String entityType() {
        return "EMAIL";
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            int idx = ctx.nextIndex(entityType());
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(m.start())
                    .end(m.end())
                    .value(m.group())
                    .replacement("[EMAIL_" + idx + "]")
                    .build());
        }
        return out;
    }
}
