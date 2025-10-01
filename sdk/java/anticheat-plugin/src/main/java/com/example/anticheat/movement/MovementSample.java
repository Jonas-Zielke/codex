package com.example.anticheat.movement;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public final class MovementSample {
    private final Location location;
    private final Vector positionDelta;
    private final Vector velocity;
    private final boolean onGround;
    private final long tick;

    public MovementSample(Location location, Vector positionDelta, Vector velocity, boolean onGround, long tick) {
        this.location = location.clone();
        this.positionDelta = positionDelta.clone();
        this.velocity = velocity.clone();
        this.onGround = onGround;
        this.tick = tick;
    }

    public Location getLocation() {
        return location.clone();
    }

    public Vector getPositionDelta() {
        return positionDelta.clone();
    }

    public Vector getVelocity() {
        return velocity.clone();
    }

    public boolean isOnGround() {
        return onGround;
    }

    public long getTick() {
        return tick;
    }
}
