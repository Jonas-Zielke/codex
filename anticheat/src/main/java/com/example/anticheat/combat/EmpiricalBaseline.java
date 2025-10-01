package com.example.anticheat.combat;

/**
 * Represents empirically observed percentile ranges for a combat metric.
 */
public final class EmpiricalBaseline {
    private final double lowerPercentile;
    private final double upperPercentile;
    private final double warmupGrace;

    public EmpiricalBaseline(double lowerPercentile, double upperPercentile) {
        this(lowerPercentile, upperPercentile, 0.0);
    }

    public EmpiricalBaseline(double lowerPercentile, double upperPercentile, double warmupGrace) {
        if (lowerPercentile > upperPercentile) {
            throw new IllegalArgumentException("Lower percentile cannot exceed upper percentile");
        }
        this.lowerPercentile = lowerPercentile;
        this.upperPercentile = upperPercentile;
        this.warmupGrace = warmupGrace;
    }

    public double getLowerPercentile() {
        return lowerPercentile;
    }

    public double getUpperPercentile() {
        return upperPercentile;
    }

    public double getWarmupGrace() {
        return warmupGrace;
    }

    public boolean isWithin(double value) {
        return value >= lowerPercentile && value <= upperPercentile;
    }
}
