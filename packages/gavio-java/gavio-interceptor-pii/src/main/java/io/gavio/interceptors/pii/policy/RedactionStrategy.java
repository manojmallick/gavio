package io.gavio.interceptors.pii.policy;

/** Redaction strategy metadata for a policy-pack detector (F-PACK-01). */
public enum RedactionStrategy {
    TOKENIZE("tokenize"),
    MASK("mask"),
    HASH("hash"),
    REDACT("redact");

    private final String wireValue;

    RedactionStrategy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static RedactionStrategy fromWire(String value) {
        for (RedactionStrategy strategy : values()) {
            if (strategy.wireValue.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("unknown redaction strategy: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
