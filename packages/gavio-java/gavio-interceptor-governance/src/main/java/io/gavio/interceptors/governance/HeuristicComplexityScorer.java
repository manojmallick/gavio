package io.gavio.interceptors.governance;

import io.gavio.PricingProvider;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zero-dependency default {@link ComplexityScorer}: prompt length + reasoning-keyword density. */
public final class HeuristicComplexityScorer implements ComplexityScorer {

    private static final Set<String> REASONING_KEYWORDS = Set.of(
            "why", "because", "compare", "trade-off", "tradeoff", "explain", "analyze",
            "analyse", "evaluate", "design", "architecture", "review", "debug",
            "reasoning", "justify", "critique");

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9-]+");

    @Override
    public double score(String text) {
        int tokens = PricingProvider.estimateTokens(text);
        double lengthScore = Math.min(tokens / 200.0, 1.0) * 0.6;

        Set<String> words = new java.util.HashSet<>();
        Matcher m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            words.add(m.group());
        }
        long keywordHits = words.stream().filter(REASONING_KEYWORDS::contains).count();
        double keywordScore = Math.min(keywordHits / 3.0, 1.0) * 0.4;

        return Math.min(lengthScore + keywordScore, 1.0);
    }
}
