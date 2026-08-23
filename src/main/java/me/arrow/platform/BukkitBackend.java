package me.arrow.platform;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * Implementation of {@link PlatformBackend} for the standard Bukkit
 * server stack (Spigot, Paper, Folia). It forwards all calls to Bukkit.
 */
public class BukkitBackend extends PlatformBackend {
    private final Plugin plugin;

    public BukkitBackend(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerListener(Object listener) {
        if (listener instanceof Listener && plugin != null) {
            Server server = plugin.getServer();
            server.getPluginManager().registerEvents((Listener) listener, plugin);
        }
    }

    @Override
    public void broadcastMessage(String message) {
        if (plugin != null) {
            plugin.getServer().broadcastMessage(message);
        } else {
            Bukkit.broadcastMessage(message);
        }
    }

    @Override
    public Server getServer() {
        return plugin != null ? plugin.getServer() : Bukkit.getServer();
    }

    @Override
    public PluginManager getPluginManager() {
        return plugin != null ? plugin.getServer().getPluginManager() : Bukkit.getPluginManager();
    }

    @Override
    public BukkitScheduler getScheduler() {
        return plugin != null ? plugin.getServer().getScheduler() : Bukkit.getScheduler();
    }

    @Override
    public void runTask(Runnable task) {
        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            task.run();
        }
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        if (plugin != null) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        } else {
            task.run();
        }
    }

    @Override
    public void runRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }
}
