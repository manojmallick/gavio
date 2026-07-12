package io.gavio.interceptors.pii.policy;

/** Action metadata for a policy-pack detector (F-PACK-01). */
public enum PolicyAction {
    ALLOW("allow"),
    FLAG("flag"),
    REDACT("redact"),
    MASK("mask"),
    HASH("hash"),
    BLOCK("block"),
    ROUTE("route"),
    REQUIRE_APPROVAL("require-approval");

    private final String wireValue;

    PolicyAction(String wireValue) {
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
