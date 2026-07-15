package me.arrow.managers.webhook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serializes Discord webhook requests so alert bursts never create a burst of
 * HTTP connections or run network I/O on an anticheat/player thread.
 */
public class DiscordWebhookQueue {

    private static final long MINIMUM_SEND_INTERVAL_MILLIS = 500L;
    private static final int MAX_SERVER_ERROR_RETRIES = 3;
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile(
            "\\\"retry_after\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"
    );

    private final BlockingQueue<WebhookRequest> requests = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Thread worker;
    private long lastAttemptNanos;

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(this::runWorker, "Arrow-Discord-Webhook");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
    }

    public void enqueue(String webhookUrl, String payload, String failureMessage) {
        if (!running.get()) {
            return;
        }

        requests.offer(new WebhookRequest(webhookUrl, payload, failureMessage));
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        requests.clear();

        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runWorker() {
        while (running.get()) {
            try {
                WebhookRequest request = requests.poll(1L, TimeUnit.SECONDS);

                if (request != null) {
                    deliver(request);
                }
            } catch (InterruptedException ignored) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Throwable throwable) {
                // Keep one malformed request from terminating the queue worker.
                throwable.printStackTrace();
            }
        }
    }

    private void deliver(WebhookRequest request) throws InterruptedException {
        int serverErrorRetries = 0;

        while (running.get()) {
            throttle();

            DeliveryResult result;

            try {
                result = post(request);
            } catch (Exception ignored) {
                System.out.println(request.failureMessage);
                return;
            }

            if (result.statusCode == 429) {
                Thread.sleep(Math.max(MINIMUM_SEND_INTERVAL_MILLIS, result.retryAfterMillis));
                continue;
            }

            if (result.statusCode >= 500 && serverErrorRetries < MAX_SERVER_ERROR_RETRIES) {
                long retryDelay = MINIMUM_SEND_INTERVAL_MILLIS << serverErrorRetries++;
                Thread.sleep(retryDelay);
                continue;
            }

            return;
        }
    }

    private void throttle() throws InterruptedException {
        long now = System.nanoTime();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - lastAttemptNanos);
        long delay = MINIMUM_SEND_INTERVAL_MILLIS - elapsedMillis;

        if (lastAttemptNanos != 0L && delay > 0L) {
            Thread.sleep(delay);
        }

        lastAttemptNanos = System.nanoTime();
    }

    private DeliveryResult post(WebhookRequest request) throws IOException {
        if (request.webhookUrl == null || request.webhookUrl.trim().isEmpty()) {
            throw new IOException("Webhook URL is empty");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(request.webhookUrl).openConnection();

        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.payload.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);
            long retryAfterMillis = statusCode == 429
                    ? parseRetryAfterMillis(connection.getHeaderField("Retry-After"), responseBody)
                    : 0L;

            return new DeliveryResult(statusCode, retryAfterMillis);
        } finally {
            connection.disconnect();
        }
    }

    private String readResponseBody(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream stream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if (stream == null) {
            return "";
        }

        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private long parseRetryAfterMillis(String retryAfterHeader, String responseBody) {
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(responseBody == null ? "" : responseBody);

        if (matcher.find()) {
            return secondsToMillis(matcher.group(1));
        }

        if (retryAfterHeader != null && !retryAfterHeader.trim().isEmpty()) {
            return secondsToMillis(retryAfterHeader.trim());
        }

        return 1000L;
    }

    private long secondsToMillis(String seconds) {
        try {
            return Math.max(1L, (long) Math.ceil(Double.parseDouble(seconds) * 1000.0D));
        } catch (NumberFormatException ignored) {
            return 1000L;
        }
    }

    private static class WebhookRequest {
        String webhookUrl;
        String payload;
        String failureMessage;

        private WebhookRequest(String webhookUrl, String payload, String failureMessage) {
            this.webhookUrl = webhookUrl;
            this.payload = payload == null ? "" : payload;
            this.failureMessage = failureMessage == null
                    ? "Failed to send Discord webhook"
                    : failureMessage;
        }
    }

    private static class DeliveryResult {
        int statusCode;
        long retryAfterMillis;

        private DeliveryResult(int statusCode, long retryAfterMillis) {
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }
    }
}
