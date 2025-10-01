package com.example.anticheat.combat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Maintains a rolling window of numeric samples and provides descriptive statistics.
 */
public final class RollingStatistics {
    private static final class Sample {
        private final long timestamp;
        private final double value;

        private Sample(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    private final long windowMillis;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private double sum;
    private double sumSquares;

    public RollingStatistics(Duration window) {
        this.windowMillis = window.toMillis();
    }

    public void addSample(double value, long timestamp) {
        Sample sample = new Sample(timestamp, value);
        samples.addLast(sample);
        sum += value;
        sumSquares += value * value;
        evictOld(timestamp);
    }

    public int size() {
        return samples.size();
    }

    public double mean() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        return sum / samples.size();
    }

    public double variance() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double mean = mean();
        return Math.max(0.0, (sumSquares / samples.size()) - mean * mean);
    }

    public double standardDeviation() {
        return Math.sqrt(variance());
    }

    private void evictOld(long now) {
        long threshold = now - windowMillis;
        while (!samples.isEmpty() && samples.peekFirst().timestamp < threshold) {
            Sample sample = samples.removeFirst();
            sum -= sample.value;
            sumSquares -= sample.value * sample.value;
        }
    }
}
