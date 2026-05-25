package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Experimental
public class ScaffoldB extends Check {

    private static final int MAX_SAMPLES = 80;
    private static final int MIN_SAMPLES = 48;
    private static final int PLACE_CONTEXT_TICKS = 8;

    private static final double MIN_YAW_SAMPLE = 0.025D;
    private static final double MIN_PITCH_SAMPLE = 0.010D;

    private static final double SAME_YAW_EPSILON = 0.035D;
    private static final double SAME_PITCH_EPSILON = 0.018D;

    private static final double YAW_SNAP = 18.0D;
    private static final double PITCH_SNAP = 8.0D;

    private final List<Double> yawSamples = new ArrayList<>();
    private final List<Double> pitchSamples = new ArrayList<>();
    private final List<Double> signedYawSamples = new ArrayList<>();
    private final List<Double> signedPitchSamples = new ArrayList<>();

    private int recentPlaceTicks = 100;
    private int placementsInWindow;
    private double buffer;

    public ScaffoldB(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "B", "Checks for scaffold aim patterns");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            this.recentPlaceTicks = 0;
            this.placementsInWindow++;
            return;
        }

        if (!isFlyingPacket(event)) {
            return;
        }

        this.recentPlaceTicks++;

        if (!isRotationPacket(event)) {
            return;
        }

        if (!isScaffoldContext()) {
            decay();
            clearIfStale();
            return;
        }

        RotationData rotationData = profile.getRotationData();

        if (rotationData == null || rotationData.getCinematicProcessor().isCinematic()) {
            decay();
            return;
        }

        double deltaYaw = rotationData.getDeltaYaw();
        double deltaPitch = rotationData.getDeltaPitch();

        if (Math.abs(deltaYaw) < MIN_YAW_SAMPLE && Math.abs(deltaPitch) < MIN_PITCH_SAMPLE) {
            return;
        }

        addSample(signedYawSamples, deltaYaw);
        addSample(signedPitchSamples, deltaPitch);
        addSample(yawSamples, Math.abs(deltaYaw));
        addSample(pitchSamples, Math.abs(deltaPitch));

        if (yawSamples.size() < MIN_SAMPLES) {
            return;
        }

        Analysis yaw = analyze(yawSamples, signedYawSamples, SAME_YAW_EPSILON, YAW_SNAP, 2.5D, false);
        Analysis pitch = analyze(pitchSamples, signedPitchSamples, SAME_PITCH_EPSILON, PITCH_SNAP, 1.5D, true);

        double suspicion = 0.0D;
        String reasons = "";

        if (yaw.snapCount >= 5) {
            suspicion += 4.0D;
            reasons += "yaw snaps; ";
        }

        if (yaw.alternatingSameMagnitude >= 7) {
            suspicion += 5.0D;
            reasons += "yaw alternating same delta; ";
        }

        if (yaw.longestIdenticalStreak >= 5 && yaw.average > 1.0D) {
            suspicion += 3.5D;
            reasons += "yaw repeated exact delta; ";
        }

        if (yaw.uniqueRatio < 0.28D && yaw.standardDeviation < 2.0D && yaw.average > 0.75D) {
            suspicion += 4.0D;
            reasons += "yaw low diversity; ";
        }

        if (yaw.entropy < 2.0D && yaw.duplicateRatio > 0.35D) {
            suspicion += 3.0D;
            reasons += "yaw low entropy; ";
        }

        if (pitch.snapCount >= 4 && pitch.maximum > 7.5D) {
            suspicion += 3.5D;
            reasons += "pitch snaps; ";
        }

        if (pitch.alternatingSameMagnitude >= 6 && pitch.maximum > 4.0D) {
            suspicion += 4.0D;
            reasons += "pitch alternating same delta; ";
        }

        if (pitch.longestIdenticalStreak >= 6 && pitch.average > 0.6D) {
            suspicion += 2.5D;
            reasons += "pitch repeated exact delta; ";
        }

        if (pitch.uniqueRatio < 0.18D && pitch.standardDeviation < 0.8D && pitch.average > 0.35D) {
            suspicion += 2.5D;
            reasons += "pitch low diversity; ";
        }

        if (yaw.snapThenStopCount >= 4) {
            suspicion += 3.0D;
            reasons += "yaw snap-stop pattern; ";
        }

        if (pitch.snapThenStopCount >= 3 && pitch.maximum > 6.0D) {
            suspicion += 2.0D;
            reasons += "pitch snap-stop pattern; ";
        }

        if (placementsInWindow < 6) {
            suspicion *= 0.55D;
        }

        if (suspicion >= 12.0D) {
            if (++buffer > 2.0D) {
                fail("Scaffold Aim Analysis",
                        "suspicion " + MsgType.MAIN_THEME_COLOR.getMessage() + round(suspicion)
                                + "\nyawAvg " + MsgType.MAIN_THEME_COLOR.getMessage() + round(yaw.average)
                                + "\nyawMax " + MsgType.MAIN_THEME_COLOR.getMessage() + round(yaw.maximum)
                                + "\nyawStd " + MsgType.MAIN_THEME_COLOR.getMessage() + round(yaw.standardDeviation)
                                + "\nyawEntropy " + MsgType.MAIN_THEME_COLOR.getMessage() + round(yaw.entropy)
                                + "\nyawDuplicates " + MsgType.MAIN_THEME_COLOR.getMessage() + round(yaw.duplicateRatio)
                                + "\nyawAlt " + MsgType.MAIN_THEME_COLOR.getMessage() + yaw.alternatingSameMagnitude
                                + "\nyawSnaps " + MsgType.MAIN_THEME_COLOR.getMessage() + yaw.snapCount
                                + "\npitchAvg " + MsgType.MAIN_THEME_COLOR.getMessage() + round(pitch.average)
                                + "\npitchMax " + MsgType.MAIN_THEME_COLOR.getMessage() + round(pitch.maximum)
                                + "\npitchStd " + MsgType.MAIN_THEME_COLOR.getMessage() + round(pitch.standardDeviation)
                                + "\npitchEntropy " + MsgType.MAIN_THEME_COLOR.getMessage() + round(pitch.entropy)
                                + "\npitchDuplicates " + MsgType.MAIN_THEME_COLOR.getMessage() + round(pitch.duplicateRatio)
                                + "\npitchAlt " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch.alternatingSameMagnitude
                                + "\npitchSnaps " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch.snapCount
                                + "\nplacements " + MsgType.MAIN_THEME_COLOR.getMessage() + placementsInWindow
                                + "\nreasons " + MsgType.MAIN_THEME_COLOR.getMessage() + reasons.trim());

                clearSamples();
                buffer = Math.max(0.0D, buffer - 1.0D);
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.075D);
        }

        verbose(this.getClass().getSimpleName(), buffer, 2.0D,
                "suspicion " + round(suspicion)
                        + "\nyawAlt " + yaw.alternatingSameMagnitude
                        + "\nyawSnaps " + yaw.snapCount
                        + "\npitchAlt " + pitch.alternatingSameMagnitude
                        + "\npitchSnaps " + pitch.snapCount
                        + "\nplacements " + placementsInWindow);
    }

    private boolean isScaffoldContext() {
        if (profile == null || profile.getPlayer() == null || profile.getMovementData() == null || profile.getRotationData() == null) {
            return false;
        }

        if (profile.getTick() < 100 || profile.shouldCancel()) {
            return false;
        }

        try {
            if (profile.isExempt().isTeleports()) {
                return false;
            }
        } catch (Throwable ignored) {
        }

        if (profile.getPlayer().isInsideVehicle() || profile.getPlayer().getAllowFlight()) {
            return false;
        }

        if (!profile.getMovementData().isMoving()) {
            return false;
        }

        int trans = Math.max(0, profile.getConnectionData().getClientTickTrans());
        boolean recentPlace = recentPlaceTicks <= PLACE_CONTEXT_TICKS + trans
                || profile.getLastBlockPlaceTimer().hasNotPassed(PLACE_CONTEXT_TICKS + trans);

        if (!recentPlace) {
            return false;
        }

        return profile.isAirBridging(profile.getMovementData().getLocation().toBukkit());
    }

    private Analysis analyze(List<Double> absolute, List<Double> signed, double sameEpsilon, double snapValue, double sameMin, boolean pitch) {
        Analysis analysis = new Analysis();

        analysis.average = average(absolute);
        analysis.minimum = min(absolute);
        analysis.maximum = max(absolute);
        analysis.standardDeviation = MathUtil.getStandardDeviation(absolute);
        analysis.entropy = entropy(absolute, pitch ? 0.125D : 0.25D);
        analysis.longestIdenticalStreak = longestIdenticalStreak(absolute, sameEpsilon);
        analysis.duplicates = duplicateCount(absolute, pitch ? 0.05D : 0.10D);
        analysis.duplicateRatio = analysis.duplicates / (double) absolute.size();
        analysis.uniqueRatio = 1.0D - analysis.duplicateRatio;
        analysis.snapCount = snapCount(absolute, snapValue);
        analysis.snapThenStopCount = snapThenStopCount(absolute, snapValue);
        analysis.alternatingSameMagnitude = alternatingSameMagnitude(signed, sameEpsilon, sameMin);

        return analysis;
    }

    private int snapCount(List<Double> values, double snapValue) {
        int count = 0;

        for (double value : values) {
            if (value >= snapValue) {
                count++;
            }
        }

        return count;
    }

    private int snapThenStopCount(List<Double> values, double snapValue) {
        int count = 0;

        for (int i = 1; i < values.size(); i++) {
            double previous = values.get(i - 1);
            double current = values.get(i);

            if (previous >= snapValue && current < 1.0D) {
                count++;
            }
        }

        return count;
    }

    private int alternatingSameMagnitude(List<Double> signed, double epsilon, double minimum) {
        int count = 0;

        for (int i = 1; i < signed.size(); i++) {
            double previous = signed.get(i - 1);
            double current = signed.get(i);

            if (Math.abs(previous) < minimum || Math.abs(current) < minimum) {
                continue;
            }

            boolean oppositeDirection = Math.signum(previous) != Math.signum(current);
            boolean sameMagnitude = Math.abs(Math.abs(previous) - Math.abs(current)) <= epsilon;

            if (oppositeDirection && sameMagnitude) {
                count++;
            }
        }

        return count;
    }

    private int duplicateCount(List<Double> values, double roundTo) {
        Map<Long, Integer> counts = new HashMap<>();
        int duplicates = 0;

        for (double value : values) {
            long rounded = Math.round(value / roundTo);
            int amount = counts.getOrDefault(rounded, 0);

            if (amount > 0) {
                duplicates++;
            }

            counts.put(rounded, amount + 1);
        }

        return duplicates;
    }

    private int longestIdenticalStreak(List<Double> values, double epsilon) {
        if (values.isEmpty()) {
            return 0;
        }

        int best = 1;
        int current = 1;

        for (int i = 1; i < values.size(); i++) {
            if (Math.abs(values.get(i) - values.get(i - 1)) <= epsilon) {
                current++;
            } else {
                best = Math.max(best, current);
                current = 1;
            }
        }

        return Math.max(best, current);
    }

    private double entropy(List<Double> values, double binSize) {
        Map<Long, Integer> counts = new HashMap<>();

        for (double value : values) {
            long bin = (long) Math.floor(value / binSize);
            counts.put(bin, counts.getOrDefault(bin, 0) + 1);
        }

        double entropy = 0.0D;

        for (int frequency : counts.values()) {
            double probability = frequency / (double) values.size();
            entropy -= probability * (Math.log(probability) / Math.log(2.0D));
        }

        return entropy;
    }

    private double average(List<Double> values) {
        double total = 0.0D;

        for (double value : values) {
            total += value;
        }

        return values.isEmpty() ? 0.0D : total / values.size();
    }

    private double min(List<Double> values) {
        double min = Double.MAX_VALUE;

        for (double value : values) {
            min = Math.min(min, value);
        }

        return values.isEmpty() ? 0.0D : min;
    }

    private double max(List<Double> values) {
        double max = 0.0D;

        for (double value : values) {
            max = Math.max(max, value);
        }

        return max;
    }

    private void addSample(List<Double> list, double value) {
        list.add(value);

        while (list.size() > MAX_SAMPLES) {
            list.remove(0);
        }
    }

    private void clearSamples() {
        yawSamples.clear();
        pitchSamples.clear();
        signedYawSamples.clear();
        signedPitchSamples.clear();
        placementsInWindow = 0;
    }

    private void clearIfStale() {
        if (recentPlaceTicks > 40) {
            clearSamples();
        }
    }

    private void decay() {
        buffer = Math.max(0.0D, buffer - 0.025D);
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean isRotationPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private static final class Analysis {
        private double average;
        private double minimum;
        private double maximum;
        private double standardDeviation;
        private double entropy;
        private double duplicateRatio;
        private double uniqueRatio;
        private int duplicates;
        private int snapCount;
        private int snapThenStopCount;
        private int longestIdenticalStreak;
        private int alternatingSameMagnitude;
    }
}
