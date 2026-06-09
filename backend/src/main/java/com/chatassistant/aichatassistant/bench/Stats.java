package com.chatassistant.aichatassistant.bench;

import java.util.Arrays;

/**
 * Simple descriptive stats over a small sample (typically n = 3 iterations per (model, prompt)).
 *
 * Uses the sample standard deviation (Bessel's correction). With n = 3 the stddev itself has
 * high variance — treat it as a rough spread indicator, not a tight confidence bound.
 */
public record Stats(int n, double mean, double stddev, double min, double max, double median) {

    public static Stats of(double[] values) {
        if (values == null || values.length == 0) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / values.length;
        double sq = 0;
        for (double v : values) sq += (v - mean) * (v - mean);
        double stddev = values.length > 1 ? Math.sqrt(sq / (values.length - 1)) : 0.0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        return new Stats(values.length, mean, stddev, min, max, median);
    }
}
