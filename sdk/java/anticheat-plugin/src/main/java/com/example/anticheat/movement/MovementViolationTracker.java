package com.example.anticheat.movement;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public final class MovementViolationTracker {
    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final int decaySeconds;
    private final int maxBuffer;

    public MovementViolationTracker(int decaySeconds, int maxBuffer) {
        this.decaySeconds = decaySeconds;
        this.maxBuffer = maxBuffer;
    }

    public void addViolation(MovementViolation violation) {
        buffer.addLast(new Entry(Instant.now().getEpochSecond(), violation));
        purgeExpired();
        while (buffer.size() > maxBuffer) {
            buffer.removeFirst();
        }
    }

    public int getViolationCount() {
        purgeExpired();
        return buffer.size();
    }

    public MovementViolation getMostRecentViolation() {
        return buffer.peekLast() == null ? null : buffer.peekLast().violation();
    }

    public void clear() {
        buffer.clear();
    }

    private void purgeExpired() {
        long cutoff = Instant.now().getEpochSecond() - decaySeconds;
        while (!buffer.isEmpty() && buffer.peekFirst().timestamp() < cutoff) {
            buffer.removeFirst();
        }
    }

    private record Entry(long timestamp, MovementViolation violation) {
    }
}
