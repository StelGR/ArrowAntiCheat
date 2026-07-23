package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
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
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Detects target-point / target-region locking.

 * The important part is that this does not require every sample to land on the
 * exact same pixel of the hitbox. It finds the strongest target-relative cluster
 * and tolerates small deliberate jitter around that cluster.
 */
@Experimental
public class AimG extends Check {

    private static final int WINDOW_SIZE = 20;
    private static final int ANALYZE_EVERY = 4;
    private static final int COMBAT_SAMPLE_TICKS = 10;
    private static final long MAX_SAMPLE_GAP_MS = 1750L;

    private static final double PLAYER_HALF_WIDTH = 0.30D;

    /*
     * Normalized hitbox-space cluster radii.
     * X/Z are divided by 0.30 and Y by target height.
     */
    private static final double CLUSTER_RADIUS_XZ = 0.34D;
    private static final double CLUSTER_RADIUS_Y = 0.22D;

    private final List<LockSample> samples = new ArrayList<>(WINDOW_SIZE);

    private int sampledTargetId = -1;
    private int samplesSinceAnalysis;
    private long lastSampleTime;
    private float lastSampleYaw = Float.NaN;
    private float lastSamplePitch = Float.NaN;

    public AimG(Profile profile) {
        super(profile, CheckType.AIM, "G", "Consistent hitbox-region tracking");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.getCombatData().getAttackedTicks() <= COMBAT_SAMPLE_TICKS) {
                collectTargetSample(profile.getCombatData().getTarget());
            }
            return;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            return;
        }

        WrapperPlayClientInteractEntity packet;

        try {
            packet = new WrapperPlayClientInteractEntity(event);
        } catch (Throwable ignored) {
            return;
        }

        if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            collectTargetSample(packet.getEntityId());
        }
    }

    private void collectTargetSample(int entityId) {
        MovementData movement = profile.getMovementData();
        RotationData rotation = profile.getRotationData();

        if (!canSample(movement, rotation)) {
            resetWindow(false);
            return;
        }

        UUID targetUuid = profile.getCombatData().getTrackedEntities().get(entityId);

        if (targetUuid == null || targetUuid.equals(profile.getPlayer().getUniqueId())) {
            return;
        }

        Profile target = Arrow.getInstance().getProfileManager().getProfile(targetUuid);

        if (!canUseTarget(target)) {
            resetWindow(false);
            return;
        }

        int pingTicks = getPingTicks(profile);

        if (pingTicks > 12) {
            resetWindow(false);
            return;
        }

        List<CustomLocation> history = snapshot(target.getMovementData().getPastLocations());

        if (history.size() < 4) {
            return;
        }

        CustomLocation attacker = movement.getLocation();
        Vector origin = new Vector(attacker.getX(), attacker.getY() + getEyeHeight(), attacker.getZ());
        Vector direction = direction(rotation.getYaw(), rotation.getPitch());

        double targetHeight = target.getActionData() != null && target.getActionData().isSneaking()
                ? 1.50D
                : 1.80D;

        AimPoint aimPoint = selectAimPoint(history, pingTicks, origin, direction, targetHeight);

        if (aimPoint == null) {
            return;
        }

        double deltaYaw = Math.abs(rotation.getDeltaYaw());
        double deltaPitch = Math.abs(rotation.getDeltaPitch());

        if (deltaYaw + deltaPitch < 0.015D) {
            return;
        }

        long now = System.currentTimeMillis();

        if (sampledTargetId != entityId || now - lastSampleTime > MAX_SAMPLE_GAP_MS) {
            resetWindow(false);
            sampledTargetId = entityId;
        }

        if (now - lastSampleTime <= 45L
                && Float.compare(lastSampleYaw, rotation.getYaw()) == 0
                && Float.compare(lastSamplePitch, rotation.getPitch()) == 0) {
            return;
        }

        double relativeX = aimPoint.targetX - attacker.getX();
        double relativeY = aimPoint.targetY - attacker.getY();
        double relativeZ = aimPoint.targetZ - attacker.getZ();
        double bearing = Math.toDegrees(Math.atan2(-relativeX, relativeZ));

        samples.add(new LockSample(
                aimPoint.normalizedX,
                aimPoint.normalizedY,
                aimPoint.normalizedZ,
                relativeX,
                relativeY,
                relativeZ,
                bearing,
                deltaYaw,
                deltaPitch
        ));

        while (samples.size() > WINDOW_SIZE) {
            samples.remove(0);
        }

        samplesSinceAnalysis++;
        lastSampleTime = now;
        lastSampleYaw = rotation.getYaw();
        lastSamplePitch = rotation.getPitch();

        if (samples.size() >= WINDOW_SIZE && samplesSinceAnalysis >= ANALYZE_EVERY) {
            analyze();
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

    private void analyze() {
        if (samples.size() < WINDOW_SIZE) {
            return;
        }

        Cluster cluster = findStrongestCluster();

        if (cluster == null || cluster.indices.isEmpty()) {
            decreaseBufferBy(0.35D);
            return;
        }

        int size = samples.size();
        int clusterSize = cluster.indices.size();
        double clusterRatio = clusterSize / (double) size;

        List<Double> clusterX = new ArrayList<>(clusterSize);
        List<Double> clusterY = new ArrayList<>(clusterSize);
        List<Double> clusterZ = new ArrayList<>(clusterSize);

        for (Integer index : cluster.indices) {
            LockSample sample = samples.get(index);
            clusterX.add(sample.x);
            clusterY.add(sample.y);
            clusterZ.add(sample.z);
        }

        double centerX = median(clusterX);
        double centerY = median(clusterY);
        double centerZ = median(clusterZ);

        List<Double> distances = new ArrayList<>(clusterSize);
        double pointTravel = 0.0D;
        double maximumDeviation = 0.0D;
        double bearingTravel = 0.0D;
        double relativeTravel = 0.0D;
        double rotationTravel = 0.0D;
        int movingRotationSamples = 0;
        int inlierTransitions = 0;
        int stableRegionTransitions = 0;
        int jitterTransitions = 0;
        int longestInlierStreak = 0;
        int currentInlierStreak = 0;

        boolean[] inlier = new boolean[size];

        for (int i = 0; i < size; i++) {
            LockSample sample = samples.get(i);
            double dx = sample.x - centerX;
            double dy = sample.y - centerY;
            double dz = sample.z - centerZ;
            double horizontal = Math.hypot(dx, dz);
            double scaledDistance = Math.sqrt(horizontal * horizontal + (dy * 1.35D) * (dy * 1.35D));

            inlier[i] = horizontal <= CLUSTER_RADIUS_XZ && Math.abs(dy) <= CLUSTER_RADIUS_Y;

            if (inlier[i]) {
                distances.add(scaledDistance);
                maximumDeviation = Math.max(maximumDeviation, scaledDistance);
                currentInlierStreak++;
                longestInlierStreak = Math.max(longestInlierStreak, currentInlierStreak);
            } else {
                currentInlierStreak = 0;
            }

            rotationTravel += sample.deltaYaw + sample.deltaPitch;

            if (sample.deltaYaw + sample.deltaPitch >= 0.12D) {
                movingRotationSamples++;
            }

            if (i == 0) {
                continue;
            }

            LockSample previous = samples.get(i - 1);
            bearingTravel += angleDistance(sample.bearing, previous.bearing);
            relativeTravel += Math.sqrt(
                    square(sample.relativeX - previous.relativeX)
                            + square(sample.relativeY - previous.relativeY)
                            + square(sample.relativeZ - previous.relativeZ)
            );

            double step = distance(sample.x, sample.y, sample.z, previous.x, previous.y, previous.z);
            pointTravel += step;

            if (inlier[i] && inlier[i - 1]) {
                inlierTransitions++;

                if (step <= 0.28D) {
                    stableRegionTransitions++;
                }

                if (step >= 0.018D && step <= 0.28D) {
                    jitterTransitions++;
                }
            }
        }

        Collections.sort(distances);

        double medianDistance = distances.isEmpty() ? 999.0D : percentileSorted(distances, 0.50D);
        double p85Distance = distances.isEmpty() ? 999.0D : percentileSorted(distances, 0.85D);
        double transitionRatio = inlierTransitions / (double) Math.max(1, size - 1);
        double stableTransitionRatio = stableRegionTransitions / (double) Math.max(1, inlierTransitions);
        double jitterRatio = jitterTransitions / (double) Math.max(1, inlierTransitions);
        double rotationRatio = movingRotationSamples / (double) size;

        double dominantCellRatio = dominantRegionCellRatio();
        double verticalBandRatio = ratioWithinAxis(centerY, CLUSTER_RADIUS_Y, true);

        boolean changingScene = rotationRatio >= 0.45D
                && rotationTravel >= 3.0D
                && bearingTravel >= 1.25D
                && relativeTravel >= 0.12D;

        boolean hardRegionLock = changingScene
                && clusterRatio >= 0.82D
                && medianDistance <= 0.105D
                && p85Distance <= 0.205D
                && longestInlierStreak >= 8
                && stableTransitionRatio >= 0.78D;

        boolean jitteredRegionLock = changingScene
                && clusterRatio >= 0.72D
                && medianDistance <= 0.165D
                && p85Distance <= 0.310D
                && maximumDeviation <= 0.48D
                && transitionRatio >= 0.62D
                && stableTransitionRatio >= 0.70D
                && jitterRatio >= 0.30D
                && pointTravel >= 0.20D;

        boolean coarseRegionLock = changingScene
                && clusterRatio >= 0.68D
                && dominantCellRatio >= 0.58D
                && verticalBandRatio >= 0.80D
                && longestInlierStreak >= 7
                && p85Distance <= 0.34D;

        boolean suspicious = hardRegionLock || jitteredRegionLock || coarseRegionLock;

        if (suspicious) {
            double evidence = hardRegionLock ? 1.55D : jitteredRegionLock ? 1.10D : 0.85D;

            int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

            if (increaseBufferBy(evidence) > requiredBuffer) {
                if (profile.getTrustFactor().getTrust() >= 80) {
                    profile.getTrustFactor().decreaseTrustBy(2.3);
                } else {
                    String type = hardRegionLock ? "hard-region"
                            : jitteredRegionLock ? "jittered-region"
                            : "coarse-region";

                    fail("Hitbox Region Lock",
                            "type " + MsgType.MAIN_THEME_COLOR.getMessage() + type
                                    + "\nclusterRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(clusterRatio)
                                    + "\nmedianDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(medianDistance)
                                    + "\np85Distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(p85Distance)
                                    + "\nmaxDeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + format(maximumDeviation)
                                    + "\ncenter " + MsgType.MAIN_THEME_COLOR.getMessage()
                                    + format(centerX) + ", " + format(centerY) + ", " + format(centerZ)
                                    + "\ndominantCellRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(dominantCellRatio)
                                    + "\nverticalBandRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(verticalBandRatio)
                                    + "\ntransitionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(transitionRatio)
                                    + "\nstableTransitionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stableTransitionRatio)
                                    + "\njitterRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(jitterRatio)
                                    + "\nlongestInlierStreak " + MsgType.MAIN_THEME_COLOR.getMessage() + longestInlierStreak
                                    + "\nbearingTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bearingTravel)
                                    + "\nrelativeTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(relativeTravel));

                    decreaseBufferBy(1.20D);
                }
            }
        } else {
            decreaseBufferBy(changingScene ? 0.40D : 0.65D);
        }

        verbose(this.getClass().getSimpleName(), getBuffer(), 2.60D,
                "clusterRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(clusterRatio)
                        + "\nmedianDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(medianDistance)
                        + "\np85Distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(p85Distance)
                        + "\ndominantCellRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(dominantCellRatio)
                        + "\nverticalBandRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(verticalBandRatio)
                        + "\ntransitionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(transitionRatio)
                        + "\nstableTransitionRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stableTransitionRatio)
                        + "\njitterRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(jitterRatio)
                        + "\nlongestInlierStreak " + MsgType.MAIN_THEME_COLOR.getMessage() + longestInlierStreak
                        + "\nchangingScene " + MsgType.MAIN_THEME_COLOR.getMessage() + changingScene);
    }

    private Cluster findStrongestCluster() {
        Cluster best = null;

        for (int centerIndex = 0; centerIndex < samples.size(); centerIndex++) {
            LockSample center = samples.get(centerIndex);
            List<Integer> indices = new ArrayList<>();
            double totalDistance = 0.0D;

            for (int i = 0; i < samples.size(); i++) {
                LockSample sample = samples.get(i);
                double horizontal = Math.hypot(sample.x - center.x, sample.z - center.z);
                double vertical = Math.abs(sample.y - center.y);

                if (horizontal <= CLUSTER_RADIUS_XZ && vertical <= CLUSTER_RADIUS_Y) {
                    indices.add(i);
                    totalDistance += horizontal + vertical * 1.25D;
                }
            }

            if (best == null
                    || indices.size() > best.indices.size()
                    || (indices.size() == best.indices.size() && totalDistance < best.totalDistance)) {
                best = new Cluster(indices, totalDistance);
            }
        }

        return best;
    }

    private double dominantRegionCellRatio() {
        int[] counts = new int[5 * 5 * 6];
        int maximum = 0;

        for (LockSample sample : samples) {
            int xBin = clamp((int) Math.floor((sample.x + 1.25D) / 0.50D), 0, 4);
            int zBin = clamp((int) Math.floor((sample.z + 1.25D) / 0.50D), 0, 4);
            int yBin = clamp((int) Math.floor((sample.y + 0.10D) / 0.20D), 0, 5);
            int index = (yBin * 25) + (xBin * 5) + zBin;
            maximum = Math.max(maximum, ++counts[index]);
        }

        return maximum / (double) samples.size();
    }

    private double ratioWithinAxis(double center, double tolerance, boolean yAxis) {
        int amount = 0;

        for (LockSample sample : samples) {
            double value = yAxis ? sample.y : sample.x;
            if (Math.abs(value - center) <= tolerance) {
                amount++;
            }
        }

        return amount / (double) samples.size();
    }

    private AimPoint selectAimPoint(List<CustomLocation> history,
                                    int pingTicks,
                                    Vector origin,
                                    Vector direction,
                                    double height) {
        int newest = history.size() - 1;
        int expectedAge = Math.min(newest, Math.max(0, pingTicks + 1));
        AimPoint best = null;
        double bestScore = Double.MAX_VALUE;

        for (int offset = -2; offset <= 2; offset++) {
            int age = expectedAge + offset;

            if (age < 0 || age > newest) {
                continue;
            }

            CustomLocation target = history.get(newest - age);

            if (target == null) {
                continue;
            }

            AimPoint point = projectToTargetPlane(origin, direction, target, height);

            if (point == null) {
                continue;
            }

            double outside = outside(point.normalizedX, -1.30D, 1.30D)
                    + outside(point.normalizedZ, -1.30D, 1.30D)
                    + outside(point.normalizedY, -0.14D, 1.14D);

            double centerPenalty = Math.max(0.0D, Math.abs(point.normalizedY - 0.55D) - 0.65D);
            double score = outside * 100.0D + centerPenalty * 0.05D + Math.abs(offset) * 0.012D;

            if (score < bestScore) {
                best = point;
                bestScore = score;
            }
        }

        return bestScore <= 0.060D ? best : null;
    }

    private AimPoint projectToTargetPlane(Vector origin,
                                          Vector direction,
                                          CustomLocation target,
                                          double height) {
        double toTargetX = target.getX() - origin.getX();
        double toTargetZ = target.getZ() - origin.getZ();
        double horizontalLength = Math.hypot(toTargetX, toTargetZ);

        if (horizontalLength < 0.20D) {
            return null;
        }

        double normalX = toTargetX / horizontalLength;
        double normalZ = toTargetZ / horizontalLength;
        double denominator = direction.getX() * normalX + direction.getZ() * normalZ;

        if (denominator <= 1.0E-5D) {
            return null;
        }

        double distance = (toTargetX * normalX + toTargetZ * normalZ) / denominator;

        if (distance < 0.0D || distance > 7.0D) {
            return null;
        }

        double hitX = origin.getX() + direction.getX() * distance;
        double hitY = origin.getY() + direction.getY() * distance;
        double hitZ = origin.getZ() + direction.getZ() * distance;

        return new AimPoint(
                (hitX - target.getX()) / PLAYER_HALF_WIDTH,
                (hitY - target.getY()) / height,
                (hitZ - target.getZ()) / PLAYER_HALF_WIDTH,
                target.getX(),
                target.getY(),
                target.getZ()
        );
    }

    private List<CustomLocation> snapshot(SampleList<CustomLocation> history) {
        if (history == null) {
            return new ArrayList<>();
        }

        try {
            return new ArrayList<>(history);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private int getPingTicks(Profile source) {
        ConnectionData connection = source.getConnectionData();

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

    private Vector direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);

        return new Vector(
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        ).normalize();
    }

    private void resetWindow(boolean decay) {
        samples.clear();
        sampledTargetId = -1;
        samplesSinceAnalysis = 0;
        lastSampleTime = 0L;
        lastSampleYaw = Float.NaN;
        lastSamplePitch = Float.NaN;

        if (decay) {
            decreaseBufferBy(0.25D);
        }
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;

        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) * 0.5D
                : sorted.get(middle);
    }

    private double percentileSorted(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0D;
        }

        double index = (sorted.size() - 1) * percentile;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sorted.get(lower);
        }

        double weight = index - lower;
        return sorted.get(lower) * (1.0D - weight) + sorted.get(upper) * weight;
    }

    private double outside(double value, double minimum, double maximum) {
        if (value < minimum) {
            double difference = minimum - value;
            return difference * difference;
        }

        if (value > maximum) {
            double difference = value - maximum;
            return difference * difference;
        }

        return 0.0D;
    }

    private double distance(double x1, double y1, double z1,
                            double x2, double y2, double z2) {
        return Math.sqrt(square(x1 - x2) + square(y1 - y2) + square(z1 - z2));
    }

    private double angleDistance(double first, double second) {
        double difference = Math.abs(first - second) % 360.0D;
        return difference > 180.0D ? 360.0D - difference : difference;
    }

    private double square(double value) {
        return value * value;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private static final class Cluster {
        private final List<Integer> indices;
        private final double totalDistance;

        private Cluster(List<Integer> indices, double totalDistance) {
            this.indices = indices;
            this.totalDistance = totalDistance;
        }
    }

    private static final class AimPoint {
        private final double normalizedX;
        private final double normalizedY;
        private final double normalizedZ;
        private final double targetX;
        private final double targetY;
        private final double targetZ;

        private AimPoint(double normalizedX,
                         double normalizedY,
                         double normalizedZ,
                         double targetX,
                         double targetY,
                         double targetZ) {
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.normalizedZ = normalizedZ;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
        }
    }

    private static final class LockSample {
        private final double x;
        private final double y;
        private final double z;
        private final double relativeX;
        private final double relativeY;
        private final double relativeZ;
        private final double bearing;
        private final double deltaYaw;
        private final double deltaPitch;

        private LockSample(double x,
                           double y,
                           double z,
                           double relativeX,
                           double relativeY,
                           double relativeZ,
                           double bearing,
                           double deltaYaw,
                           double deltaPitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.relativeX = relativeX;
            this.relativeY = relativeY;
            this.relativeZ = relativeZ;
            this.bearing = bearing;
            this.deltaYaw = deltaYaw;
            this.deltaPitch = deltaPitch;
        }
    }
}
