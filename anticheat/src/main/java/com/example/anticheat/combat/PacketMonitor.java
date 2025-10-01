package com.example.anticheat.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Observes combat related packets to compute rolling statistics and trigger anti-cheat heuristics.
 */
public final class PacketMonitor {
    public enum Metric {
        ROTATIONS_PER_SECOND,
        ROTATION_VARIANCE,
        CLICKS_PER_SECOND,
        PERFECT_YAW_LOCK,
        CONSTANT_CPS
    }

    private static final Logger LOGGER = Logger.getLogger(PacketMonitor.class.getName());
    private static final Duration ROLLING_WINDOW = Duration.ofSeconds(6);
    private static final Duration VIOLATION_DECAY = Duration.ofSeconds(45);
    private static final int TRACE_CAPACITY = 256;
    private static final double YAW_LOCK_EPSILON = 0.05D;
    private static final int YAW_LOCK_WINDOW = 10;
    private static final int CONSTANT_CPS_WINDOW = 12;

    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<Metric, EmpiricalBaseline> baselines = new EnumMap<>(Metric.class);
    private final Map<Metric, ViolationBuffer> violationBuffers = new EnumMap<>(Metric.class);
    private final PunishmentManager punishmentManager;

    public PacketMonitor(PunishmentManager punishmentManager) {
        this.punishmentManager = Objects.requireNonNull(punishmentManager, "punishmentManager");
        baselines.put(Metric.ROTATIONS_PER_SECOND, new EmpiricalBaseline(0.3, 4.8, 1.0));
        baselines.put(Metric.ROTATION_VARIANCE, new EmpiricalBaseline(0.1, 120.0, 1.0));
        baselines.put(Metric.CLICKS_PER_SECOND, new EmpiricalBaseline(3.0, 13.5, 1.0));
        violationBuffers.put(Metric.ROTATIONS_PER_SECOND,
                new ViolationBuffer(6.0, VIOLATION_DECAY, punishmentManager));
        violationBuffers.put(Metric.ROTATION_VARIANCE,
                new ViolationBuffer(6.0, VIOLATION_DECAY, punishmentManager));
        violationBuffers.put(Metric.CLICKS_PER_SECOND,
                new ViolationBuffer(6.0, VIOLATION_DECAY, punishmentManager));
        violationBuffers.put(Metric.PERFECT_YAW_LOCK,
                new ViolationBuffer(4.0, VIOLATION_DECAY, punishmentManager));
        violationBuffers.put(Metric.CONSTANT_CPS,
                new ViolationBuffer(4.0, VIOLATION_DECAY, punishmentManager));
    }

    public void recordRotation(UUID playerId, String playerName, float yaw, float pitch, long timestamp) {
        handlePacket(playerId, playerName, PacketType.ROTATION, yaw, pitch, timestamp);
    }

    public void recordSwing(UUID playerId, String playerName, float yaw, float pitch, long timestamp) {
        handlePacket(playerId, playerName, PacketType.SWING, yaw, pitch, timestamp);
    }

    public void recordAttack(UUID playerId, String playerName, float yaw, float pitch, long timestamp) {
        handlePacket(playerId, playerName, PacketType.ATTACK, yaw, pitch, timestamp);
    }

    public List<TraceEntry> dumpTraces(UUID playerId) {
        SessionState session = sessions.get(playerId);
        if (session == null) {
            return Collections.emptyList();
        }
        return session.getTraceEntries();
    }

    private void handlePacket(UUID playerId, String playerName, PacketType type, float yaw, float pitch,
            long timestamp) {
        SessionState session = sessions.computeIfAbsent(playerId,
                ignored -> new SessionState(playerId, playerName));
        SessionSnapshot snapshot = session.record(type, yaw, pitch, timestamp);
        logPacket(session, type, snapshot);
        evaluateBaselines(session, snapshot);
        evaluateHeuristics(session);
    }

    private void logPacket(SessionState session, PacketType type, SessionSnapshot snapshot) {
        if (!LOGGER.isLoggable(Level.FINE)) {
            return;
        }
        LOGGER.log(Level.FINE,
                () -> String.format(
                        "[%s] %s %s yawΔ=%.3f pitchΔ=%.3f interval=%dms rps=%.2f(mean=%.2f) cps=%.2f(mean=%.2f)",
                        session.playerName, type, session.playerId, snapshot.yawDelta, snapshot.pitchDelta,
                        snapshot.intervalMillis, snapshot.instantRotationsPerSecond,
                        snapshot.meanRotationsPerSecond, snapshot.instantCps, snapshot.meanCps));
    }

