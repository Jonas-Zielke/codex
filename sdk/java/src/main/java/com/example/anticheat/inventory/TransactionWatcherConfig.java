package com.example.anticheat.inventory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Configuration wrapper for {@link TransactionWatcher} runtime behaviour.
 */
public final class TransactionWatcherConfig {
    private static final String KEY_SWAP_RATE = "max-swaps-per-second";
    private static final String KEY_DUPE_THRESHOLD = "dupe-threshold";
    private static final String KEY_COOLDOWN = "alert-cooldown";
    private static final String KEY_COOLDOWN_EXEMPTIONS = "cooldown-exemptions";

    private final double maxSwapsPerSecond;
    private final int dupeSuspicionThreshold;
    private final Duration alertCooldown;
    private final Map<UUID, Duration> perPlayerCooldowns;

    public TransactionWatcherConfig(double maxSwapsPerSecond, int dupeSuspicionThreshold, Duration alertCooldown,
            Map<UUID, Duration> perPlayerCooldowns) {
        this.maxSwapsPerSecond = maxSwapsPerSecond;
        this.dupeSuspicionThreshold = dupeSuspicionThreshold;
        this.alertCooldown = Objects.requireNonNull(alertCooldown, "alertCooldown");
        this.perPlayerCooldowns = Map.copyOf(perPlayerCooldowns);
    }

    public double maxSwapsPerSecond() {
        return maxSwapsPerSecond;
    }

    public int dupeSuspicionThreshold() {
        return dupeSuspicionThreshold;
    }

    public Duration alertCooldown(UUID playerId) {
        return Optional.ofNullable(perPlayerCooldowns.get(playerId)).orElse(alertCooldown);
    }

    public Map<UUID, Duration> perPlayerCooldowns() {
        return perPlayerCooldowns;
    }

    public static TransactionWatcherConfig defaults() {
        return new TransactionWatcherConfig(12.0D, 0, Duration.ofSeconds(8), Collections.emptyMap());
    }

    public static TransactionWatcherConfig fromConfigurationSection(ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }

        double swapRate = section.getDouble(KEY_SWAP_RATE, 12.0D);
        int dupeThreshold = section.getInt(KEY_DUPE_THRESHOLD, 0);
        long cooldownSeconds = section.getLong(KEY_COOLDOWN, 8L);
        Map<UUID, Duration> cooldowns = new HashMap<>();

        ConfigurationSection exemptions = section.getConfigurationSection(KEY_COOLDOWN_EXEMPTIONS);
        if (exemptions != null) {
            for (String rawKey : exemptions.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(rawKey);
                    long seconds = exemptions.getLong(rawKey, cooldownSeconds);
                    cooldowns.put(playerId, Duration.ofSeconds(Math.max(0L, seconds)));
                } catch (IllegalArgumentException ex) {
                    // ignore malformed UUID entries but leave a breadcrumb in server logs
                    org.bukkit.Bukkit.getLogger()
                            .warning("Invalid UUID in cooldown-exemptions: " + rawKey);
                }
            }
        }

        return new TransactionWatcherConfig(swapRate, dupeThreshold, Duration.ofSeconds(Math.max(0L, cooldownSeconds)),
                cooldowns);
    }
}
