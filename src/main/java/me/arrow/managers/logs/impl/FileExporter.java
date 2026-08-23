package me.arrow.managers.logs.impl;

import me.arrow.managers.logs.LogExporter;
import me.arrow.managers.logs.PlayerLog;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class FileExporter extends LogExporter {

    private final File logsFolder;
    private final Object writeLock = new Object();

    private static final String SEPARATOR = "\t";
    private final SimpleDateFormat displayFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

    public FileExporter(JavaPlugin plugin) {
        super(plugin);
        File dataFolder = plugin != null ? plugin.getDataFolder() : me.arrow.Arrow.getInstance().getDataFolder();
        this.logsFolder = new File(dataFolder, "logs");
    }

    @Override
    public void initialize() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!logsFolder.exists()) {
                    logsFolder.mkdirs();
                }

                clearOldLogFiles();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    @Override
    public void shutdown() {
        // nothing needed
    }

    @Override
    public void logMultiple(Collection<PlayerLog> logs) {
        if (logs == null || logs.isEmpty()) return;

        synchronized (writeLock) {
            try {
                if (!logsFolder.exists()) {
                    logsFolder.mkdirs();
                }

                Map<String, List<PlayerLog>> grouped = new HashMap<>();

                for (PlayerLog log : logs) {
                    if (log == null || log.getPlayer() == null) continue;

                    String safeName = safeFileName(log.getPlayer());
                    grouped.computeIfAbsent(safeName, ignored -> new ArrayList<>()).add(log);
                }

                for (Map.Entry<String, List<PlayerLog>> entry : grouped.entrySet()) {
                    Path path = new File(logsFolder, entry.getKey() + ".log").toPath();

                    try (BufferedWriter writer = Files.newBufferedWriter(
                            path,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    )) {
                        for (PlayerLog log : entry.getValue()) {
                            writer.write(serialize(log));
                            writer.newLine();
                        }
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void log(PlayerLog log) {
        if (log == null) return;
        logMultiple(Collections.singletonList(log));
    }

    @Override
    public List<PlayerLog> getLogs() {
        if (!logsFolder.exists()) return new ArrayList<>();

        List<PlayerLog> all = new ArrayList<>();

        try (Stream<Path> stream = Files.list(logsFolder.toPath())) {
            stream
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(path -> all.addAll(readAll(path)));
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        all.sort((a, b) -> Long.compare(extractEpoch(b), extractEpoch(a)));
        return all;
    }

    @Override
    public List<PlayerLog> getLogsForPlayer(String player) {
        return getLogsForPlayer(player, 1, Integer.MAX_VALUE).getLogs();
    }

    @Override
    public PagedLogs getLogsForPlayer(String player, int page, int perPage) {
        if (player == null || player.isEmpty()) {
            return new PagedLogs(new ArrayList<>(), 0, 1, 1);
        }

        if (!logsFolder.exists()) {
            return new PagedLogs(new ArrayList<>(), 0, 1, 1);
        }

        Path path = new File(logsFolder, safeFileName(player) + ".log").toPath();

        if (!Files.exists(path)) {
            return new PagedLogs(new ArrayList<>(), 0, 1, 1);
        }

        List<String> lines = new ArrayList<>();

        synchronized (writeLock) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.add(line);
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        int total = lines.size();
        int maxPages = Math.max(1, (total + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, maxPages));

        int newestIndex = total - 1 - ((page - 1) * perPage);
        int oldestIndex = Math.max(0, newestIndex - perPage + 1);

        List<PlayerLog> result = new ArrayList<>();

        for (int i = newestIndex; i >= oldestIndex; i--) {
            PlayerLog log = deserialize(lines.get(i));
            if (log != null) {
                result.add(log);
            }
        }

        return new PagedLogs(result, total, page, maxPages);
    }

    private List<PlayerLog> readAll(Path path) {
        List<PlayerLog> logs = new ArrayList<>();

        synchronized (writeLock) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;

                while ((line = reader.readLine()) != null) {
                    PlayerLog log = deserialize(line);
                    if (log != null) {
                        logs.add(log);
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        return logs;
    }

    private String serialize(PlayerLog log) {
        long epoch = System.currentTimeMillis();

        return epoch + SEPARATOR
                + encode(log.getPlayer()) + SEPARATOR
                + encode(log.getUuid()) + SEPARATOR
                + encode(log.getCheck()) + SEPARATOR
                + encode(log.getInformation());
    }

    private PlayerLog deserialize(String line) {
        try {
            String[] split = line.split(SEPARATOR, 5);
            if (split.length < 5) return null;

            long epoch = Long.parseLong(split[0]);

            String player = decode(split[1]);
            String uuid = decode(split[2]);
            String check = decode(split[3]);
            String information = decode(split[4]);

            String formatted = displayFormat.format(new Date(epoch));
            return new PlayerLog(player, uuid, check, information, formatted);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long extractEpoch(PlayerLog log) {
        try {
            return displayFormat.parse(log.getTimeStamp()).getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void clearOldLogFiles() {
        if (!logsFolder.exists()) return;

        long now = System.currentTimeMillis();

        try (Stream<Path> stream = Files.list(logsFolder.toPath())) {
            stream
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(path -> {
                        try {
                            long modified = Files.getLastModifiedTime(path).toMillis();

                            if (now - modified > DELETE_DAYS) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private String safeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase(Locale.ROOT);
    }

    private String encode(String input) {
        if (input == null) input = "";
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String input) {
        if (input == null || input.isEmpty()) return "";

        try {
            return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}