    private void evaluateBaselines(SessionState session, SessionSnapshot snapshot) {
        long timestamp = snapshot.timestamp.toEpochMilli();
        checkAgainstBaseline(session, Metric.ROTATIONS_PER_SECOND, snapshot.meanRotationsPerSecond, timestamp,
                "mean rotation speed outside empirical range");
        checkAgainstBaseline(session, Metric.ROTATION_VARIANCE, snapshot.rotationVariance, timestamp,
                "rotation variance outside empirical range");
        if (snapshot.meanCps > 0) {
            checkAgainstBaseline(session, Metric.CLICKS_PER_SECOND, snapshot.meanCps, timestamp,
                    "mean clicks per second outside empirical range");
        }
    }

    private void checkAgainstBaseline(SessionState session, Metric metric, double value, long timestamp,
            String reason) {
        EmpiricalBaseline baseline = baselines.get(metric);
        if (baseline == null) {
            return;
        }
        if (session.rollingAgeSeconds < baseline.getWarmupGrace()) {
            return;
        }
        if (baseline.isWithin(value)) {
            session.resetBaselineViolations(metric);
            return;
        }
        int strikes = session.incrementBaselineViolations(metric);
        if (strikes >= 3) {
            ViolationBuffer buffer = violationBuffers.get(metric);
            if (buffer != null) {
                buffer.addViolation(session.playerName, strikes, timestamp, reason);
            }
        }
    }

    private void evaluateHeuristics(SessionState session) {
        long timestamp = session.lastPacketTimestamp;
        if (detectPerfectYawLock(session)) {
            ViolationBuffer buffer = violationBuffers.get(Metric.PERFECT_YAW_LOCK);
            if (buffer != null) {
                buffer.addViolation(session.playerName, 1.5, timestamp, "sustained perfect yaw lock");
            }
        }
        if (detectConstantCps(session)) {
            ViolationBuffer buffer = violationBuffers.get(Metric.CONSTANT_CPS);
            if (buffer != null) {
                buffer.addViolation(session.playerName, 1.5, timestamp, "constant CPS pattern");
            }
        }
    }

