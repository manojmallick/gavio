package io.gavio.types;

/** Detection strictness — controls the per-match confidence floor. */
public enum Sensitivity {
    STRICT("strict"),
    BALANCED("balanced"),
    PERMISSIVE("permissive");

    private final String value;

    Sensitivity(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Sensitivity coerce(Object value) {
        if (value instanceof Sensitivity s) {
            return s;
        }
        String str = String.valueOf(value).toLowerCase();
        for (Sensitivity s : values()) {
            if (s.value.equals(str)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown Sensitivity: " + value);
    }
}
