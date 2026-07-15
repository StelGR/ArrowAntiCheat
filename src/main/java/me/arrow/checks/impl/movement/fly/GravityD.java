
package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.ChatColor;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

public class GravityD extends Check {

    public GravityD(Profile profile) {
        super(profile, CheckType.GRAVITY, "D", "Checks for modified downward gravity during jumps and falls.");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        addCauseIfPresent(set, "VOID");
        addCauseIfPresent(set, "POISON");
        addCauseIfPresent(set, "WITHER");
        addCauseIfPresent(set, "FALL");
        addCauseIfPresent(set, "MAGIC");
        addCauseIfPresent(set, "FIRE");
        addCauseIfPresent(set, "FIRE_TICK");
        addCauseIfPresent(set, "CAMPFIRE");
        addCauseIfPresent(set, "SUFFOCATION");
        addCauseIfPresent(set, "LIGHTNING");
        addCauseIfPresent(set, "CONTACT");
        addCauseIfPresent(set, "THORNS");
        addCauseIfPresent(set, "FLY_INTO_WALL");
        addCauseIfPresent(set, "CRAMMING");
        addCauseIfPresent(set, "WORLD_BORDER");
        return set;
    }

    void addCauseIfPresent(Set<EntityDamageEvent.DamageCause> set, String name) {
        try {
            set.add(EntityDamageEvent.DamageCause.valueOf(name));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            if (movementData == null) {
                return;
            }

            if (movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearLava()
                    || movementData.isNearWater()
                    || profile.getExempt().isVehicle()
                    || profile.shouldCancel()
                    || movementData.getSinceGlidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 4)
                    || !CollisionUtils.isChunkLoaded(movementData.getLocation())
                    || movementData.getSinceLevitationEffectTicks() < 10) {
                resetGravityD("globalMovementExempt");
                return;
            }

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

            if (world.shouldExemptMovementChecks()
                    || world.nextToGhostWall
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.insideGhostBlock
                    || world.underGhostBlock
                    || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                resetGravityD("clientWorldMismatch");
                return;
            }

            if (movementData.getSinceTeleportTicks() < 5) {
                resetGravityD("recentTeleport");
                return;
            }

            if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                resetGravityD("recentDamage");
                return;
            }

