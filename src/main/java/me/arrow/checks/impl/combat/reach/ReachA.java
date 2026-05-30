package me.arrow.checks.impl.combat.reach;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Checks;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// fully configurable reach pastlocations check, lag compensated
// has hitbox check in here but it does not seem to work rn

@Experimental
public class ReachA extends Check {

    double BASE_REACH_LIMIT;
    int MAX_HISTORY_SAMPLES;
    int MIN_HISTORY_SAMPLES;

    double MAX_VALID_DISTANCE = 10D;

    double BASE_BOX_EXPAND_HORIZONTAL;
    double BASE_BOX_EXPAND_VERTICAL;

    double MAX_LAG_BOX_EXPAND;
    double MAX_FORGIVING_HORIZONTAL_BOX_EXPAND;
    double MAX_FORGIVING_VERTICAL_EXPAND;
    double MAX_REACH_TOLERANCE;

    boolean REQUIRE_RAY_INTERSECTION_FOR_REACH;
    boolean VERBOSE_RAY_HITBOX_STATE = true;

    int ROTATION_HISTORY_SIZE = 20;
    long BASE_ROTATION_LOOKBACK_MS = 150L;
    long MAX_ROTATION_LOOKBACK_MS = 450L;

    double FLICK_YAW_DELTA = 18.0D;
    double FLICK_PITCH_DELTA = 8.0D;

    double CLEAN_MISS_MIN_CENTER_ANGLE = 8.5D;

    double HITBOX_BUFFER_REQUIRED = 3.0D;



    final List<RotationSnapshot> rotationHistory = new ArrayList<>(ROTATION_HISTORY_SIZE);

    private double hitboxBuffer;

