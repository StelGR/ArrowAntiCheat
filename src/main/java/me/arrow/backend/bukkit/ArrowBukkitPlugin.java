package me.arrow.backend.bukkit;

import me.arrow.Arrow;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Bukkit-only plugin entrypoint. Fabric uses {@code ArrowFabricMod} instead. */
public final class ArrowBukkitPlugin extends JavaPlugin {

    private Arrow arrow;

    private static ArrowBukkitPlugin instance;

    public Arrow getArrow() {
        return arrow;
    }

    public void setArrow(Arrow arrow) {
        this.arrow = arrow;
    }

    public static ArrowBukkitPlugin getInstance() {
        return instance;
    }

    /**
     * Bukkit-only bridge for ArrowLoader. The loader is already the registered
     * JavaPlugin, so it must not construct a second JavaPlugin instance.
     */
    public static Arrow createForLoader(JavaPlugin host, File dataFolder) {
        if (host == null || dataFolder == null) {
            throw new IllegalArgumentException("ArrowLoader requires a Bukkit plugin host and data folder");
        }
        return new Arrow(host, dataFolder);
    }

    @Override
    public void onEnable() {
        instance = this;
        arrow = new Arrow(this, getDataFolder());
        arrow.onEnable();
    }

    @Override
    public void onDisable() {
        if (arrow != null) {
            arrow.onDisable();
        }
    }
}
