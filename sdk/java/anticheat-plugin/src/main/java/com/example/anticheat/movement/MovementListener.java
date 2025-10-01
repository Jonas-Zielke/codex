package com.example.anticheat.movement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MovementListener implements Listener {
    private final MovementConfig config;
    private final MovementMonitor monitor;
    private final Map<UUID, MovementSample> samples = new HashMap<>();
    private final Map<UUID, MovementViolationTracker> violationTrackers = new HashMap<>();
    private final Map<UUID, Location> lastSafeLocations = new HashMap<>();

    public MovementListener(MovementConfig config, MovementMonitor monitor) {
        this.config = config;
        this.monitor = monitor;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        if (event instanceof PlayerTeleportEvent) {
            recordSample(player, event);
            return;
        }

        if (player.hasPermission(config.getBypassPermission()) || hasTemporaryBypass(player)) {
            recordSample(player, event);
            return;
        }

        if (!event.hasChangedPosition()) {
            return;
        }

        MovementSample previous = samples.get(player.getUniqueId());
        MovementSample current = buildSample(event);
        samples.put(player.getUniqueId(), current);
        if (current.isOnGround()) {
            lastSafeLocations.put(player.getUniqueId(), current.getLocation());
        }

        Optional<MovementViolation> violation = monitor.evaluate(current, previous);
        if (violation.isEmpty()) {
            getTracker(player).getViolationCount();
            return;
        }

        MovementViolationTracker tracker = getTracker(player);
        tracker.addViolation(violation.get());
        if (violation.get().isHardReject()) {
            performSetBack(player, event, previous, violation.get());
            tracker.clear();
            return;
        }
        handleActions(player, tracker, violation.get(), event, previous);
    }

    private void handleActions(Player player, MovementViolationTracker tracker, MovementViolation violation, PlayerMoveEvent event, MovementSample previous) {
        int violations = tracker.getViolationCount();
        for (MovementConfig.ActionThreshold threshold : config.getActionThresholds().values()) {
            if (violations < threshold.threshold()) {
                continue;
            }
            switch (threshold.type().toLowerCase()) {
                case "set-back" -> {
                    performSetBack(player, event, previous, violation);
                    tracker.clear();
                }
                case "notify" -> notifyStaff(player, violation, violations);
                default -> {
                    // allow custom hooks via plugin messaging in the future
                }
            }
        }
    }

    private void performSetBack(Player player, PlayerMoveEvent event, MovementSample previous, MovementViolation violation) {
        Location target = lastSafeLocations.getOrDefault(player.getUniqueId(), previous != null ? previous.getLocation() : player.getLocation());
        event.setTo(target);
        player.sendMessage("§cYour movement was reset due to suspicious motion: " + violation.getReason());
    }

    private void notifyStaff(Player player, MovementViolation violation, int violations) {
        String message = String.format("§e[AntiCheat] §c%s flagged for movement (%s). Buffer=%d", player.getName(), violation.getReason(), violations);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("anticheat.notify"))
                .forEach(p -> p.sendMessage(message));
    }

    private MovementSample buildSample(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            to = from;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        Vector velocity = event.getPlayer().getVelocity();
        boolean onGround = event.getPlayer().isOnGround();
        long tick = event.getPlayer().getWorld().getFullTime();
        return new MovementSample(to, delta, velocity, onGround, tick);
    }

    private void recordSample(Player player, PlayerMoveEvent event) {
        MovementSample sample = buildSample(event);
        samples.put(player.getUniqueId(), sample);
        if (sample.isOnGround()) {
            lastSafeLocations.put(player.getUniqueId(), sample.getLocation());
        }
    }

    private MovementViolationTracker getTracker(Player player) {
        return violationTrackers.computeIfAbsent(player.getUniqueId(), ignored -> new MovementViolationTracker(config.getViolationBufferSeconds(), config.getViolationMaxSamples()));
    }

    private boolean hasTemporaryBypass(Player player) {
        for (PermissionAttachmentInfo attachment : player.getEffectivePermissions()) {
            if (attachment.getPermission().startsWith(config.getBypassPermission() + ".") && attachment.getValue()) {
                return true;
            }
        }
        return false;
    }
}
