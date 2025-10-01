package com.example.anticheat.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Listens to player driven inventory transactions and identifies anomalous changes.
 */
public final class TransactionWatcher implements Listener {
    private final Plugin plugin;
    private final TransactionWatcherConfig config;
    private final Map<UUID, SnapshotState> stateByPlayer = new ConcurrentHashMap<>();
    private final Logger transactionLogger;

    public TransactionWatcher(Plugin plugin, TransactionWatcherConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNullElse(config, TransactionWatcherConfig.defaults());
        this.transactionLogger = prepareLogger(plugin.getDataFolder().toPath());
    }

    private Logger prepareLogger(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to create plugin data directory", ex);
        }

        Logger logger = Logger.getLogger(plugin.getName() + ":transactions");
        logger.setUseParentHandlers(false);
        if (logger.getHandlers().length == 0) {
            try {
                FileHandler handler = new FileHandler(dataDirectory.resolve("transactions.log").toString(), true);
                handler.setFormatter(new TransactionLogFormatter());
                logger.addHandler(handler);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Unable to initialise transaction log file", ex);
            }
        }
        return logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        observeMutation(event.getWhoClicked(), MutationType.CLICK, event.getSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        observeMutation(event.getWhoClicked(), MutationType.DRAG, event.getRawSlots().toArray(new Integer[0]));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        observeMutation(event.getWhoClicked(), MutationType.CREATIVE_GIVE, event.getSlot());
    }

    private void observeMutation(HumanEntity human, MutationType type, Integer... touchedSlots) {
        if (!(human instanceof Player player)) {
            return;
        }

        SnapshotState state = stateByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new SnapshotState());
        long sequence = state.nextSequence();
        Set<Integer> slots = new HashSet<>();
        Collections.addAll(slots, touchedSlots);

