package io.gavio.platform;

import java.util.List;
import java.util.Map;

/** Verification result for a Platform Runtime Profile. */
public record PlatformRuntimeVerification(
        boolean valid, List<String> errors, String computedHash, Map<String, Object> readiness) {}