    private boolean detectPerfectYawLock(SessionState session) {
        if (session.yawLockWindow.size() < YAW_LOCK_WINDOW) {
            return false;
        }
        for (double delta : session.yawLockWindow) {
            if (Math.abs(delta) > YAW_LOCK_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private boolean detectConstantCps(SessionState session) {
        if (session.attackIntervals.size() < CONSTANT_CPS_WINDOW) {
            return false;
        }
        OptionalDouble meanOptional = session.attackIntervals.stream()
                .mapToLong(Long::longValue)
                .average();
        if (meanOptional.isEmpty()) {
            return false;
        }
        double meanInterval = meanOptional.getAsDouble();
        double variance = 0.0;
        for (long interval : session.attackIntervals) {
            double delta = interval - meanInterval;
            variance += delta * delta;
        }
        variance /= session.attackIntervals.size();
        double stdDeviation = Math.sqrt(variance);
        double cps = meanInterval <= 0 ? 0 : 1000.0 / meanInterval;
        return cps >= 8.0 && cps <= 15.0 && stdDeviation <= 8.0;
    }

    private static final class SessionSnapshot {
        private final UUID playerId;
        private final Instant timestamp;
        private final double yawDelta;
        private final double pitchDelta;
        private final long intervalMillis;
        private final double instantRotationsPerSecond;
        private final double meanRotationsPerSecond;
        private final double rotationVariance;
        private final double instantCps;
        private final double meanCps;

        private SessionSnapshot(UUID playerId, Instant timestamp, double yawDelta, double pitchDelta,
                long intervalMillis, double instantRotationsPerSecond, double meanRotationsPerSecond,
                double rotationVariance, double instantCps, double meanCps) {
            this.playerId = playerId;
            this.timestamp = timestamp;
            this.yawDelta = yawDelta;
            this.pitchDelta = pitchDelta;
            this.intervalMillis = intervalMillis;
            this.instantRotationsPerSecond = instantRotationsPerSecond;
            this.meanRotationsPerSecond = meanRotationsPerSecond;
            this.rotationVariance = rotationVariance;
            this.instantCps = instantCps;
            this.meanCps = meanCps;
        }
    }

    private static final class SessionState {
        private final UUID playerId;
        private final String playerName;
        private final RollingStatistics rotationSpeedStats = new RollingStatistics(ROLLING_WINDOW);
        private final RollingStatistics yawDeltaStats = new RollingStatistics(ROLLING_WINDOW);
        private final RollingStatistics cpsStats = new RollingStatistics(ROLLING_WINDOW);
        private final ArrayDeque<TraceEntry> traces = new ArrayDeque<>(TRACE_CAPACITY);
        private final ArrayDeque<Double> yawLockWindow = new ArrayDeque<>();
        private final ArrayDeque<Long> attackIntervals = new ArrayDeque<>();
        private final Map<Metric, Integer> baselineViolations = new EnumMap<>(Metric.class);

        private long lastPacketTimestamp;
        private long lastAttackTimestamp;
        private double lastYaw;
        private double lastPitch;
        private boolean hasRotation;
        private double rollingAgeSeconds;

        private SessionState(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }

        private SessionSnapshot record(PacketType type, float yaw, float pitch, long timestamp) {
            Instant instant = Instant.ofEpochMilli(timestamp);
            long interval = lastPacketTimestamp == 0 ? 0 : timestamp - lastPacketTimestamp;
            double deltaSeconds = interval <= 0 ? 0.0 : interval / 1000.0;
            double yawDelta = hasRotation ? angleDelta(yaw, lastYaw) : 0.0;
            double pitchDelta = hasRotation ? angleDelta(pitch, lastPitch) : 0.0;
            if (!hasRotation) {
                lastYaw = yaw;
                lastPitch = pitch;
                hasRotation = true;
            }
            lastPacketTimestamp = timestamp;
            lastYaw = yaw;
            lastPitch = pitch;
            if (interval > 0) {
                rollingAgeSeconds += Math.min(4.0, interval / 1000.0);
            }

            yawLockWindow.addLast(yawDelta);
            if (yawLockWindow.size() > YAW_LOCK_WINDOW) {
                yawLockWindow.removeFirst();
            }

            double instantRotationsPerSecond = deltaSeconds <= 0 ? 0.0 : Math.abs(yawDelta) / deltaSeconds;
            rotationSpeedStats.addSample(instantRotationsPerSecond, timestamp);
            yawDeltaStats.addSample(Math.abs(yawDelta), timestamp);

            double meanRotationsPerSecond = rotationSpeedStats.mean();
            double rotationVariance = rotationSpeedStats.variance() + yawDeltaStats.variance();

            double instantCps = 0.0;
            if (type == PacketType.ATTACK || type == PacketType.SWING) {
                if (lastAttackTimestamp > 0) {
                    long attackInterval = timestamp - lastAttackTimestamp;
                    attackIntervals.addLast(attackInterval);
                    if (attackIntervals.size() > CONSTANT_CPS_WINDOW) {
                        attackIntervals.removeFirst();
                    }
                    if (attackInterval > 0) {
                        instantCps = 1000.0 / attackInterval;
                        cpsStats.addSample(instantCps, timestamp);
                    }
                }
                lastAttackTimestamp = timestamp;
            }
            double meanCps = cpsStats.mean();
            if (instantCps == 0.0 && meanCps > 0) {
                instantCps = meanCps;
            }

            TraceEntry trace = new TraceEntry(instant, type, yawDelta, pitchDelta, interval,
                    instantRotationsPerSecond, meanRotationsPerSecond, instantCps, meanCps);
            traces.addLast(trace);
            if (traces.size() > TRACE_CAPACITY) {
                traces.removeFirst();
            }

            return new SessionSnapshot(playerId, instant, yawDelta, pitchDelta, interval,
                    instantRotationsPerSecond, meanRotationsPerSecond, rotationVariance, instantCps, meanCps);
        }

        private List<TraceEntry> getTraceEntries() {
            return Collections.unmodifiableList(new ArrayList<>(traces));
        }

        private void resetBaselineViolations(Metric metric) {
            baselineViolations.remove(metric);
        }

        private int incrementBaselineViolations(Metric metric) {
            return baselineViolations.merge(metric, 1, Integer::sum);
        }

        private static double angleDelta(double current, double previous) {
            double delta = current - previous;
            while (delta <= -180.0) {
                delta += 360.0;
            }
            while (delta > 180.0) {
                delta -= 360.0;
            }
            return delta;
        }
    }
}