            if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity D: Exempt - vehicle");
                resetGravityD("recentVehicle");
                return;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Gravity D: is Exempting (ghostblock liquid/web)");
                }
                resetGravityD("ghostLiquidWeb");
                return;
            }

            if (profile.getBlockProcessor().isNearGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Gravity D: is Exempting (near Ghostblock)");
                }
                resetGravityD("nearGhostBlock");
                return;
            }

            if (profile.getBlockProcessor().isUnderGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Gravity D: is Exempting (under Ghostblock)");
                }
                resetGravityD("underGhostBlock");
                return;
            }

            if (profile.getMovementData().getSinceGlidingTicks() < 20) {
                resetGravityD("recentGliding");
                return;
            }

            if (profile.getGeysersTracker().isBeingPushed()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity D: Exempt - geysers (26.2+)");
                resetGravityD("geyserPush");
                return;
            }

            GravityPredictionD(movementData);
        }
    }

    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.9800000190734863D;
    private static final double TERMINAL_VELOCITY = -3.92D;
    private static final int MAX_AGGREGATED_GRAVITY_TICKS = 4;

    boolean trackingFall;
    double predictedDY;
    double predictedFallDist;
    int predictedTicks;

    double negGravStreak;
    double cumulativeNegativeGravity;
    double gravityNoiseEma;
    double lastGravityDObservedDY = Double.NaN;
    int lastGravityDSampleTick = Integer.MIN_VALUE;

    private void GravityPredictionD(MovementData data) {
        // Rotation/ground-only flying packets do not represent another physics step.
        // MovementData intentionally produces deltaY=0 for them, so consuming them here
        // would both create false low-hop samples and destroy the fall trajectory.
        if (data == null) {
            return;
        }

        if (!data.isPacketMoving()) {
            // A long stationary period must not turn the next jump into an
            // unmodelled packet gap. Ground-only packets are safe synchronization
            // points because their vertical displacement is known to be zero.
            if (isActualGround(data) || isTrustedClientGround(data)) {
                lastGravityDSampleTick = data.getTick();
                lastGravityDObservedDY = 0.0D;
                resetGravityDTrackingOnly();

                if (data.getClientGroundTicks() > 3 || data.getServerGroundTicks() > 3) {
                    cumulativeNegativeGravity = 0.0D;
                    negGravStreak = Math.max(0.0D, negGravStreak - 0.75D);
                    decreaseBufferBy(0.50D);
                }
            }

            return;
        }

        final double displacementY = data.getDeltaY();
        final double fallDist = data.getFallDistance();
        final int transTicks = profile.getConnectionData().getClientTickTrans();

        if (!Double.isFinite(displacementY)) {
            resetGravityD("invalidMotion");
            return;
        }

        if (isGravityDExempt(data, transTicks)) {
            return;
        }

        final int movementTick = data.getTick();
        final boolean actualGround = isActualGround(data);
        final boolean trustedClientGround = isTrustedClientGround(data);
        final int airTicks = getAirTicks(data);
        final boolean slowFalling = profile.getPotionData().isHasSlowFalling();

        if (lastGravityDSampleTick == Integer.MIN_VALUE || !Double.isFinite(lastGravityDObservedDY)) {
            lastGravityDSampleTick = movementTick;
            synchronizeGravityDSample(data, displacementY, actualGround || trustedClientGround);
            return;
        }

        final int sampleTicks = movementTick - lastGravityDSampleTick;

        if (sampleTicks <= 0) {
            return;
        }

        lastGravityDSampleTick = movementTick;

        // Vanilla may aggregate a very small number of position changes. Model those
        // as cumulative displacement; a larger gap is not safe to infer from one sample.
        if (sampleTicks > MAX_AGGREGATED_GRAVITY_TICKS) {
            synchronizeGravityDSample(data, displacementY, actualGround || trustedClientGround);
            decayGravityDEvidence(0.75D, 0.35D);
            return;
        }

        if ((actualGround || trustedClientGround) && !trackingFall) {
            lastGravityDObservedDY = 0.0D;
            cumulativeNegativeGravity *= 0.45D;
            decayGravityDEvidence(0.45D, 0.20D);
            return;
        }

        final double lastObservedDY = lastGravityDObservedDY;
        final boolean launchSample = sampleTicks == 1
                && displacementY > 0.0D
                && !actualGround
                && !trustedClientGround
                && Math.abs(lastObservedDY) < 0.035D
                && (data.isLastOnGround() || data.getClientAirTicks() <= 2);

        GravityMotion localMotion;
        PredictionResult expectedResult;

        if (launchSample) {
            double jumpMotion = getExpectedJumpMotion();
            expectedResult = selectGravityPrediction(data, jumpMotion, true);
            localMotion = new GravityMotion(expectedResult.prediction, expectedResult.prediction);
        } else {
            PredictionResult recoveredGravityPhase = sampleTicks == 1
                    ? getRecoveredGravityPhasePrediction(data, lastObservedDY, displacementY, fallDist)
                    : null;

            if (recoveredGravityPhase != null) {
                expectedResult = recoveredGravityPhase;
                localMotion = new GravityMotion(recoveredGravityPhase.prediction, recoveredGravityPhase.prediction);
            } else {
                GravityMotion normalLocalMotion = simulateGravity(lastObservedDY, sampleTicks);
                expectedResult = sampleTicks == 1
                        ? selectGravityPrediction(data, normalLocalMotion.terminalDY, false)
                        : new PredictionResult(normalLocalMotion.terminalDY, "normal-aggregate", 0.0D);
                localMotion = sampleTicks == 1
                        ? new GravityMotion(expectedResult.prediction, expectedResult.prediction)
                        : normalLocalMotion;
            }
        }

        final boolean recoveredGravityPhase = expectedResult.type.startsWith("recovered-");
        final boolean hadTrajectory = trackingFall
                && Double.isFinite(predictedDY)
                && !recoveredGravityPhase;
        GravityMotion trajectoryMotion = hadTrajectory && !launchSample
                ? simulateGravity(predictedDY, sampleTicks)
                : localMotion;

        // For the one-step decision use the more conservative (more downward)
        // physically valid prediction. The independent trajectory is still evaluated
        // separately, which catches small repeated modifications without overfitting
        // every new prediction to already-modified client motion.
        final boolean selectedLocal = localMotion.displacementY <= trajectoryMotion.displacementY;
        final double selectedExpectedDisplacement = selectedLocal
                ? localMotion.displacementY
                : trajectoryMotion.displacementY;
        final double selectedExpectedDY = selectedLocal
                ? localMotion.terminalDY
                : trajectoryMotion.terminalDY;
        final String selectedType = selectedLocal ? expectedResult.type : "trajectory";

        final double currentDY = estimateTerminalDY(
                lastObservedDY, displacementY, sampleTicks, launchSample, localMotion
        );
        final double normalExpectedDY = launchSample
                ? getExpectedJumpMotion()
                : simulateGravity(lastObservedDY, sampleTicks).terminalDY;
        final double selectedAllowedPerTick = getFastFallAllowed(
                profile, data, currentDY, lastObservedDY, selectedExpectedDY
        );
        final double selectedAllowed = getAggregateGravityAllowance(selectedAllowedPerTick, sampleTicks);
        final double selectedExcess = selectedExpectedDisplacement - displacementY;
        final double selectedExtraGravity = selectedExcess - selectedAllowed;
        final boolean selectedTooFast = selectedExcess > selectedAllowed;

        final double trajectoryExcess = trajectoryMotion.displacementY - displacementY;
        final double trajectoryAllowed = getTrajectoryGravityAllowance(sampleTicks, predictedTicks);
        final boolean trajectoryEvidence = hadTrajectory
                && predictedTicks >= 2
                && trajectoryExcess > trajectoryAllowed;

        updateCumulativeNegativeGravity(localMotion.displacementY - displacementY, selectedAllowed, actualGround);
        final double cumulativeRequired = getCumulativeGravityRequired();
        final boolean cumulativeEvidence = airTicks >= 3
                && cumulativeNegativeGravity > cumulativeRequired;

        final double expectedAcceleration = lastObservedDY - selectedExpectedDY;
        final double actualAcceleration = lastObservedDY - currentDY;
        final double accelerationExcess = sampleTicks == 1
                ? actualAcceleration - expectedAcceleration
                : 0.0D;

        final double normalDoubleGravityDY = sampleTicks == 1
                ? predictGravityDY(profile, data, selectedExpectedDY)
                : Double.NaN;
        final PredictionResult doubleResult = sampleTicks == 1
                ? selectGravityPrediction(data, normalDoubleGravityDY, false)
                : new PredictionResult(Double.NaN, "none", Double.POSITIVE_INFINITY);
        final double doubleGravityDY = doubleResult.prediction;

        if (!profile.isBedrockPlayer()
                && sampleTicks == 1
                && isVanillaMicroFallTransition(currentDY, lastObservedDY, selectedExpectedDY, doubleGravityDY)) {
            lastGravityDObservedDY = currentDY;
            trackingFall = true;
            predictedDY = currentDY;
            predictedFallDist += Math.max(0.0D, -currentDY);
            predictedTicks = Math.max(predictedTicks + 1, data.getCustomAirTicks());
            decayGravityDEvidence(0.45D, 0.20D);
            return;
        }

        boolean directFastFall = sampleTicks == 1 && isDirectFastFallMotion(
                data, currentDY, lastObservedDY, selectedExpectedDY,
                selectedExpectedDY - currentDY, selectedAllowedPerTick,
                airTicks, actualGround, slowFalling, transTicks
        );
        boolean lowHopMotion = sampleTicks == 1 && isLowHopMotion(
                data, currentDY, lastObservedDY, selectedExpectedDY,
                selectedAllowedPerTick, selectedExpectedDY - currentDY,
                airTicks, actualGround, trustedClientGround, transTicks
        );
        boolean impossibleFastLanding = sampleTicks == 1 && isImpossibleFastLanding(
                data, currentDY, lastObservedDY, selectedExpectedDY,
                selectedExpectedDY - currentDY, selectedAllowedPerTick,
                actualGround, slowFalling, transTicks
        );

        final double perTickExcess = selectedExpectedDY - currentDY;
        final double perTickExtraGravity = perTickExcess - selectedAllowedPerTick;
        final double severity = Math.max(
                selectedAllowed <= 0.0D ? selectedExcess : selectedExcess / selectedAllowed,
                Math.max(
                        trajectoryAllowed <= 0.0D ? trajectoryExcess : trajectoryExcess / trajectoryAllowed,
                        cumulativeRequired <= 0.0D ? cumulativeNegativeGravity : cumulativeNegativeGravity / cumulativeRequired
                )
        );

        if (directFastFall || lowHopMotion || impossibleFastLanding) {
            int evidenceAirTicks = impossibleFastLanding ? Math.max(airTicks, predictedTicks) : airTicks;
            boolean fastFallEvidence = directFastFall || impossibleFastLanding;

            if (handleGravityDFlag(data,
                    true,
                    fallDist,
                    evidenceAirTicks,
                    selectedExpectedDY,
                    normalExpectedDY,
                    "direct-" + selectedType,
                    doubleGravityDY,
                    currentDY,
                    lastObservedDY,
                    perTickExcess,
                    perTickExtraGravity,
                    selectedAllowedPerTick,
                    severity,
                    accelerationExcess,
                    false,
                    lowHopMotion,
                    false,
                    false,
                    false,
                    actualGround,
                    trustedClientGround,
                    transTicks,
                    fastFallEvidence ? 0.75D : 1.25D,
                    impossibleFastLanding ? 3.25D : directFastFall ? 3.00D : 2.25D)) {
                return;
            }
        }

        boolean doubleGravityMatch = sampleTicks == 1
                && selectedTooFast
                && Double.isFinite(doubleGravityDY)
                && doubleGravityDY < selectedExpectedDY
                && Math.abs(currentDY - doubleGravityDY) < Math.abs(currentDY - selectedExpectedDY);

        boolean earlyMotionCut = sampleTicks == 1
                && selectedTooFast
                && airTicks >= 2
                && airTicks <= 6 + transTicks
                && lastObservedDY > 0.080D
                && selectedExpectedDY > 0.030D
                && currentDY < selectedExpectedDY - Math.max(0.075D, selectedAllowedPerTick * 1.60D)
                && accelerationExcess > Math.max(0.050D, selectedAllowedPerTick * 1.20D);

        boolean lateMotionSet = sampleTicks == 1
                && selectedTooFast
                && airTicks >= 5
                && airTicks <= 18 + transTicks
                && currentDY < -0.300D
                && perTickExtraGravity > 0.060D
                && actualAcceleration > expectedAcceleration + Math.max(0.045D, selectedAllowedPerTick * 1.10D);

        boolean hardMotionSet = sampleTicks == 1
                && selectedTooFast
                && airTicks >= 2
                && currentDY < -0.520D
                && perTickExcess > Math.max(0.105D, selectedAllowedPerTick * 2.00D);

        boolean impossibleAcceleration = sampleTicks == 1
                && selectedTooFast
                && airTicks >= 2
                && currentDY < -0.095D
                && accelerationExcess > Math.max(0.055D, selectedAllowedPerTick * 1.45D);

        boolean terminalBreak = sampleTicks == 1
                && currentDY < TERMINAL_VELOCITY - selectedAllowedPerTick;
        boolean genericFastFall = selectedTooFast
                || trajectoryEvidence
                || cumulativeEvidence
                || terminalBreak;

        verbose(this.getClass().getSimpleName(), currentDY, selectedExpectedDY,
                ChatColor.RED + "Verbose (4)"
                        + "\nfallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                        + "\npredTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedTicks
                        + "\nsampleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + sampleTicks
                        + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExpectedDY
                        + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + normalExpectedDY
                        + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedType
                        + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                        + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + currentDY
                        + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastObservedDY
                        + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExcess
                        + "\ntrajectoryExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + trajectoryExcess
                        + "\ncumulativeExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + cumulativeNegativeGravity
                        + "\nextraGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExtraGravity
                        + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedAllowed
                        + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                        + "\naccelExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + accelerationExcess
                        + "\ndoubleGravityMatch " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch

                        + "\nearlyMotionCut " + MsgType.MAIN_THEME_COLOR.getMessage() + earlyMotionCut
                        + "\nlateMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + lateMotionSet
                        + "\nhardMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + hardMotionSet
                        + "\nimpossibleAcceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleAcceleration
                        + "\ntrajectoryEvidence " + MsgType.MAIN_THEME_COLOR.getMessage() + trajectoryEvidence
                        + "\ncumulativeEvidence " + MsgType.MAIN_THEME_COLOR.getMessage() + cumulativeEvidence
                        + "\nterminalBreak " + MsgType.MAIN_THEME_COLOR.getMessage() + terminalBreak
                        + "\ndirectEvidence " + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (directFastFall || impossibleFastLanding || lowHopMotion)
                        + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

        if (genericFastFall) {
            boolean hardFastFall = terminalBreak
                    || hardMotionSet
                    || lateMotionSet
                    || earlyMotionCut
                    || impossibleAcceleration
                    || trajectoryEvidence
                    || cumulativeEvidence
                    || (doubleGravityMatch && perTickExcess > 0.050D && airTicks >= 3)
                    || (selectedExcess > 0.095D && severity > 3.0D);

            boolean extremeFastFall = terminalBreak
                    || directFastFall
                    || hardMotionSet
                    || (sampleTicks == 1 && airTicks >= 3 && currentDY < -0.700D && perTickExcess > 0.180D);

            double required = extremeFastFall ? 0.75D : hardFastFall ? 1.75D : 3.50D;
            double added = getFastFallStreakAdd(
                    doubleGravityMatch, earlyMotionCut, lateMotionSet,
                    hardMotionSet, impossibleAcceleration, terminalBreak, severity
            );

            if (trajectoryEvidence) added += 0.75D;
            if (cumulativeEvidence) added += 0.65D;

            if (handleGravityDFlag(data,
                    trajectoryEvidence || cumulativeEvidence,
                    fallDist,
                    airTicks,
                    selectedExpectedDY,
                    normalExpectedDY,
                    selectedType,
                    doubleGravityDY,
                    currentDY,
                    lastObservedDY,
                    selectedExcess,
                    selectedExtraGravity,
                    selectedAllowed,
                    severity,
                    accelerationExcess,
                    doubleGravityMatch,
                    earlyMotionCut,
                    lateMotionSet,
                    hardMotionSet,
                    impossibleAcceleration,
                    actualGround,
                    trustedClientGround,
                    transTicks,
                    required,
                    added)) {
                return;
            }
        } else {
            decayGravityDEvidence(0.35D, 0.12D);
        }

        updateGravityDNoise(
                Math.abs(selectedExpectedDisplacement - displacementY) / sampleTicks,
                !genericFastFall && !actualGround && !trustedClientGround
        );

        lastGravityDObservedDY = currentDY;

        if (actualGround || trustedClientGround) {
            resetGravityDTrackingOnly();
            lastGravityDObservedDY = 0.0D;
            cumulativeNegativeGravity *= 0.45D;
            decayGravityDEvidence(0.45D, 0.20D);
            return;
        }

        trackingFall = true;
        predictedTicks = Math.max(predictedTicks + sampleTicks, data.getCustomAirTicks());

        // If the client moved substantially slower/upward compared with the model,
        // it is a new non-negative force. D does not judge that direction, so rebase
        // rather than letting a stale trajectory create a later false fast-fall.
        if (selectedExcess < -Math.max(0.060D, selectedAllowed * 2.0D)) {
            predictedDY = currentDY;
            predictedFallDist = Math.max(0.0D, -currentDY);
            cumulativeNegativeGravity *= 0.35D;
        } else {
            predictedDY = trajectoryMotion.terminalDY;

            if (trajectoryMotion.displacementY < 0.0D) {
                predictedFallDist += -trajectoryMotion.displacementY;
            } else if (currentDY > 0.0D) {
                predictedFallDist = 0.0D;
            }
        }
    }

    private boolean isGravityDExempt(MovementData data, int transTicks) {
        if (profile.shouldCancel()) { resetGravityD("shouldCancel"); return true; }
        if (profile.isBouncingOnSlime()) { resetGravityD("bouncingOnSlime"); return true; }
        if (profile.isExempt().isTeleports()) { resetGravityD("teleport"); return true; }
        if (profile.isExempt().vehicle()) { resetGravityD("vehicle"); return true; }
        if (profile.getMovementData().getSinceOnGhostBlock() <= 10 + transTicks) { resetGravityD("ghostBlock"); return true; }

        if (data.isNearWater()) { resetGravityD("nearWater"); return true; }
        if (data.isNearLava()) { resetGravityD("nearLava"); return true; }
        if (data.isNearWebs()) { resetGravityD("nearWebs"); return true; }
        if (data.isNearBoat()) { resetGravityD("nearBoat"); return true; }
        if (data.isNearBed()) { resetGravityD("nearBed"); return true; }
        if (data.isNearShulker()) { resetGravityD("nearShulker"); return true; }
        if (data.isNearShulkerBox()) { resetGravityD("nearShulkerBox"); return true; }
        if (data.isNearClimbable()) { resetGravityD("nearClimbable"); return true; }
        if (data.isOnSlime()) { resetGravityD("onSlime"); return true; }
        if (data.isNearContact()) { resetGravityD("nearContact"); return true; }
        if (data.getSinceGlidingTicks() < 20 + transTicks) { resetGravityD("gliding"); return true; }
        if (data.isOnHoney()) { resetGravityD("onHoney"); return true; }
        if (data.isInsideWater()) { resetGravityD("insideWater"); return true; }
        if (data.isOnTopOfWater()) { resetGravityD("onTopOfWater"); return true; }
        if (data.isBottomOfWater()) { resetGravityD("bottomOfWater"); return true; }
        if (data.isUnderblock()) { resetGravityD("underBlock"); return true; }
        if (data.getMovingUnderblockTicks() > 0) { resetGravityD("movingUnderBlock"); return true; }
        if (data.getSinceRiptidingTicks() < 10 + transTicks) { resetGravityD("riptiding"); return true; }

        if (profile.getVelocityData().isTakingVelocity()) {
            resetGravityD("takingVelocity");
            return true;
        }

        if (profile.getPotionData().isHasLevitation()) { resetGravityD("levitation"); return true; }

        if (data.getSincePowderSnowTicks() < 15 + (transTicks * 2)) {
            resetGravityD("powderSnow");
            return true;
        }

        return false;
    }

    private boolean isActualGround(MovementData data) {
        // CollisionUtils deliberately has generous support around block edges.
        // That is useful for ground checks, but it must not reset a gravity
        // trajectory while the client is explicitly airborne and still moving Y.
        if (!data.isOnGround()
                && (Math.abs(data.getDeltaY()) > 1.0E-7D
                || data.getClientAirTicks() > 0
                || data.getFallDistance() > 0.0F)) {
            return false;
        }

        return !data.isCustomInAir()
                && (data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround());
    }

    private boolean isTrustedClientGround(MovementData data) {
        return data.isOnGround()
                && !data.isCustomInAir()
                && data.getServerAirTicks() <= 1
                && data.getCustomAirTicks() <= 1;
    }

    private int getAirTicks(MovementData data) {
        return Math.max(data.getCustomAirTicks(), Math.max(data.getClientAirTicks(), data.getServerAirTicks()));
    }

    private boolean isDirectFastFallMotion(MovementData data,
                                           double dy,
                                           double lastDy,
                                           double expectedDY,
                                           double excess,
                                           double allowed,
                                           int airTicks,
                                           boolean actualGround,
                                           boolean slowFalling,
                                           int transTicks) {
        if (actualGround
                || data.isNearStepMaterial()
                || hasRecentGravitySupportChange(4 + (transTicks * 2))) {
            return false;
        }

        boolean tickWindow = airTicks >= 2 && airTicks <= 20 + transTicks;
        boolean descendingPhase = lastDy <= 0.040D || expectedDY <= 0.0D;
        double acceleratedThreshold = slowFalling ? -0.165D : -0.300D;
        double acceleratedExcess = slowFalling ? 0.075D : 0.095D;
        double hardThreshold = slowFalling ? -0.300D : -0.520D;
        double hardExcess = slowFalling ? 0.090D : 0.105D;
        boolean acceleratedFall = dy < acceleratedThreshold
                && excess > Math.max(acceleratedExcess, allowed * 1.75D);
        boolean hardMotionSet = dy < hardThreshold
                && excess > Math.max(hardExcess, allowed * 2.00D);
        boolean terminalBreak = dy < TERMINAL_VELOCITY - allowed;

        return tickWindow
                && (terminalBreak || hardMotionSet || (descendingPhase && acceleratedFall));
    }

    private boolean isImpossibleFastLanding(MovementData data,
                                            double dy,
                                            double lastDy,
                                            double expectedDY,
                                            double excess,
                                            double allowed,
                                            boolean actualGround,
                                            boolean slowFalling,
                                            int transTicks) {
        if (!actualGround
                || !trackingFall
                || predictedTicks < 4
                || predictedFallDist < 0.20D
                || data.isNearStepMaterial()) {
            return false;
        }

        // A real landing only shortens the predicted downward move. It cannot make
        // that move substantially more negative. Fast-fall clients can cross the
        // remaining gap and land in one packet, which used to be discarded here.
        if (data.isLastServerGround() || data.isLastPositionYGround()) {
            return false;
        }

        if (hasRecentGravitySupportChange(4 + (transTicks * 2))) {
            return false;
        }

        double requiredExcess = Math.max(slowFalling ? 0.200D : 0.165D, allowed * 2.75D);
        boolean wasDescending = lastDy < -0.040D || predictedDY < -0.040D;
        boolean largeLandingMove = dy < -0.300D;
        boolean impossibleDisplacement = dy < expectedDY - requiredExcess
                && excess > requiredExcess;

        return wasDescending && largeLandingMove && impossibleDisplacement;
    }

    private boolean isLowHopMotion(MovementData data,
                                   double dy,
                                   double lastDy,
                                   double expectedDY,
                                   double allowed,
                                   double excess,
                                   int airTicks,
                                   boolean actualGround,
                                   boolean trustedClientGround,
                                   int transTicks) {
        if (actualGround
                || trustedClientGround
                || profile.getPotionData().isHasSlowFalling()
                || data.isNearStepMaterial()) {
            return false;
        }

        int supportTicks = 5 + (transTicks * 2);

        if (hasRecentGravitySupportChange(supportTicks)) {
            return false;
        }

        boolean earlyJumpTick = airTicks >= 1 && airTicks <= 5 + transTicks;
        boolean leftGround = data.isLastOnGround()
                || data.isLastServerGround();
        boolean fromJump = leftGround || lastDy > 0.180D;
        double launchTolerance = profile.isBedrockPlayer() ? 0.085D : 0.075D;
        boolean reducedJumpLaunch = leftGround
                && data.getClientAirTicks() == 1
                && airTicks <= 2
                && dy > 0.040D
                && dy < MoveUtils.getJumpMotion(profile) - launchTolerance;
        double requiredCut = Math.max(0.090D, allowed * 1.40D);
        boolean expectedToKeepRising = expectedDY > 0.040D;
        boolean severeRiseCut = dy < expectedDY - requiredCut
                && excess > requiredCut;

        return reducedJumpLaunch
                || (earlyJumpTick && fromJump && expectedToKeepRising && severeRiseCut);
    }

    private boolean hasRecentGravitySupportChange(int ticks) {
        ActionData actionData = profile.getActionData();

        return actionData != null
                && (actionData.hasRecentConfirmedUnderPlace(ticks)
                || actionData.hasRecentConfirmedUnderBreak(ticks)
                || actionData.hasRecentBlockUpdateUnder(ticks)
                || actionData.hasRecentPistonUpdate(ticks));
    }

    private double getFastFallStreakAdd(boolean doubleGravityMatch,
                                        boolean earlyMotionCut,
                                        boolean lateMotionSet,
                                        boolean hardMotionSet,
                                        boolean impossibleAcceleration,
                                        boolean terminalBreak,
                                        double severity) {
        double added = 0.75D;

        if (doubleGravityMatch) {
            added += 0.85D;
        }

        if (earlyMotionCut) {
            added += 0.70D;
        }

        if (lateMotionSet) {
            added += 1.05D;
        }

        if (hardMotionSet) {
            added += 1.60D;
        }

        if (impossibleAcceleration) {
            added += 0.75D;
        }

        if (severity > 2.25D) {
            added += 0.45D;
        }

        if (severity > 3.25D) {
            added += 0.55D;
        }

        if (terminalBreak) {
            added += 1.25D;
        }

        return added;
    }

    private boolean handleGravityDFlag(MovementData data,
                                       boolean directEvidence,
                                       double fallDist,
                                       int airTicks,
                                       double expectedDY,
                                       double normalExpectedDY,
                                       String expectedType,
                                       double doubleGravityDY,
                                       double dy,
                                       double lastDy,
                                       double excess,
                                       double extraGravity,
                                       double allowed,
                                       double severity,
                                       double accelerationExcess,
                                       boolean doubleGravityMatch,
                                       boolean earlyMotionCut,
                                       boolean lateMotionSet,
                                       boolean hardMotionSet,
                                       boolean impossibleAcceleration,
                                       boolean actualGround,
                                       boolean trustedClientGround,
                                       int transTicks,
                                       double required,
                                       double added) {
        if (!directEvidence) {
            if (data.getSincePredictUpwardsTicks() < 5 + transTicks) {
                resetGravityD("predictUpwards");
                return true;
            }

            if (data.getSincePredictDownwardsTicks() < 5 + transTicks) {
                resetGravityD("predictDownwards");
                return true;
            }
        }

        negGravStreak += added;
        double bufferAdded = Math.max(0.25D, Math.min(1.0D, added * 0.30D));

        if (increaseBufferBy(bufferAdded) > required || negGravStreak > required) {
            fail("Negative Gravity Modification",
                    "fallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                            + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                            + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDY
                            + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + normalExpectedDY
                            + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedType
                            + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                            + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + dy
                            + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDy
                            + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                            + "\nextraGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + extraGravity
                            + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + allowed
                            + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                            + "\naccelExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + accelerationExcess
                            + "\ndoubleGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch
                            + "\nearlyMotionCut " + MsgType.MAIN_THEME_COLOR.getMessage() + earlyMotionCut
                            + "\nlateMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + lateMotionSet
                            + "\nhardMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + hardMotionSet
                            + "\nimpossibleAcceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleAcceleration
                            + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + data.isOnGround()
                            + "\ncustomInAir " + MsgType.MAIN_THEME_COLOR.getMessage() + data.isCustomInAir()
                            + "\nactualGround " + MsgType.MAIN_THEME_COLOR.getMessage() + actualGround
                            + "\ntrustedClientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + trustedClientGround
                            + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaXZ()
                            + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

            resetGravityDTrackingOnly();
            lastGravityDObservedDY = dy;
            lastGravityDSampleTick = data.getTick();
            cumulativeNegativeGravity = 0.0D;
            negGravStreak = Math.max(required + 1.0D, negGravStreak);
            return true;
        }

        return false;
    }

    private void resetGravityDTrackingOnly() {
        trackingFall = false;
        predictedDY = 0.0D;
        predictedFallDist = 0.0D;
        predictedTicks = 0;
    }

    private void decayGravityDStreaks(double amount) {
        negGravStreak = Math.max(0.0D, negGravStreak - amount);
    }

    private void decayGravityDEvidence(double streakAmount, double bufferAmount) {
        decayGravityDStreaks(streakAmount);
        decreaseBufferBy(bufferAmount);
    }

    private void synchronizeGravityDSample(MovementData data, double displacementY, boolean grounded) {
        resetGravityDTrackingOnly();
        cumulativeNegativeGravity = 0.0D;

        if (grounded) {
            lastGravityDObservedDY = 0.0D;
            return;
        }

        double maximumUpward = Math.max(0.62D, getExpectedJumpMotion() + 0.30D);
        double seed = Math.max(TERMINAL_VELOCITY, Math.min(maximumUpward, displacementY));

        lastGravityDObservedDY = seed;
        predictedDY = seed;
        predictedTicks = Math.max(1, data.getCustomAirTicks());
        predictedFallDist = Math.max(0.0D, -seed);
        trackingFall = true;
    }

    private GravityMotion simulateGravity(double previousDY, int ticks) {
        double velocity = Double.isFinite(previousDY) ? previousDY : 0.0D;
        double displacement = 0.0D;
        int simulatedTicks = Math.max(1, ticks);

        for (int i = 0; i < simulatedTicks; i++) {
            velocity = predictGravityDY(profile, profile.getMovementData(), velocity);
            displacement += velocity;
        }

        return new GravityMotion(displacement, velocity);
    }

    private PredictionResult getRecoveredGravityPhasePrediction(MovementData data,
                                                                double previousDY,
                                                                double currentDY,
                                                                double fallDistance) {
        if (data == null
                || data.isOnGround()
                || Math.abs(previousDY) > 1.0E-6D
                || currentDY >= -0.070D
                || fallDistance <= 0.0D) {
            return null;
        }

        double motionTolerance = profile.isBedrockPlayer() ? 0.025D : 1.0E-5D;
        double distanceTolerance = profile.isBedrockPlayer() ? 0.090D : 0.015D;
        double[] startingVelocities = {0.0D, getExpectedJumpMotion()};
        String[] predictionTypes = {"recovered-edge-fall", "recovered-jump-fall"};
        PredictionResult best = null;

        // Broad edge/single-block support can temporarily report ground while the
        // client continues a real jump or ledge fall. Recover the phase only when
        // both the current velocity and the already accumulated fall distance match
        // the same vanilla trajectory. This is prediction, not an edge exemption.
        for (int trajectory = 0; trajectory < startingVelocities.length; trajectory++) {
            double velocity = startingVelocities[trajectory];
            double accumulatedBefore = 0.0D;

            for (int gravityStep = 1; gravityStep <= 20; gravityStep++) {
                velocity = predictGravityDY(profile, data, velocity);

                double motionOffset = Math.abs(currentDY - velocity);
                double distanceOffset = Math.abs(fallDistance - accumulatedBefore);

                if (gravityStep >= 2
                        && motionOffset <= motionTolerance
                        && distanceOffset <= distanceTolerance) {
                    double combinedOffset = motionOffset + distanceOffset;


                    if (best == null || combinedOffset < best.offset) {
                        best = new PredictionResult(velocity, predictionTypes[trajectory], combinedOffset);
                    }
                }

                accumulatedBefore += Math.max(0.0D, -velocity);
            }
        }

        return best;
    }

    private double estimateTerminalDY(double previousDY,
                                      double displacementY,
                                      int ticks,
                                      boolean launchSample,
                                      GravityMotion expectedMotion) {
        if (ticks <= 1 || launchSample) {
            return displacementY;
        }

        // If the same unexpected acceleration affected every aggregated client tick,
        // 2 * displacementError / (ticks + 1) estimates its terminal contribution.
        double displacementError = displacementY - expectedMotion.displacementY;
        double terminal = expectedMotion.terminalDY
                + ((2.0D * displacementError) / (ticks + 1.0D));
        double maximumUpward = Math.max(0.72D, getExpectedJumpMotion() + 0.40D);

        if (!Double.isFinite(terminal)) {
            terminal = displacementY / ticks;
        }

        return Math.max(TERMINAL_VELOCITY, Math.min(maximumUpward, terminal));
    }

    private double getExpectedJumpMotion() {
        double motion = MoveUtils.getJumpMotion(profile);

        if (!Double.isFinite(motion) || motion < 0.30D || motion > 1.60D) {
            motion = 0.42D;

            if (profile.getPotionData().isHasJump()) {
                motion += Math.max(0, profile.getPotionData().getJumpAmplifier()) * 0.10D;
            }
        }

        return motion;
    }

    private double getAggregateGravityAllowance(double perTickAllowance, int sampleTicks) {
        int ticks = Math.max(1, sampleTicks);
        double aggregation = perTickAllowance * (1.0D + ((ticks - 1) * 0.65D));
        double noise = gravityNoiseEma * ticks;
        return aggregation + noise;
    }

    private double getTrajectoryGravityAllowance(int sampleTicks, int trajectoryTicks) {
        boolean bedrock = profile.isBedrockPlayer();
        double base = bedrock ? 0.055D : 0.026D;
        double growth = Math.min(
                bedrock ? 0.090D : 0.050D,
                Math.max(0, trajectoryTicks) * (bedrock ? 0.006D : 0.0035D)
        );
        double aggregation = Math.max(0, sampleTicks - 1) * (bedrock ? 0.025D : 0.012D);
        return base + growth + aggregation + (gravityNoiseEma * Math.max(1, sampleTicks) * 1.5D);
    }

    private void updateCumulativeNegativeGravity(double localExcess, double allowed, boolean grounded) {
        double noiseFloor = Math.max(
                profile.isBedrockPlayer() ? 0.0035D : 0.0015D,
                Math.min(0.012D, allowed * 0.18D)
        );
        double evidence = localExcess - noiseFloor;

        if (evidence > 0.0D) {
            cumulativeNegativeGravity = Math.min(
                    1.50D,
                    (cumulativeNegativeGravity * 0.92D) + evidence
            );
        } else {
            cumulativeNegativeGravity *= grounded ? 0.45D : 0.62D;
        }
    }

    private double getCumulativeGravityRequired() {
        return (profile.isBedrockPlayer() ? 0.105D : 0.040D)
                + (gravityNoiseEma * (profile.isBedrockPlayer() ? 3.0D : 2.0D));
    }

    private void updateGravityDNoise(double residual, boolean cleanSample) {
        if (!cleanSample || !Double.isFinite(residual)) {
            return;
        }

        double cap = profile.isBedrockPlayer() ? 0.030D : 0.010D;
        double sample = Math.min(cap, Math.max(0.0D, residual));
        gravityNoiseEma = Math.min(cap, (gravityNoiseEma * 0.90D) + (sample * 0.10D));
    }

    private PredictionResult selectGravityPrediction(MovementData data, double normalPrediction, boolean allowJump) {
        double actual = data.getDeltaY();
        PredictionResult best = new PredictionResult(normalPrediction, "normal", Math.abs(actual - normalPrediction));

        double placedLanding = getPlacedBlockLandingPrediction(data, normalPrediction);

        if (Double.isFinite(placedLanding)) {
            double offset = Math.abs(actual - placedLanding);

            if (offset + 1.0E-7D < best.offset
                    && offset <= getPlacedBlockCollisionTolerance() + 0.015D) {
                best = new PredictionResult(placedLanding, "placedBlockLanding", offset);
            }
        }

        if (allowJump) {
            double placedJump = getPlacedBlockJumpPrediction(data);

            if (Double.isFinite(placedJump)) {
                double offset = Math.abs(actual - placedJump);
                double launchTolerance = profile.isBedrockPlayer() ? 0.090D : 0.060D;

                if (offset + 1.0E-7D < best.offset && offset <= launchTolerance) {
                    best = new PredictionResult(placedJump, "placedBlockJump", offset);
                }
            }
        }

        return best;
    }

    private double getPlacedBlockLandingPrediction(MovementData data, double normalPrediction) {
        ActionData actionData = profile.getActionData();

        if (actionData == null || data == null || data.getLocation() == null) {
            return Double.NaN;
        }

        int ticks = actionData.getBlockPlacePredictionTicks();

        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) {
            return Double.NaN;
        }

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN
                : actionData.getLastConfirmedUnderPlaceY() + 1.0D;

        double currentY = data.getLocation().getY();
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY()
                : currentY - data.getDeltaY();

        if (!Double.isFinite(topY) || !Double.isFinite(lastY) || !Double.isFinite(currentY)) {
            return Double.NaN;
        }

        if (!isHorizontallyOverPlacedBlock(data, actionData)) {
            return Double.NaN;
        }

        double predictedY = lastY + normalPrediction;
        double tolerance = getPlacedBlockCollisionTolerance();

        boolean movingTowardBlock = normalPrediction <= 0.08D && currentY <= lastY + 0.08D;
        boolean crossesTop = lastY >= topY - tolerance && predictedY <= topY + tolerance;
        boolean actualAtTop = Math.abs(currentY - topY) <= tolerance;

        if (!movingTowardBlock || !crossesTop || !actualAtTop) {
            return Double.NaN;
        }

        return topY - lastY;
    }

    private double getPlacedBlockJumpPrediction(MovementData data) {
        ActionData actionData = profile.getActionData();

        if (actionData == null || data == null || data.getLocation() == null) {
            return Double.NaN;
        }

        int ticks = Math.min(5 + profile.getConnectionData().getClientTickTrans(), actionData.getBlockPlacePredictionTicks());

        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) {
            return Double.NaN;
        }

        if (!isHorizontallyOverPlacedBlock(data, actionData)) {
            return Double.NaN;
        }

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN
                : actionData.getLastConfirmedUnderPlaceY() + 1.0D;

        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY()
                : data.getLocation().getY() - data.getDeltaY();

        if (!Double.isFinite(topY) || !Double.isFinite(lastY)) {
            return Double.NaN;
        }

        double tolerance = getPlacedBlockCollisionTolerance();

        boolean wasOnPlacedBlock = Math.abs(lastY - topY) <= tolerance
                && (data.isLastOnGround()
                || data.isLastServerGround()
                || data.isLastPositionYGround()
                || data.getCustomAirTicks() <= 1);

        if (!wasOnPlacedBlock
                || data.getDeltaY() <= 0.0D
                || data.getCustomAirTicks() > 2 + profile.getConnectionData().getClientTickTrans()) {
            return Double.NaN;
        }

        return getExpectedJumpMotion();
    }

    private boolean isHorizontallyOverPlacedBlock(MovementData data, ActionData actionData) {
        if (data == null || actionData == null || data.getLocation() == null) {
            return false;
        }

        double x = data.getLocation().getX();
        double z = data.getLocation().getZ();

        int blockX = actionData.getLastConfirmedUnderPlaceX();
        int blockZ = actionData.getLastConfirmedUnderPlaceZ();

        if (blockX == Integer.MIN_VALUE || blockZ == Integer.MIN_VALUE) {
            return false;
        }

        double blockCenterX = blockX + 0.5D;
        double blockCenterZ = blockZ + 0.5D;

        double dx = Math.abs(x - blockCenterX);
        double dz = Math.abs(z - blockCenterZ);

        // A 0.6-wide player overlaps a one-block support while its centre is at
        // most 0.8 from the block centre. Only add a small collision epsilon;
        // ping changes when the placement is known, not the physical overlap.
        double tolerance = 0.800001D + Math.min(0.050D, getPlacedBlockCollisionTolerance());

        if (profile.isBedrockPlayer()) {
            tolerance += 0.015D;
        }

        return dx <= tolerance && dz <= tolerance;
    }

    private double getPlacedBlockCollisionTolerance() {
        double tolerance = 0.03125D;

        try {
            tolerance += Math.min(0.0625D, profile.getConnectionData().getClientTickTrans() * 0.004D);
        } catch (Throwable ignored) {
        }

        try {
            tolerance += Math.min(0.0625D, (profile.getConnectionData().getTransPing() / 50.0D) * 0.003D);
        } catch (Throwable ignored) {
        }

        return Math.min(0.125D, tolerance);
    }

    private double predictGravityDY(Profile profile, MovementData data, double previousDY) {
        if (!Double.isFinite(previousDY)) {
            previousDY = 0.0D;
        }

        boolean slowFalling = profile.getPotionData().isHasSlowFalling() && previousDY <= 0.0D;

        double gravity = slowFalling ? 0.01D : GRAVITY;
        double prediction = (previousDY - gravity) * AIR_DRAG;

        if (slowFalling && prediction < -0.125D) {
            prediction = -0.125D;
        }

        if (prediction < TERMINAL_VELOCITY) {
            prediction = TERMINAL_VELOCITY;
        }

        if (Math.abs(prediction) < 0.003D) {
            prediction = 0.0D;
        }

        return Double.isFinite(prediction) ? prediction : 0.0D;
    }

    private double getFastFallAllowed(Profile profile, MovementData data, double dy, double lastDy, double expectedDY) {
        int pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        boolean slowFalling = profile.getPotionData().isHasSlowFalling() && lastDy <= 0.0D;
        boolean bedrock = profile.isBedrockPlayer();

        double allowed = slowFalling ? 0.014D : 0.016D;

        allowed += Math.min(0.025D, Math.abs(expectedDY) * 0.050D);
        allowed += Math.min(0.020D, Math.abs(lastDy) * 0.030D);
        allowed += Math.min(bedrock ? 0.025D : 0.012D, pingTicks * (bedrock ? 0.0025D : 0.0015D));
        allowed += gravityNoiseEma;

        if (data.getCustomAirTicks() <= 2) {
            allowed += bedrock ? 0.035D : 0.025D;
        }

        if (Math.abs(lastDy) <= 1.0E-6D && dy < 0.0D) {
            allowed += 0.060D;
        }

        if (data.getSinceCollideTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
            allowed += 0.025D;
        }

        if (bedrock) {
            allowed += 0.030D;
        } else if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
            allowed += 0.002D;
        }

        if (slowFalling) {
            return Math.min(bedrock ? 0.105D : 0.075D, allowed);
        }

        return Math.min(bedrock ? 0.145D : 0.105D, allowed);
    }

    private void resetGravityD(String reason) {
        if (lastGravityDSampleTick != Integer.MIN_VALUE
                || negGravStreak > 0.0D
                || cumulativeNegativeGravity > 0.0D
                || getBuffer() > 0.0D) {
            debugExemptD(reason);
        }

        negGravStreak = 0.0D;
        cumulativeNegativeGravity = 0.0D;
        gravityNoiseEma = 0.0D;
        lastGravityDSampleTick = Integer.MIN_VALUE;
        lastGravityDObservedDY = Double.NaN;
        resetBuffer();
        resetGravityDTrackingOnly();
    }

    public static double hypot(double... value) {
        double total = 0.0D;

        for (double val : value) {
            total += val * val;
        }

        return FastMath.sqrt(total);
    }

    public static float hypot(float... value) {
        float total = 0.0F;

        for (float val : value) {
            total += val * val;
        }

        return (float) FastMath.sqrt(total);
    }

    private void debugExemptD(String reason) {

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Gravity D: is Exempting (" + reason + ")");
        }
    }

    private boolean isVanillaMicroFallTransition(double dy, double lastDy, double expectedDY, double doubleGravityDY) {
        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.07840000152587834D;

        boolean expectedMicro = Math.abs(expectedDY - VANILLA_MICRO) <= 1.0E-9D;
        boolean currentVanillaNeg = Math.abs(dy - VANILLA_NEG) <= 1.0E-8D;
        boolean lastSmallPositive = lastDy > 0.0D && lastDy <= 0.085D;
        boolean doubleGravityNear = Math.abs(doubleGravityDY - VANILLA_NEG) <= 0.006D
                || Math.abs(doubleGravityDY + 0.07544406518949479D) <= 0.006D;

        return expectedMicro && currentVanillaNeg && lastSmallPositive && doubleGravityNear;
    }

    static class GravityMotion {

        final double displacementY;
        final double terminalDY;

        private GravityMotion(double displacementY, double terminalDY) {
            this.displacementY = displacementY;
            this.terminalDY = terminalDY;
        }
    }

    static class PredictionResult {

        double prediction;
        final String type;
        double offset;

        private PredictionResult(double prediction, String type, double offset) {
            this.prediction = prediction;
            this.type = type;
            this.offset = offset;
        }
    }
}
