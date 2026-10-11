package me.arrow.neural;

import lombok.Getter;
import me.arrow.files.Config;

/**
 * Holds the global NeuralNetClient instance.
 * Training is controlled entirely from the Python server (--training flag),
 * not from the anticheat config.
 */
public class NeuralNetManager {

    @Getter
    private static NeuralNetClient client;

    public static void initialize() {
        String url = Config.Setting.NEURAL_SERVER_URL.getString();

        if (url == null || url.isEmpty()) {
            client = null;
            return;
        }

        client = new NeuralNetClient(url);
        client.checkHealth();
    }

    public static void shutdown() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        TrainingManager.stopAll();
    }
}
