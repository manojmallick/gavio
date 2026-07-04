package io.gavio.interceptors.pii;

/** Detects PII in a non-text modality (images today; audio/video later) — F-SEC-09. */
public interface ModalityScanner {

    String name();

    ModalityScanResult scan(byte[] image);
}
