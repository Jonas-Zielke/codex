package com.example.anticheat.movement;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MovementConfig {
    private final double gravity;
    private final double airDrag;
    private final double groundFriction;
    private final double horizontalTolerancePercent;
    private final double verticalTolerancePercent;
    private final double collisionHorizontalBonus;
    private final double collisionVerticalBonus;
    private final double minimumHorizontalAllowance;
    private final double minimumVerticalAllowance;
    private final int violationBufferSeconds;
    private final int violationMaxSamples;
    private final Map<String, ActionThreshold> actionThresholds;
    private final String bypassPermission;

    public MovementConfig(FileConfiguration configuration) {
        ConfigurationSection physics = configuration.getConfigurationSection("physics");
        if (physics == null) {
            throw new IllegalStateException("Missing physics section in movement.yml");
        }
        this.gravity = physics.getDouble("gravity", 0.08D);
        this.airDrag = physics.getDouble("air-drag", 0.91D);
        this.groundFriction = physics.getDouble("ground-friction", 0.6D);

        ConfigurationSection thresholds = configuration.getConfigurationSection("thresholds");
        if (thresholds == null) {
            throw new IllegalStateException("Missing thresholds section in movement.yml");
        }
        this.horizontalTolerancePercent = thresholds.getDouble("horizontal-percent", 15D);
        this.verticalTolerancePercent = thresholds.getDouble("vertical-percent", 20D);
        this.collisionHorizontalBonus = thresholds.getDouble("collision-horizontal-bonus", 0.2D);
        this.collisionVerticalBonus = thresholds.getDouble("collision-vertical-bonus", 0.1D);
        this.minimumHorizontalAllowance = thresholds.getDouble("minimum-horizontal", 0.05D);
        this.minimumVerticalAllowance = thresholds.getDouble("minimum-vertical", 0.05D);

        ConfigurationSection violations = configuration.getConfigurationSection("violations");
        if (violations == null) {
            throw new IllegalStateException("Missing violations section in movement.yml");
        }
        this.violationBufferSeconds = violations.getInt("decay-seconds", 8);
        this.violationMaxSamples = violations.getInt("max-buffer", 20);

        ConfigurationSection triggersSection = violations.getConfigurationSection("triggers");
        this.actionThresholds = new LinkedHashMap<>();
        if (triggersSection != null) {
            for (String key : triggersSection.getKeys(false)) {
                ConfigurationSection actionSection = triggersSection.getConfigurationSection(key);
                if (actionSection != null && actionSection.getBoolean("enabled", true)) {
                    int threshold = actionSection.getInt("threshold", 3);
                    String actionType = actionSection.getString("type", key);
                    this.actionThresholds.put(actionType, new ActionThreshold(actionType, threshold));
                }
            }
        }

        this.bypassPermission = configuration.getString("bypass-permission", "anticheat.movement.bypass");
    }

    public double getGravity() {
        return gravity;
    }

    public double getAirDrag() {
        return airDrag;
    }

    public double getGroundFriction() {
        return groundFriction;
    }

    public double getHorizontalTolerancePercent() {
        return horizontalTolerancePercent;
    }

    public double getVerticalTolerancePercent() {
        return verticalTolerancePercent;
    }

    public double getCollisionHorizontalBonus() {
        return collisionHorizontalBonus;
    }

    public double getCollisionVerticalBonus() {
        return collisionVerticalBonus;
    }

    public double getMinimumHorizontalAllowance() {
        return minimumHorizontalAllowance;
    }

    public double getMinimumVerticalAllowance() {
        return minimumVerticalAllowance;
    }

    public int getViolationBufferSeconds() {
        return violationBufferSeconds;
    }

    public int getViolationMaxSamples() {
        return violationMaxSamples;
    }

    public Map<String, ActionThreshold> getActionThresholds() {
        return actionThresholds;
    }

    public String getBypassPermission() {
        return bypassPermission;
    }

    public record ActionThreshold(String type, int threshold) {
    }
}
