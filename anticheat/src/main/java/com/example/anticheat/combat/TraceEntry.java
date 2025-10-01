package com.example.anticheat.combat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Snapshot of a processed packet for developer diagnostics.
 */
public final class TraceEntry {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final Instant timestamp;
    private final PacketType packetType;
    private final double yawDelta;
    private final double pitchDelta;
    private final long intervalMillis;
    private final double instantRotationsPerSecond;
    private final double meanRotationsPerSecond;
    private final double instantCps;
    private final double meanCps;

    TraceEntry(Instant timestamp, PacketType packetType, double yawDelta, double pitchDelta,
            long intervalMillis, double instantRotationsPerSecond, double meanRotationsPerSecond,
            double instantCps, double meanCps) {
        this.timestamp = timestamp;
        this.packetType = packetType;
        this.yawDelta = yawDelta;
        this.pitchDelta = pitchDelta;
        this.intervalMillis = intervalMillis;
        this.instantRotationsPerSecond = instantRotationsPerSecond;
        this.meanRotationsPerSecond = meanRotationsPerSecond;
        this.instantCps = instantCps;
        this.meanCps = meanCps;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public PacketType getPacketType() {
        return packetType;
    }

    public double getYawDelta() {
        return yawDelta;
    }

    public double getPitchDelta() {
        return pitchDelta;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public double getInstantRotationsPerSecond() {
        return instantRotationsPerSecond;
    }

    public double getMeanRotationsPerSecond() {
        return meanRotationsPerSecond;
    }

    public double getInstantCps() {
        return instantCps;
    }

    public double getMeanCps() {
        return meanCps;
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] %s yawΔ=%.3f pitchΔ=%.3f interval=%dms rps=%.2f(mean=%.2f) cps=%.2f(mean=%.2f)",
                FORMATTER.format(timestamp), packetType, yawDelta, pitchDelta, intervalMillis,
                instantRotationsPerSecond, meanRotationsPerSecond, instantCps, meanCps);
    }
}
