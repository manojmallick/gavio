package io.gavio.inspector;

/**
 * How much request/response content the inspector captures (F-DX-09).
 *
 * <p>In {@link #METADATA} mode the content-bearing fields (messages, response
 * content, diff bodies) are structurally absent — the emitters never receive
 * the content, so nothing can leak by accident.
 */
public enum CaptureMode {
    /** Full prompts, responses and mutation diffs (from + to). Dev-mode only unless acked. */
    FULL,
    /** Mutation diffs carry only the post-mutation ("to") side; no pre-mutation content. */
    REDACTED,
    /** Timings, names and flags only — no content fields exist at all. */
    METADATA;

    /** Lowercase wire value used in events and HTTP headers. */
    public String wireValue() {
        return name().toLowerCase();
    }

    /** Parse a wire value ("full" | "redacted" | "metadata"), case-insensitive. */
    public static CaptureMode fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
