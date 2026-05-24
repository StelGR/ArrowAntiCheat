package me.arrow.managers.logs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Hastebin {

    private static final String MCLOGS_UPLOAD_URL = "https://api.mclo.gs/1/log";
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ERROR_PATTERN = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]+)\"");

    private Hastebin() {
    }

    public static String uploadPaste(String contents) {
        if (contents == null || contents.trim().isEmpty()) {
            return null;
        }

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(MCLOGS_UPLOAD_URL).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setDoInput(true);
            connection.setDoOutput(true);

            connection.setRequestProperty("User-Agent", "Arrow-Anticheat");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

            String body =
                    "content=" + URLEncoder.encode(contents, StandardCharsets.UTF_8)
                            + "&source=" + URLEncoder.encode("Arrow Anticheat", StandardCharsets.UTF_8);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();

            String response = readResponse(connection, responseCode);

            if (responseCode < 200 || responseCode >= 300) {
                System.out.println("[Arrow] mclo.gs upload failed HTTP " + responseCode + ": " + response);
                return null;
            }

            Matcher urlMatcher = URL_PATTERN.matcher(response);

            if (urlMatcher.find()) {
                return urlMatcher.group(1).replace("\\/", "/");
            }

            Matcher errorMatcher = ERROR_PATTERN.matcher(response);

            if (errorMatcher.find()) {
                System.out.println("[Arrow] mclo.gs upload failed: " + errorMatcher.group(1));
            } else {
                System.out.println("[Arrow] mclo.gs upload returned unexpected response: " + response);
            }

            return null;
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readResponse(HttpURLConnection connection, int responseCode) {
        StringBuilder response = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } catch (Exception ignored) {
        }

        return response.toString();
    }
}