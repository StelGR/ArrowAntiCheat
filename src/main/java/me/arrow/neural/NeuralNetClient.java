package me.arrow.neural;

import lombok.Getter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * Async HTTP client that talks to the Python inference server.
 * Checks server health and training status from the server side.
 */
public class NeuralNetClient {

    private final String serverUrl;
    private final ExecutorService executor;
    @Getter
    private volatile boolean available = false;
    /**
     * -- GETTER --
     * Whether the server has training mode enabled (controlled by --training flag on the server).
     */
    @Getter
    private volatile boolean trainingEnabled = false;

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 ArrowAntiCheat";

    private final ScheduledExecutorService scheduler;

    public NeuralNetClient(String serverUrl) {
        String url = serverUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        this.serverUrl = url.endsWith("/") ? url : url + "/";
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Arrow-NeuralNet");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Arrow-NeuralNet-Health");
            t.setDaemon(true);
            return t;
        });
        // Check server health every 10 seconds
        this.scheduler.scheduleWithFixedDelay(this::checkHealth, 0, 10, TimeUnit.SECONDS);
    }

    /** Synchronously check server health. */
    public boolean checkHealthSync() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "health").openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            available = code == 200;
            conn.disconnect();
            return available;
        } catch (Throwable e) {
            available = false;
            return false;
        }
    }

    /** Check server health + training status.  Non-blocking. */
    public void checkHealth() {
        executor.submit(() -> {
            // Health check
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "health").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                int code = conn.getResponseCode();
                available = code == 200;
                conn.disconnect();
                if (!available) {
                    org.bukkit.Bukkit.getLogger().warning("[NeuralNet] Server at " + serverUrl + " returned HTTP " + code);
                }
            } catch (Throwable e) {
                available = false;
                trainingEnabled = false;
                org.bukkit.Bukkit.getLogger().warning("[NeuralNet] Cannot reach " + serverUrl + "health: " + e.getMessage());
                return;
            }

            // Training status check
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "training/status").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", USER_AGENT);

                if (conn.getResponseCode() == 200) {
                    String body = readBody(conn);
                    trainingEnabled = body.contains("\"enabled\": true") || body.contains("\"enabled\":true");
                }
                conn.disconnect();
            } catch (Throwable e) {
                trainingEnabled = false;
            }
        });
    }

    /**
     * Synchronously check if the server allows training.
     * Used by the training command before starting a session.
     */
    public boolean checkTrainingStatus() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "training/status").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return false;
            }

            String body = readBody(conn);
            conn.disconnect();

            boolean enabled = body.contains("\"enabled\": true") || body.contains("\"enabled\":true");
            trainingEnabled = enabled;
            return enabled;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Send a rotation window to the server and get the cheating probability.
     *
     * @param window 2D array [20 ticks][8 features]
     * @return Future with the cheating probability (0.0 - 1.0), or -1 on error
     */
    public CompletableFuture<Double> predict(float[][] window) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = buildJson(window);

                HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "predict").openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return -1.0;
                }

                String response = readBody(conn);
                conn.disconnect();

                // Parse "cheating": <number>
                int idx = response.indexOf("\"cheating\"");
                if (idx < 0) return -1.0;
                int colon = response.indexOf(':', idx);
                int end = response.indexOf('}', colon);
                if (end < 0) end = response.length();
                String value = response.substring(colon + 1, end).trim().replace(",", "");
                return Double.parseDouble(value);

            } catch (Throwable e) {
                return -1.0;
            }
        }, executor);
    }

    private String buildJson(float[][] window) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"features\":[");
        for (int i = 0; i < window.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            for (int j = 0; j < window[i].length; j++) {
                if (j > 0) sb.append(',');
                sb.append(window[i][j]);
            }
            sb.append(']');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }
}
