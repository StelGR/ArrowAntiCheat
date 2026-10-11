package me.arrow.checks.impl.suspicion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.neural.NeuralNetClient;
import me.arrow.neural.NeuralNetManager;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.processors.impl.SensitivityProcessor;
import me.arrow.utils.customutils.OtherUtility;

import static me.arrow.playerdata.processors.impl.SensitivityProcessor.getGcd;

// Neural network aim suspicion check.
//
// Collects 30-tick rotation windows and sends them to the inference server.
// Only flags after ≥95% confidence.
// Decreases trust factor on flag rather than instant punishment.

public class Suspicion extends Check {

    private static final int WINDOW_SIZE = 30; // 1.5 seconds of rotations (20 TPS * 1.5s)
    private static final int EVAL_INTERVAL = 10; // Evaluate rolling 1.5s window every 0.5s once full
    private static final int NUM_FEATURES = 15;
    private static final double CONFIDENCE_THRESHOLD = 0.95;

    // Sliding window buffer (1.5-second rolling history)
    private final float[][] window = new float[WINDOW_SIZE][NUM_FEATURES];
    private int windowIndex = 0;
    private boolean windowFull = false;

    // Throttle evaluation interval
    private int tickCounter = 0;

    public Suspicion(Profile profile) {
        super(profile, CheckType.SUSPICION, "",
                "Suspicious aiming pattern (Neural Network)");
    }

    @Override
    public void handle(PacketSendEvent event) {
        // No server-side handling needed
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isRotation(event.getPacketType())) return;

        if (profile.shouldCancel() || profile.isExempt().isTeleports() || profile.isExempt().isVehicle() || profile.getMovementData().getMovingTicks() < 10) return;

        // Record current tick's features
        RotationData rotationData = profile.getRotationData();

        SensitivityProcessor sensitivityProcessor = rotationData.getSensitivityProcessor();

        boolean validSensitivity = sensitivityProcessor.getSensitivityPercent() > 0 && sensitivityProcessor.getSensitivityPercent() < 200;

        if (validSensitivity) {
            float mcpSensitivity = (float) sensitivityProcessor.getMcpSensitivity();

            float f = mcpSensitivity * 0.6f + 0.2f;

            float gcd = f * f * f * 1.2f;

            NeuralNetClient client = NeuralNetManager.getClient();
            if (client == null || !client.isAvailable()) return;

            // Don't flag while training — we're collecting data, not detecting
            if (me.arrow.neural.TrainingManager.hasActiveSession(profile.getPlayer().getName())) {
                me.arrow.neural.TrainingManager.recordTick(profile);
            }


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

            window[windowIndex] = new float[]{
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

            windowIndex = (windowIndex + 1) % WINDOW_SIZE;
            if (!windowFull && windowIndex == 0) windowFull = true;

            tickCounter++;

            // Send prediction every EVAL_INTERVAL ticks (0.5s) once the 1.5-second window is full
            if (!windowFull || tickCounter < EVAL_INTERVAL) return;
            tickCounter = 0;

            // Build ordered window (oldest → newest)
            float[][] ordered = new float[WINDOW_SIZE][NUM_FEATURES];
            for (int i = 0; i < WINDOW_SIZE; i++) {
                int idx = (windowIndex + i) % WINDOW_SIZE;
                System.arraycopy(window[idx], 0, ordered[i], 0, NUM_FEATURES);
            }

            // Async prediction
            client.predict(ordered).thenAccept(confidence -> {
                if (confidence < 0) return; // Error

                if (confidence >= CONFIDENCE_THRESHOLD) {
                    // The higher the confidence above 95%, the higher the trust factor is decreased
                    double excessRatio = Math.min(1.0, Math.max(0.0, (confidence - CONFIDENCE_THRESHOLD) / (1.0 - CONFIDENCE_THRESHOLD)));
                    double trustDecrease = 1.0 + (excessRatio * 3.0);
                    profile.getTrustFactor().decreaseTrustBy(trustDecrease);

                    // Only flag if trust factor is below 45
                    if (profile.getTrustFactor().getTrust() < 90) {
                        int sensitivity = profile.getRotationData().getSensitivityProcessor().getSensitivityPercent();

                        fail("Suspicious aiming pattern",
                                "confidence " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.2f%%", confidence * 100)
                                        + "\nsensitivity " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + sensitivity + "%"
                                        + "\ndeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", deltaYaw)
                                        + "\ndeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", deltaPitch)
                                        + "\nlastDeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", lastDeltaYaw)
                                        + "\nlastDeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", lastDeltaPitch)
                                        + "\ngcd " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", gcd)
                                        + "\nyawGcd " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", yawGCD)
                                        + "\npitchGcd " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", pitchGCD)
                                        + "\nmcpSens " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", mcpSensitivity)
                                        + "\nsensPerc " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + sensitivityProcessor.getSensitivityPercent()
                                        + "\nyawAccel " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", yawAccel)
                                        + "\npitchAccel " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", pitchAccel)
                                        + "\njerkYaw " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", jerkYaw)
                                        + "\njerkPitch " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", jerkPitch)
                                        + "\nspeed " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.4f", combinedSpeed)
                                        + "\ntrustScore " + MsgType.MAIN_THEME_COLOR.getMessage()
                                        + String.format("%.1f", profile.getTrustFactor().getTrust()));
                    }
                } else {
                    profile.getTrustFactor().increaseTrustBy(0.05);
                }
            });
        }
    }
}
