package com.example.anticheat.combat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal facade that would typically interface with the broader punishment system.
 */
public final class PunishmentManager {
    public static final class ViolationRecord {
        private final String playerName;
        private final String reason;
        private final double severity;
        private final Instant timestamp;

        private ViolationRecord(String playerName, String reason, double severity, Instant timestamp) {
            this.playerName = playerName;
            this.reason = reason;
            this.severity = severity;
            this.timestamp = timestamp;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getReason() {
            return reason;
        }

        public double getSeverity() {
            return severity;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }

    private final List<ViolationRecord> records = new LinkedList<>();

    public synchronized void flagSuspect(String playerName, String reason, double severity) {
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(reason, "reason");
        records.add(new ViolationRecord(playerName, reason, severity, Instant.now()));
    }

    public synchronized List<ViolationRecord> getRecords() {
        return Collections.unmodifiableList(new LinkedList<>(records));
    }
}