        Bukkit.getScheduler().runTask(plugin, () -> evaluate(player, state, type, sequence, slots));
    }

    private void evaluate(Player player, SnapshotState state, MutationType type, long sequence, Set<Integer> touchedSlots) {
        InventorySnapshot snapshot = captureSnapshot(player.getOpenInventory());
        InventorySnapshot previous = state.lastSnapshot;

        if (previous == null) {
            state.lastSnapshot = snapshot;
            return;
        }

        long nowNanos = System.nanoTime();
        List<SlotChange> changes = snapshot.differences(previous);

        boolean rapidSwaps = state.registerSwaps(nowNanos, changes.size(), config.maxSwapsPerSecond());
        Optional<Anomaly> dupeAnomaly = detectDuplication(previous, snapshot);

        if (rapidSwaps || dupeAnomaly.isPresent()) {
            Duration cooldown = config.alertCooldown(player.getUniqueId());
            if (!state.isOnCooldown(Instant.now(), cooldown)) {
                state.startCooldown(Instant.now(), cooldown);

                Map<String, Object> logPayload = new HashMap<>();
                logPayload.put("player", player.getUniqueId().toString());
                logPayload.put("sequence", sequence);
                logPayload.put("type", type);
                logPayload.put("touchedSlots", touchedSlots);
                logPayload.put("changes", changes);
                dupeAnomaly.ifPresent(anomaly -> logPayload.put("dupe", anomaly));
                logPayload.put("rapidSwaps", rapidSwaps);

                transactionLogger.warning(logPayload.toString());
                revertSnapshot(player, previous);
            }
            return;
        }

        state.lastSnapshot = snapshot;
    }

    private Optional<Anomaly> detectDuplication(InventorySnapshot before, InventorySnapshot after) {
        Map<ItemKey, Integer> beforeCounts = before.aggregateCounts();
        Map<ItemKey, Integer> afterCounts = after.aggregateCounts();
        Map<ItemKey, Integer> deltas = new HashMap<>();
        Set<ItemKey> allKeys = new HashSet<>();
        allKeys.addAll(beforeCounts.keySet());
        allKeys.addAll(afterCounts.keySet());

        int suspiciousDelta = 0;
        for (ItemKey key : allKeys) {
            int beforeAmount = beforeCounts.getOrDefault(key, 0);
            int afterAmount = afterCounts.getOrDefault(key, 0);
            int delta = afterAmount - beforeAmount;
            if (delta > 0) {
                suspiciousDelta += delta;
            }
            if (delta != 0) {
                deltas.put(key, delta);
            }
        }

        if (suspiciousDelta > config.dupeSuspicionThreshold()) {
            return Optional.of(new Anomaly("Net item creation detected", deltas));
        }
        return Optional.empty();
    }

    private void revertSnapshot(Player player, InventorySnapshot snapshot) {
        InventoryView view = player.getOpenInventory();
        for (Map.Entry<Integer, ItemStack> entry : snapshot.slots.entrySet()) {
            view.setItem(entry.getKey(), cloneItem(entry.getValue()));
        }
        player.updateInventory();
    }

    private InventorySnapshot captureSnapshot(InventoryView view) {
        Map<Integer, ItemStack> slots = new HashMap<>();
        int slotCount = view.countSlots();
        for (int i = 0; i < slotCount; i++) {
            slots.put(i, cloneItem(view.getItem(i)));
        }
        return new InventorySnapshot(slots);
    }

    private ItemStack cloneItem(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        return stack.clone();
    }

    private static boolean isSame(ItemStack a, ItemStack b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        if (a.getAmount() != b.getAmount()) {
            return false;
        }
        if (a.hasItemMeta() != b.hasItemMeta()) {
            return false;
        }
        if (!a.hasItemMeta()) {
            return true;
        }
        return a.getItemMeta().equals(b.getItemMeta());
    }

    private enum MutationType {
        CLICK,
        DRAG,
        CREATIVE_GIVE
    }

    private static final class SnapshotState {
        private InventorySnapshot lastSnapshot;
        private long sequenceCounter;
        private Instant cooldownUntil = Instant.EPOCH;
        private final ArrayDeque<Long> swapTimestamps = new ArrayDeque<>();

        private SnapshotState() {}

        private long nextSequence() {
            return ++sequenceCounter;
        }

        private boolean registerSwaps(long nowNanos, int swapCount, double thresholdPerSecond) {
            if (swapCount <= 0) {
                return false;
            }
            long windowStart = nowNanos - 1_000_000_000L;
            for (int i = 0; i < swapCount; i++) {
                swapTimestamps.addLast(nowNanos);
            }
            while (!swapTimestamps.isEmpty() && swapTimestamps.peekFirst() < windowStart) {
                swapTimestamps.removeFirst();
            }
            return swapTimestamps.size() > thresholdPerSecond;
        }

        private boolean isOnCooldown(Instant now, Duration cooldown) {
            if (cooldown.isZero() || cooldown.isNegative()) {
                return false;
            }
            return now.isBefore(cooldownUntil);
        }

        private void startCooldown(Instant now, Duration cooldown) {
            if (cooldown.isZero() || cooldown.isNegative()) {
                cooldownUntil = Instant.EPOCH;
                return;
            }
            cooldownUntil = now.plus(cooldown);
        }
    }

    private record InventorySnapshot(Map<Integer, ItemStack> slots) {
        private InventorySnapshot {
            this.slots = Map.copyOf(slots);
        }

        private List<SlotChange> differences(InventorySnapshot other) {
            int maxSlots = Math.max(slots.size(), other.slots.size());
            List<SlotChange> changes = new ArrayList<>();
            for (int i = 0; i < maxSlots; i++) {
                ItemStack before = other.slots.get(i);
                ItemStack after = slots.get(i);
                if (!isSame(before, after)) {
                    changes.add(new SlotChange(i, before, after));
                }
            }
            return changes;
        }

        private Map<ItemKey, Integer> aggregateCounts() {
            Map<ItemKey, Integer> counts = new HashMap<>();
            for (ItemStack stack : slots.values()) {
                if (stack == null || stack.getType() == Material.AIR) {
                    continue;
                }
                ItemKey key = ItemKey.fromStack(stack);
                counts.merge(key, stack.getAmount(), Integer::sum);
            }
            return counts;
        }
    }

    private record SlotChange(int slot, ItemStack before, ItemStack after) {
        @Override
        public String toString() {
            return "SlotChange{" + "slot=" + slot + ", before=" + describe(before) + ", after=" + describe(after)
                    + '}';
        }

        private String describe(ItemStack stack) {
            if (stack == null || stack.getType() == Material.AIR) {
                return "empty";
            }
            return stack.getType() + "x" + stack.getAmount();
        }
    }

    private record ItemKey(Material material, Map<String, Object> meta) {
        private static ItemKey fromStack(ItemStack stack) {
            Map<String, Object> serialisedMeta = Collections.emptyMap();
            if (stack.hasItemMeta()) {
                serialisedMeta = stack.getItemMeta().serialize();
            }
            return new ItemKey(stack.getType(), serialisedMeta);
        }
    }

    private record Anomaly(String reason, Map<ItemKey, Integer> deltas) {
        @Override
        public String toString() {
            return reason + " " + deltas.entrySet().stream()
                    .map(entry -> entry.getKey().material() + ":" + entry.getValue())
                    .collect(Collectors.joining(", "));
        }
    }

    private static final class TransactionLogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return Instant.ofEpochMilli(record.getMillis()) + " " + record.getLevel().getName() + " "
                    + record.getMessage() + System.lineSeparator();
        }
    }
}
