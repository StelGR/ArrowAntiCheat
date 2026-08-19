package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import org.bukkit.GameMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Detects target-relative correction behavior.

 * Two independent paths are used:
 *  - repeated correction snaps which erase most target error in one rotation;
 *  - small smooth corrections which repeatedly follow the target-error vector
 *    with an unusually stable correction gain.

 * A single normal flick/snap is not enough to flag.
 */
@Experimental
public class AimH extends Check {

    private static final int WINDOW_SIZE = 32;
    private static final int ANALYZE_EVERY = 4;
    private static final int COMBAT_SAMPLE_TICKS = 10;
    private static final long MAX_WINDOW_GAP_MS = 1500L;

    private final List<CorrectionSample> samples = new ArrayList<>(WINDOW_SIZE);

    private int targetId = -1;
    private int samplesSinceAnalysis;
    private long lastSampleTime;

    private double snapBuffer;
    private double smoothBuffer;
    private int pendingSnapFollowTicks;

    public AimH(Profile profile) {
        super(profile, CheckType.AIM, "H", "Target-relative correction snaps and smoothing");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            if (profile.getCombatData().getAttackedTicks() > COMBAT_SAMPLE_TICKS) {
                resetWindow(false);
                return;
            }

            collect(profile.getCombatData().getTarget());
        }
    }

    private void collect(int entityId) {
        MovementData movement = profile.getMovementData();
        RotationData rotation = profile.getRotationData();

        if (!canSample(movement, rotation)) {
            resetWindow(false);
            return;
        }

        UUID uuid = profile.getCombatData().getTrackedEntities().get(entityId);

        if (uuid == null || uuid.equals(profile.getPlayer().getUniqueId())) {
            return;
        }

        Profile target = Arrow.getInstance().getProfileManager().getProfile(uuid);

        if (!canUseTarget(target)) {
            resetWindow(false);
            return;
        }

        int pingTicks = getPingTicks();

        if (pingTicks > 12) {
            resetWindow(false);
            return;
        }

        CustomLocation targetLocation = rewind(target.getMovementData().getPastLocations(), pingTicks + 1);

        if (targetLocation == null) {
            return;
        }

        CustomLocation attacker = movement.getLocation();
        double eyeY = attacker.getY() + getEyeHeight();
        double relativeX = targetLocation.getX() - attacker.getX();
        double relativeZ = targetLocation.getZ() - attacker.getZ();
        double horizontalDistance = Math.hypot(relativeX, relativeZ);

        if (horizontalDistance < 0.35D || horizontalDistance > 7.0D) {
            resetWindow(false);
            return;
        }

        double targetHeight = target.getActionData() != null && target.getActionData().isSneaking()
                ? 1.50D
                : 1.80D;

        double desiredYaw = Math.toDegrees(Math.atan2(-relativeX, relativeZ));
        PitchRange pitchRange = getTargetPitchRange(
                eyeY,
                horizontalDistance,
                targetLocation.getY(),
                targetHeight
        );

        double yawChange = wrap(rotation.getYaw() - rotation.getLastYaw());
        double pitchChange = rotation.getPitch() - rotation.getLastPitch();
        double changeMagnitude = Math.hypot(yawChange, pitchChange);

        if (changeMagnitude < 0.015D || changeMagnitude > 120.0D) {
            return;
        }

        double previousYawError = wrap(desiredYaw - rotation.getLastYaw());
        double currentYawError = wrap(desiredYaw - rotation.getYaw());
        double previousPitchError = pitchRange.error(rotation.getLastPitch());
        double currentPitchError = pitchRange.error(rotation.getPitch());

        double previousError = Math.hypot(previousYawError, previousPitchError);
        double currentError = Math.hypot(currentYawError, currentPitchError);

        if (previousError < 0.08D || previousError > 90.0D) {
            return;
        }

        double dot = previousYawError * yawChange + previousPitchError * pitchChange;
        double alignmentDenominator = previousError * changeMagnitude;
        double alignment = alignmentDenominator <= 1.0E-9D ? 0.0D : dot / alignmentDenominator;
        double projectedGain = dot / Math.max(1.0E-9D, previousError * previousError);
        double improvement = previousError - currentError;
        double reductionRatio = improvement / Math.max(0.001D, previousError);

        boolean improved = alignment > 0.0D
                && improvement > 0.001D
                && projectedGain > 0.0D
                && projectedGain <= 1.45D;

        long now = System.currentTimeMillis();

        if (targetId != entityId || now - lastSampleTime > MAX_WINDOW_GAP_MS) {
            resetWindow(false);
            targetId = entityId;
        }

        double targetYawTravel = 0.0D;
        double targetPitchTravel = 0.0D;
        double relativeTravel = 0.0D;

        if (!samples.isEmpty()) {
            CorrectionSample previous = samples.get(samples.size() - 1);
            targetYawTravel = Math.abs(wrap(desiredYaw - previous.targetYaw));
            targetPitchTravel = Math.abs(pitchRange.center - previous.targetPitchCenter);
            relativeTravel = Math.hypot(relativeX - previous.relativeX, relativeZ - previous.relativeZ);
        }

        CorrectionSample sample = new CorrectionSample(
                desiredYaw,
                pitchRange.center,
                relativeX,
                relativeZ,
                previousError,
                currentError,
                changeMagnitude,
                alignment,
                projectedGain,
                reductionRatio,
                improved,
                targetYawTravel,
                targetPitchTravel,
                relativeTravel
        );

        handleSnap(sample);

        samples.add(sample);

        while (samples.size() > WINDOW_SIZE) {
            samples.remove(0);
        }

        samplesSinceAnalysis++;
        lastSampleTime = now;

        if (samples.size() >= WINDOW_SIZE && samplesSinceAnalysis >= ANALYZE_EVERY) {
            analyzeSmoothCorrections();
            samplesSinceAnalysis = 0;
        }
    }

    private boolean canSample(MovementData movement, RotationData rotation) {
        return movement != null
                && rotation != null
                && movement.getLocation() != null
                && profile.getActionData() != null
                && !profile.shouldCancel()
                && !profile.isBedrockPlayer()
                && !profile.isSwimming()
                && !profile.isCrawling()
                && !profile.isSleeping()
                && !profile.isExempt().isTeleports()
                && !profile.getPlayer().isInsideVehicle()
                && rotation.getRotationsAfterTeleport() > 5
                && profile.getActionData().getGameMode() != GameMode.CREATIVE
                && profile.getActionData().getGameMode() != GameMode.SPECTATOR;
    }

    private boolean canUseTarget(Profile target) {
        return target != null
                && target.getMovementData() != null
                && target.getMovementData().getLocation() != null
                && target.getMovementData().isMoving()
                && !(target.getMovementData().isUnderblock()
                && target.getMovementData().isNearWall())
                && !target.shouldCancel()
                && !target.isBedrockPlayer()
                && !target.isSwimming()
                && !target.isCrawling()
                && !target.isSleeping()
                && !target.isExempt().isTeleports();
    }

    private void handleSnap(CorrectionSample sample) {
        boolean snap = sample.previousError >= 3.5D
                && sample.change >= 2.75D
                && sample.alignment >= 0.965D
                && sample.reductionRatio >= 0.68D
                && (sample.currentError <= 2.20D || sample.reductionRatio >= 0.82D);

        boolean perfectSnap = sample.previousError >= 6.0D
                && sample.change >= 5.0D
                && sample.alignment >= 0.992D
                && sample.reductionRatio >= 0.88D
                && sample.currentError <= 0.90D;

        boolean snapFollowCorrection = pendingSnapFollowTicks > 0
                && sample.previousError >= 0.10D
                && sample.previousError <= 3.25D
                && sample.change <= 2.25D
                && sample.alignment >= 0.93D
                && sample.reductionRatio >= 0.42D;

        if (perfectSnap) {
            snapBuffer += 1.75D;
            pendingSnapFollowTicks = 3;
        } else if (snap) {
            snapBuffer += 1.00D;
            pendingSnapFollowTicks = 3;
        } else if (snapFollowCorrection) {
            snapBuffer += 0.75D;
            pendingSnapFollowTicks = 0;
        } else {
            snapBuffer = Math.max(0.0D, snapBuffer - 0.12D);
            pendingSnapFollowTicks = Math.max(0, pendingSnapFollowTicks - 1);
        }

        /*
         * A normal player may make one very good flick. Requiring accumulated
         * target-relative snap evidence prevents a single normal snap from flagging.
         */
        if (snapBuffer > 2.65D) {
            fail("Target Correction Snap",
                    "perfect " + MsgType.MAIN_THEME_COLOR.getMessage() + perfectSnap
                            + "\nfollowCorrection " + MsgType.MAIN_THEME_COLOR.getMessage() + snapFollowCorrection
                            + "\npreviousError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.previousError)
                            + "\ncurrentError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.currentError)
                            + "\nchange " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.change)
                            + "\nalignment " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.alignment)
                            + "\nreductionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.reductionRatio)
                            + "\nprojectedGain " + MsgType.MAIN_THEME_COLOR.getMessage() + format(sample.gain)
                            + "\nsnapBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(snapBuffer));

            snapBuffer = Math.max(1.20D, snapBuffer - 1.45D);
        }
    }

    private void analyzeSmoothCorrections() {
        int size = samples.size();
        double[] gains = new double[size];
        double[] alignments = new double[size];
        double[] gainSteps = new double[size];

        int valid = 0;
        int micro = 0;
        int verySmall = 0;
        int improved = 0;
        int aligned = 0;
        int stableGainTransitions = 0;
        int longestStableGainStreak = 0;
        int currentStableGainStreak = 0;

        double targetTravel = 0.0D;
        double relativeTravel = 0.0D;
        double previousGain = Double.NaN;

        for (CorrectionSample sample : samples) {
            targetTravel += sample.targetYawTravel + sample.targetPitchTravel;
            relativeTravel += sample.relativeTravel;

            if (!sample.improved
                    || sample.previousError < 0.15D
                    || sample.previousError > 35.0D
                    || sample.change < 0.025D
                    || sample.change > 6.0D
                    || sample.gain <= 0.0D
                    || sample.gain > 1.20D) {
                currentStableGainStreak = 0;
                continue;
            }

            improved++;

            if (sample.alignment >= 0.80D) {
                aligned++;
            }

            if (sample.alignment < 0.72D) {
                currentStableGainStreak = 0;
                continue;
            }

            gains[valid] = sample.gain;
            alignments[valid] = sample.alignment;

            if (sample.change <= 1.75D) {
                micro++;
            }

            if (sample.change <= 0.75D) {
                verySmall++;
            }

            if (Double.isFinite(previousGain)) {
                double gainStep = Math.abs(sample.gain - previousGain);
                gainSteps[valid - 1] = gainStep;

                if (gainStep <= 0.055D) {
                    stableGainTransitions++;
                    currentStableGainStreak++;
                } else {
                    currentStableGainStreak = 0;
                }

                longestStableGainStreak = Math.max(longestStableGainStreak, currentStableGainStreak);
            }

            previousGain = sample.gain;
            valid++;
        }

        double correctionRatio = improved / (double) size;
        double alignedRatio = aligned / (double) Math.max(1, improved);

        if (valid < 14) {
            smoothBuffer = Math.max(0.0D, smoothBuffer - 0.45D);
            verboseSmooth(valid, correctionRatio, alignedRatio, 0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, targetTravel, relativeTravel,
                    longestStableGainStreak, false, false);
            return;
        }

        double medianGain = median(gains, valid);
        double gainMad = medianAbsoluteDeviation(gains, valid, medianGain);
        double meanAlignment = average(alignments, valid);
        double alignmentStd = standardDeviation(alignments, valid, meanAlignment);
        double microRatio = micro / (double) valid;
        double verySmallRatio = verySmall / (double) valid;
        double stableGainRatio = stableGainTransitions / (double) Math.max(1, valid - 1);
        double medianGainStep = median(gainSteps, Math.max(0, valid - 1));

        boolean dynamicScene = targetTravel >= 2.0D || relativeTravel >= 0.16D;

        boolean smoothAssist = dynamicScene
                && correctionRatio >= 0.76D
                && alignedRatio >= 0.84D
                && meanAlignment >= 0.915D
                && alignmentStd <= 0.095D
                && medianGain >= 0.025D
                && medianGain <= 0.85D
                && gainMad <= 0.050D
                && microRatio >= 0.48D
                && stableGainRatio >= 0.48D
                && longestStableGainStreak >= 5;

        boolean microAssist = dynamicScene
                && correctionRatio >= 0.82D
                && alignedRatio >= 0.88D
                && meanAlignment >= 0.945D
                && medianGain >= 0.015D
                && medianGain <= 0.60D
                && gainMad <= 0.037D
                && microRatio >= 0.66D
                && verySmallRatio >= 0.28D
                && medianGainStep <= 0.045D;

        if (smoothAssist || microAssist) {
            smoothBuffer += smoothAssist && microAssist ? 1.35D : 0.90D;

            if (smoothBuffer > 2.45D) {
                fail("Smooth Target Correction",
                        "pattern " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + (smoothAssist && microAssist ? "smooth-micro" : microAssist ? "micro" : "smooth")
                                + "\nvalid " + MsgType.MAIN_THEME_COLOR.getMessage() + valid
                                + "\ncorrectionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(correctionRatio)
                                + "\nalignedRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(alignedRatio)
                                + "\nmeanAlignment " + MsgType.MAIN_THEME_COLOR.getMessage() + format(meanAlignment)
                                + "\nalignmentStd " + MsgType.MAIN_THEME_COLOR.getMessage() + format(alignmentStd)
                                + "\nmedianGain " + MsgType.MAIN_THEME_COLOR.getMessage() + format(medianGain)
                                + "\ngainMad " + MsgType.MAIN_THEME_COLOR.getMessage() + format(gainMad)
                                + "\nmicroRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(microRatio)
                                + "\nverySmallRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(verySmallRatio)
                                + "\nstableGainRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stableGainRatio)
                                + "\nmedianGainStep " + MsgType.MAIN_THEME_COLOR.getMessage() + format(medianGainStep)
                                + "\nlongestStableGainStreak " + MsgType.MAIN_THEME_COLOR.getMessage() + longestStableGainStreak
                                + "\ntargetTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(targetTravel)
                                + "\nrelativeTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(relativeTravel));

                smoothBuffer = Math.max(1.0D, smoothBuffer - 1.25D);
            }
        } else {
            smoothBuffer = Math.max(0.0D, smoothBuffer - (dynamicScene ? 0.35D : 0.60D));
        }

        verboseSmooth(valid, correctionRatio, alignedRatio, meanAlignment, alignmentStd,
                medianGain, gainMad, microRatio, targetTravel, relativeTravel,
                longestStableGainStreak, smoothAssist, microAssist);
    }

    private void verboseSmooth(int valid,
                               double correctionRatio,
                               double alignedRatio,
                               double meanAlignment,
                               double alignmentStd,
                               double medianGain,
                               double gainMad,
                               double microRatio,
                               double targetTravel,
                               double relativeTravel,
                               int longestStableGainStreak,
                               boolean smooth,
                               boolean micro) {
        verbose(this.getClass().getSimpleName(), Math.max(snapBuffer, smoothBuffer), 2.65D,
                "valid " + MsgType.MAIN_THEME_COLOR.getMessage() + valid
                        + "\ncorrectionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(correctionRatio)
                        + "\nalignedRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(alignedRatio)
                        + "\nmeanAlignment " + MsgType.MAIN_THEME_COLOR.getMessage() + format(meanAlignment)
                        + "\nalignmentStd " + MsgType.MAIN_THEME_COLOR.getMessage() + format(alignmentStd)
                        + "\nmedianGain " + MsgType.MAIN_THEME_COLOR.getMessage() + format(medianGain)
                        + "\ngainMad " + MsgType.MAIN_THEME_COLOR.getMessage() + format(gainMad)
                        + "\nmicroRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(microRatio)
                        + "\nlongestStableGainStreak " + MsgType.MAIN_THEME_COLOR.getMessage() + longestStableGainStreak
                        + "\ntargetTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(targetTravel)
                        + "\nrelativeTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(relativeTravel)
                        + "\nsnapBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(snapBuffer)
                        + "\nsmoothBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(smoothBuffer)
                        + "\nsmooth " + MsgType.MAIN_THEME_COLOR.getMessage() + smooth
                        + "\nmicro " + MsgType.MAIN_THEME_COLOR.getMessage() + micro);
    }

    private PitchRange getTargetPitchRange(double eyeY,
                                           double horizontalDistance,
                                           double targetY,
                                           double targetHeight) {
        double lowAimY = targetY + targetHeight * 0.10D;
        double highAimY = targetY + targetHeight * 0.93D;

        double pitchLow = -Math.toDegrees(Math.atan2(lowAimY - eyeY, horizontalDistance));
        double pitchHigh = -Math.toDegrees(Math.atan2(highAimY - eyeY, horizontalDistance));

        return new PitchRange(Math.min(pitchLow, pitchHigh), Math.max(pitchLow, pitchHigh));
    }

    private CustomLocation rewind(SampleList<CustomLocation> history, int age) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        try {
            int index = Math.max(0, history.size() - 1 - Math.max(0, age));
            return history.get(index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getPingTicks() {
        ConnectionData connection = profile.getConnectionData();

        if (connection == null) {
            return 0;
        }

        int ticks = Math.max(0, connection.getClientTickTrans());
        ticks = Math.max(ticks, (int) Math.ceil(Math.max(0, connection.getTransPing()) / 50.0D));
        ticks = Math.max(ticks, (int) Math.ceil(Math.max(0, connection.getPing()) / 50.0D));
        return Math.min(20, ticks);
    }

    private double getEyeHeight() {
        return profile.getActionData() != null && profile.getActionData().isSneaking() ? 1.54D : 1.62D;
    }

    private double average(double[] values, int length) {
        if (length <= 0) {
            return 0.0D;
        }

        double total = 0.0D;

        for (int i = 0; i < length; i++) {
            total += values[i];
        }

        return total / length;
    }

    private double standardDeviation(double[] values, int length, double mean) {
        if (length <= 0) {
            return 0.0D;
        }

        double variance = 0.0D;

        for (int i = 0; i < length; i++) {
            double difference = values[i] - mean;
            variance += difference * difference;
        }

        return Math.sqrt(variance / length);
    }

    private double median(double[] values, int length) {
        if (length <= 0) {
            return 0.0D;
        }

        double[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        int middle = length / 2;

        return length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) * 0.5D
                : copy[middle];
    }

    private double medianAbsoluteDeviation(double[] values, int length, double center) {
        if (length <= 0) {
            return 0.0D;
        }

        double[] deviations = new double[length];

        for (int i = 0; i < length; i++) {
            deviations[i] = Math.abs(values[i] - center);
        }

        return median(deviations, length);
    }

    private double wrap(double angle) {
        angle %= 360.0D;

        if (angle >= 180.0D) {
            angle -= 360.0D;
        } else if (angle < -180.0D) {
            angle += 360.0D;
        }

        return angle;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private void resetWindow(boolean decay) {
        samples.clear();
        targetId = -1;
        samplesSinceAnalysis = 0;
        lastSampleTime = 0L;
        pendingSnapFollowTicks = 0;

        if (decay) {
            snapBuffer = Math.max(0.0D, snapBuffer - 0.25D);
            smoothBuffer = Math.max(0.0D, smoothBuffer - 0.25D);
        }
    }

    private static final class PitchRange {
        private final double minimum;
        private final double maximum;
        private final double center;

        private PitchRange(double minimum, double maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
            this.center = (minimum + maximum) * 0.5D;
        }

        private double error(double pitch) {
            if (pitch < minimum) {
                return minimum - pitch;
            }

            if (pitch > maximum) {
                return maximum - pitch;
            }

            return 0.0D;
        }
    }

    private static final class CorrectionSample {
        private final double targetYaw;
        private final double targetPitchCenter;
        private final double relativeX;
        private final double relativeZ;
        private final double previousError;
        private final double currentError;
        private final double change;
        private final double alignment;
        private final double gain;
        private final double reductionRatio;
        private final boolean improved;
        private final double targetYawTravel;
        private final double targetPitchTravel;
        private final double relativeTravel;

        private CorrectionSample(double targetYaw,
                                 double targetPitchCenter,
                                 double relativeX,
                                 double relativeZ,
                                 double previousError,
                                 double currentError,
                                 double change,
                                 double alignment,
                                 double gain,
                                 double reductionRatio,
                                 boolean improved,
                                 double targetYawTravel,
                                 double targetPitchTravel,
                                 double relativeTravel) {
            this.targetYaw = targetYaw;
            this.targetPitchCenter = targetPitchCenter;
            this.relativeX = relativeX;
            this.relativeZ = relativeZ;
            this.previousError = previousError;
            this.currentError = currentError;
            this.change = change;
            this.alignment = alignment;
            this.gain = gain;
            this.reductionRatio = reductionRatio;
            this.improved = improved;
            this.targetYawTravel = targetYawTravel;
            this.targetPitchTravel = targetPitchTravel;
            this.relativeTravel = relativeTravel;
        }
    }
}
