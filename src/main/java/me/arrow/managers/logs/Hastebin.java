package me.arrow.managers.logs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Hastebin {

    private static final String PASTE_URL = "https://paste.md-5.net/";
    private static final String PASTE_UPLOAD_URL = "https://paste.md-5.net/documents";
    private static final Pattern KEY_PATTERN = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"");

    private Hastebin() {
    }

    public static String uploadPaste(String contents) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(PASTE_UPLOAD_URL).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "Arrow-Anticheat");
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(contents.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();

            if (responseCode < 200 || responseCode >= 300) {
                return null;
            }

            StringBuilder response = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            Matcher matcher = KEY_PATTERN.matcher(response.toString());

            if (!matcher.find()) {
                return null;
            }

            return PASTE_URL + matcher.group(1);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
