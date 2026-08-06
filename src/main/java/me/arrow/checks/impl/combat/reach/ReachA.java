package me.arrow.checks.impl.combat.reach;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
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
import me.arrow.playerdata.data.impl.ReachEntityTracker;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.BoundingBox;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    boolean VERBOSE_RAY_HITBOX_STATE = true;

    int ROTATION_HISTORY_SIZE = 20;
    long BASE_ROTATION_LOOKBACK_MS = 150L;
    long MAX_ROTATION_LOOKBACK_MS = 450L;

    double FLICK_YAW_DELTA = 18.0D;
    double FLICK_PITCH_DELTA = 8.0D;

    double CLEAN_MISS_MIN_CENTER_ANGLE = 8.5D;

    final List<RotationSnapshot> rotationHistory = new ArrayList<>(ROTATION_HISTORY_SIZE);
    final List<PendingAttack> pendingAttacks = new ArrayList<>(4);

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
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (isMovement(event)) {
            if (isRotation(event)) {
                recordRotation(event);
            }

            List<PendingAttack> attacks;

            synchronized (pendingAttacks) {
                if (pendingAttacks.isEmpty()) {
                    return;
                }

                attacks = new ArrayList<>(pendingAttacks);
                pendingAttacks.clear();
            }

            for (PendingAttack attack : attacks) {
                processAttack(attack.entityId, attack.timestamp);
            }
            return;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            return;
        }

        WrapperPlayClientInteractEntity interactEntity = new WrapperPlayClientInteractEntity(event);

        if (interactEntity.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        synchronized (pendingAttacks) {
            if (pendingAttacks.size() >= 4) {
                pendingAttacks.remove(0);
            }

            pendingAttacks.add(new PendingAttack(
                    interactEntity.getEntityId(),
                    event.getTimestamp()
            ));
        }
    }

    private void processAttack(int entityId, long attackTimestamp) {

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.shouldCancel()) {
            return;
        }

        if (isBadAttackerState(profile)) {
            decreaseBufferBy(0.15D);
            return;
        }

        MovementData attackerMovement = profile.getMovementData();
        RotationData attackerRotation = profile.getRotationData();

        if (attackerMovement == null
                || attackerRotation == null
                || attackerMovement.getLocation() == null) {
            return;
        }

        // Reach is evaluated on the flying packet following the attack. The
        // 1.8 client attacked from the previous (from) position, while the new
        // flying packet supplies the finalized look/position for packet-order
        // uncertainty. This is the important behavior retained from Doctor.
        CustomLocation attackLocation = attackerMovement.getLastLocation() == null
                ? attackerMovement.getLocation()
                : attackerMovement.getLastLocation();

        final double attackerX = attackLocation.getX();
        final double attackerY = attackLocation.getY();
        final double attackerZ = attackLocation.getZ();

        final float yaw = attackerRotation.getYaw();
        final float pitch = attackerRotation.getPitch();

        final int initialAttackerPingTicks = getPingTicks(profile);
        final List<RotationSnapshot> rotationCandidates = getAttackRotationCandidates(
                attackerRotation,
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
            return;
        }

        int attackerPingTicks = getPingTicks(profile);
        int targetPingTicks = getPingTicks(targetProfile);
        ReachEntityTracker.RenderSnapshot renderSnapshot = null;

        if (!profile.isBedrockPlayer() && profile.getReachEntityTracker() != null) {
            renderSnapshot = profile.getReachEntityTracker().getRenderSnapshot(
                    entityId,
                    target.getUniqueId(),
                    target.getWorld(),
                    attackTimestamp,
                    getPingMillis(profile),
                    MIN_HISTORY_SAMPLES
            );
        }

        boolean usingClientEntityTracker = renderSnapshot != null;
        List<CustomLocation> precisionHistory = Collections.emptyList();
        List<CustomLocation> compensatedSamples;
        int historyAmount;

        if (usingClientEntityTracker) {
            compensatedSamples = renderSnapshot.getCandidates();
            historyAmount = renderSnapshot.getUpdates();
        } else {
            SampleList<CustomLocation> targetPastLocations = targetProfile.getMovementData().getPastLocations();

            if (targetPastLocations == null || targetPastLocations.size() < 4) {
                return;
            }

            List<CustomLocation> samples = snapshotSamples(targetPastLocations);

            if (samples.size() < MIN_HISTORY_SAMPLES) {
                return;
            }

            historyAmount = getHistoryAmount(samples.size(), attackerPingTicks, targetPingTicks);
            List<CustomLocation> historySamples = getLastSamples(samples, historyAmount);
            precisionHistory = snapshotSamples(targetProfile.getMovementData().getReachPastLocations());
            compensatedSamples = getCompensatedSamples(
                    historySamples,
                    attackerPingTicks,
                    MIN_HISTORY_SAMPLES
            );

            if (compensatedSamples.size() < MIN_HISTORY_SAMPLES) {
                return;
            }
        }

        double eyeHeight = getAccurateEyeHeight(profile);
        Vector origin = new Vector(attackerX, attackerY + eyeHeight, attackerZ);

        double bestDistance = Double.MAX_VALUE;
        double bestValidationDistance = Double.MAX_VALUE;
        double bestRawDistance = Double.MAX_VALUE;
        double bestForgivingDistance = Double.MAX_VALUE;
        double bestCenterDistance = Double.MAX_VALUE;
        double bestCenterRayDistance = Double.MAX_VALUE;
        double bestCenterAngle = Double.MAX_VALUE;

        boolean rayHitBox = false;
        boolean forgivingRayHitBox = false;
        boolean originInsideBox = false;
        boolean usedCompensatedRotation = false;
        boolean cornerRayHit = false;

        boolean recentFlick = isRecentFlick(rotationCandidates);
        boolean laggy = isLaggy(profile, targetProfile, attackerPingTicks, targetPingTicks);

        double reachTolerance = getReachTolerance(profile, targetProfile);
        double clientHorizontalMargin = getClientHitboxMargin();
        double clientVerticalMargin = getClientHitboxMargin();
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

        /*
         * This is the distance the client actually measured: one position at
         * the estimated render time, the attacker's latest look, and only the
         * vanilla version-specific hitbox margin. Lag/configured expansion is
         * deliberately excluded so a 3.05 client reach reports as 3.05 rather
         * than being shortened by the check's uncertainty allowance.
         */
        CustomLocation preciseSample = usingClientEntityTracker
                ? renderSnapshot.getPrecise()
                : getPreciseCompensatedSample(
                        precisionHistory,
                        attackTimestamp,
                        profile
                );
        double measuredDistance = Double.MAX_VALUE;

        if (preciseSample != null && preciseSample.getWorld() != null) {
            BoundingBox measuredBox = createPlayerBox(
                    target,
                    preciseSample,
                    clientHorizontalMargin,
                    clientVerticalMargin
            );
            RayBoxHit measuredHit = rayTraceBox(
                    origin,
                    getDirection(yaw, pitch),
                    measuredBox,
                    MAX_VALID_DISTANCE
            );
            measuredDistance = measuredHit.hit ? measuredHit.distance : Double.MAX_VALUE;
            cornerRayHit = measuredHit.hit && isHorizontalCornerHit(measuredHit.hitPosition, measuredBox);

            if (isInsideBox(origin, measuredBox)) {
                measuredDistance = 0.0D;
            }
        }

        for (CustomLocation sample : compensatedSamples) {
            if (sample == null || sample.getWorld() == null) {
                continue;
            }

            BoundingBox rawBox = createPlayerBox(target, sample, 0.0D, 0.0D);
            BoundingBox clientBox = createPlayerBox(target, sample, clientHorizontalMargin, clientVerticalMargin);
            BoundingBox expandedBox = createPlayerBox(target, sample, horizontalExpand, verticalExpand);
            BoundingBox forgivingBox = createPlayerBox(target, sample, forgivingHorizontalExpand, forgivingVerticalExpand);

            if (isInsideBox(origin, clientBox)) {
                originInsideBox = true;
                bestDistance = 0.0D;
                bestRawDistance = 0.0D;
                bestValidationDistance = 0.0D;
                bestForgivingDistance = 0.0D;
                rayHitBox = true;
                forgivingRayHitBox = true;
                break;
            }

            if (isInsideBox(origin, expandedBox)) {
                bestValidationDistance = 0.0D;
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
                RayBoxHit clientHit = rayTraceBox(origin, direction, clientBox, MAX_VALID_DISTANCE);
                double clientDistance = clientHit.hit ? clientHit.distance : Double.MAX_VALUE;
                double validationDistance = rayTraceDistanceToBox(origin, direction, expandedBox, MAX_VALID_DISTANCE);
                double forgivingDistance = rayTraceDistanceToBox(origin, direction, forgivingBox, MAX_VALID_DISTANCE);
                double centerRayDistance = distancePointToRay(origin, direction, center);
                double centerAngle = angleToPoint(origin, direction, center);

                if (rawDistance < bestRawDistance) {
                    bestRawDistance = rawDistance;
                }

                if (clientDistance < bestDistance) {
                    bestDistance = clientDistance;

                    if (i > 0) {
                        usedCompensatedRotation = true;
                    }
                }

                if (validationDistance < bestValidationDistance) {
                    bestValidationDistance = validationDistance;
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

                if (clientDistance != Double.MAX_VALUE) {
                    rayHitBox = true;
                    cornerRayHit |= isHorizontalCornerHit(clientHit.hitPosition, clientBox);

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

            // Reach is only a distance check. Hitbox A owns clean ray misses.
            decreaseBufferBy(cleanMiss ? 0.02D : 0.05D);
            return;
        }

        try {
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_11)) {
                org.bukkit.inventory.ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
                if (main != null && main.getItemMeta().getItemName().endsWith("_SPEAR")) {
                    BASE_REACH_LIMIT = 4.5;
                }
            }
        } catch (Throwable ignored) {
        }

        double allowedReach = BASE_REACH_LIMIT + reachTolerance;
        boolean validRayHit = rayHitBox || originInsideBox;
        double decisionDistance = cornerRayHit && bestValidationDistance != Double.MAX_VALUE
                ? bestValidationDistance
                : bestDistance;
        boolean overLimit = decisionDistance > allowedReach && decisionDistance < MAX_VALID_DISTANCE;
        double finalBestDistance = cornerRayHit
                ? decisionDistance
                : (measuredDistance == Double.MAX_VALUE ? bestDistance : measuredDistance);

        if (overLimit && validRayHit) {
            double punishDistance = decisionDistance - allowedReach;

            if (punishDistance > 0.03D) {
                double added = punishDistance > 0.18D ? 1.35D : 1.0D;

                if (usedCompensatedRotation || recentFlick || laggy) {
                    added *= 0.75D;
                }

                if (decisionDistance > 3.4) {
                    profile.getTrustFactor().decreaseTrustBy(20);
                    increaseBufferBy(3);
                }

                if (profile.getTrustScore() >= 80) {
                    increaseBufferBy(0.5);
                    profile.getTrustFactor().decreaseTrustBy(5);
                    return;
                }

                if (increaseBufferBy(added) > (Math.max( 0, (profile.getTrustFactor().getRequiredBuffer() + 1) / 2))) {
                    fail(
                            "Increased interaction range",
                            "distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(finalBestDistance)
                                    + "\nconservativeDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestDistance)
                                    + "\ndecisionDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(decisionDistance)
                                    + "\nmeasuredDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(measuredDistance)
                                    + "\nrawDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestRawDistance)
                                    + "\nvalidationDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestValidationDistance)
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
                                    + "\ncornerRayHit " + MsgType.MAIN_THEME_COLOR.getMessage() + cornerRayHit
                                    + "\ninsideBox " + MsgType.MAIN_THEME_COLOR.getMessage() + originInsideBox
                                    + "\nrecentFlick " + MsgType.MAIN_THEME_COLOR.getMessage() + recentFlick
                                    + "\nlaggy " + MsgType.MAIN_THEME_COLOR.getMessage() + laggy
                                    + "\nusedRotationHistory " + MsgType.MAIN_THEME_COLOR.getMessage() + usedCompensatedRotation
                                    + "\nclientEntityTracker " + MsgType.MAIN_THEME_COLOR.getMessage() + usingClientEntityTracker
                                    + "\ntarget " + MsgType.MAIN_THEME_COLOR.getMessage() + target.getName()
                    );
                    profile.getTrustFactor().decreaseTrustBy(2);
                }
            }
        } else {
            decreaseBufferBy(validRayHit ? 0.018D : 0.005D);
            profile.getTrustFactor().increaseTrustBy(0.0025);
        }

        double finalValidationDistance = bestValidationDistance;
        double finalBestCenterRayDistance = bestCenterRayDistance;
        double finalBestCenterAngle = bestCenterAngle;
        boolean finalRayHitBox = rayHitBox;
        boolean finalOriginInsideBox = originInsideBox;
        boolean finalUsedCompensatedRotation = usedCompensatedRotation;

        verbose(
                this.getClass().getSimpleName(),
                finalBestDistance,
                allowedReach,
                "Distance: " + format(finalBestDistance)
                        + "\nconservativeDistance " + format(decisionDistance)
                        + "\nvalidationDistance " + format(finalValidationDistance)
                        + "\nmeasuredDistance " + format(measuredDistance)
                        + "\ncornerRayHit " + cornerRayHit
                        + "\nlimit " + format(allowedReach)
                        + "\nsamples " + compensatedSamples.size()
                        + "\nhistory " + historyAmount
                        + "\nrayHitBox " + finalRayHitBox
                        + "\ninsideBox " + finalOriginInsideBox
                        + "\ncenterRayDistance " + format(finalBestCenterRayDistance)
                        + "\ncenterAngle " + format(finalBestCenterAngle)
                        + "\nrecentFlick " + recentFlick
                        + "\nlaggy " + laggy
                        + "\nclientEntityTracker " + usingClientEntityTracker
                        + "\nusedRotationHistory " + finalUsedCompensatedRotation);
        profile.setReachDistance(finalBestDistance);
    }

    private boolean isRotation(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
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
                    normalizePacketTimestamp(event.getTimestamp())
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

    private List<RotationSnapshot> getAttackRotationCandidates(RotationData rotationData,
                                                               int pingTicks) {
        List<RotationSnapshot> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        double deltaYaw = Math.abs(rotationData.getDeltaYaw());
        double deltaPitch = Math.abs(rotationData.getDeltaPitch());

        // Doctor evaluates the previous look, current look, and the mixed
        // previous-yaw/current-pitch packet-order case. These are the only
        // ordinary 1.8 possibilities; accepting a long rotation history made
        // normal hits look artificially shorter.
        addRotationCandidate(candidates, rotationData.getYaw(), rotationData.getPitch(), deltaYaw, deltaPitch, now);
        addRotationCandidate(candidates, rotationData.getLastYaw(), rotationData.getLastPitch(), deltaYaw, deltaPitch, now);
        addRotationCandidate(candidates, rotationData.getLastYaw(), rotationData.getPitch(), deltaYaw, deltaPitch, now);

        boolean needsLagHistory = pingTicks >= 4
                || deltaYaw >= FLICK_YAW_DELTA
                || deltaPitch >= FLICK_PITCH_DELTA;

        if (needsLagHistory) {
            long maxAge = Math.min(MAX_ROTATION_LOOKBACK_MS, BASE_ROTATION_LOOKBACK_MS + (pingTicks * 50L));
            int maxCandidates = Math.min(ROTATION_HISTORY_SIZE, Math.max(3, 3 + (pingTicks / 2)));

            synchronized (rotationHistory) {
                for (int i = rotationHistory.size() - 1; i >= 0 && candidates.size() < maxCandidates; i--) {
                    RotationSnapshot snapshot = rotationHistory.get(i);

                    if (now - snapshot.timestamp <= maxAge) {
                        addRotationCandidate(
                                candidates,
                                snapshot.yaw,
                                snapshot.pitch,
                                snapshot.deltaYaw,
                                snapshot.deltaPitch,
                                snapshot.timestamp
                        );
                    }
                }
            }
        }

        return candidates;
    }

    private void addRotationCandidate(List<RotationSnapshot> candidates,
                                      float yaw,
                                      float pitch,
                                      double deltaYaw,
                                      double deltaPitch,
                                      long timestamp) {
        for (RotationSnapshot candidate : candidates) {
            if (Math.abs(candidate.yaw - yaw) < 1.0E-4F
                    && Math.abs(candidate.pitch - pitch) < 1.0E-4F) {
                return;
            }
        }

        candidates.add(new RotationSnapshot(yaw, pitch, deltaYaw, deltaPitch, timestamp));
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

    /**
     * Approximate the transaction-compensated render window used by precise
     * reach checks. Living entities interpolate over three client ticks. We
     * include one packet-ordering tick before the expected RTT rewind and the
     * complete interpolation path, rather than accepting every recent server
     * position as a possible hit location.
     */
    private List<CustomLocation> getCompensatedSamples(List<CustomLocation> history,
                                                       int attackerPingTicks,
                                                       int minimumSamples) {
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }

        int newest = history.size() - 1;
        int expectedAge = Math.min(newest, Math.max(0, attackerPingTicks + 1));
        int youngestAge = Math.max(0, expectedAge - 1);
        int requiredPositions = Math.max(2, (int) Math.ceil((Math.max(1, minimumSamples) + 2) / 3.0D));
        int oldestAge = youngestAge + requiredPositions - 1;

        if (oldestAge > newest) {
            int overflow = oldestAge - newest;
            youngestAge = Math.max(0, youngestAge - overflow);
            oldestAge = newest;
        }

        List<CustomLocation> positions = new ArrayList<>();

        for (int age = youngestAge; age <= oldestAge; age++) {
            CustomLocation location = history.get(newest - age);

            if (location != null) {
                positions.add(location);
            }
        }

        if (positions.size() < 2) {
            return positions;
        }

        List<CustomLocation> interpolated = new ArrayList<>(positions.size() * 3);

        for (int i = 0; i < positions.size(); i++) {
            CustomLocation current = positions.get(i);
            interpolated.add(current);

            if (i + 1 >= positions.size()) {
                continue;
            }

            CustomLocation next = positions.get(i + 1);

            if (current.getWorld() != next.getWorld()) {
                continue;
            }

            interpolated.add(interpolate(current, next, 1.0D / 3.0D));
            interpolated.add(interpolate(current, next, 2.0D / 3.0D));
        }

        return interpolated;
    }

    private CustomLocation getPreciseCompensatedSample(List<CustomLocation> history,
                                                        long attackTimestamp,
                                                        Profile attacker) {
        if (history == null || history.isEmpty()) {
            return null;
        }

        attackTimestamp = normalizePacketTimestamp(attackTimestamp);

        // The attack has travelled client -> server while the entity update
        // travelled server -> client. Rewinding by the measured RTT accounts
        // for both halves; one ordering tick matches the entity render update.
        long renderTimestamp = attackTimestamp - getPingMillis(attacker) - 50L;
        CustomLocation oldest = history.get(0);
        CustomLocation newest = history.get(history.size() - 1);

        if (renderTimestamp <= oldest.getTimeStamp()) {
            return oldest;
        }

        if (renderTimestamp >= newest.getTimeStamp()) {
            return newest;
        }

        for (int i = history.size() - 2; i >= 0; i--) {
            CustomLocation from = history.get(i);
            CustomLocation to = history.get(i + 1);

            if (from == null || to == null
                    || from.getWorld() == null
                    || from.getWorld() != to.getWorld()) {
                continue;
            }

            long fromTime = from.getTimeStamp();
            long toTime = to.getTimeStamp();

            if (renderTimestamp < fromTime || renderTimestamp > toTime) {
                continue;
            }

            if (toTime <= fromTime) {
                return to;
            }

            double amount = (double) (renderTimestamp - fromTime) / (double) (toTime - fromTime);
            return interpolate(from, to, Math.max(0.0D, Math.min(1.0D, amount)));
        }

        // Timestamp gaps can happen while a stationary client only sends
        // ground packets. The tick-index rewind remains a safe fallback.
        int age = Math.min(history.size() - 1, Math.max(0, getPingTicks(attacker) + 1));
        return history.get(history.size() - 1 - age);
    }

    /**
     * PacketEvents can be configured to expose either epoch milliseconds,
     * monotonic nanoseconds, or no timestamp. Convert all three modes to the
     * same epoch-millisecond clock used by CustomLocation history.
     */
    private long normalizePacketTimestamp(long timestamp) {
        long nowMillis = System.currentTimeMillis();

        if (timestamp <= 0L) {
            return nowMillis;
        }

        if (Math.abs(nowMillis - timestamp) <= 60_000L) {
            return timestamp;
        }

        long nowNanos = System.nanoTime();
        long nanoAge = nowNanos - timestamp;

        if (nanoAge >= 0L && nanoAge <= TimeUnit.SECONDS.toNanos(60L)) {
            return nowMillis - TimeUnit.NANOSECONDS.toMillis(nanoAge);
        }

        return nowMillis;
    }

    private CustomLocation interpolate(CustomLocation from, CustomLocation to, double amount) {
        return new CustomLocation(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * amount,
                from.getY() + (to.getY() - from.getY()) * amount,
                from.getZ() + (to.getZ() - from.getZ()) * amount,
                from.getYaw(),
                from.getPitch()
        );
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

    private int getPingMillis(Profile profile) {
        if (profile == null) {
            return 0;
        }

        int ping = 0;

        try {
            ConnectionData connection = profile.getConnectionData();

            if (connection != null) {
                ping = Math.max(ping, connection.getTransPing());
                ping = Math.max(ping, connection.getPing());
            }
        } catch (Throwable ignored) {
        }

        try {
            ping = Math.max(ping, profile.getPing());
        } catch (Throwable ignored) {
        }

        return Math.min(2_000, ping);
    }

    private double getReachTolerance(Profile attacker, Profile target) {
        int attackerPingTicks = getPingTicks(attacker);
        double tolerance = Math.max(0, attackerPingTicks - 6) * 0.001D;

        return Math.min(MAX_REACH_TOLERANCE, tolerance);
    }

    private double getHorizontalBoxExpand(Profile target) {
        double configured = Math.max(0.0D, BASE_BOX_EXPAND_HORIZONTAL);
        return Math.min(MAX_LAG_BOX_EXPAND, getClientHitboxMargin() + configured);
    }

    private double getVerticalBoxExpand(Profile target) {
        double configured = Math.max(0.0D, BASE_BOX_EXPAND_VERTICAL);
        return Math.min(MAX_FORGIVING_VERTICAL_EXPAND, getClientHitboxMargin() + configured);
    }

    private double getClientHitboxMargin() {
        // 1.7/1.8 clients expand entity hitboxes by 0.1 before performing the
        // attack ray trace. This is vanilla client behavior, not lag leniency.
        return isLegacyClient() ? 0.10D : 0.0D;
    }

    private boolean isLegacyClient() {
        return profile.getVersion() != null
                && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
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

        double width = 0.6;
        double height = getTargetHeight(target);

        double halfWidth = width * 0.5D;

        return new BoundingBox(
                (x - halfWidth - horizontalExpand),
                (y - verticalExpand),
                (z - halfWidth - horizontalExpand),
                (x + halfWidth + horizontalExpand),
                (y + height + verticalExpand),
                (z + halfWidth + horizontalExpand)
        );
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

    private boolean isHorizontalCornerHit(Vector hit, BoundingBox box) {
        if (hit == null || box == null) {
            return false;
        }

        // A client ray landing on a vertical corner is sensitive to both X and
        // Z interpolation error at once. Apply the configured compensated box
        // only for that geometry instead of weakening ordinary face hits.
        final double epsilon = 0.03D;
        boolean nearXFace = Math.min(
                Math.abs(hit.getX() - box.minX),
                Math.abs(hit.getX() - box.maxX)
        ) <= epsilon;
        boolean nearZFace = Math.min(
                Math.abs(hit.getZ() - box.minZ),
                Math.abs(hit.getZ() - box.maxZ)
        ) <= epsilon;

        return nearXFace && nearZFace;
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

    private static class AxisResult {
        boolean valid;
        double tMin;
        double tMax;

        private AxisResult(boolean valid, double tMin, double tMax) {
            this.valid = valid;
            this.tMin = tMin;
            this.tMax = tMax;
        }

        private static AxisResult invalid() {
            return new AxisResult(false, 0.0D, 0.0D);
        }
    }

    private static class RayBoxHit {
        boolean hit;
        double distance;
        Vector hitPosition;

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

    private static class RotationSnapshot {

        float yaw;
        float pitch;
        double deltaYaw;
        double deltaPitch;
        long timestamp;

        private RotationSnapshot(float yaw, float pitch, double deltaYaw, double deltaPitch, long timestamp) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.deltaYaw = deltaYaw;
            this.deltaPitch = deltaPitch;
            this.timestamp = timestamp;
        }
    }

    private static class PendingAttack {
        int entityId;
        long timestamp;

        private PendingAttack(int entityId, long timestamp) {
            this.entityId = entityId;
            this.timestamp = timestamp;
        }
    }
}
