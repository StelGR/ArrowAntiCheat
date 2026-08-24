package me.arrow.platform;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fabric implementation of {@link PlatformBackend}.
 * No Bukkit dependencies; commands are ignored.
 */
public class FabricBackend extends PlatformBackend {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "Arrow-FabricScheduler");
        t.setDaemon(true);
        return t;
    });

    /** Constructor kept for compatibility; the plugin argument is unused on Fabric. */
    public FabricBackend(Object plugin) {
        initFabricEvents();
    }

    private void initFabricEvents() {
        try {
            Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
        } catch (Throwable ignored) { }
    }

    @Override
    public void registerListener(Object listener) {
        // Fabric does not use Bukkit listeners; ignore.
    }

    @Override
    public void broadcastMessage(String message) {
        try {
            Object server = getFabricMinecraftServer();
            if (server != null) {
                Method getPlayerManager = server.getClass().getMethod("getPlayerManager");
                Object playerManager = getPlayerManager.invoke(server);
                if (playerManager != null) {
                    Method broadcast = playerManager.getClass()
                            .getMethod("broadcast", Class.forName("net.minecraft.text.Text"), boolean.class);
                    Class<?> textClass = Class.forName("net.minecraft.text.Text");
                    Method of = textClass.getMethod("of", String.class);
                    Object textObj = of.invoke(null, message);
                    broadcast.invoke(playerManager, textObj, false);
                    return;
                }
            }
        } catch (Throwable ignored) { }
        System.out.println(message);
    }

    private Object getFabricMinecraftServer() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Method getInstance = loaderClass.getMethod("getInstance");
            Object loader = getInstance.invoke(null);
            Method getGameInstance = loader.getClass().getMethod("getGameInstance");
            return getGameInstance.invoke(loader);
        } catch (Throwable ignored) { return null; }
    }

    @Override
    public org.bukkit.Server getServer() { return null; }

    @Override
    public org.bukkit.plugin.PluginManager getPluginManager() { return null; }

    @Override
    public org.bukkit.scheduler.BukkitScheduler getScheduler() { return null; }

    @Override
    public void runTask(Runnable task) { scheduler.submit(task); }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        long delayMs = Math.max(0, delayTicks * 50L);
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        long initialDelay = Math.max(0, delayTicks * 50L);
        long period = Math.max(1, periodTicks) * 50L;
        scheduler.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispatchCommand(String command) {
        System.out.println("[FabricBackend] Ignoring command registration: " + command);
    }

    @Override
    public String getVersion() { return "Fabric"; }


}
