package me.arrow.backend.bukkit;

import me.arrow.Arrow;
import org.bukkit.plugin.java.JavaPlugin;

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
