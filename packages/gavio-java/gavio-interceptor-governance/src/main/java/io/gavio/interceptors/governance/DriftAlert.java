package io.gavio.interceptors.governance;

import java.util.Map;

/**
 * One drift detection (F-GOV-07).
 *
 * @param baseline the rolling baseline: {@code {mean, std, n}}
 * @param z standard scores from the baseline mean; {@code null} when the
 *     baseline had zero variance
 */
public record DriftAlert(
        String metric, double value, Map<String, Object> baseline, Double z, double threshold) {}
