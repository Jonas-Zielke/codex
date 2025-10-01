package com.example.anticheat.command;

import com.example.anticheat.combat.PacketMonitor;
import com.example.anticheat.combat.TraceEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Developer command that dumps recent packet traces for a player.
 */
public final class DumpTracesCommand {
    private final PacketMonitor monitor;

    public DumpTracesCommand(PacketMonitor monitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    public void execute(UUID playerId, Appendable output) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(output, "output");
        List<TraceEntry> entries = monitor.dumpTraces(playerId);
        if (entries.isEmpty()) {
            appendLine(output, "No trace data recorded for player " + playerId + ".");
            return;
        }
        appendLine(output, "Dumping " + entries.size() + " packets for player " + playerId + ":");
        for (TraceEntry entry : entries) {
            appendLine(output, entry.toString());
        }
    }

    private static void appendLine(Appendable output, String value) {
        try {
            output.append(value).append('\n');
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
