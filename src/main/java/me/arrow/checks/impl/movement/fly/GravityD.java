
package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
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


@Experimental
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

            long profiler = Profiler.start();


            try {
                MovementData movementData = profile.getMovementData();

                if (movementData == null) {
                    return;
                }

                final int transTicks = getLagCompensationTicks();

                if (refreshMovementAttributes(movementData)) {
                    resetStrictGravityInvariant();
                    resetGravityD("movementAttributeChange");
                    return;
                }

                // Keep one high-confidence physics invariant independent from the broad
                // compatibility exemptions below. Nearby non-full blocks or a client
                // ground bit cannot make a clean ascent lose velocity instantly.
                if (handleStrictNegativeGravityInvariant(movementData, transTicks)) {
                    return;
                }

                if (movementData.isOnBoat()
                        || movementData.isNearBoat()
                        || movementData.isNearShulker()
                        || movementData.isNearShulkerBox()
                        || movementData.isNearLava()
                        || movementData.isNearWater()
                        || movementData.isNearBed()
                        || profile.getExempt().isVehicle()
                        || profile.shouldCancel()
                        || movementData.getSinceGlidingTicks() < 30 + (transTicks * 2)
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
                        || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (transTicks * 2))) {
                    resetGravityD("clientWorldMismatch");
                    return;
                }

                if (movementData.getSinceTeleportTicks() < 5) {
                    resetGravityD("recentTeleport");
                    return;
                }

                if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + transTicks)) {
                    resetGravityD("recentDamage");
                    return;
                }

                if (profile.getVehicleData().getSinceVehicleTicks() < 1 + transTicks) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity D: Exempt - vehicle");
                    resetGravityD("recentVehicle");
                    return;
                }

                int ghostLiquidWebTicks = Math.min(
                        profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                        profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
                );

                if (ghostLiquidWebTicks < 10 + transTicks) {
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
            } finally {
                Profiler.stop("Gravity D (Total)", profiler);
            }
        }
    }

    private static final double DEFAULT_GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.9800000190734863D;
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
    boolean lastExactGroundSupport;

    double strictLastDY = Double.NaN;
    int strictLastPositionTick = Integer.MIN_VALUE;
    boolean strictLastExactGroundSupport;
    boolean strictTrackingAir;
    double strictNegativeEvidence;

    double cachedGravity = DEFAULT_GRAVITY;
    double cachedJumpStrength = Double.NaN;
    int lastGravityAttributeTick = Integer.MIN_VALUE;
    boolean movementAttributesInitialized;

    /**
     * Independent launch/fall invariant used only for mathematically strong
     * negative-gravity evidence. It deliberately does not trust the packet ground
     * bit and is not disabled merely because a non-full block exists nearby.
     */
    private boolean handleStrictNegativeGravityInvariant(MovementData data, int transTicks) {
        if (isStrictGravityContextInvalid(data, transTicks)) {
            resetStrictGravityInvariant();
            return false;
        }

        boolean exactGroundSupport = hasExactGroundSupport(data);
        int movementTick = data.getTick();

        if (!data.isPacketMoving()) {
            if (exactGroundSupport) {
                primeStrictGravityGround(movementTick);
                return false;
            }

            if (!profile.isBedrockPlayer()
                    && strictTrackingAir
                    && strictLastPositionTick != Integer.MIN_VALUE
                    && Double.isFinite(strictLastDY)
                    && strictLastDY > 0.080D) {
                int sampleTicks = movementTick - strictLastPositionTick;

                if (sampleTicks > 0 && sampleTicks <= MAX_AGGREGATED_GRAVITY_TICKS) {
                    GravityMotion expected = simulateGravity(strictLastDY, sampleTicks);

                    if (expected.displacementY > 0.0305D) {
                        return registerStrictGravityEvidence(
                                data,
                                "missing-position",
                                0.0D,
                                strictLastDY,
                                expected.displacementY,
                                0.0305D,
                                sampleTicks
                        );
                    }
                }
            }

            return false;
        }

        double currentDY = data.getDeltaY();

        if (!Double.isFinite(currentDY)) {
            resetStrictGravityInvariant();
            return false;
        }

        // A collision probe can remain supported for the first upward packet because
        // its vertical tolerance overlaps the block top. Only consume support as a
        // landing/step when the packet is not moving upward; otherwise the +0.42
        // launch anchor is lost before a YPort client forces its downward motion.
        if (exactGroundSupport && currentDY <= 1.0E-7D) {
            primeStrictGravityGround(movementTick);
            return false;
        }

        if (strictLastPositionTick == Integer.MIN_VALUE || !Double.isFinite(strictLastDY)) {
            strictLastPositionTick = movementTick;
            strictLastDY = currentDY;
            strictLastExactGroundSupport = false;
            strictTrackingAir = true;
            return false;
        }

        int sampleTicks = movementTick - strictLastPositionTick;

        if (sampleTicks <= 0) {
            return false;
        }

        boolean launch = sampleTicks == 1
                && strictLastExactGroundSupport
                && Math.abs(strictLastDY) < 0.035D
                && currentDY > 0.015D;

        if (launch) {
            double expectedJump = getExpectedJumpMotion();
            double allowed = profile.isBedrockPlayer() ? 0.120D : 0.045D;
            double deficit = expectedJump - currentDY;

            strictLastPositionTick = movementTick;
            strictLastDY = currentDY;
            strictLastExactGroundSupport = false;
            strictTrackingAir = true;

            if (deficit > allowed) {
                return registerStrictGravityEvidence(
                        data,
                        "reduced-launch",
                        currentDY,
                        0.0D,
                        deficit,
                        allowed,
                        sampleTicks
                );
            }

            strictNegativeEvidence = Math.max(0.0D, strictNegativeEvidence - 0.35D);
            return false;
        }

        if (sampleTicks > MAX_AGGREGATED_GRAVITY_TICKS || !strictTrackingAir) {
            strictLastPositionTick = movementTick;
            strictLastDY = currentDY;
            strictLastExactGroundSupport = false;
            strictTrackingAir = true;
            strictNegativeEvidence = Math.max(0.0D, strictNegativeEvidence - 0.50D);
            return false;
        }

        double previousStrictDY = strictLastDY;
        GravityMotion expected = simulateGravity(previousStrictDY, sampleTicks);
        double residual = expected.displacementY - currentDY;
        boolean bedrock = profile.isBedrockPlayer();
        double allowed = bedrock ? 0.155D : 0.060D;
        boolean ascentCut = strictLastDY > (bedrock ? 0.100D : 0.055D)
                && expected.terminalDY > (bedrock ? 0.035D : 0.015D)
                && residual > allowed;
        boolean excessiveDescent = expected.terminalDY <= 0.015D
                && currentDY < expected.displacementY - (allowed * 1.65D)
                && residual > allowed * 1.65D;

        strictLastPositionTick = movementTick;
        strictLastDY = estimateTerminalDY(
                previousStrictDY,
                currentDY,
                sampleTicks,
                false,
                expected
        );
        strictLastExactGroundSupport = false;
        strictTrackingAir = true;

        if (ascentCut || excessiveDescent) {
            return registerStrictGravityEvidence(
                    data,
                    ascentCut ? "ascent-cut" : "excessive-descent",
                    currentDY,
                    previousStrictDY,
                    residual,
                    allowed,
                    sampleTicks
            );
        }

        strictNegativeEvidence = Math.max(0.0D, strictNegativeEvidence - 0.40D);
        return false;
    }

    private boolean registerStrictGravityEvidence(MovementData data,
                                                  String type,
                                                  double currentDY,
                                                  double lastDY,
                                                  double residual,
                                                  double allowed,
                                                  int sampleTicks) {
        double ratio = residual / Math.max(1.0E-6D, allowed);
        strictNegativeEvidence += Math.max(0.75D, Math.min(3.50D, ratio));
        boolean bedrock = profile.isBedrockPlayer();
        boolean mathematicallyStrong = residual > (bedrock ? 0.300D : 0.145D);
        double required = bedrock ? 3.75D : 2.25D;
        String information = "predictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + type
                + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + currentDY
                + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDY
                + "\nresidual " + MsgType.MAIN_THEME_COLOR.getMessage() + residual
                + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + allowed
                + "\nsampleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + sampleTicks
                + "\nnearStepMaterial " + data.isNearStepMaterial()
                + "\nexactGroundSupport " + MsgType.MAIN_THEME_COLOR.getMessage() + hasExactGroundSupport(data)
                + "\nevidence " + MsgType.MAIN_THEME_COLOR.getMessage() + strictNegativeEvidence;

        verbose(getClass().getSimpleName(), strictNegativeEvidence, required, information);

        if (data.getSincePredictUpwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictUpwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            resetGravityD("predictUp/Down");
            return false;
        }

        if (mathematicallyStrong || strictNegativeEvidence > required) {
            fail("Negative Gravity Modification", information);
            strictNegativeEvidence = Math.max(required, strictNegativeEvidence * 0.50D);
            return true;
        }

        return false;
    }

    private boolean isStrictGravityContextInvalid(MovementData data, int transTicks) {
        ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

        if (data == null
                || profile.shouldCancel()
                || profile.isExempt().vehicle()
                || profile.getExempt().isVehicle()
                || profile.isBouncingOnSlime()
                || profile.getGeysersTracker().isBeingPushed()
                || data.getSinceTeleportTicks() < 5 + transTicks
                || data.getSinceGlidingTicks() < 20 + transTicks
                || data.getSinceRiptidingTicks() < 10 + transTicks
                || data.isUnderblock()
                || data.isNearWater()
                || data.isNearLava()
                || data.isNearWebs()
                || data.isNearClimbable()
                || data.isInsideLiquid()
                || data.isInsideWater()
                || data.isOnTopOfWater()
                || data.isBottomOfWater()
                || data.isOnSlime()
                || data.isOnHoney()
                || data.getSincePowderSnowTicks() < 15 + transTicks
                || data.getSinceOnGhostBlock() < 4 + transTicks
                || world.onGhostBlock
                || world.insideGhostBlock
                || world.underGhostBlock
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().isCancelledBlockPlacementExempt(4 + transTicks)
                || !CollisionUtils.isChunkLoaded(data.getLocation())
                || hasRecentGravityVelocity(transTicks)
                || hasRecentGravitySupportChange(4 + transTicks)) {
            return true;
        }

        if (profile.getPotionData().isHasLevitation()
                || profile.getPotionData().getLevitationTicks() > 0
                || profile.getPotionData().isHasSlowFalling()
                || profile.getPotionData().getSlowFallingTicks() > 0) {
            return true;
        }

        return profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 5 + transTicks);
    }

    private void primeStrictGravityGround(int movementTick) {
        strictLastPositionTick = movementTick;
        strictLastDY = 0.0D;
        strictLastExactGroundSupport = true;
        strictTrackingAir = false;
        strictNegativeEvidence = Math.max(0.0D, strictNegativeEvidence - 0.75D);
    }

    private void resetStrictGravityInvariant() {
        strictLastDY = Double.NaN;
        strictLastPositionTick = Integer.MIN_VALUE;
        strictLastExactGroundSupport = false;
        strictTrackingAir = false;
        strictNegativeEvidence = 0.0D;
    }

    private void GravityPredictionD(MovementData data) {
        // Rotation/ground-only flying packets do not represent another physics step.
        // MovementData intentionally produces deltaY=0 for them, so consuming them here
        // would both create false low-hop samples and destroy the fall trajectory.
        if (data == null) {
            return;
        }

        if (data.getSincePredictUpwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictUpwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            resetGravityD("predictUp/Down");
            return;
        }

        final boolean exactGroundSupport = hasExactGroundSupport(data);
        final int transTicks = getLagCompensationTicks();

        if (refreshMovementAttributes(data)) {
            resetGravityD("movementAttributeChange");
            return;
        }

        if (isGravityDExempt(data, transTicks)) {
            return;
        }

        if (!data.isPacketMoving()) {
            if (!exactGroundSupport && handleAirborneNoPositionGravity(data, transTicks)) {
                return;
            }

            // A long stationary period must not turn the next jump into an
            // unmodelled packet gap. Ground-only packets are safe synchronization
            // points because their vertical displacement is known to be zero.
            if (exactGroundSupport && (isActualGround(data) || isTrustedClientGround(data))) {
                lastGravityDSampleTick = data.getTick();
                lastGravityDObservedDY = 0.0D;
                resetGravityDTrackingOnly();

                if (data.getClientGroundTicks() > 3 || data.getServerGroundTicks() > 3) {
                    cumulativeNegativeGravity = 0.0D;
                    negGravStreak = Math.max(0.0D, negGravStreak - 0.75D);
                    decreaseBufferBy(0.50D);
                }
            }

            lastExactGroundSupport = exactGroundSupport;

            return;
        }

        final double displacementY = data.getDeltaY();
        final double fallDist = data.getFallDistance();

        if (!Double.isFinite(displacementY)) {
            resetGravityD("invalidMotion");
            return;
        }

        final int movementTick = data.getTick();
        final boolean previousExactGroundSupport = lastExactGroundSupport;
        lastExactGroundSupport = exactGroundSupport;
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
                && (previousExactGroundSupport
                || data.isLastOnGround()
                || data.getClientAirTicks() <= 2);

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
        boolean abruptMotionCut = sampleTicks == 1 && isAbruptNegativeGravityTransition(
                data, currentDY, lastObservedDY, selectedExpectedDY,
                selectedAllowedPerTick, launchSample, previousExactGroundSupport,
                transTicks
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

        if (directFastFall || lowHopMotion || impossibleFastLanding || abruptMotionCut) {
            int evidenceAirTicks = impossibleFastLanding ? Math.max(airTicks, predictedTicks) : airTicks;
            boolean fastFallEvidence = directFastFall || impossibleFastLanding || abruptMotionCut;

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
                    lowHopMotion || abruptMotionCut,
                    false,
                    false,
                    false,
                    actualGround,
                    trustedClientGround,
                    transTicks,
                    fastFallEvidence ? 0.75D : 1.25D,
                    impossibleFastLanding ? 3.25D : abruptMotionCut ? 3.15D : directFastFall ? 3.00D : 2.25D)) {
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
                && currentDY < getTerminalVelocity(data) - selectedAllowedPerTick;
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
                        + (directFastFall || impossibleFastLanding || lowHopMotion || abruptMotionCut)
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

        double noiseResidual = Math.abs(selectedExpectedDisplacement - displacementY) / sampleTicks;
        double learnableNoise = profile.isBedrockPlayer() ? 0.008D : 0.0015D;
        updateGravityDNoise(
                noiseResidual,
                !genericFastFall
                        && !actualGround
                        && !trustedClientGround
                        && noiseResidual <= learnableNoise
                        && selectedExcess <= learnableNoise
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
        if (profile.getMovementData().getSinceTeleportTicks() < 5 + (transTicks * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("GravityD : Exempt - teleporting");
            resetGravityD("teleporting");
            return true;
        }
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

        if (data.getSincePredictUpwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictUpwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            resetGravityD("predictUp/Down");
            return true;
        }

        if (hasRecentGravityVelocity(transTicks)) {
            resetGravityD("recentVelocity");
            return true;
        }

        // Bukkit potion state is not transaction-confirmed to the client. Ignore the
        // active effect and its short decay window so application/removal cannot leave
        // a stale normal-gravity trajectory or false high-ping players.
        if (profile.getPotionData().isHasLevitation()
                || profile.getPotionData().getLevitationTicks() > 0
                || profile.getPotionData().isHasSlowFalling()
                || profile.getPotionData().getSlowFallingTicks() > 0) {
            resetGravityD("gravityPotion");
            return true;
        }

        if (data.getSincePowderSnowTicks() < 15 + (transTicks * 2)) {
            resetGravityD("powderSnow");
            return true;
        }

        return false;
    }

    private boolean isActualGround(MovementData data) {
        if (!hasExactGroundSupport(data) || data.getDeltaY() > 1.0E-7D) {
            return false;
        }

        return data.isOnGround()
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround();
    }

    private boolean isTrustedClientGround(MovementData data) {
        return data.isOnGround()
                && hasExactGroundSupport(data)
                && Math.abs(data.getDeltaY()) <= 1.0E-7D
                && data.getServerAirTicks() <= 1
                && data.getCustomAirTicks() <= 1;
    }

    private boolean hasExactGroundSupport(MovementData data) {
        try {
            return data != null
                    && data.getNearbyBlocksResult() != null
                    && data.getNearbyBlocksResult().hasExactGroundSupport();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getAirTicks(MovementData data) {
        return Math.max(data.getCustomAirTicks(), Math.max(data.getClientAirTicks(), data.getServerAirTicks()));
    }

    private int getLagCompensationTicks() {
        try {
            int ticks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            return Math.min(profile.isBedrockPlayer() ? 8 : 6, ticks);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * A Java client may omit its position while the change is below the packet
     * threshold. It cannot omit a position while a tracked jump still predicts a
     * sizeable upward displacement. Treat that missing displacement as a gravity
     * sample without rebasing the positional trajectory.
     */
    private boolean handleAirborneNoPositionGravity(MovementData data, int transTicks) {
        if (profile.isBedrockPlayer()
                || !trackingFall
                || !Double.isFinite(lastGravityDObservedDY)
                || lastGravityDObservedDY <= 0.080D
                || lastGravityDSampleTick == Integer.MIN_VALUE
                || hasRecentGravitySupportChange(4 + transTicks)) {
            return false;
        }

        int sampleTicks = data.getTick() - lastGravityDSampleTick;

        if (sampleTicks <= 0 || sampleTicks > MAX_AGGREGATED_GRAVITY_TICKS) {
            return false;
        }

        GravityMotion expectedMotion = simulateGravity(lastGravityDObservedDY, sampleTicks);
        double expectedDisplacement = expectedMotion.displacementY;

        // Java reports position once displacement from its last report exceeds
        // roughly 0.03. The epsilon covers float/packet quantization at the edge.
        final double packetThreshold = 0.0305D;

        if (expectedDisplacement <= packetThreshold) {
            return false;
        }

        double severity = expectedDisplacement / packetThreshold;
        double doubleGravityDY = predictGravityDY(profile, data, expectedMotion.terminalDY);
        int airTicks = getAirTicks(data);
        String information = ChatColor.RED + "Verbose (missing position)"
                + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                + "\nsampleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + sampleTicks
                + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastGravityDObservedDY
                + "\nexpectedDisplacement " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDisplacement
                + "\npacketThreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + packetThreshold
                + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak;

        verbose(getClass().getSimpleName(), expectedDisplacement, packetThreshold, information);

        boolean strong = expectedDisplacement > 0.080D;

        return handleGravityDFlag(
                data,
                true,
                data.getFallDistance(),
                airTicks,
                expectedMotion.terminalDY,
                expectedMotion.terminalDY,
                "airborne-no-position",
                doubleGravityDY,
                0.0D,
                lastGravityDObservedDY,
                expectedDisplacement,
                expectedDisplacement - packetThreshold,
                packetThreshold,
                severity,
                lastGravityDObservedDY - expectedMotion.terminalDY,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                transTicks,
                strong ? 0.75D : 1.75D,
                strong ? 3.15D : 0.90D
        );
    }

    private boolean hasRecentGravityVelocity(int transTicks) {
        try {
            int pendingWindow = 3 + transTicks;
            int confirmedWindow = 10 + transTicks;

            double pendingY = profile.getVelocityData().getPendingEntityVelocity() == null
                    ? 0.0D
                    : profile.getVelocityData().getPendingEntityVelocity().getY();
            double entityY = profile.getVelocityData().getVelocityV();
            double explosionY = profile.getVelocityData().getExplosionKnockbackPacket() == null
                    ? 0.0D
                    : profile.getVelocityData().getExplosionKnockbackPacket().getY();

            boolean pendingEntity = profile.getVelocityData().getEntityVelocityPacketTicks() <= pendingWindow
                    && Math.abs(pendingY) > 1.0E-5D;
            boolean confirmedEntity = profile.getVelocityData().getEntityVelocityTicks() <= confirmedWindow
                    && Math.abs(entityY) > 1.0E-5D;
            boolean pendingExplosion = profile.getVelocityData().getExplosionVelocityPacketTicks() <= pendingWindow;
            boolean confirmedExplosion = profile.getVelocityData().getExplosionVelocityTicks() <= confirmedWindow
                    && (Math.abs(explosionY) > 1.0E-5D
                    || Math.abs(profile.getVelocityData().getTotalVerticalVelocity()) > 1.0E-5D);

            return pendingEntity || confirmedEntity || pendingExplosion || confirmedExplosion;
        } catch (Throwable ignored) {
            return false;
        }
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
        boolean terminalBreak = dy < getTerminalVelocity(data) - allowed;

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

    /**
     * A downward force is observable before the player is actually descending:
     * vanilla must preserve most of a positive jump velocity on the next tick.
     * Keeping this invariant independent of the reported ground bit closes y-port
     * and low-jump variants which zero or reverse the ascent immediately.
     */
    private boolean isAbruptNegativeGravityTransition(MovementData data,
                                                       double dy,
                                                       double lastDy,
                                                       double expectedDY,
                                                       double allowed,
                                                       boolean launchSample,
                                                       boolean previousExactGroundSupport,
                                                       int transTicks) {
        if (data == null
                || data.isUnderblock()
                || data.getMovingUnderblockTicks() > 0
                || data.isNearStepMaterial()
                || hasRecentGravitySupportChange(4 + transTicks)) {
            return false;
        }

        final boolean bedrock = profile.isBedrockPlayer();
        final double residual = expectedDY - dy;
        final double transitionTolerance = Math.max(
                bedrock ? 0.135D : 0.070D,
                allowed * (bedrock ? 1.35D : 1.10D)
        );

        boolean ascentWasCut = lastDy > (bedrock ? 0.075D : 0.045D)
                && expectedDY > (bedrock ? 0.035D : 0.020D)
                && residual > transitionTolerance;

        // Java jump velocity is fixed. Bedrock receives a wider launch envelope
        // because Geyser can expose its variable/quantized jump displacement.
        double expectedJump = getExpectedJumpMotion();
        double launchTolerance = bedrock ? 0.100D : 0.028D;
        boolean reducedLaunch = launchSample
                && previousExactGroundSupport
                && dy > 0.015D
                && expectedJump - dy > launchTolerance;

        return ascentWasCut || reducedLaunch;
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
        negGravStreak += added;
        double bufferAdded = Math.max(0.25D, Math.min(1.0D, added * 0.30D));

        if (isGravityDExempt(data, getLagCompensationTicks())) return false;

        double expected = 0.59260459763505993D;
        double predictedWrong = 0.502352515459515D;

        if (fallDist == 0 && airTicks == 0 && dy == 0 && ((lastDy - expected) < 1E-6) && !data.isCustomInAir() && data.isOnGround() && ((expectedDY - predictedWrong) < 1E-6)) return false;


        if (data.getSincePredictUpwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictUpwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                || data.getSincePredictDownwardsTicksWithoutMaterial() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            resetGravityD("predictUp/Down");
            return false;
        }

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
        double seed = Math.max(-64.0D, Math.min(maximumUpward, displacementY));

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

        return Math.max(-64.0D, Math.min(maximumUpward, terminal));
    }

    private double getExpectedJumpMotion() {
        double motion;

        if (!profile.isBedrockPlayer() && Double.isFinite(cachedJumpStrength)) {
            motion = cachedJumpStrength;

            if (profile.getPotionData().isHasJump()) {
                motion += Math.max(0, profile.getPotionData().getJumpAmplifier()) * 0.10D;
            }
        } else {
            motion = MoveUtils.getJumpMotion(profile);
        }

        if (!Double.isFinite(motion) || motion < 0.30D || motion > 1.60D) {
            motion = 0.42D;

            if (profile.getPotionData().isHasJump()) {
                motion += Math.max(0, profile.getPotionData().getJumpAmplifier()) * 0.10D;
            }
        }

        return motion;
    }

    /**
     * Newer Java versions expose gravity and jump strength as attributes. Read
     * them reflectively so the same jar still loads on legacy servers. A genuine
     * server-side attribute change rebases the trajectory instead of looking like
     * a client gravity modification.
     */
    private boolean refreshMovementAttributes(MovementData data) {
        if (profile.isBedrockPlayer() || data == null) {
            return false;
        }

        int tick = data.getTick();

        if (lastGravityAttributeTick != Integer.MIN_VALUE
                && tick - lastGravityAttributeTick >= 0
                && tick - lastGravityAttributeTick < 10) {
            return false;
        }

        lastGravityAttributeTick = tick;

        double gravity = readBukkitAttribute("GRAVITY", "GENERIC_GRAVITY");
        double jumpStrength = readBukkitAttribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH");

        if (!Double.isFinite(gravity) || gravity < 0.0D || gravity > 4.0D) {
            gravity = DEFAULT_GRAVITY;
        }

        if (!Double.isFinite(jumpStrength) || jumpStrength < 0.0D || jumpStrength > 4.0D) {
            jumpStrength = Double.NaN;
        }

        boolean changed = movementAttributesInitialized
                && (Math.abs(cachedGravity - gravity) > 1.0E-7D
                || !sameFiniteValue(cachedJumpStrength, jumpStrength));

        cachedGravity = gravity;
        cachedJumpStrength = jumpStrength;
        movementAttributesInitialized = true;
        return changed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private double readBukkitAttribute(String... names) {
        try {
            Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
            Object attribute = null;

            for (String name : names) {
                try {
                    attribute = attributeClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    if (attributeClass.isEnum()) {
                        try {
                            attribute = Enum.valueOf((Class<? extends Enum>) attributeClass, name);
                        } catch (Throwable ignoredAgain) {
                        }
                    }
                }

                if (attribute != null) {
                    break;
                }
            }

            if (attribute == null || profile.getPlayer() == null) {
                return Double.NaN;
            }

            Object instance = profile.getPlayer().getClass()
                    .getMethod("getAttribute", attributeClass)
                    .invoke(profile.getPlayer(), attribute);

            if (instance == null) {
                return Double.NaN;
            }

            Object value = instance.getClass().getMethod("getValue").invoke(instance);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private boolean sameFiniteValue(double first, double second) {
        if (Double.isFinite(first) != Double.isFinite(second)) {
            return false;
        }

        return !Double.isFinite(first) || Math.abs(first - second) <= 1.0E-7D;
    }

    private double getGravityValue(MovementData data) {
        refreshMovementAttributes(data);
        return Double.isFinite(cachedGravity) ? cachedGravity : DEFAULT_GRAVITY;
    }

    private double getTerminalVelocity(MovementData data) {
        double gravity = getGravityValue(data);

        if (gravity <= 1.0E-9D) {
            return -64.0D;
        }

        return -(gravity * AIR_DRAG) / (1.0D - AIR_DRAG);
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

        int ticks = Math.min(5 + getLagCompensationTicks(), actionData.getBlockPlacePredictionTicks());

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
                || data.getCustomAirTicks() > 2 + getLagCompensationTicks()) {
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
            tolerance += Math.min(0.0625D, getLagCompensationTicks() * 0.004D);
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

        // Slow Falling and Levitation are deliberately rebased/exempted before
        // prediction because Bukkit's current effect state is not client-confirmed.
        double gravity = getGravityValue(data);
        double prediction = (previousDY - gravity) * AIR_DRAG;

        if (Math.abs(prediction) < 0.003D) {
            prediction = 0.0D;
        }

        return Double.isFinite(prediction) ? prediction : 0.0D;
    }

    private double getFastFallAllowed(Profile profile, MovementData data, double dy, double lastDy, double expectedDY) {
        boolean bedrock = profile.isBedrockPlayer();

        double allowed = bedrock ? 0.040D : 0.008D;

        allowed += Math.min(bedrock ? 0.022D : 0.010D, Math.abs(expectedDY) * (bedrock ? 0.045D : 0.025D));
        allowed += Math.min(bedrock ? 0.018D : 0.008D, Math.abs(lastDy) * (bedrock ? 0.030D : 0.015D));
        allowed += gravityNoiseEma;

        if (data.getCustomAirTicks() <= 2) {
            allowed += bedrock ? 0.025D : 0.012D;
        }

        if (Math.abs(lastDy) <= 1.0E-6D && dy < 0.0D) {
            allowed += bedrock ? 0.055D : 0.035D;
        }

        if (data.getSinceCollideTicks() < 5 + getLagCompensationTicks()) {
            allowed += 0.025D;
        }

        if (bedrock) {
            allowed += 0.015D;
        } else if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
            allowed += 0.002D;
        }

        return Math.min(bedrock ? 0.120D : 0.060D, allowed);
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
        lastExactGroundSupport = false;
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
