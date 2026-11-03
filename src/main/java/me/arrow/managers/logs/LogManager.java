package me.arrow.managers.logs;

import lombok.Getter;
import lombok.Setter;
import me.arrow.files.Config;
import me.arrow.managers.Initializer;
import me.arrow.managers.logs.impl.FileExporter;
import me.arrow.utils.TaskUtils;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class LogManager implements Initializer {

    private static final int MAX_BATCH_SIZE = 500;

    private final Queue<PlayerLog> logsQueue = new ConcurrentLinkedQueue<>();
    private final LogExporter logExporter;

    @Setter
    private boolean logging;

    private final JavaPlugin plugin;
    private TaskUtils.CancellableTask flushTask;

    public LogManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logExporter = new FileExporter(plugin);
    }

    @Override
    public void initialize() {
        this.logExporter.initialize();

        this.logging = true;

        this.flushTask = TaskUtils.taskTimerAsync(() -> {
            if (!logging) {
                return;
            }

            if (me.arrow.Arrow.getInstance() == null) {
                return;
            }

            if (!Config.Setting.LOGS_ENABLED.getBoolean()) {
                return;
            }

            flushQueuedLogs(false);
        }, 40L, 40L);
    }

    public void addLogToQueue(PlayerLog playerLog) {
        if (!Config.Setting.LOGS_ENABLED.getBoolean()) return;
        if (playerLog == null) return;

        this.logsQueue.add(playerLog);
    }

    public int getTotalLogsForPlayer(UUID uuid, String playerName) {
        int total = 0;
        if (playerName != null && !playerName.isEmpty()) {
            total = this.logExporter.getLogsForPlayer(playerName, 1, 1).getTotalLogs();
        }

        String uuidString = uuid == null ? "" : uuid.toString();
        String expectedName = playerName == null ? "" : playerName;
        for (PlayerLog log : this.logsQueue) {
            if (log == null) continue;
            if ((!uuidString.isEmpty() && uuidString.equalsIgnoreCase(log.getUuid()))
                    || (!expectedName.isEmpty() && expectedName.equalsIgnoreCase(log.getPlayer()))) {
                total++;
            }
        }
        return total;
    }

    public void clearQueuedLogs() {
        this.logsQueue.clear();
    }

    private void flushQueuedLogs(boolean flushAll) {
        if (this.logsQueue.isEmpty()) return;

        List<PlayerLog> batch = new ArrayList<>();
        PlayerLog log;

        while ((log = this.logsQueue.poll()) != null) {
            batch.add(log);

            if (!flushAll && batch.size() >= MAX_BATCH_SIZE) {
                break;
            }
        }

        if (!batch.isEmpty()) {
            this.logExporter.logMultiple(batch);
        }
    }

    @Override
    public void shutdown() {
        this.logging = false;

        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }

        if (!this.logsQueue.isEmpty()) {
            Collection<PlayerLog> remaining = new ArrayList<>();
            PlayerLog log;

            while ((log = this.logsQueue.poll()) != null) {
                remaining.add(log);
            }

            if (!remaining.isEmpty()) {
                this.logExporter.logMultiple(remaining);
            }
        }

        this.logExporter.shutdown();
    }
}
