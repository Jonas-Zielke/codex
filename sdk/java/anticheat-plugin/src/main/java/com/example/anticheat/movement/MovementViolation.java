package com.example.anticheat.movement;

public final class MovementViolation {
    private final double severity;
    private final String reason;
    private final boolean hardReject;

    public MovementViolation(double severity, String reason, boolean hardReject) {
        this.severity = severity;
        this.reason = reason;
        this.hardReject = hardReject;
    }

    public double getSeverity() {
        return severity;
    }

    public String getReason() {
        return reason;
    }

    public boolean isHardReject() {
        return hardReject;
    }
}
