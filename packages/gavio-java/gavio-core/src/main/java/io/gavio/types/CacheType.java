package io.gavio.types;

/** Kind of cache hit recorded on a response. */
public enum CacheType {
    EXACT("exact"),
    SEMANTIC("semantic");

    private final String value;

    CacheType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
