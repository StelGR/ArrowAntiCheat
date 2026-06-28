package me.arrow.utils.customutils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class GeyserConfigEnforcer {

    private GeyserConfigEnforcer() {
    }

    public static boolean enforceForwardPlayerPing(JavaPlugin plugin, boolean reloadGeyser) {
        File configFile = findGeyserConfigFile();

        if (configFile == null || !configFile.exists()) {
            plugin.getLogger().info("Geyser config was not found. Skipping forward-player-ping enforcement.");
            return false;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        boolean changed = false;

        if (config.contains("forward-player-ping")) {
            if (!config.getBoolean("forward-player-ping")) {
                config.set("forward-player-ping", true);
                changed = true;
            }
        } else if (config.contains("gameplay.forward-player-ping")) {
            if (!config.getBoolean("gameplay.forward-player-ping")) {
                config.set("gameplay.forward-player-ping", true);
                changed = true;
            }
        } else {
            config.set("forward-player-ping", true);
            changed = true;
        }

        if (!changed) {
            plugin.getLogger().info("Geyser forward-player-ping is already enabled.");
            return true;
        }

        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save Geyser config.yml: " + e.getMessage());
            return false;
        }

        plugin.getLogger().warning("Enabled Geyser forward-player-ping in " + configFile.getPath() + ".");

        if (reloadGeyser) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "geyser reload");
            plugin.getLogger().warning("Ran /geyser reload. Geyser players may be kicked by the reload.");
        } else {
            plugin.getLogger().warning("Restart the server or run /geyser reload for this to fully apply.");
        }

        return true;
    }

    private static File findGeyserConfigFile() {
        List<String> pluginNames = Arrays.asList(
                "Geyser-Spigot",
                "Geyser-Bukkit",
                "Geyser"
        );

        for (String pluginName : pluginNames) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);

            if (plugin == null) continue;

            File file = new File(plugin.getDataFolder(), "config.yml");

            if (file.exists()) {
                return file;
            }
        }

        List<File> fallbackFiles = Arrays.asList(
                new File("plugins/Geyser-Spigot/config.yml"),
                new File("plugins/Geyser-Bukkit/config.yml"),
                new File("plugins/Geyser/config.yml"),
                new File("config/Geyser-Fabric/config.yml"),
                new File("config/Geyser-NeoForge/config.yml")
        );

        for (File file : fallbackFiles) {
            if (file.exists()) {
                return file;
            }
        }

        return null;
    }
}
