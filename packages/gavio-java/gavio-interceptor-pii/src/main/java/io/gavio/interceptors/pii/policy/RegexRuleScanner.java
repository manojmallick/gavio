package io.gavio.interceptors.pii.policy;

import io.gavio.interceptors.pii.PiiMatch;
import io.gavio.interceptors.pii.PiiScanner;
import io.gavio.interceptors.pii.ScanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scanner generated from a custom regex policy-pack rule. */
public final class RegexRuleScanner implements PiiScanner {

    private final RegexPolicyRule rule;
    private final Pattern pattern;

    public RegexRuleScanner(RegexPolicyRule rule) {
        this.rule = rule;
        this.pattern = Pattern.compile(rule.pattern());
    }

    @Override
    public String entityType() {
        return rule.entityType();
    }

    @Override
    public List<PiiMatch> scan(String text, ScanContext ctx) {
        List<PiiMatch> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int idx = ctx.nextIndex(entityType());
            String prefix = rule.replacementPrefix() != null
                    ? rule.replacementPrefix() : entityType();
            out.add(PiiMatch.builder()
                    .entityType(entityType())
                    .start(matcher.start())
                    .end(matcher.end())
                    .value(matcher.group())
                    .confidence(rule.confidence())
                    .replacement("[" + prefix + "_" + idx + "]")
                    .build());
        }
        return out;
    }
}
