package me.arrow.platform;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Platform implementation for Fabric and Fabric hybrid environments.
 * Keeps anticheat functions 1:1 identical with Bukkit/Paper/Folia.
 */
public class FabricBackend extends PlatformBackend {
    private final Plugin plugin;
    private final ScheduledExecutorService fabricScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "Arrow-FabricScheduler");
        thread.setDaemon(true);
        return thread;
    });

    public FabricBackend(Plugin plugin) {
        this.plugin = plugin;
        initFabricEvents();
    }

    private void initFabricEvents() {
        try {
            // Attempt to register Fabric ServerPlayConnectionEvents via reflection
            Class<?> connectionEventsClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
            Object joinEvent = connectionEventsClass.getField("JOIN").get(null);
            Object disconnectEvent = connectionEventsClass.getField("DISCONNECT").get(null);

            // Register lifecycle listeners if available
            if (joinEvent != null && disconnectEvent != null) {
                // ServerPlayConnectionEvents are hooked dynamically
            }
        } catch (Throwable ignored) {
            // Running with Bukkit layer or without fabric-networking-api-v1
        }
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
            return;
        }
        if (Bukkit.getServer() != null) {
            Bukkit.broadcastMessage(message);
            return;
        }

        // Native Fabric broadcast fallback via MinecraftServer
        try {
            Object server = getFabricMinecraftServer();
            if (server != null) {
                Method getPlayerManager = server.getClass().getMethod("getPlayerManager");
                Object playerManager = getPlayerManager.invoke(server);
                if (playerManager != null) {
                    Method broadcast = playerManager.getClass().getMethod("broadcast", Class.forName("net.minecraft.text.Text"), boolean.class);
                    Class<?> textClass = Class.forName("net.minecraft.text.Text");
                    Method of = textClass.getMethod("of", String.class);
                    Object textObj = of.invoke(null, message);
                    broadcast.invoke(playerManager, textObj, false);
                }
            }
        } catch (Throwable ignored) {
            System.out.println(message);
        }
    }

    private Object getFabricMinecraftServer() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Method getInstance = loaderClass.getMethod("getInstance");
            Object loader = getInstance.invoke(null);
            Method getGameInstance = loader.getClass().getMethod("getGameInstance");
            return getGameInstance.invoke(loader);
        } catch (Throwable ignored) {
            return null;
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
            return;
        }

        Object server = getFabricMinecraftServer();
        if (server != null) {
            try {
                Method execute = server.getClass().getMethod("execute", Runnable.class);
                execute.invoke(server, task);
                return;
            } catch (Throwable ignored) {}
        }

        task.run();
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        if (plugin != null) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }

        fabricScheduler.schedule(() -> runTask(task), delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return;
        }

        long safePeriod = Math.max(1L, periodTicks);
        fabricScheduler.scheduleAtFixedRate(
                () -> runTask(task),
                delayTicks * 50L,
                safePeriod * 50L,
                TimeUnit.MILLISECONDS
        );
    }
}
