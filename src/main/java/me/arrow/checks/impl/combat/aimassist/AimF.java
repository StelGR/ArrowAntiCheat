package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;

import java.util.ArrayList;
import java.util.List;


import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.Arrow;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collections;

// this is actually again a check made by GPT 5.5, although it does not seem to flag anything so it may need rework

@Experimental
public class AimF extends Check {

    int SAMPLE_SIZE = 800;

    double MIN_DELTA = 1.0E-4D;
    double MAX_VALID_DELTA = 180.0D;

    int SMOOTH_WINDOW = 80;
    double SMOOTH_WINDOW_MAX_AVERAGE = 1.35D;
    double SMOOTH_WINDOW_MAX_STD = 0.18D;
    double SMOOTH_WINDOW_MIN_AVERAGE = 0.035D;

    double SPIKE_DELTA = 28.0D;
    double SPIKE_PREVIOUS_MAX = 1.25D;
    double SPIKE_NEXT_MAX = 2.25D;

    double FAIL_SUSPICION = 10.0D;
    double SOFT_SUSPICION = 6.0D;

    int ATTACK_SAMPLE_SIZE = 100;
    int MIN_ATTACK_SAMPLE_SIZE = 50;

    double CENTER_LOCK_DISTANCE = 0.085D;
    double CENTER_LOCK_DISTANCE_LAG = 0.145D;

    double SNAP_YAW_DELTA = 18.0D;
    double SNAP_PITCH_DELTA = 7.5D;
    double HARD_SNAP_YAW_DELTA = 35.0D;

    double MAX_CENTER_ANGLE = 3.75D;
    double MAX_CENTER_ANGLE_LAG = 6.25D;

    int TARGET_SWITCH_TICKS = 8;

    List<AttackAimSample> attackSamples = new ArrayList<>(ATTACK_SAMPLE_SIZE);

    int lastTargetEntityId = -1;
    long lastTargetSwitchTime;
    double lastAttackYawDelta;
    double lastAttackPitchDelta;

    List<YawSample> samples = new ArrayList<>(SAMPLE_SIZE);

    public AimF(Profile profile) {
        super(profile, CheckType.AIM, "F", "Aim Heuristics Analysis (Yaw)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            handleAttack(event);
            return;
        }

        if (!isRotation(event)) {
            return;
        }

        RotationData rotationData = profile.getRotationData();

        if (rotationData == null
                || rotationData.getCinematicProcessor().isCinematic()
                || profile.shouldCancel()
                || !profile.getMovementData().isMoving()
                || profile.getCombatData().getAttackedTicks() > 35) {
            decreaseBufferBy(0.025D);
            return;
        }

        float yaw = rotationData.getYaw();
        float deltaYaw = rotationData.getDeltaYaw();
        double absoluteDeltaYaw = Math.abs(deltaYaw);

        if (Double.isNaN(absoluteDeltaYaw)
                || Double.isInfinite(absoluteDeltaYaw)
                || absoluteDeltaYaw < MIN_DELTA
                || absoluteDeltaYaw > MAX_VALID_DELTA) {
            return;
        }

        samples.add(new YawSample(yaw, deltaYaw, absoluteDeltaYaw, System.currentTimeMillis()));

        if (samples.size() < SAMPLE_SIZE) {
            return;
        }

        analyze();
        samples.clear();
    }

