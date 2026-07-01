package io.gavio.types;

/** What PiiGuard does with a detected entity. */
public enum PiiMode {
    /** Replace with a typed placeholder token. */
    REDACT("redact"),
    /** Replace characters with asterisks. */
    MASK("mask"),
    /** Annotate inline but keep the value. */
    TAG("tag"),
    /** Raise and refuse the request. */
    BLOCK("block");

    private final String value;

    PiiMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PiiMode coerce(Object value) {
        if (value instanceof PiiMode m) {
            return m;
        }
        String s = String.valueOf(value).toLowerCase();
        for (PiiMode m : values()) {
            if (m.value.equals(s)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown PiiMode: " + value);
    }
}
