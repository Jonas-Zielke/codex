package com.example.anticheat.movement;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class MovementPlugin extends JavaPlugin {
    private MovementConfig movementConfig;

    @Override
    public void onEnable() {
        saveDefaultConfigFiles();
        reloadMovementConfig();
        MovementMonitor monitor = new MovementMonitor(movementConfig);
        MovementListener listener = new MovementListener(movementConfig, monitor);
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("Movement anti-cheat listener enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Movement anti-cheat listener disabled");
    }

    public void reloadMovementConfig() {
        File movementFile = new File(getDataFolder(), "movement.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(movementFile);
        movementConfig = new MovementConfig(configuration);
    }

    private void saveDefaultConfigFiles() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File movementFile = new File(getDataFolder(), "movement.yml");
        if (!movementFile.exists()) {
            saveResource("movement.yml", false);
        }
    }

    public MovementConfig getMovementConfig() {
        return movementConfig;
    }
}
