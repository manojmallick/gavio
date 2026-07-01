package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gavio.types.TokenUsage;
import org.junit.jupiter.api.Test;

class PricingTest {

    @Test
    void knownAndUnknownModels() {
        PricingProvider p = new PricingProvider();
        TokenUsage usage = new TokenUsage(1000, 1000);
        double cost = p.estimate("gpt-4o", usage);
        assertEquals(Math.round((0.0025 + 0.010) * 1e8) / 1e8, cost, 1e-9);
        assertEquals(0.0, p.estimate("totally-unknown-model", usage), 1e-9);
    }

    @Test
    void prefixMatch() {
        PricingProvider p = new PricingProvider();
        // "gpt-4o-2024-..." should fall back to the "gpt-4o" prefix rate.
        TokenUsage usage = new TokenUsage(1000, 0);
        assertEquals(0.0025, p.estimate("gpt-4o-2024-08-06", usage), 1e-9);
    }

    @Test
    void estimateTokens() {
        assertEquals(0, PricingProvider.estimateTokens(""));
        assertEquals(10, PricingProvider.estimateTokens("abcd".repeat(10)));
    }
}
