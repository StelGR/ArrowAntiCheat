package me.arrow.neural;

import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active training sessions.  Singleton, accessed from the check and commands.
 * Training is gated behind config: neural.training-enabled must be true.
 */
public class TrainingManager {

    private static final Map<String, TrainingSession> sessions = new ConcurrentHashMap<>();

    /** Start recording rotation data for a player. */
    public static String startSession(String playerName, boolean cheating) {
        if (sessions.containsKey(playerName.toLowerCase())) {
            return "§cA training session is already active for " + playerName;
        }

        sessions.put(playerName.toLowerCase(), new TrainingSession(playerName, cheating));
        String label = cheating ? "§ccheating" : "§alegit";
        return "§aStarted training session for §f" + playerName + " §7as " + label;
    }

    /** Stop recording and save to CSV.  Returns a status message. */
    public static String stopSession(String playerName) {
        TrainingSession session = sessions.remove(playerName.toLowerCase());
        if (session == null) {
            return "§cNo active training session for " + playerName;
        }

        File dataDir = new File(Arrow.getInstance().getDataFolder(), "neural/data");
        String path = session.stop(dataDir);

        if (path == null) {
            return "§cNo samples were collected for " + playerName;
        }

        return "§aStopped training for §f" + playerName
                + " §7— §f" + session.getSampleCount() + " §7samples saved to §f" + path;
    }

    /** Called every tick by the check to record samples if a session is active. */
    public static void recordTick(Profile profile) {
        String name = profile.getPlayer().getName().toLowerCase();
        TrainingSession session = sessions.get(name);
        if (session != null && session.isActive()) {
            session.record(profile);
        }
    }

    public static boolean hasActiveSession(String playerName) {
        return sessions.containsKey(playerName.toLowerCase());
    }

    public static void stopAll() {
        for (String key : sessions.keySet()) {
            stopSession(key);
        }
    }
}
