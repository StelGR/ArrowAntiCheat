package me.arrow.utils;

import me.arrow.Arrow;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * A small utility class that we can use in order to create and run tasks quickly
 */

public final class TaskUtils {

    private static final Plugin PLUGIN = Arrow.getInstance().getHost();
    private static final boolean FOLIA = isFolia();

    private TaskUtils() {}

    // =========================================
    // NORMAL TASK
    // Automatically selects Bukkit or Folia
    // =========================================

    public static void task(Runnable runnable) {
        if (FOLIA) {
            runFolia(runnable);
        } else {
            Bukkit.getScheduler().runTask(PLUGIN, runnable);
        }
    }

// =========================================
// ASYNC TASK
// Same on both
// =========================================

    public static BukkitTask taskAsync(Runnable runnable) {
        return Bukkit.getScheduler().runTaskAsynchronously(PLUGIN, runnable);
    }

    public static BukkitTask taskLaterAsync(Runnable runnable, long delay) {
        return Bukkit.getScheduler().runTaskLaterAsynchronously(PLUGIN, runnable, delay);
    }

    public static BukkitTask taskTimerAsync(Runnable runnable, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(
                PLUGIN,
                runnable,
                delay,
                period
        );
    }

    // =========================================
    // DELAYED TASK
    // =========================================

    public static void taskLater(Runnable runnable, long delay) {
        if (FOLIA) {
            runFoliaLater(runnable, delay);
        } else {
            Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay);
        }
    }

    // =========================================
    // TIMER TASK
    // =========================================

    public static CancellableTask taskTimer(Runnable runnable, long delay, long period) {
        if (FOLIA) {
            return runFoliaTimer(runnable, delay, period);
        } else {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(PLUGIN, runnable, delay, period);
            return task::cancel;
        }
    }

    // =========================================
    // FOLIA METHODS
    // =========================================

    private static void runFolia(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class
                    .getMethod("getGlobalRegionScheduler")
                    .invoke(null);

            Method execute = scheduler.getClass().getMethod(
                    "execute",
                    Plugin.class,
                    Runnable.class
            );

            execute.invoke(scheduler, PLUGIN, runnable);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runFoliaLater(Runnable runnable, long delay) {
        try {
            Object scheduler = Bukkit.class
                    .getMethod("getGlobalRegionScheduler")
                    .invoke(null);

            Method runDelayed = scheduler.getClass().getMethod(
                    "runDelayed",
                    Plugin.class,
                    Consumer.class,
                    long.class
            );

            runDelayed.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    delay
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static CancellableTask runFoliaTimer(Runnable runnable, long delay, long period) {
        try {
            Object scheduler = Bukkit.class
                    .getMethod("getGlobalRegionScheduler")
                    .invoke(null);

            Method runAtFixedRate = scheduler.getClass().getMethod(
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class
            );

            // Folia returns a ScheduledTask object (not BukkitTask)
            Object scheduledTask = runAtFixedRate.invoke(
                    scheduler,
                    PLUGIN,
                    (Consumer<Object>) task -> runnable.run(),
                    delay,
                    period
            );

            // ScheduledTask has cancel()
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

    // =========================================
    // DETECT FOLIA
    // =========================================

    private static boolean isFolia() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public interface CancellableTask {
        void cancel();
    }
}