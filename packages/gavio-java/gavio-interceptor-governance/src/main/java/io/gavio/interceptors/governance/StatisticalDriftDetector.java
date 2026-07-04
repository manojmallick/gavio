package io.gavio.interceptors.governance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rolling-window z-score detector — the default {@link DriftDetector} (F-GOV-07). */
public final class StatisticalDriftDetector implements DriftDetector {

    private final int windowSize;
    private final int minSamples;
    private final double threshold;
    private final Map<String, Deque<Double>> windows = new HashMap<>();

    public StatisticalDriftDetector() {
        this(50, 50, 3.0);
    }

    public StatisticalDriftDetector(int windowSize, int minSamples, double threshold) {
        this.windowSize = windowSize;
        this.minSamples = minSamples;
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return "statistical";
    }

    @Override
    public synchronized List<DriftAlert> observe(Map<String, Double> sample) {
        List<DriftAlert> alerts = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sample.entrySet()) {
            String metric = entry.getKey();
            double value = entry.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            Deque<Double> window = windows.computeIfAbsent(metric, k -> new ArrayDeque<>());
            if (window.size() >= minSamples) {
                double mean = mean(window);
                double std = pstd(window, mean);
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("mean", round4(mean));
                baseline.put("std", round4(std));
                baseline.put("n", window.size());
                if (std > 0) {
                    double z = (value - mean) / std;
                    if (Math.abs(z) > threshold) {
                        alerts.add(new DriftAlert(metric, value, baseline, round4(z), threshold));
                    }
                } else if (value != mean) {
                    alerts.add(new DriftAlert(metric, value, baseline, null, threshold));
                }
            }
            window.addLast(value);
            if (window.size() > windowSize) {
                window.removeFirst();
            }
        }
        return alerts;
    }

    private static double mean(Deque<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double pstd(Deque<Double> values, double mean) {
        double sq = 0.0;
        for (double v : values) {
            sq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sq / values.size());
    }

    private static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
