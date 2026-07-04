package io.gavio.interceptors.pii;

import java.util.List;

/**
 * Result of a {@link ModalityScanner} (F-SEC-09).
 *
 * @param text OCR-extracted text (empty when none); fed to the text PII scanners
 * @param entityTypes direct detections, e.g. {@code ["FACE"]} from face detection
 */
public record ModalityScanResult(String text, List<String> entityTypes) {
    public ModalityScanResult {
        text = text == null ? "" : text;
        entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
    }
}
