package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.RotationFrame;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Vape AimAssist rotation fingerprint.
 *
 * <p>Vape's simple mode applies integer mouse deltas after accumulating the
 * controller velocity, and swaps that velocity state every ten worker ticks.
 * Consequently, while it has a target, the server receives a sustained,
 * almost entirely horizontal, one-direction trajectory. This check observes
 * that packet rotation trajectory directly: no target, click, or combat state
 * is required to build evidence.</p>
 */
@Experimental
public class AimJ extends Check {

    private static final int WINDOW = 24;
    private static final int ANALYZE_EVERY = 4;
    private static final double MIN_TURN = 0.035D;

    private final List<RotationFrame> frames = new ArrayList<>(WINDOW);
    private int analysesSinceReset;

    public AimJ(Profile profile) {
        super(profile, CheckType.AIM, "J", "Generated mouse rotation trajectory");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!RotationFrame.isRotationPacket(event.getPacketType())) {
            return;
        }

        if (shouldReset()) {
            reset(0.35D);
            return;
        }

        RotationFrame frame = RotationFrame.capture(profile.getRotationData());
        if (frame.length() < MIN_TURN || frame.length() > 48.0D) {
            reset(0.18D);
            return;
        }

        frames.add(frame);
        while (frames.size() > WINDOW) {
            frames.remove(0);
        }

        if (frames.size() == WINDOW && ++analysesSinceReset >= ANALYZE_EVERY) {
            analyze();
            analysesSinceReset = 0;
        }
    }

    private void analyze() {
        int yawTicks = 0;
        int sameDirectionTicks = 0;
        int directionChanges = 0;
        int previousDirection = 0;
        double yawTravel = 0.0D;
        double pitchTravel = 0.0D;
        double yawSpeedSum = 0.0D;
        double yawSpeedSquaredSum = 0.0D;
        double jerkTravel = 0.0D;
        double previousYaw = 0.0D;

        for (RotationFrame frame : frames) {
            double yaw = Math.abs(frame.yaw);
            double pitch = Math.abs(frame.pitch);
            yawTravel += yaw;
            pitchTravel += pitch;

            if (yaw < MIN_TURN) {
                continue;
            }

            int direction = frame.yaw > 0.0D ? 1 : -1;
            if (previousDirection != 0) {
                if (direction == previousDirection) {
                    sameDirectionTicks++;
                } else {
                    directionChanges++;
                }
                jerkTravel += Math.abs(frame.yaw - previousYaw);
            }

            previousDirection = direction;
            previousYaw = frame.yaw;
            yawTicks++;
            yawSpeedSum += yaw;
            yawSpeedSquaredSum += yaw * yaw;
        }

        double meanYawSpeed = yawSpeedSum / Math.max(1, yawTicks);
        double variance = yawSpeedSquaredSum / Math.max(1, yawTicks) - meanYawSpeed * meanYawSpeed;
        double speedVariation = Math.sqrt(Math.max(0.0D, variance)) / Math.max(0.001D, meanYawSpeed);
        double directionRatio = sameDirectionTicks / (double) Math.max(1, yawTicks - 1);
        double normalizedJerk = jerkTravel / Math.max(0.001D, yawTravel);
        double pitchRatio = pitchTravel / Math.max(0.001D, yawTravel);

        /*
         * A generic smooth-turn check would false on normal mouse swipes. The
         * stricter conditions below describe Vape's default horizontal worker:
         * an uninterrupted generated track with essentially no vertical input
         * or human correction/reversal during the full window.
         */
        boolean generatedTrajectory = yawTicks >= 22
                && yawTravel >= 68.0D
                && meanYawSpeed >= 0.70D
                && directionRatio >= 0.95D
                && directionChanges == 0
                && pitchTravel <= 0.45D
                && pitchRatio <= 0.008D
                && speedVariation <= 0.95D
                && normalizedJerk <= 0.95D;

        verbose(getClass().getSimpleName(), getBuffer(), 3.0D,
                "yawTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawTravel)
                        + "\npitchTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchTravel)
                        + "\ndirectionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(directionRatio)
                        + "\ndirectionChanges " + MsgType.MAIN_THEME_COLOR.getMessage() + directionChanges
                        + "\nmeanYawSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(meanYawSpeed)
                        + "\nspeedVariation " + MsgType.MAIN_THEME_COLOR.getMessage() + format(speedVariation)
                        + "\nnormalizedJerk " + MsgType.MAIN_THEME_COLOR.getMessage() + format(normalizedJerk)
                        + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + frames.size());

        if (!generatedTrajectory) {
            decreaseBufferBy(0.45D);
            return;
        }

        if (increaseBufferBy(1.55D) >= 3.0D) {
            fail("Generated Mouse Trajectory",
                    "yawTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawTravel)
                            + "\npitchTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchTravel)
                            + "\ndirectionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(directionRatio)
                            + "\nmeanYawSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(meanYawSpeed)
                            + "\nspeedVariation " + MsgType.MAIN_THEME_COLOR.getMessage() + format(speedVariation));
            decreaseBufferBy(1.50D);
        }
    }

    private boolean shouldReset() {
        return profile.isBedrockPlayer()
                || profile.shouldCancel()
                || profile.isExempt().isVehicle()
                || profile.getRotationData().getCinematicProcessor().isCinematic()
                || profile.getRotationData().getRotationsAfterTeleport() <= 10;
    }

    private void reset(double decay) {
        frames.clear();
        analysesSinceReset = 0;
        decreaseBufferBy(decay);
    }

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
