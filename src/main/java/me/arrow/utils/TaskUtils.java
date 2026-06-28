package me.arrow.utils;

import me.arrow.Arrow;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.*;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public class TaskUtils {

    private static final Plugin PLUGIN = Arrow.getInstance().getHost();
    private static final boolean FOLIA = isFolia();

    private static final ScheduledExecutorService ASYNC_POOL =
            Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
                Thread t = new Thread(r, "Arrow-Async");
                t.setDaemon(true);
                return t;
            });

    private TaskUtils() {}

    public static void task(Runnable runnable) {
        if (FOLIA) {
            runFoliaGlobal(runnable);
        } else {
            Bukkit.getScheduler().runTask(PLUGIN, runnable);
        }
    }

    public static CancellableTask taskAsync(Runnable runnable) {
        ScheduledFuture<?> future = ASYNC_POOL.schedule(runnable, 0L, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    public static CancellableTask taskLaterAsync(Runnable runnable, long delayTicks) {
        ScheduledFuture<?> future = ASYNC_POOL.schedule(
                runnable,
                delayTicks * 50L,
                TimeUnit.MILLISECONDS
        );
        return () -> future.cancel(false);
    }

    public static CancellableTask taskTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        long safePeriod = Math.max(1L, periodTicks);

        ScheduledFuture<?> future = ASYNC_POOL.scheduleAtFixedRate(
                () -> {
                    try {
                        runnable.run();
                    } catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                },
                delayTicks * 50L,
                safePeriod * 50L,
                TimeUnit.MILLISECONDS
        );

        return () -> future.cancel(false);
    }

    public static void taskLater(Runnable runnable, long delay) {
        if (FOLIA) {
            runFoliaGlobalLater(runnable, delay);
        } else {
            Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay);
        }
    }

    public static CancellableTask taskTimer(Runnable runnable, long delay, long period) {
        long safePeriod = safePeriod(period);

        if (FOLIA) {
            return runFoliaGlobalTimer(runnable, safeFoliaDelay(delay), safePeriod);
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(PLUGIN, runnable, delay, safePeriod);
        return task::cancel;
    }

    private static long safeFoliaDelay(long delay) {
        return Math.max(1L, delay);
    }

    private static long safePeriod(long period) {
        return Math.max(1L, period);
    }

    private static void runFoliaGlobal(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            execute.invoke(scheduler, PLUGIN, runnable);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runFoliaGlobalLater(Runnable runnable, long delay) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method runDelayed = scheduler.getClass().getMethod(
                    "runDelayed",
                    Plugin.class,
                    Consumer.class,
                    long.class
            );
            runDelayed.invoke(scheduler, PLUGIN, (Consumer<Object>) task -> runnable.run(), delay);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static CancellableTask runFoliaGlobalTimer(Runnable runnable, long delay, long period) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method runAtFixedRate = scheduler.getClass().getMethod(
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class
            );

            Object scheduledTask = runAtFixedRate.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    delay,
                    period
            );

            return () -> {
                try {
                    scheduledTask.getClass().getMethod("cancel").invoke(scheduledTask);
                } catch (Exception ignored) {}
            };
        } catch (Exception e) {
            e.printStackTrace();
            return () -> {};
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public interface CancellableTask {
        void cancel();
    }
    public static boolean isFoliaServer() {
        return FOLIA;
    }

    public static void player(Player player, Runnable runnable) {
        entity(player, runnable);
    }

    public static void playerLater(Player player, long delay, Runnable runnable) {
        entityLater(player, delay, runnable);
    }

    public static CancellableTask playerTimer(Player player, long delay, long period, Runnable runnable) {
        return entityTimer(player, delay, period, runnable);
    }

    public static void entity(Entity entity, Runnable runnable) {
        if (entity == null) return;

        if (FOLIA) {
            runFoliaEntity(entity, runnable);
        } else {
            Bukkit.getScheduler().runTask(PLUGIN, runnable);
        }
    }

    public static void entityLater(Entity entity, long delay, Runnable runnable) {
        if (entity == null) return;

        if (FOLIA) {
            runFoliaEntityLater(entity, delay, runnable);
        } else {
            Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay);
        }
    }

    public static CancellableTask entityTimer(Entity entity, long delay, long period, Runnable runnable) {
        if (entity == null) return () -> {};

        long safePeriod = safePeriod(period);

        if (FOLIA) {
            return runFoliaEntityTimer(entity, safeFoliaDelay(delay), safePeriod, runnable);
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(PLUGIN, runnable, delay, safePeriod);
        return task::cancel;
    }

    public static void regionLater(Location location, long delay, Runnable runnable) {
        if (location == null) return;

        if (FOLIA) {
            runFoliaRegionLater(location, delay, runnable);
        } else {
            Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay);
        }
    }

    private static void runFoliaEntity(Entity entity, Runnable runnable) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);

            Method run = scheduler.getClass().getMethod(
                    "run",
                    Plugin.class,
                    Consumer.class,
                    Runnable.class
            );

            run.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    null
            );
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private static void runFoliaEntityLater(Entity entity, long delay, Runnable runnable) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);

            Method runDelayed = scheduler.getClass().getMethod(
                    "runDelayed",
                    Plugin.class,
                    Consumer.class,
                    Runnable.class,
                    long.class
            );

            runDelayed.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    null,
                    delay
            );
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private static CancellableTask runFoliaEntityTimer(Entity entity, long delay, long period, Runnable runnable) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);

            Method runAtFixedRate = scheduler.getClass().getMethod(
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    Runnable.class,
                    long.class,
                    long.class
            );

            Object scheduledTask = runAtFixedRate.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    null,
                    delay,
                    period
            );

            return () -> {
                try {
                    scheduledTask.getClass().getMethod("cancel").invoke(scheduledTask);
                } catch (Throwable ignored) {}
            };
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return () -> {};
        }
    }

    private static void runFoliaRegionLater(Location location, long delay, Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);

            Method runDelayed = scheduler.getClass().getMethod(
                    "runDelayed",
                    Plugin.class,
                    Location.class,
                    Consumer.class,
                    long.class
            );

            runDelayed.invoke(
                    scheduler,
                    PLUGIN,
                    location,
                    (Consumer<Object>) task -> runnable.run(),
                    delay
            );
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    public static void teleport(Player player, Location location, PlayerTeleportEvent.TeleportCause cause, Runnable after) {
        if (player == null || location == null) return;

        if (!FOLIA) {
            Bukkit.getScheduler().runTask(PLUGIN, () -> {
                if (!player.isOnline()) return;

                player.teleport(location, cause);

                if (after != null) {
                    after.run();
                }
            });
            return;
        }

        try {
            Method teleportAsync;

            try {
                teleportAsync = player.getClass().getMethod(
                        "teleportAsync",
                        Location.class,
                        PlayerTeleportEvent.TeleportCause.class
                );

                Object futureObject = teleportAsync.invoke(player, location, cause);

                if (futureObject instanceof CompletableFuture<?>) {
                    ((CompletableFuture<?>) futureObject).thenRun(() -> {
                        if (after != null) {
                            player(player, after);
                        }
                    });
                }
            } catch (NoSuchMethodException ignored) {
                teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);

                Object futureObject = teleportAsync.invoke(player, location);

                if (futureObject instanceof CompletableFuture<?>) {
                    ((CompletableFuture<?>) futureObject).thenRun(() -> {
                        if (after != null) {
                            player(player, after);
                        }
                    });
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        if (entity == null) return false;

        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }

        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
            Object result = method.invoke(null, entity);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isOwnedByCurrentRegion(Block block) {
        if (block == null) {
            return false;
        }

        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }

        try {
            Method method = Bukkit.class.getMethod(
                    "isOwnedByCurrentRegion",
                    World.class,
                    int.class,
                    int.class
            );

            Object result = method.invoke(
                    null,
                    block.getWorld(),
                    block.getX(),
                    block.getZ()
            );

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isOwnedByCurrentRegion(Location location) {
        if (location == null) return false;

        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }

        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
            Object result = method.invoke(null, location);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isOwnedByCurrentRegion(CustomLocation location) {
        if (location == null) return false;

        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }

        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
            Object result = method.invoke(null, location);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }
}