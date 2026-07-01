package io.gavio;

import io.gavio.types.TokenUsage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Token cost tracking (F-GOV-01).
 *
 * <p>Prices are USD per 1,000 tokens, sourced from public provider pricing and
 * overridable. Unknown models price at zero (logged once) rather than guessing.
 */
public final class PricingProvider {

    private static final Logger LOG = Logger.getLogger("gavio.pricing");

    /** model -> [input_per_1k_usd, output_per_1k_usd]. */
    private static final Map<String, double[]> DEFAULT_PRICES = new HashMap<>();

    static {
        // OpenAI
        DEFAULT_PRICES.put("gpt-4o", new double[] {0.0025, 0.010});
        DEFAULT_PRICES.put("gpt-4o-mini", new double[] {0.00015, 0.0006});
        DEFAULT_PRICES.put("o1", new double[] {0.015, 0.060});
        DEFAULT_PRICES.put("o1-mini", new double[] {0.0011, 0.0044});
        // Anthropic
        DEFAULT_PRICES.put("claude-sonnet-4-6", new double[] {0.003, 0.015});
        DEFAULT_PRICES.put("claude-sonnet-4-20250514", new double[] {0.003, 0.015});
        DEFAULT_PRICES.put("claude-haiku-4-5", new double[] {0.0008, 0.004});
        DEFAULT_PRICES.put("claude-opus-4-1", new double[] {0.015, 0.075});
        // Local / mock are free.
        DEFAULT_PRICES.put("mock", new double[] {0.0, 0.0});
    }

    private final Map<String, double[]> prices;
    private final Set<String> warned = new HashSet<>();

    public PricingProvider() {
        this(null);
    }

    public PricingProvider(Map<String, double[]> overrides) {
        this.prices = new HashMap<>(DEFAULT_PRICES);
        if (overrides != null) {
            this.prices.putAll(overrides);
        }
    }

    public void setPrice(String model, double inputPer1k, double outputPer1k) {
        prices.put(model, new double[] {inputPer1k, outputPer1k});
    }

    /** Return [input_per_1k, output_per_1k] for a model, with prefix fallback. */
    public double[] rates(String model) {
        double[] rate = prices.get(model);
        if (rate != null) {
            return rate;
        }
        for (Map.Entry<String, double[]> e : prices.entrySet()) {
            if (model.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        if (warned.add(model)) {
            LOG.warning("no pricing for model '" + model + "'; treating as free");
        }
        return new double[] {0.0, 0.0};
    }

    public double estimate(String model, TokenUsage usage) {
        double[] r = rates(model);
        double cost = (usage.promptTokens() / 1000.0) * r[0];
        cost += (usage.completionTokens() / 1000.0) * r[1];
        return round8(cost);
    }

    private static double round8(double v) {
        return Math.round(v * 1e8) / 1e8;
    }

    /** Rough token estimate (~4 chars/token) for providers without a tokenizer. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
