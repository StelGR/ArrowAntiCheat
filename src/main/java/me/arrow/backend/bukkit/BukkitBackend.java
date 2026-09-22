package me.arrow.backend.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

/** Concrete Bukkit, Spigot, Paper, and Folia implementation. */
public final class BukkitBackend extends PlatformBackend {

    private final Plugin plugin;

    BukkitBackend(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerListener(Object listener) {
        if (listener instanceof Listener && plugin != null) {
            plugin.getServer().getPluginManager().registerEvents((Listener) listener, plugin);
        }
    }

    @Override
    public void broadcastMessage(String message) {
        getServer().broadcastMessage(message);
    }

    @Override
    public Server getServer() {
        return plugin != null ? plugin.getServer() : Bukkit.getServer();
    }

    @Override
    public PluginManager getPluginManager() {
        return getServer().getPluginManager();
    }

    @Override
    public BukkitScheduler getScheduler() {
        return getServer().getScheduler();
    }

    @Override
    public void runTask(Runnable task) {
        if (plugin != null) {
            getScheduler().runTask(plugin, task);
        } else {
            task.run();
        }
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        if (plugin != null) {
            getScheduler().runTaskLater(plugin, task, delayTicks);
        } else {
            task.run();
        }
    }

    @Override
    public void runRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        if (plugin != null) {
            getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }

    @Override
    public void dispatchCommand(String command) {
        getServer().dispatchCommand(getServer().getConsoleSender(), command);
    }

    @Override
    public String getVersion() {
        return getServer().getVersion();
    }
}
