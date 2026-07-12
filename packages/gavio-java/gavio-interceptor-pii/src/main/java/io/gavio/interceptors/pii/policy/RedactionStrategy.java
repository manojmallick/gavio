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

    @Override
    public String toString() {
        return wireValue;
    }
}