    private void analyze() {
        List<Double> deltas = new ArrayList<>(samples.size());
        List<Double> signedDeltas = new ArrayList<>(samples.size());
        List<Long> intervals = new ArrayList<>(samples.size() - 1);

        for (int i = 0; i < samples.size(); i++) {
            YawSample sample = samples.get(i);

            deltas.add(sample.absoluteDeltaYaw);
            signedDeltas.add((double) sample.deltaYaw);

            if (i > 0) {
                long interval = sample.timestamp - samples.get(i - 1).timestamp;

                if (interval > 0L && interval < 1000L) {
                    intervals.add(interval);
                }
            }
        }

        double average = average(deltas);
        double min = min(deltas);
        double max = max(deltas);
        double stdDev = standardDeviation(deltas, average);
        double skewness = skewness(deltas, average, stdDev);
        double kurtosis = kurtosis(deltas, average, stdDev);

        double median = percentile(deltas, 0.50D);
        double p75 = percentile(deltas, 0.75D);
        double p95 = percentile(deltas, 0.95D);
        double p99 = percentile(deltas, 0.99D);

        double entropyFine = entropy(deltas, 0.05D);
        double entropyMedium = entropy(deltas, 0.25D);

        double roundedRatio = roundedRatio(deltas, 0.05D);
        double duplicateRatio = duplicateRatio(deltas, 0.001D);
        double smallRatio = ratioBelow(deltas, 0.75D);
        double tinyRatio = ratioBelow(deltas, 0.25D);

        int smoothWindows = countSmoothWindows(deltas);
        int spikeCount = countSpikes(deltas);
        int spikeRecoveries = countSpikeRecoveries(deltas);
        int longestSameDirection = longestSameDirectionStreak(signedDeltas);
        int longestRoundedStreak = longestRoundedStreak(deltas, 0.01D);

        double spikeRecoveryRatio = spikeCount == 0 ? 0.0D : spikeRecoveries / (double) spikeCount;
        double intervalStd = intervals.isEmpty() ? 0.0D : standardDeviationLong(intervals, averageLong(intervals));

        double suspicion = 0.0D;
        StringBuilder reasons = new StringBuilder();

        if (smoothWindows >= 9 && average < 1.45D && stdDev < 0.42D && entropyFine < 3.1D) {
            suspicion += 4.25D;
            reasons.append("Smooth Windows; ");
        }

        if (smoothWindows >= 14 && p95 < 2.4D && stdDev < 0.35D) {
            suspicion += 4.0D;
            reasons.append("Sustained Smoothness; ");
        }

        if (tinyRatio > 0.58D && average < 0.9D && stdDev < 0.30D && entropyFine < 2.7D) {
            suspicion += 3.75D;
            reasons.append("Overly Fine Tracking; ");
        }

        if (roundedRatio > 0.72D && duplicateRatio > 0.38D && entropyMedium < 2.25D) {
            suspicion += 4.25D;
            reasons.append("Rounding Pattern; ");
        }

        if (longestRoundedStreak >= 18 && stdDev < 0.55D) {
            suspicion += 3.25D;
            reasons.append("Repeated Rounded Values; ");
        }

        if (spikeCount >= 11 && spikeRecoveryRatio > 0.58D) {
            suspicion += 5.0D;
            reasons.append("Repeated Snap Recoveries; ");
        }

        if (spikeCount >= 17) {
            suspicion += 3.0D;
            reasons.append("High Spike Count; ");
        }

        if (max > 75.0D && spikeRecoveries >= 4 && p95 < 8.0D) {
            suspicion += 2.75D;
            reasons.append("Large Isolated Snaps; ");
        }

        if (kurtosis > 7.5D && spikeCount >= 6) {
            suspicion += 2.5D;
            reasons.append("Heavy Spike Distribution; ");
        }

        if (Math.abs(skewness) > 2.0D && spikeCount >= 5) {
            suspicion += 1.5D;
            reasons.append("Skewed Distribution; ");
        }

        if (longestSameDirection >= 140 && average < 2.2D && p95 < 5.0D) {
            suspicion += 2.0D;
            reasons.append("Long Direction Bias; ");
        }

        if (smallRatio > 0.82D && p99 < 4.5D && entropyFine < 3.0D) {
            suspicion += 3.0D;
            reasons.append("Low Range Combat Aim; ");
        }

        if (average < 0.65D && median < 0.35D && p95 < 1.7D && stdDev < 0.28D) {
            suspicion += 3.0D;
            reasons.append("Extremely Smooth Combat Aim; ");
        }

        if (intervalStd > 80.0D) {
            suspicion -= 1.25D;
        }

        if (p95 > 12.0D && spikeCount < 5) {
            suspicion -= 1.5D;
        }

        if (entropyFine > 4.1D) {
            suspicion -= 2.0D;
        }

        if (stdDev > 2.25D && spikeCount < 8) {
            suspicion -= 1.5D;
        }

        if (suspicion >= FAIL_SUSPICION) {
            int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

            if (increaseBuffer() > requiredBuffer) {
                if (profile.getTrustFactor().getTrust() >= 80) {
                    profile.getTrustFactor().decreaseTrustBy(4);
                    decreaseBufferBy(0.75D);
                } else {
                    fail("Aim Analysis (Yaw)",
                            "avg " + MsgType.MAIN_THEME_COLOR.getMessage() + format(average)
                                    + "\nmin " + MsgType.MAIN_THEME_COLOR.getMessage() + format(min)
                                    + "\nmax " + MsgType.MAIN_THEME_COLOR.getMessage() + format(max)
                                    + "\nmedian " + MsgType.MAIN_THEME_COLOR.getMessage() + format(median)
                                    + "\np75 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(p75)
                                    + "\np95 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(p95)
                                    + "\np99 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(p99)
                                    + "\nstddev " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stdDev)
                                    + "\nskewness " + MsgType.MAIN_THEME_COLOR.getMessage() + format(skewness)
                                    + "\nkurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + format(kurtosis)
                                    + "\nentropyFine " + MsgType.MAIN_THEME_COLOR.getMessage() + format(entropyFine)
                                    + "\nentropyMedium " + MsgType.MAIN_THEME_COLOR.getMessage() + format(entropyMedium)
                                    + "\nroundedRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(roundedRatio)
                                    + "\nduplicateRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(duplicateRatio)
                                    + "\ntinyRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(tinyRatio)
                                    + "\nsmallRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(smallRatio)
                                    + "\nsmoothWindows " + MsgType.MAIN_THEME_COLOR.getMessage() + smoothWindows
                                    + "\nspikes " + MsgType.MAIN_THEME_COLOR.getMessage() + spikeCount
                                    + "\nspikeRecoveries " + MsgType.MAIN_THEME_COLOR.getMessage() + spikeRecoveries
                                    + "\nlongestDirection " + MsgType.MAIN_THEME_COLOR.getMessage() + longestSameDirection
                                    + "\nlongestRounded " + MsgType.MAIN_THEME_COLOR.getMessage() + longestRoundedStreak
                                    + "\nintervalStd " + MsgType.MAIN_THEME_COLOR.getMessage() + format(intervalStd)
                                    + "\nsuspicion " + MsgType.MAIN_THEME_COLOR.getMessage() + format(suspicion)
                                    + "\nreasons " + MsgType.MAIN_THEME_COLOR.getMessage() + reasons.toString().trim());

                    profile.getTrustFactor().decreaseTrustBy(12);
                    decreaseBufferBy(1.25D);
                }
            }
        } else if (suspicion >= SOFT_SUSPICION) {
            increaseBuffer();

            if (profile.getTrustFactor().getTrust() >= 70) {
                profile.getTrustFactor().decreaseTrustBy(1);
            }
        } else {
            decreaseBufferBy(suspicion < 5.0D ? 0.35D : 0.12D);

            if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                profile.getTrustFactor().increaseTrustBy(0.0025D);
            }
        }
    }

    private void analyzeAttackAim() {
        if (attackSamples.size() < MIN_ATTACK_SAMPLE_SIZE) {
            return;
        }

        int rayHits = 0;
        int centerLocks = 0;
        int tightAngles = 0;
        int snaps = 0;
        int hardSnaps = 0;
        int snapCenterLocks = 0;
        int switchSnaps = 0;
        int laggySamples = 0;

        double averageCenterDistance = 0.0D;
        double averageCenterAngle = 0.0D;
        double averageYawSnap = 0.0D;

        for (AttackAimSample sample : attackSamples) {
            if (sample.rayHit) {
                rayHits++;
            }

            if (sample.nearCenter) {
                centerLocks++;
            }

            if (sample.tightCenterAngle) {
                tightAngles++;
            }

            if (sample.snap) {
                snaps++;
            }

            if (sample.hardSnap) {
                hardSnaps++;
            }

            if (sample.snap && sample.nearCenter && sample.tightCenterAngle && sample.rayHit) {
                snapCenterLocks++;
            }

            if (sample.fastSwitch && sample.snap && sample.rayHit) {
                switchSnaps++;
            }

            if (sample.laggy) {
                laggySamples++;
            }

            averageCenterDistance += sample.centerLineDistance == Double.MAX_VALUE ? 1.0D : sample.centerLineDistance;
            averageCenterAngle += sample.centerAngle == Double.MAX_VALUE ? 30.0D : sample.centerAngle;
            averageYawSnap += sample.deltaYaw;
        }

        int size = attackSamples.size();

        averageCenterDistance /= size;
        averageCenterAngle /= size;
        averageYawSnap /= size;

        double rayHitRatio = rayHits / (double) size;
        double centerLockRatio = centerLocks / (double) size;
        double tightAngleRatio = tightAngles / (double) size;
        double snapRatio = snaps / (double) size;
        double snapCenterRatio = snapCenterLocks / (double) size;
        double switchSnapRatio = switchSnaps / (double) size;
        double lagRatio = laggySamples / (double) size;

        double suspicion = 0.0D;
        StringBuilder reasons = new StringBuilder();

        if (centerLockRatio > 0.72D && tightAngleRatio > 0.74D && rayHitRatio > 0.86D) {
            suspicion += 4.0D;
            reasons.append("Center Lock; ");
        }

        if (snapCenterRatio > 0.36D && snapRatio > 0.42D) {
            suspicion += 4.5D;
            reasons.append("Snap To Center; ");
        }

        if (switchSnapRatio > 0.24D && snapRatio > 0.38D) {
            suspicion += 4.0D;
            reasons.append("Target Switch Snap; ");
        }

        if (hardSnaps >= 18 && snapCenterLocks >= 14) {
            suspicion += 3.75D;
            reasons.append("Hard Snap Hits; ");
        }

        if (averageCenterDistance < 0.045D && centerLockRatio > 0.66D && averageCenterAngle < 1.85D) {
            suspicion += 3.5D;
            reasons.append("Repeated Middle Aim; ");
        }

        if (snapRatio > 0.50D && averageYawSnap > 22.0D && rayHitRatio > 0.75D) {
            suspicion += 2.5D;
            reasons.append("High Snap Hit Ratio; ");
        }

        if (lagRatio > 0.45D) {
            suspicion -= 2.0D;
        }

        if (profile.isBedrockPlayer()) {
            suspicion -= 2.5D;
        }

        if (averageCenterAngle > 8.0D || rayHitRatio < 0.45D) {
            suspicion -= 2.5D;
        }

        if (suspicion >= 8.0D) {
            int required = profile.getTrustFactor().getRequiredBuffer();

            if (increaseBufferBy(suspicion >= 11.0D ? 1.5D : 1.0D) > required) {
                fail("Attack Aim Analysis",
                        "suspicion " + MsgType.MAIN_THEME_COLOR.getMessage() + format(suspicion)
                                + "\nrayHitRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(rayHitRatio)
                                + "\ncenterLockRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(centerLockRatio)
                                + "\ntightAngleRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(tightAngleRatio)
                                + "\nsnapRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(snapRatio)
                                + "\nsnapCenterRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(snapCenterRatio)
                                + "\nswitchSnapRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(switchSnapRatio)
                                + "\ncenterDistanceAvg " + MsgType.MAIN_THEME_COLOR.getMessage() + format(averageCenterDistance)
                                + "\ncenterAngleAvg " + MsgType.MAIN_THEME_COLOR.getMessage() + format(averageCenterAngle)
                                + "\navgYawSnap " + MsgType.MAIN_THEME_COLOR.getMessage() + format(averageYawSnap)
                                + "\nlagRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lagRatio)
                                + "\nreasons " + MsgType.MAIN_THEME_COLOR.getMessage() + reasons.toString().trim());

                decreaseBufferBy(1.0D);
            }
        } else {
            decreaseBufferBy(0.05D);
        }

        attackSamples.clear();
    }

    private void handleAttack(PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity wrapper;

        try {
            wrapper = new WrapperPlayClientInteractEntity(event);
        } catch (Throwable ignored) {
            return;
        }

        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.shouldCancel()
                || profile.isExempt().isTeleports()
                || profile.getPlayer().getGameMode() == GameMode.CREATIVE
                || profile.getPlayer().getGameMode() == GameMode.SPECTATOR
                || profile.getPlayer().isInsideVehicle()) {
            return;
        }

        RotationData rotationData = profile.getRotationData();
        MovementData movementData = profile.getMovementData();

        if (rotationData == null
                || movementData == null
                || movementData.getLocation() == null
                || rotationData.getCinematicProcessor().isCinematic()) {
            return;
        }

        int targetId = wrapper.getEntityId();

        double attackerX = movementData.getLocation().getX();
        double attackerY = movementData.getLocation().getY();
        double attackerZ = movementData.getLocation().getZ();

        float yaw = rotationData.getYaw();
        float pitch = rotationData.getPitch();

        double deltaYaw = Math.abs(rotationData.getDeltaYaw());
        double deltaPitch = Math.abs(rotationData.getDeltaPitch());

        long now = System.currentTimeMillis();

        boolean switchedTarget = lastTargetEntityId != -1 && lastTargetEntityId != targetId;
        long sinceSwitch = switchedTarget && lastTargetSwitchTime > 0L ? now - lastTargetSwitchTime : Long.MAX_VALUE;

        if (switchedTarget || lastTargetSwitchTime == 0L) {
            lastTargetSwitchTime = now;
        }

        lastTargetEntityId = targetId;
        lastAttackYawDelta = deltaYaw;
        lastAttackPitchDelta = deltaPitch;

        TaskUtils.task(() -> {
            Player target = getPlayerByEntityId(targetId);

            if (target == null || !target.isOnline() || target == profile.getPlayer()) {
                return;
            }

            Profile targetProfile = Arrow.getInstance().getProfileManager().getProfile(target);

            if (targetProfile == null
                    || targetProfile.getMovementData() == null
                    || targetProfile.getMovementData().getPastLocations() == null
                    || targetProfile.isExempt().isTeleports()
                    || target.isDead()
                    || target.isInsideVehicle()) {
                return;
            }

            SampleList<CustomLocation> pastLocations = targetProfile.getMovementData().getPastLocations();

            if (pastLocations.size() < 8) {
                return;
            }

            List<CustomLocation> samples = snapshotSamples(pastLocations);

            if (samples.isEmpty()) {
                return;
            }

            int attackerPingTicks = getPingTicks(profile);
            int targetPingTicks = getPingTicks(targetProfile);
            int historyAmount = getHistoryAmount(samples.size(), attackerPingTicks, targetPingTicks);

            List<CustomLocation> compensated = getLastSamples(samples, historyAmount);

            if (compensated.isEmpty()) {
                return;
            }

            Vector origin = new Vector(attackerX, attackerY + getEyeHeight(profile.getPlayer()), attackerZ);
            Vector direction = getDirection(yaw, pitch);

            double horizontalExpand = 0.035D + Math.min(0.13D, targetProfile.getMovementData().getDeltaXZ() * 0.45D + targetPingTicks * 0.002D);
            double verticalExpand = 0.035D + Math.min(0.11D, Math.abs(targetProfile.getMovementData().getDeltaY()) * 0.35D);

            double bestRayBoxDistance = Double.MAX_VALUE;
            double bestCenterLineDistance = Double.MAX_VALUE;
            double bestCenterAngle = Double.MAX_VALUE;

            for (CustomLocation location : compensated) {
                if (location == null) {
                    continue;
                }

                BoundingBox box = createPlayerBox(location, horizontalExpand, verticalExpand);
                Vector center = new Vector(location.getX(), location.getY() + 0.9D, location.getZ());

                double rayBoxDistance = rayTraceDistanceToBox(origin, direction, box, 7.2D);
                double centerLineDistance = distancePointToRay(origin, direction, center);
                double centerAngle = angleToPoint(origin, direction, center);

                if (rayBoxDistance < bestRayBoxDistance) {
                    bestRayBoxDistance = rayBoxDistance;
                }

                if (centerLineDistance < bestCenterLineDistance) {
                    bestCenterLineDistance = centerLineDistance;
                }

                if (centerAngle < bestCenterAngle) {
                    bestCenterAngle = centerAngle;
                }
            }

            boolean laggy = attackerPingTicks >= 5 || targetPingTicks >= 5 || profile.isBedrockPlayer() || targetProfile.isBedrockPlayer();

            double centerDistanceLimit = laggy ? CENTER_LOCK_DISTANCE_LAG : CENTER_LOCK_DISTANCE;
            double centerAngleLimit = laggy ? MAX_CENTER_ANGLE_LAG : MAX_CENTER_ANGLE;

            boolean rayHit = bestRayBoxDistance != Double.MAX_VALUE;
            boolean nearCenter = bestCenterLineDistance <= centerDistanceLimit;
            boolean tightCenterAngle = bestCenterAngle <= centerAngleLimit;

            boolean snap = deltaYaw >= SNAP_YAW_DELTA || deltaPitch >= SNAP_PITCH_DELTA;
            boolean hardSnap = deltaYaw >= HARD_SNAP_YAW_DELTA;
            boolean fastSwitch = switchedTarget && sinceSwitch <= TARGET_SWITCH_TICKS * 50L;

            AttackAimSample sample = new AttackAimSample(
                    deltaYaw,
                    deltaPitch,
                    bestRayBoxDistance,
                    bestCenterLineDistance,
                    bestCenterAngle,
                    rayHit,
                    nearCenter,
                    tightCenterAngle,
                    snap,
                    hardSnap,
                    fastSwitch,
                    laggy,
                    target.getName(),
                    now
            );

            attackSamples.add(sample);

            if (attackSamples.size() > ATTACK_SAMPLE_SIZE) {
                attackSamples.remove(0);
            }

            analyzeAttackAim();
        });
    }

    private boolean isRotation(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private int countSmoothWindows(List<Double> values) {
        if (values.size() < SMOOTH_WINDOW) {
            return 0;
        }

        int count = 0;

        for (int i = 0; i + SMOOTH_WINDOW <= values.size(); i += SMOOTH_WINDOW) {
            double average = 0.0D;

            for (int j = i; j < i + SMOOTH_WINDOW; j++) {
                average += values.get(j);
            }

            average /= SMOOTH_WINDOW;

            double stdDev = 0.0D;

            for (int j = i; j < i + SMOOTH_WINDOW; j++) {
                double difference = values.get(j) - average;
                stdDev += difference * difference;
            }

            stdDev = Math.sqrt(stdDev / SMOOTH_WINDOW);

            if (average > SMOOTH_WINDOW_MIN_AVERAGE
                    && average < SMOOTH_WINDOW_MAX_AVERAGE
                    && stdDev < SMOOTH_WINDOW_MAX_STD) {
                count++;
            }
        }

        return count;
    }

    private int countSpikes(List<Double> values) {
        int count = 0;

        for (int i = 1; i < values.size(); i++) {
            double current = values.get(i);
            double previous = values.get(i - 1);

            if (current >= SPIKE_DELTA && previous <= SPIKE_PREVIOUS_MAX) {
                count++;
            }
        }

        return count;
    }

    private int countSpikeRecoveries(List<Double> values) {
        int count = 0;

        for (int i = 1; i + 1 < values.size(); i++) {
            double previous = values.get(i - 1);
            double current = values.get(i);
            double next = values.get(i + 1);

            if (current >= SPIKE_DELTA
                    && previous <= SPIKE_PREVIOUS_MAX
                    && next <= SPIKE_NEXT_MAX) {
                count++;
            }
        }

        return count;
    }

    private int longestSameDirectionStreak(List<Double> values) {
        int best = 0;
        int current = 0;
        int lastDirection = 0;

        for (double value : values) {
            int direction = Double.compare(value, 0.0D);

            if (direction == 0) {
                current = 0;
                lastDirection = 0;
                continue;
            }

            if (direction == lastDirection) {
                current++;
            } else {
                current = 1;
                lastDirection = direction;
            }

            if (current > best) {
                best = current;
            }
        }

        return best;
    }

    private int longestRoundedStreak(List<Double> values, double precision) {
        int best = 1;
        int current = 1;

        for (int i = 1; i < values.size(); i++) {
            double previous = roundTo(values.get(i - 1), precision);
            double now = roundTo(values.get(i), precision);

            if (Math.abs(previous - now) <= 1.0E-9D) {
                current++;
            } else {
                if (current > best) {
                    best = current;
                }

                current = 1;
            }
        }

        return Math.max(best, current);
    }

    private double entropy(List<Double> values, double binSize) {
        if (values.isEmpty()) {
            return 0.0D;
        }

        Map<Integer, Integer> counts = new HashMap<>();

        for (double value : values) {
            int bin = (int) Math.floor(value / binSize);
            counts.put(bin, counts.getOrDefault(bin, 0) + 1);
        }

        double entropy = 0.0D;
        double size = values.size();

        for (int count : counts.values()) {
            double probability = count / size;
            entropy -= probability * (Math.log(probability) / Math.log(2.0D));
        }

        return entropy;
    }

    private double roundedRatio(List<Double> values, double precision) {
        int rounded = 0;

        for (double value : values) {
            double roundedValue = roundTo(value, precision);

            if (Math.abs(value - roundedValue) <= 1.0E-4D) {
                rounded++;
            }
        }

        return rounded / (double) values.size();
    }

    private double duplicateRatio(List<Double> values, double precision) {
        Map<Long, Integer> counts = new HashMap<>();

        for (double value : values) {
            long key = Math.round(value / precision);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        int duplicates = 0;

        for (int count : counts.values()) {
            if (count > 1) {
                duplicates += count - 1;
            }
        }

        return duplicates / (double) values.size();
    }

    private double ratioBelow(List<Double> values, double limit) {
        int count = 0;

        for (double value : values) {
            if (value < limit) {
                count++;
            }
        }

        return count / (double) values.size();
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }

        double total = 0.0D;

        for (double value : values) {
            total += value;
        }

        return total / values.size();
    }

    private double averageLong(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }

        double total = 0.0D;

        for (long value : values) {
            total += value;
        }

        return total / values.size();
    }

    private double standardDeviation(List<Double> values, double average) {
        if (values.size() <= 1) {
            return 0.0D;
        }

        double variance = 0.0D;

        for (double value : values) {
            double difference = value - average;
            variance += difference * difference;
        }

        return Math.sqrt(variance / values.size());
    }

    private double standardDeviationLong(List<Long> values, double average) {
        if (values.size() <= 1) {
            return 0.0D;
        }

        double variance = 0.0D;

        for (long value : values) {
            double difference = value - average;
            variance += difference * difference;
        }

        return Math.sqrt(variance / values.size());
    }

    private double skewness(List<Double> values, double average, double stdDev) {
        if (values.size() < 3 || stdDev == 0.0D) {
            return 0.0D;
        }

        double total = 0.0D;

        for (double value : values) {
            double z = (value - average) / stdDev;
            total += z * z * z;
        }

        return total / values.size();
    }

    private double kurtosis(List<Double> values, double average, double stdDev) {
        int n = values.size();

        if (n < 4 || stdDev == 0.0D) {
            return 0.0D;
        }

        double m4 = 0.0D;

        for (double value : values) {
            double z = (value - average) / stdDev;
            m4 += z * z * z * z;
        }

        double term1 = (n * (n + 1.0D)) / ((n - 1.0D) * (n - 2.0D) * (n - 3.0D));
        double term2 = 3.0D * ((n - 1.0D) * (n - 1.0D)) / ((n - 2.0D) * (n - 3.0D));

        return term1 * m4 - term2;
    }

    private double min(List<Double> values) {
        double min = Double.MAX_VALUE;

        for (double value : values) {
            if (value < min) {
                min = value;
            }
        }

        return min == Double.MAX_VALUE ? 0.0D : min;
    }

    private double max(List<Double> values) {
        double max = 0.0D;

        for (double value : values) {
            if (value > max) {
                max = value;
            }
        }

        return max;
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0D;
        }

        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);

        int index = (int) Math.floor((sorted.size() - 1) * percentile);

        if (index < 0) {
            index = 0;
        }

        if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }

        return sorted.get(index);
    }

    private double roundTo(double value, double precision) {
        return Math.round(value / precision) * precision;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    static class YawSample {

        float yaw;
        float deltaYaw;
        double absoluteDeltaYaw;
        long timestamp;

        private YawSample(float yaw, float deltaYaw, double absoluteDeltaYaw, long timestamp) {
            this.yaw = yaw;
            this.deltaYaw = deltaYaw;
            this.absoluteDeltaYaw = absoluteDeltaYaw;
            this.timestamp = timestamp;
        }
    }

    private Player getPlayerByEntityId(int entityId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }

    private List<CustomLocation> snapshotSamples(SampleList<CustomLocation> sampleList) {
        try {
            return new ArrayList<>(sampleList);
        } catch (Throwable ignored) {
            List<CustomLocation> list = new ArrayList<>();

            for (CustomLocation location : sampleList) {
                list.add(location);
            }

            return list;
        }
    }

    private List<CustomLocation> getLastSamples(List<CustomLocation> samples, int amount) {
        if (samples.isEmpty()) {
            return Collections.emptyList();
        }

        int from = Math.max(0, samples.size() - amount);
        return new ArrayList<>(samples.subList(from, samples.size()));
    }

    private int getHistoryAmount(int sampleSize, int attackerPingTicks, int targetPingTicks) {
        int amount = 6 + attackerPingTicks + Math.max(0, targetPingTicks / 2);

        amount = Math.max(8, amount);
        amount = Math.min(40, amount);
        amount = Math.min(sampleSize, amount);

        return amount;
    }

    private int getPingTicks(Profile profile) {
        if (profile == null || profile.getConnectionData() == null) {
            return 0;
        }

        ConnectionData connectionData = profile.getConnectionData();

        int ticks = 0;

        try {
            ticks = Math.max(ticks, connectionData.getClientTickTrans());
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, (int) Math.ceil(connectionData.getTransPing() / 50.0D));
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, (int) Math.ceil(connectionData.getPing() / 50.0D));
        } catch (Throwable ignored) {
        }

        return Math.min(40, ticks);
    }

    private double getEyeHeight(Player player) {
        if (player == null) {
            return 1.62D;
        }

        return player.isSneaking() ? 1.54D : 1.62D;
    }

    private Vector getDirection(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);

        double y = -Math.sin(pitchRadians);
        double horizontal = Math.cos(pitchRadians);

        double x = -horizontal * Math.sin(yawRadians);
        double z = horizontal * Math.cos(yawRadians);

        Vector direction = new Vector(x, y, z);

        if (direction.lengthSquared() == 0.0D) {
            return new Vector(0.0D, 0.0D, 1.0D);
        }

        return direction.normalize();
    }

    private BoundingBox createPlayerBox(CustomLocation location, double horizontalExpand, double verticalExpand) {
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        return new BoundingBox(
                (float) (x - 0.3D - horizontalExpand),
                (float) (y - verticalExpand),
                (float) (z - 0.3D - horizontalExpand),
                (float) (x + 0.3D + horizontalExpand),
                (float) (y + 1.8D + verticalExpand),
                (float) (z + 0.3D + horizontalExpand)
        );
    }

    private double distancePointToRay(Vector origin, Vector direction, Vector point) {
        Vector toPoint = point.clone().subtract(origin);
        double projection = toPoint.dot(direction);

        if (projection < 0.0D) {
            return toPoint.length();
        }

        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return point.distance(closest);
    }

    private double angleToPoint(Vector origin, Vector direction, Vector point) {
        Vector toPoint = point.clone().subtract(origin);

        if (toPoint.lengthSquared() == 0.0D || direction.lengthSquared() == 0.0D) {
            return 0.0D;
        }

        double dot = direction.clone().normalize().dot(toPoint.normalize());
        dot = Math.max(-1.0D, Math.min(1.0D, dot));

        return Math.toDegrees(Math.acos(dot));
    }

    private double rayTraceDistanceToBox(Vector origin, Vector direction, BoundingBox box, double maxDistance) {
        final double eps = 1.0E-9D;

        double ox = origin.getX();
        double oy = origin.getY();
        double oz = origin.getZ();

        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();

        double tMin = 0.0D;
        double tMax = maxDistance;

        if (Math.abs(dx) < eps) {
            if (ox < box.minX || ox > box.maxX) return Double.MAX_VALUE;
        } else {
            double inverse = 1.0D / dx;
            double t1 = (box.minX - ox) * inverse;
            double t2 = (box.maxX - ox) * inverse;

            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) return Double.MAX_VALUE;
        }

        if (Math.abs(dy) < eps) {
            if (oy < box.minY || oy > box.maxY) return Double.MAX_VALUE;
        } else {
            double inverse = 1.0D / dy;
            double t1 = (box.minY - oy) * inverse;
            double t2 = (box.maxY - oy) * inverse;

            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) return Double.MAX_VALUE;
        }

        if (Math.abs(dz) < eps) {
            if (oz < box.minZ || oz > box.maxZ) return Double.MAX_VALUE;
        } else {
            double inverse = 1.0D / dz;
            double t1 = (box.minZ - oz) * inverse;
            double t2 = (box.maxZ - oz) * inverse;

            if (t1 > t2) {
                double temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) return Double.MAX_VALUE;
        }

        if (tMax < 0.0D) {
            return Double.MAX_VALUE;
        }

        return Math.max(0.0D, tMin);
    }


    static class AttackAimSample {

        double deltaYaw;
        double deltaPitch;
        double rayBoxDistance;
        double centerLineDistance;
        double centerAngle;
        boolean rayHit;
        boolean nearCenter;
        boolean tightCenterAngle;
        boolean snap;
        boolean hardSnap;
        boolean fastSwitch;
        boolean laggy;

        String targetName;
        long timestamp;

        private AttackAimSample(double deltaYaw,
                                double deltaPitch,
                                double rayBoxDistance,
                                double centerLineDistance,
                                double centerAngle,
                                boolean rayHit,
                                boolean nearCenter,
                                boolean tightCenterAngle,
                                boolean snap,
                                boolean hardSnap,
                                boolean fastSwitch,
                                boolean laggy,
                                String targetName,
                                long timestamp) {
            this.deltaYaw = deltaYaw;
            this.deltaPitch = deltaPitch;
            this.rayBoxDistance = rayBoxDistance;
            this.centerLineDistance = centerLineDistance;
            this.centerAngle = centerAngle;
            this.rayHit = rayHit;
            this.nearCenter = nearCenter;
            this.tightCenterAngle = tightCenterAngle;
            this.snap = snap;
            this.hardSnap = hardSnap;
            this.fastSwitch = fastSwitch;
            this.laggy = laggy;
            this.targetName = targetName;
            this.timestamp = timestamp;
        }
    }
}