    public ReachA(Profile profile) {
        super(profile, CheckType.REACH, "A", "Checks for entity reach");
        BASE_REACH_LIMIT = Checks.Setting.REACH_A_MINIMUM_REACH.getDouble();
        MIN_HISTORY_SAMPLES = Checks.Setting.REACH_A_FLAG_SAMPLES.getInt();
        MAX_HISTORY_SAMPLES = Checks.Setting.REACH_A_MAX_SAMPLES.getInt();
        BASE_BOX_EXPAND_HORIZONTAL = Checks.Setting.REACH_A_BOX_EXPAND_HORIZONTAL.getDouble();
        BASE_BOX_EXPAND_VERTICAL = Checks.Setting.REACH_A_BOX_EXPAND_VERTICAL.getDouble();
        MAX_LAG_BOX_EXPAND = Checks.Setting.REACH_A_MAX_LAG_BOX_EXPAND.getDouble();
        MAX_FORGIVING_HORIZONTAL_BOX_EXPAND = Checks.Setting.REACH_A_MAX_FORGIVING_HORIZONTAL_BOX_EXPAND.getDouble();
        MAX_FORGIVING_VERTICAL_EXPAND = Checks.Setting.REACH_A_MAX_FORGIVING_VERTICAL_BOX_EXPAND.getDouble();
        MAX_REACH_TOLERANCE = Checks.Setting.REACH_A_MAX_REACH_TOLERANCE.getDouble();
        REQUIRE_RAY_INTERSECTION_FOR_REACH = Checks.Setting.REACH_A_RAY.getBoolean();
        
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (isRotation(event)) {
            recordRotation(event);
            return;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            return;
        }

        WrapperPlayClientInteractEntity interactEntity = new WrapperPlayClientInteractEntity(event);

        if (interactEntity.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.shouldCancel()) {
            return;
        }

        if (isBadAttackerState(profile)) {
            decreaseBufferBy(0.15D);
            hitboxBuffer -= Math.min(hitboxBuffer, 0.25D);
            return;
        }

        int entityId = interactEntity.getEntityId();

        MovementData attackerMovement = profile.getMovementData();
        RotationData attackerRotation = profile.getRotationData();

        if (attackerMovement == null
                || attackerRotation == null
                || attackerMovement.getLocation() == null) {
            return;
        }

        final double attackerX = attackerMovement.getLocation().getX();
        final double attackerY = attackerMovement.getLocation().getY();
        final double attackerZ = attackerMovement.getLocation().getZ();

        final float yaw = attackerRotation.getYaw();
        final float pitch = attackerRotation.getPitch();

        final double deltaYaw = Math.abs(attackerRotation.getDeltaYaw());
        final double deltaPitch = Math.abs(attackerRotation.getDeltaPitch());

        final int initialAttackerPingTicks = getPingTicks(profile);
        final List<RotationSnapshot> rotationCandidates = getRotationCandidates(
                yaw,
                pitch,
                deltaYaw,
                deltaPitch,
                initialAttackerPingTicks
        );

        Player target = getPlayerByEntityId(entityId);

        if (target == null || !target.isOnline() || target == profile.getPlayer()) {
            return;
        }

        Profile targetProfile = Arrow.getInstance().getProfileManager().getProfile(target);

        if (targetProfile == null || targetProfile.getMovementData() == null) {
            return;
        }

        if (isBadTargetState(targetProfile)) {
            decreaseBufferBy(0.15D);
            hitboxBuffer -= Math.min(hitboxBuffer, 0.25D);
            return;
        }

        SampleList<CustomLocation> targetPastLocations = targetProfile.getMovementData().getPastLocations();

        if (targetPastLocations == null || targetPastLocations.size() < MIN_HISTORY_SAMPLES) {
            return;
        }

        List<CustomLocation> samples = snapshotSamples(targetPastLocations);

        if (samples.size() < MIN_HISTORY_SAMPLES) {
            return;
        }

        int attackerPingTicks = getPingTicks(profile);
        int targetPingTicks = getPingTicks(targetProfile);

        int historyAmount = getHistoryAmount(samples.size(), attackerPingTicks, targetPingTicks);
        List<CustomLocation> compensatedSamples = getLastSamples(samples, historyAmount);

        if (compensatedSamples.size() < MIN_HISTORY_SAMPLES) {
            return;
        }

        double eyeHeight = getAccurateEyeHeight(profile);
        Vector origin = new Vector(attackerX, attackerY + eyeHeight, attackerZ);

        double bestDistance = Double.MAX_VALUE;
        double bestRawDistance = Double.MAX_VALUE;
        double bestForgivingDistance = Double.MAX_VALUE;
        double bestCenterDistance = Double.MAX_VALUE;
        double bestCenterRayDistance = Double.MAX_VALUE;
        double bestCenterAngle = Double.MAX_VALUE;

        boolean rayHitBox = false;
        boolean forgivingRayHitBox = false;
        boolean originInsideBox = false;
        boolean usedCompensatedRotation = false;

        boolean recentFlick = isRecentFlick(rotationCandidates);
        boolean laggy = isLaggy(profile, targetProfile, attackerPingTicks, targetPingTicks);

        double reachTolerance = getReachTolerance(profile, targetProfile);
        double horizontalExpand = getHorizontalBoxExpand(targetProfile);
        double verticalExpand = getVerticalBoxExpand(targetProfile);

        double forgivingHorizontalExpand = Math.min(
                MAX_FORGIVING_HORIZONTAL_BOX_EXPAND,
                horizontalExpand + getAdditionalHitboxExpand(profile, targetProfile, recentFlick, attackerPingTicks, targetPingTicks)
        );

        double forgivingVerticalExpand = Math.min(
                MAX_FORGIVING_VERTICAL_EXPAND,
                verticalExpand + getAdditionalVerticalExpand(profile, targetProfile, recentFlick, attackerPingTicks, targetPingTicks)
        );

        for (CustomLocation sample : compensatedSamples) {
            if (sample == null || sample.getWorld() == null) {
                continue;
            }

            BoundingBox rawBox = createPlayerBox(target, sample, 0.0D, 0.0D);
            BoundingBox expandedBox = createPlayerBox(target, sample, horizontalExpand, verticalExpand);
            BoundingBox forgivingBox = createPlayerBox(target, sample, forgivingHorizontalExpand, forgivingVerticalExpand);

            if (isInsideBox(origin, expandedBox)) {
                originInsideBox = true;
                bestDistance = 0.0D;
                bestRawDistance = 0.0D;
                bestForgivingDistance = 0.0D;
                rayHitBox = true;
                forgivingRayHitBox = true;
                break;
            }

            Vector center = new Vector(sample.getX(), sample.getY() + 0.9D, sample.getZ());
            double centerDistance = origin.distance(center);

            if (centerDistance < bestCenterDistance) {
                bestCenterDistance = centerDistance;
            }

            for (int i = 0; i < rotationCandidates.size(); i++) {
                RotationSnapshot rotation = rotationCandidates.get(i);
                Vector direction = getDirection(rotation.yaw, rotation.pitch);

                double rawDistance = rayTraceDistanceToBox(origin, direction, rawBox, MAX_VALID_DISTANCE);
                double expandedDistance = rayTraceDistanceToBox(origin, direction, expandedBox, MAX_VALID_DISTANCE);
                double forgivingDistance = rayTraceDistanceToBox(origin, direction, forgivingBox, MAX_VALID_DISTANCE);
                double centerRayDistance = distancePointToRay(origin, direction, center);
                double centerAngle = angleToPoint(origin, direction, center);

                if (rawDistance < bestRawDistance) {
                    bestRawDistance = rawDistance;
                }

                if (expandedDistance < bestDistance) {
                    bestDistance = expandedDistance;

                    if (i > 0) {
                        usedCompensatedRotation = true;
                    }
                }

                if (forgivingDistance < bestForgivingDistance) {
                    bestForgivingDistance = forgivingDistance;
                }

                if (centerRayDistance < bestCenterRayDistance) {
                    bestCenterRayDistance = centerRayDistance;
                }

                if (centerAngle < bestCenterAngle) {
                    bestCenterAngle = centerAngle;
                }

                if (expandedDistance != Double.MAX_VALUE) {
                    rayHitBox = true;

                    if (i > 0) {
                        usedCompensatedRotation = true;
                    }
                }

                if (forgivingDistance != Double.MAX_VALUE) {
                    forgivingRayHitBox = true;
                }
            }
        }

        if (bestDistance == Double.MAX_VALUE) {
            boolean softMiss = forgivingRayHitBox
                    || recentFlick
                    || laggy
                    || bestCenterRayDistance < 0.28D
                    || bestCenterAngle < 5.5D;

            boolean cleanMiss = !softMiss
                    && bestCenterDistance > 0.4
                    && bestCenterAngle > CLEAN_MISS_MIN_CENTER_ANGLE;

            if (VERBOSE_RAY_HITBOX_STATE) {

                verbose(
                        this.getClass().getSimpleName(),
                        MAX_VALID_DISTANCE,
                        Checks.Setting.REACH_A_MINIMUM_REACH.getDouble(),
                        "Ray did not intersect compensated hitbox"
                                + "\nsamples " + compensatedSamples.size()
                                + "\nhistory " + historyAmount
                                + "\ncenterDistance " + format(bestCenterDistance)
                                + "\ncenterRayDistance " + format(bestCenterRayDistance)
                                + "\ncenterAngle " + format(bestCenterAngle)
                                + "\nrecentFlick " + recentFlick
                                + "\nlaggy " + laggy
                                + "\nforgivingRayHit " + forgivingRayHitBox
                                + "\nusedCompensatedRotation " + usedCompensatedRotation
                );
            }

            if (cleanMiss) {
                hitboxBuffer += laggy ? 0.35D : 1.0D;

                if (hitboxBuffer > HITBOX_BUFFER_REQUIRED) {
                    fail(
                            "Expanded Hitbox",
                            "centerDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterDistance)
                                    + "\ncenterRayDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterRayDistance)
                                    + "\ncenterAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterAngle)
                                    + "\nrecentFlick " + MsgType.MAIN_THEME_COLOR.getMessage() + recentFlick
                                    + "\nlaggy " + MsgType.MAIN_THEME_COLOR.getMessage() + laggy
                                    + "\nforgivingRayHit " + MsgType.MAIN_THEME_COLOR.getMessage() + forgivingRayHitBox
                                    + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + compensatedSamples.size()
                                    + "\nhistory " + MsgType.MAIN_THEME_COLOR.getMessage() + historyAmount
                    );

                    hitboxBuffer *= 0.5D;
                }
            } else {
                hitboxBuffer -= Math.min(hitboxBuffer, recentFlick || laggy ? 0.5D : 0.2D);
            }

            if (REQUIRE_RAY_INTERSECTION_FOR_REACH) {
                decreaseBufferBy(0.05D);
                return;
            }
        } else {
            hitboxBuffer -= Math.min(hitboxBuffer, 0.35D);
        }

        double allowedReach = BASE_REACH_LIMIT + reachTolerance;
        boolean validRayHit = rayHitBox || originInsideBox;
        boolean overLimit = bestDistance > allowedReach && bestDistance < MAX_VALID_DISTANCE;

        if (overLimit && validRayHit) {
            double punishDistance = bestDistance - allowedReach;

            if (punishDistance > 0.03D) {
                double added = punishDistance > 0.18D ? 1.35D : 1.0D;

                if (usedCompensatedRotation || recentFlick || laggy) {
                    added *= 0.75D;
                }

                if (bestDistance > 3.7) profile.getTrustFactor().decreaseTrustBy(30);

                if (profile.getTrustScore() > 80) {
                    increaseBufferBy(0.5);
                    profile.getTrustFactor().decreaseTrustBy(5);
                    return;
                }

                if (increaseBufferBy(added) > (Math.max( 0, (profile.getTrustFactor().getRequiredBuffer() + 1) / 2))) {
                    fail(
                            "Increased interaction range",
                            "distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestDistance)
                                    + "\nrawDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestRawDistance)
                                    + "\nforgivingDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestForgivingDistance)
                                    + "\nlimit " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowedReach)
                                    + "\ntolerance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reachTolerance)
                                    + "\nboxExpandH " + MsgType.MAIN_THEME_COLOR.getMessage() + format(horizontalExpand)
                                    + "\nboxExpandV " + MsgType.MAIN_THEME_COLOR.getMessage() + format(verticalExpand)
                                    + "\nforgivingExpandH " + MsgType.MAIN_THEME_COLOR.getMessage() + format(forgivingHorizontalExpand)
                                    + "\nforgivingExpandV " + MsgType.MAIN_THEME_COLOR.getMessage() + format(forgivingVerticalExpand)
                                    + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + compensatedSamples.size()
                                    + "\nhistory " + MsgType.MAIN_THEME_COLOR.getMessage() + historyAmount
                                    + "\nrayHitBox " + MsgType.MAIN_THEME_COLOR.getMessage() + rayHitBox
                                    + "\ninsideBox " + MsgType.MAIN_THEME_COLOR.getMessage() + originInsideBox
                                    + "\nrecentFlick " + MsgType.MAIN_THEME_COLOR.getMessage() + recentFlick
                                    + "\nlaggy " + MsgType.MAIN_THEME_COLOR.getMessage() + laggy
                                    + "\nusedRotationHistory " + MsgType.MAIN_THEME_COLOR.getMessage() + usedCompensatedRotation
                                    + "\ntarget " + MsgType.MAIN_THEME_COLOR.getMessage() + target.getName()
                    );
                    profile.getTrustFactor().decreaseTrustBy(2);
                }
            }
        } else {
            decreaseBufferBy(validRayHit ? 0.018D : 0.005D);
            profile.getTrustFactor().increaseTrustBy(0.0025);
        }

