package me.arrow.managers;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.managers.webhook.DiscordWebhookQueue;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * An alert manager class holding information about players with alerts
 */
@Getter
public class AlertManager implements Listener, Initializer {

    private final ExecutorService alertExecutor = createExecutor("Arrow-Alerts");
    private final ExecutorService verboseExecutor = createExecutor("Arrow-Verbose-Alerts");

    private final List<UUID> playersWithAlerts = new CopyOnWriteArrayList<>();
    private final DiscordWebhookQueue webhookQueue = new DiscordWebhookQueue();

    @Override
    public void initialize() {
        this.webhookQueue.start();
        Bukkit.getPluginManager().registerEvents(this, Arrow.getInstance().getHost());
    }

    public void queueAlert(Runnable alert) {
        execute(this.alertExecutor, alert);
    }

    public void queueVerbose(Runnable alert) {
        execute(this.verboseExecutor, alert);
    }

    private void execute(ExecutorService executor, Runnable alert) {
        try {
            executor.execute(alert);
        } catch (RejectedExecutionException ignored) {
            // The plugin is already shutting down.
        }
    }

    private static ExecutorService createExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void queueWebhook(String webhookUrl, String payload, String failureMessage) {
        this.webhookQueue.enqueue(webhookUrl, payload, failureMessage);
    }

    public void addPlayerToAlerts(UUID uuid) {
        if (!this.playersWithAlerts.contains(uuid)) {
            this.playersWithAlerts.add(uuid);
        }
    }

    public void removePlayerFromAlerts(UUID uuid) {
        this.playersWithAlerts.remove(uuid);
    }

    public boolean hasAlerts(UUID uuid) {
        return this.playersWithAlerts.contains(uuid);
    }

    //Make sure we dont get a memory leak
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayerFromAlerts(event.getPlayer().getUniqueId());
    }

    @Override
    public void shutdown() {
        this.playersWithAlerts.clear();
        this.alertExecutor.shutdownNow();
        this.verboseExecutor.shutdownNow();
        this.webhookQueue.shutdown();
    }
}
