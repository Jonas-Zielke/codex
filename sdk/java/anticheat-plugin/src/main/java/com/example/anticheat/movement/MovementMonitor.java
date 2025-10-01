package com.example.anticheat.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.Optional;

public final class MovementMonitor {
    private final MovementConfig config;

    public MovementMonitor(MovementConfig config) {
        this.config = config;
    }

    public Optional<MovementViolation> evaluate(MovementSample current, MovementSample previous) {
        if (previous == null) {
            return Optional.empty();
        }

        double horizontalExpected = expectedHorizontal(previous);
        double horizontalObserved = horizontalObserved(current.getPositionDelta());
        double horizontalAllowance = Math.max(config.getMinimumHorizontalAllowance(), horizontalExpected * (1 + config.getHorizontalTolerancePercent() / 100.0));
        if (collided(current.getLocation())) {
            horizontalAllowance += config.getCollisionHorizontalBonus();
        }
        double horizontalExcess = horizontalObserved - horizontalAllowance;

        double verticalExpected = expectedVertical(previous);
        double verticalObserved = current.getPositionDelta().getY();
        double verticalAllowance = Math.max(config.getMinimumVerticalAllowance(), Math.abs(verticalExpected) * (1 + config.getVerticalTolerancePercent() / 100.0));
        if (collided(current.getLocation())) {
            verticalAllowance += config.getCollisionVerticalBonus();
        }
        double verticalExcess = Math.abs(verticalObserved - verticalExpected) - verticalAllowance;

        if (horizontalExcess <= 0 && verticalExcess <= 0) {
            return Optional.empty();
        }

        double severity = Math.max(horizontalExcess, verticalExcess);
        StringBuilder reason = new StringBuilder();
        if (horizontalExcess > 0) {
            reason.append(String.format("Horizontal excess %.3f", horizontalExcess));
        }
        if (verticalExcess > 0) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append(String.format("Vertical deviation %.3f", verticalExcess));
        }
        boolean hardReject = horizontalExcess > config.getMinimumHorizontalAllowance() * 4 || verticalExcess > config.getMinimumVerticalAllowance() * 4;
        return Optional.of(new MovementViolation(severity, reason.toString(), hardReject));
    }

    private double expectedHorizontal(MovementSample previous) {
        Vector velocity = previous.getVelocity();
        Vector horizontal = velocity.clone();
        horizontal.setY(0);
        double speed = horizontal.length();
        if (previous.isOnGround()) {
            speed *= config.getGroundFriction();
        } else {
            speed *= config.getAirDrag();
        }
        return speed;
    }

    private double horizontalObserved(Vector delta) {
        Vector horizontal = delta.clone();
        horizontal.setY(0);
        return horizontal.length();
    }

    private double expectedVertical(MovementSample previous) {
        double velocityY = previous.getVelocity().getY();
        double expected = velocityY - config.getGravity();
        if (previous.isOnGround() && velocityY <= 0) {
            expected = 0;
        }
        return expected;
    }

    private boolean collided(Location location) {
        Block block = location.getBlock();
        if (block == null) {
            return false;
        }
        if (block.getType().isSolid()) {
            return true;
        }
        Block above = block.getRelative(0, 1, 0);
        return above.getType() == Material.LADDER || above.getType().isSolid();
    }
}
