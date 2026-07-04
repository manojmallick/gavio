package io.gavio.interceptors.governance;

import java.util.List;
import java.util.Map;

/** Pluggable drift detector: fed per-request samples, returns any alerts (F-GOV-07). */
public interface DriftDetector {

    String name();

    List<DriftAlert> observe(Map<String, Double> sample);
}
