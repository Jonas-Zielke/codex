package com.example.anticheat.combat;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple violation buffer that decays over time and notifies the punishment manager when the
 * severity crosses the configured threshold.
 */
public final class ViolationBuffer {
    private final double threshold;
    private final double decayPerSecond;
    private final PunishmentManager punishmentManager;
    private final AtomicReference<Long> lastUpdate = new AtomicReference<>();

    private double level;

    public ViolationBuffer(double threshold, Duration decayInterval, PunishmentManager punishmentManager) {
        this.threshold = threshold;
        this.decayPerSecond = threshold <= 0 ? 0 : threshold / Math.max(1.0, decayInterval.toSeconds());
        this.punishmentManager = Objects.requireNonNull(punishmentManager, "punishmentManager");
    }

    public synchronized void addViolation(String playerName, double amount, long timestamp, String reason) {
        decay(timestamp);
        level += amount;
        if (level >= threshold) {
            punishmentManager.flagSuspect(playerName, reason, level);
            level = threshold * 0.5; // provide some hysteresis to avoid constant firing
        }
    }

    public synchronized double getLevel(long timestamp) {
        decay(timestamp);
        return level;
    }

    private void decay(long timestamp) {
        Long previous = lastUpdate.getAndSet(timestamp);
        if (previous == null || decayPerSecond <= 0) {
            return;
        }
        long deltaMillis = Math.max(0, timestamp - previous);
        double decayAmount = (deltaMillis / 1000.0) * decayPerSecond;
        level = Math.max(0.0, level - decayAmount);
    }
}
