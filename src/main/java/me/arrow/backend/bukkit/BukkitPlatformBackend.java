package me.arrow.backend.bukkit;

import me.arrow.backend.PlatformType;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bukkit-only runtime bridge used by the plugin entrypoint.
 *
 * <p>This type deliberately exposes Bukkit objects. Fabric must use its own
 * {@code me.arrow.backend.fabric} runtime and must never load this class.</p>
 */
public abstract class BukkitPlatformBackend {

    private final List<Consumer<Player>> joinListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Player>> quitListeners = new CopyOnWriteArrayList<>();

    private static BukkitPlatformBackend instance;
    private static PlatformType platformType;

    /** Detects the Bukkit-family server currently hosting the plugin. */
    public static PlatformType detectPlatformType() {
        if (platformType != null) {
            return platformType;
        }

        if (hasClass("io.papermc.paper.threadedregions.RegionizedServer")) {
            return platformType = PlatformType.FOLIA;
        }
        if (hasClass("com.destroystokyo.paper.PaperConfig")
                || hasClass("io.papermc.paper.configuration.Configuration")) {
            return platformType = PlatformType.PAPER;
        }
        if (hasClass("org.spigotmc.SpigotConfig")) {
            return platformType = PlatformType.SPIGOT;
        }
        return platformType = PlatformType.BUKKIT;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, BukkitPlatformBackend.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /** Initialises the Bukkit runtime. This must only be called by a Bukkit plugin. */
    public static void initialize(Plugin plugin) {
        instance = new BukkitBackend(plugin);
    }

    public static BukkitPlatformBackend get() {
        if (instance == null) {
            throw new IllegalStateException("BukkitPlatformBackend not initialized");
        }
        return instance;
    }

    public PlatformType getPlatformType() {
        return detectPlatformType();
    }

    /** Retained for Bukkit-only call sites that guard optional behaviour. */
    public boolean isFabric() {
        return false;
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

    /** Check whether a Bukkit player joined through Floodgate or Geyser. */
    public boolean isBedrockPlayer(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();

        try {
            Object api = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
                    .getMethod("getInstance").invoke(null);
            Object result = api == null ? null : api.getClass()
                    .getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object geyser = Class.forName("org.geysermc.geyser.GeyserImpl")
                    .getMethod("getInstance").invoke(null);
            return geyser != null && geyser.getClass()
                    .getMethod("connectionByUuid", UUID.class).invoke(geyser, uuid) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public abstract void registerListener(Object listener);

    public abstract void broadcastMessage(String message);

    public void addJoinListener(Consumer<Player> consumer) {
        if (consumer != null) {
            joinListeners.add(consumer);
        }
    }

    public void addQuitListener(Consumer<Player> consumer) {
        if (consumer != null) {
            quitListeners.add(consumer);
        }
    }

    public void firePlayerJoin(Player player) {
        notify(joinListeners, player);
    }

    public void firePlayerQuit(Player player) {
        notify(quitListeners, player);
    }

    private void notify(List<Consumer<Player>> listeners, Player player) {
        for (Consumer<Player> listener : listeners) {
            try {
                listener.accept(player);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    public abstract Server getServer();

    public abstract PluginManager getPluginManager();

    public abstract BukkitScheduler getScheduler();

    public abstract void runTask(Runnable task);

    public abstract void runTaskLater(Runnable task, long delayTicks);

    public abstract void runRepeatingTask(Runnable task, long delayTicks, long periodTicks);

    public abstract void dispatchCommand(String command);

    public abstract String getVersion();

    public void cancelAllTasks(Plugin plugin) {
        BukkitScheduler scheduler = getScheduler();
        if (scheduler != null && plugin != null) {
            scheduler.cancelTasks(plugin);
        }
    }
}
