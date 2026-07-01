package io.gavio.interceptors.pii;

import java.util.List;

/**
 * Detects one class of PII entity within text.
 *
 * <p>Scanners are tiered: tier 1 = regex, tier 2 = NER/ML, tier 3 = LLM. Lower
 * tiers run first. v0.1.0 ships only tier-1 regex scanners.
 */
public interface PiiScanner {

    /** e.g. "EMAIL", "IBAN", "BSN". */
    String entityType();

    /** 1=regex, 2=NER/ML, 3=LLM. Lower tiers run first. */
    default int tier() {
        return 1;
    }

    List<PiiMatch> scan(String text, ScanContext ctx);

    default double confidence() {
        return 1.0;
    }

    default boolean supportsLanguage(String lang) {
        return true;
    }

    default boolean supportsLocale(String locale) {
        return true;
    }
}
