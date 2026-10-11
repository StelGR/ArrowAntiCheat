package me.arrow.neural;

import lombok.Getter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.processors.impl.SensitivityProcessor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static me.arrow.playerdata.processors.impl.SensitivityProcessor.getGcd;

/**
 * Records per-tick dynamic rotation features for a single player.
 * Writes to CSV when stopped. Polar-style training prioritizes "legit" samples.
 */
public class TrainingSession {

    private static final String CSV_HEADER =
            "deltaYaw,deltaPitch,lastDeltaYaw,lastDeltaPitch,yawAccel,pitchAccel,jerkYaw,jerkPitch,combinedSpeed,ticksSinceAttack,mcpSensitivity,sensitivityPercentage,GCD,yawGCD,pitchGCD,label";

    @Getter
    private final String playerName;
    private final int label; // 0 = legit, 1 = cheating
    private final List<float[]> samples = new ArrayList<>();
    @Getter
    private volatile boolean active = true;

    public TrainingSession(String playerName, boolean cheating) {
        this.playerName = playerName;
        this.label = cheating ? 1 : 0;
    }

    /** Call every flying/rotation tick while active. */
    public void record(Profile profile) {
        if (!active) return;

        // Record current tick's features
        RotationData rotationData = profile.getRotationData();
        SensitivityProcessor sensitivityProcessor = rotationData.getSensitivityProcessor();

        boolean validSensitivity = sensitivityProcessor.getSensitivityPercent() > 0 && sensitivityProcessor.getSensitivityPercent() < 200;

        if (validSensitivity) {
            float mcpSensitivity = (float) sensitivityProcessor.getMcpSensitivity();

            float f = mcpSensitivity * 0.6f + 0.2f;

            float gcd = f * f * f * 1.2f;

            float deltaYaw = rotationData.getDeltaYaw();
            float deltaPitch = rotationData.getDeltaPitch();
            float lastDeltaYaw = rotationData.getLastDeltaYaw();
            float lastDeltaPitch = rotationData.getLastDeltaPitch();
            float yawAccel = rotationData.getYawAccel();
            float pitchAccel = rotationData.getPitchAccel();
            float jerkYaw = Math.abs(yawAccel - rotationData.getLastYawAccel());
            float jerkPitch = Math.abs(pitchAccel - rotationData.getLastPitchAccel());
            float combinedSpeed = (float) Math.hypot(deltaYaw, deltaPitch);
            int ticksSinceAttack = Math.min(profile.getCombatData().getAttackedTicks(), 100);

            float yawGCD = getGcd(Math.abs(deltaYaw), Math.abs(lastDeltaYaw));
            float pitchGCD = getGcd(Math.abs(deltaPitch), Math.abs(lastDeltaPitch));

            float[] sample = new float[]{
                    deltaYaw,
                    deltaPitch,
                    lastDeltaYaw,
                    lastDeltaPitch,
                    yawAccel,
                    pitchAccel,
                    jerkYaw,
                    jerkPitch,
                    combinedSpeed,
                    ticksSinceAttack,
                    mcpSensitivity,
                    sensitivityProcessor.getSensitivityPercent(),
                    gcd,
                    yawGCD,
                    pitchGCD
            };

            samples.add(sample);
        }
    }

    /** Stop recording and write all samples to a CSV file. Returns the file path. */
    public String stop(File dataDir) {
        active = false;

        if (samples.isEmpty()) return null;

        if (!dataDir.exists()) dataDir.mkdirs();

        String labelName = label == 0 ? "legit" : "cheating";
        String fileName = playerName + "_" + labelName + "_" + System.currentTimeMillis() + ".csv";
        File file = new File(dataDir, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(CSV_HEADER);
            writer.newLine();

            for (float[] s : samples) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.length; i++) {
                    if (i > 0) sb.append(',');
                    if (i == 9) {
                        sb.append((int) s[i]);
                    } else {
                        sb.append(s[i]);
                    }
                }
                sb.append(',').append(label);
                writer.write(sb.toString());
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return file.getAbsolutePath();
    }

    public int getSampleCount() {
        return samples.size();
    }
}
