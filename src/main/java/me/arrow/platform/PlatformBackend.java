package me.arrow.platform;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Unified platform abstraction for Bukkit, Spigot, Paper, Folia, and Fabric.
 * Adaptively detects running server software to maintain 1:1 functionality.
 */
public abstract class PlatformBackend {

    private final List<Consumer<Player>> joinListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Player>> quitListeners = new CopyOnWriteArrayList<>();

    private static PlatformBackend instance;
    private static PlatformType platformType;

    /**
     * Adaptively detects the running platform software.
     */
    public static PlatformType detectPlatformType() {
        if (platformType != null) {
            return platformType;
        }

        // 1. Check for Fabric
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            platformType = PlatformType.FABRIC;
            return platformType;
        } catch (Throwable ignored) {}

        // 2. Check for Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            platformType = PlatformType.FOLIA;
            return platformType;
        } catch (Throwable ignored) {}

        // 3. Check for Paper
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            platformType = PlatformType.PAPER;
            return platformType;
        } catch (Throwable ignored) {}

        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            platformType = PlatformType.PAPER;
            return platformType;
        } catch (Throwable ignored) {}

        // 4. Check for Spigot
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            platformType = PlatformType.SPIGOT;
            return platformType;
        } catch (Throwable ignored) {}

        // 5. Default to Bukkit
        platformType = PlatformType.BUKKIT;
        return platformType;
    }

    /**
     * Initialise the backend based on the adaptively detected server environment.
     */
    public static void initialize(Plugin plugin) {
        PlatformType detected = detectPlatformType();

        if (detected == PlatformType.FABRIC) {
            instance = new FabricBackend(plugin);
        } else {
            // BukkitBackend natively supports Bukkit, Spigot, Paper, and Folia
            instance = new BukkitBackend(plugin);
        }
    }

    public static PlatformBackend get() {
        if (instance == null) {
            throw new IllegalStateException("PlatformBackend not initialized");
        }
        return instance;
    }

    /** Returns the adaptively detected server software. */
    public PlatformType getPlatformType() {
        return detectPlatformType();
    }

    public boolean isFabric() {
        return getPlatformType() == PlatformType.FABRIC;
    }

    public boolean isFolia() {
        return getPlatformType() == PlatformType.FOLIA;
    }

    public boolean isPaper() {
        return getPlatformType() == PlatformType.PAPER || isFolia();
    }

    public boolean isSpigot() {
        return getPlatformType() == PlatformType.SPIGOT || isPaper();
    }

    public boolean isBukkit() {
        return true;
    }

    /** Check whether a player joined from Bedrock Edition via Floodgate or Geyser. */
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();

        // Check Floodgate API
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = floodgateApiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            if (api != null) {
                Method isFloodgatePlayer = api.getClass().getMethod("isFloodgatePlayer", UUID.class);
                Object result = isFloodgatePlayer.invoke(api, uuid);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // Check Geyser API / GeyserImpl
        try {
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl");
            Method getInstance = geyserImplClass.getMethod("getInstance");
            Object geyser = getInstance.invoke(null);
            if (geyser != null) {
                Method sessionByUuid = geyser.getClass().getMethod("connectionByUuid", UUID.class);
                Object session = sessionByUuid.invoke(geyser, uuid);
                if (session != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    /** Register a platform listener (Bukkit Listener or Fabric event handler). */
    public abstract void registerListener(Object listener);

    /** Broadcast a chat message to all online players. */
    public abstract void broadcastMessage(String message);

    /** Add a consumer that will be called when a player joins the server. */
    public void addJoinListener(Consumer<Player> consumer) {
        if (consumer != null) {
            joinListeners.add(consumer);
        }
    }

    /** Add a consumer that will be called when a player quits the server. */
    public void addQuitListener(Consumer<Player> consumer) {
        if (consumer != null) {
            quitListeners.add(consumer);
        }
    }

    /** Fire player join hooks to all registered consumers. */
    public void firePlayerJoin(Player player) {
        for (Consumer<Player> consumer : joinListeners) {
            try {
                consumer.accept(player);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /** Fire player quit hooks to all registered consumers. */
    public void firePlayerQuit(Player player) {
        for (Consumer<Player> consumer : quitListeners) {
            try {
                consumer.accept(player);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /** Underlying server – useful for version detection and player queries. */
    public abstract Server getServer();

    /** Plugin manager for event registration. */
    public abstract PluginManager getPluginManager();

    /** Scheduler – may be Folia's scheduler when running on Folia. */
    public abstract BukkitScheduler getScheduler();

    /** Run a task on the server's main/primary thread. */
    public abstract void runTask(Runnable task);

    /** Run a task delayed by ticks. */
    public abstract void runTaskLater(Runnable task, long delayTicks);

    /** Run a repeating task with delay and period in ticks. */
    public abstract void runRepeatingTask(Runnable task, long delayTicks, long periodTicks);

    /** Cancel all tasks belonging to the given plugin. */
    public void cancelAllTasks(Plugin plugin) {
        BukkitScheduler scheduler = getScheduler();
        if (scheduler != null && plugin != null) {
            scheduler.cancelTasks(plugin);
        }
    }
}