        double finalBestDistance = bestDistance;
        double finalBestCenterRayDistance = bestCenterRayDistance;
        double finalBestCenterAngle = bestCenterAngle;
        boolean finalRayHitBox = rayHitBox;
        boolean finalOriginInsideBox = originInsideBox;
        boolean finalUsedCompensatedRotation = usedCompensatedRotation;

        verbose(
                this.getClass().getSimpleName(),
                finalBestDistance == Double.MAX_VALUE ? MAX_VALID_DISTANCE : finalBestDistance,
                allowedReach,
                "Distance: " + format(finalBestDistance == Double.MAX_VALUE ? -1.0D : finalBestDistance)
                        + "\nlimit " + format(allowedReach)
                        + "\nsamples " + compensatedSamples.size()
                        + "\nhistory " + historyAmount
                        + "\nrayHitBox " + finalRayHitBox
                        + "\ninsideBox " + finalOriginInsideBox
                        + "\ncenterRayDistance " + format(finalBestCenterRayDistance)
                        + "\ncenterAngle " + format(finalBestCenterAngle)
                        + "\nrecentFlick " + recentFlick
                        + "\nlaggy " + laggy
                        + "\nusedRotationHistory " + finalUsedCompensatedRotation);
        profile.setReachDistance(finalBestDistance);
    }

    private boolean isRotation(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private void recordRotation(PacketReceiveEvent event) {
        if (profile == null || profile.getRotationData() == null) {
            return;
        }

        try {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);

            float yaw = wrapper.getLocation().getYaw();
            float pitch = wrapper.getLocation().getPitch();

            RotationData rotationData = profile.getRotationData();

            double deltaYaw = Math.abs(rotationData.getDeltaYaw());
            double deltaPitch = Math.abs(rotationData.getDeltaPitch());

            RotationSnapshot snapshot = new RotationSnapshot(
                    yaw,
                    pitch,
                    deltaYaw,
                    deltaPitch,
                    System.currentTimeMillis()
            );

            synchronized (rotationHistory) {
                rotationHistory.add(snapshot);

                while (rotationHistory.size() > ROTATION_HISTORY_SIZE) {
                    rotationHistory.remove(0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private List<RotationSnapshot> getRotationCandidates(float currentYaw,
                                                         float currentPitch,
                                                         double currentDeltaYaw,
                                                         double currentDeltaPitch,
                                                         int pingTicks) {
        List<RotationSnapshot> candidates = new ArrayList<>();

        long now = System.currentTimeMillis();
        long maxAge = Math.min(MAX_ROTATION_LOOKBACK_MS, BASE_ROTATION_LOOKBACK_MS + (pingTicks * 50L));
        int maxCandidates = Math.min(ROTATION_HISTORY_SIZE, Math.max(3, 3 + (pingTicks / 2)));

        candidates.add(new RotationSnapshot(
                currentYaw,
                currentPitch,
                currentDeltaYaw,
                currentDeltaPitch,
                now
        ));

        synchronized (rotationHistory) {
            for (int i = rotationHistory.size() - 1; i >= 0 && candidates.size() < maxCandidates; i--) {
                RotationSnapshot snapshot = rotationHistory.get(i);

                if (now - snapshot.timestamp > maxAge) {
                    continue;
                }

                candidates.add(snapshot);
            }
        }

        return candidates;
    }

    private boolean isRecentFlick(List<RotationSnapshot> rotations) {
        if (rotations == null || rotations.isEmpty()) {
            return false;
        }

        for (RotationSnapshot rotation : rotations) {
            if (rotation.deltaYaw >= FLICK_YAW_DELTA || rotation.deltaPitch >= FLICK_PITCH_DELTA) {
                return true;
            }
        }

        return false;
    }

    private boolean isLaggy(Profile attacker, Profile target, int attackerPingTicks, int targetPingTicks) {
        return attackerPingTicks >= 4
                || targetPingTicks >= 4
                || attacker.isBedrockPlayer()
                || target.isBedrockPlayer();
    }

    private double getAdditionalHitboxExpand(Profile attacker,
                                             Profile target,
                                             boolean recentFlick,
                                             int attackerPingTicks,
                                             int targetPingTicks) {
        double expand = 0.0D;

        expand += Math.min(0.045D, attackerPingTicks * 0.003D);
        expand += Math.min(0.035D, targetPingTicks * 0.002D);

        if (recentFlick) {
            expand += 0.035D;
        }

        if (attacker.isBedrockPlayer() || target.isBedrockPlayer()) {
            expand += 0.035D;
        }

        if (attacker.getMovementData() != null) {
            MovementData movement = attacker.getMovementData();

            if (movement.isNearWall()
                    || movement.isInsideWater()
                    || movement.isNearWater()
                    || movement.isNearBubble()) {
                expand += 0.02D;
            }
        }

        return Math.min(0.09D, expand);
    }

    private double getAdditionalVerticalExpand(Profile attacker,
                                               Profile target,
                                               boolean recentFlick,
                                               int attackerPingTicks,
                                               int targetPingTicks) {
        double expand = 0.0D;

        expand += Math.min(0.025D, attackerPingTicks * 0.0015D);
        expand += Math.min(0.025D, targetPingTicks * 0.0015D);

        if (recentFlick) {
            expand += 0.025D;
        }

        if (attacker.isBedrockPlayer() || target.isBedrockPlayer()) {
            expand += 0.025D;
        }

        return Math.min(0.07D, expand);
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
        int amount = 8 + attackerPingTicks + Math.max(0, targetPingTicks / 2);

        amount = Math.max(MIN_HISTORY_SAMPLES, amount);
        amount = Math.min(MAX_HISTORY_SAMPLES, amount);
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

    private double getReachTolerance(Profile attacker, Profile target) {
        int attackerPingTicks = getPingTicks(attacker);
        int targetPingTicks = getPingTicks(target);

        double tolerance = 0.0025D;

        tolerance += Math.min(0.025D, attackerPingTicks * 0.0015D);
        tolerance += Math.min(0.015D, targetPingTicks * 0.0010D);

        if (attacker.isBedrockPlayer() || target.isBedrockPlayer()) {
            tolerance += 0.015D;
        }

        return Math.min(MAX_REACH_TOLERANCE, tolerance);
    }

    private double getHorizontalBoxExpand(Profile target) {
        double expand = BASE_BOX_EXPAND_HORIZONTAL;

        if (target != null && target.getMovementData() != null) {
            MovementData movement = target.getMovementData();

            expand += Math.min(0.065D, movement.getDeltaXZ() * 0.45D);
            expand += Math.min(0.04D, getPingTicks(target) * 0.002D);

            if (movement.isNearWall()
                    || movement.isNearFence()
                    || movement.isOnIce()
                    || movement.isOnSlime()
                    || movement.isNearWebs()
                    || movement.isInsideWater()
                    || movement.isNearWater()) {
                expand += 0.025D;
            }
        }

        return Math.min(MAX_LAG_BOX_EXPAND, expand);
    }

    private double getVerticalBoxExpand(Profile target) {
        double expand = BASE_BOX_EXPAND_VERTICAL;

        if (target != null && target.getMovementData() != null) {
            MovementData movement = target.getMovementData();

            expand += Math.min(0.05D, Math.abs(movement.getDeltaY()) * 0.35D);

            if (movement.isNearClimbable()
                    || movement.isNearWebs()
                    || movement.isInsideWater()
                    || movement.isNearWater()
                    || movement.isNearBubble()) {
                expand += 0.025D;
            }
        }

        return Math.min(0.12D, expand);
    }

    private boolean isBadAttackerState(Profile profile) {
        if (profile.getPlayer() == null) {
            return true;
        }

        Player player = profile.getPlayer();

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        if (player.isInsideVehicle()) {
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            return true;
        }

        if (profile.getVehicleData() != null && profile.getVehicleData().getVehicleTicks() > 0) {
            return true;
        }

        if (profile.getMovementData() != null) {
            MovementData movement = profile.getMovementData();

            return movement.isNearWebs()
                    || movement.isNearClimbable()
                    || movement.isNearLava()
                    || movement.isRiptiding()
                    || movement.getSinceGlidingTicks() < 20
                    || movement.getSinceRiptidingTicks() < 20;
        }

        return false;
    }

    private boolean isBadTargetState(Profile profile) {
        if (profile.getPlayer() == null) {
            return true;
        }

        Player player = profile.getPlayer();

        if (player.isDead() || player.isInsideVehicle()) {
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            return true;
        }

        if (profile.getVehicleData() != null && profile.getVehicleData().getVehicleTicks() > 0) {
            return true;
        }

        if (profile.getMovementData() != null) {
            MovementData movement = profile.getMovementData();

            return movement.getSinceGlidingTicks() < 10
                    || movement.getSinceRiptidingTicks() < 10
                    || movement.isRiptiding();
        }

        return false;
    }

    private double getAccurateEyeHeight(Profile profile) {
        if (profile == null || profile.getPlayer() == null) {
            return 1.62D;
        }

        Player player = profile.getPlayer();

        if (isSwimmingOrGliding(player)) {
            return 0.4D;
        }

        boolean sneaking = isSneaking(profile);

        if (sneaking) {
            return hasModernSneakingDimensions() ? 1.27D : 1.54D;
        }

        return 1.62D;
    }

    private boolean isSneaking(Profile profile) {
        if (profile == null) {
            return false;
        }

        try {
            Object actionData = profile.getActionData();

            if (actionData != null) {
                java.lang.reflect.Method method = actionData.getClass().getMethod("isSneaking");
                Object result = method.invoke(actionData);

                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            return profile.getPlayer() != null && profile.getPlayer().isSneaking();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isSwimmingOrGliding(Player player) {
        if (player == null) {
            return false;
        }

        try {
            if (player.isGliding()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            java.lang.reflect.Method method = player.getClass().getMethod("isSwimming");
            Object result = method.invoke(player);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private boolean hasModernSneakingDimensions() {
        try {
            return com.github.retrooper.packetevents.PacketEvents.getAPI()
                    .getServerManager()
                    .getVersion()
                    .isNewerThanOrEquals(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_14);
        } catch (Throwable ignored) {
            return true;
        }
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

    private BoundingBox createPlayerBox(Player target, CustomLocation location, double horizontalExpand, double verticalExpand) {
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        double width = getTargetWidth(target);
        double height = getTargetHeight(target);

        double halfWidth = width * 0.5D;

        return new BoundingBox(
                (float) (x - halfWidth - horizontalExpand),
                (float) (y - verticalExpand),
                (float) (z - halfWidth - horizontalExpand),
                (float) (x + halfWidth + horizontalExpand),
                (float) (y + height + verticalExpand),
                (float) (z + halfWidth + horizontalExpand)
        );
    }

    private double getTargetWidth(Player target) {
        return 0.6D;
    }

    private double getTargetHeight(Player target) {
        if (target == null) {
            return 1.8D;
        }

        if (isSwimmingOrGliding(target)) {
            return 0.6D;
        }

        if (hasModernSneakingDimensions() && target.isSneaking()) {
            return 1.5D;
        }

        return 1.8D;
    }

    private boolean isInsideBox(Vector origin, BoundingBox box) {
        return origin.getX() >= box.minX
                && origin.getX() <= box.maxX
                && origin.getY() >= box.minY
                && origin.getY() <= box.maxY
                && origin.getZ() >= box.minZ
                && origin.getZ() <= box.maxZ;
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
        RayBoxHit hit = rayTraceBox(origin, direction, box, maxDistance);
        return hit.hit ? hit.distance : Double.MAX_VALUE;
    }

    private RayBoxHit rayTraceBox(Vector origin, Vector direction, BoundingBox box, double maxDistance) {
        if (origin == null || direction == null || box == null) {
            return RayBoxHit.miss();
        }

        double lengthSquared = direction.lengthSquared();

        if (lengthSquared <= 1.0E-12D
                || Double.isNaN(lengthSquared)
                || Double.isInfinite(lengthSquared)) {
            return RayBoxHit.miss();
        }

        Vector dir = direction;

        if (Math.abs(lengthSquared - 1.0D) > 1.0E-6D) {
            dir = direction.clone().normalize();
        }

        double ox = origin.getX();
        double oy = origin.getY();
        double oz = origin.getZ();

        double dx = dir.getX();
        double dy = dir.getY();
        double dz = dir.getZ();

        double tMin = 0.0D;
        double tMax = maxDistance;

        AxisResult x = clipAxis(ox, dx, box.minX, box.maxX, tMin, tMax);

        if (!x.valid) {
            return RayBoxHit.miss();
        }

        tMin = x.tMin;
        tMax = x.tMax;

        AxisResult y = clipAxis(oy, dy, box.minY, box.maxY, tMin, tMax);

        if (!y.valid) {
            return RayBoxHit.miss();
        }

        tMin = y.tMin;
        tMax = y.tMax;

        AxisResult z = clipAxis(oz, dz, box.minZ, box.maxZ, tMin, tMax);

        if (!z.valid) {
            return RayBoxHit.miss();
        }

        tMin = z.tMin;
        tMax = z.tMax;

        if (tMax < 0.0D || tMin > maxDistance) {
            return RayBoxHit.miss();
        }

        double distance = Math.max(0.0D, tMin);

        Vector hitPosition = new Vector(
                ox + dx * distance,
                oy + dy * distance,
                oz + dz * distance
        );

        return new RayBoxHit(true, distance, hitPosition);
    }

    private AxisResult clipAxis(double origin, double direction, double min, double max, double currentMin, double currentMax) {
        final double epsilon = 1.0E-12D;

        if (Math.abs(direction) < epsilon) {
            if (origin < min || origin > max) {
                return AxisResult.invalid();
            }

            return new AxisResult(true, currentMin, currentMax);
        }

        double inverse = 1.0D / direction;
        double t1 = (min - origin) * inverse;
        double t2 = (max - origin) * inverse;

        if (t1 > t2) {
            double temp = t1;
            t1 = t2;
            t2 = temp;
        }

        double nextMin = Math.max(currentMin, t1);
        double nextMax = Math.min(currentMax, t2);

        if (nextMin > nextMax) {
            return AxisResult.invalid();
        }

        return new AxisResult(true, nextMin, nextMax);
    }

    private static final class AxisResult {
        private final boolean valid;
        private final double tMin;
        private final double tMax;

        private AxisResult(boolean valid, double tMin, double tMax) {
            this.valid = valid;
            this.tMin = tMin;
            this.tMax = tMax;
        }

        private static AxisResult invalid() {
            return new AxisResult(false, 0.0D, 0.0D);
        }
    }

    private static final class RayBoxHit {
        private final boolean hit;
        private final double distance;
        private final Vector hitPosition;

        private RayBoxHit(boolean hit, double distance, Vector hitPosition) {
            this.hit = hit;
            this.distance = distance;
            this.hitPosition = hitPosition;
        }

        private static RayBoxHit miss() {
            return new RayBoxHit(false, Double.MAX_VALUE, null);
        }
    }

    private String format(double value) {
        if (value == Double.MAX_VALUE) {
            return "miss";
        }

        return String.format("%.4f", value);
    }

    private static final class RotationSnapshot {

        private final float yaw;
        private final float pitch;
        private final double deltaYaw;
        private final double deltaPitch;
        private final long timestamp;

        private RotationSnapshot(float yaw, float pitch, double deltaYaw, double deltaPitch, long timestamp) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.deltaYaw = deltaYaw;
            this.deltaPitch = deltaPitch;
            this.timestamp = timestamp;
        }
    }
}