package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.customutils.OtherUtility;

import static me.arrow.checks.impl.movement.fly.GravityUtility.DEFAULT_GRAVITY;
import static me.arrow.checks.impl.movement.fly.GravityUtility.GravityMotion;
import static me.arrow.checks.impl.movement.fly.GravityUtility.PredictionResult;

@Experimental
public class GravityD extends Check {

    private static final int MAX_AGGREGATED_GRAVITY_TICKS = 4;

    // Trajectory & airborne tracking
    private boolean trackingFall;
    private double predictedDY;
    private double predictedFallDist;
    private int predictedTicks;

    // Observed motion samples
    private double lastObservedDY = Double.NaN;
    private int lastSampleTick = Integer.MIN_VALUE;
    private boolean lastExactGroundSupport;

    // Evidence & streaks
    private double streak;
    private double cumulativeExcess;
    private double gravityNoiseEma;

    // Attributes cache
    private double cachedGravity = DEFAULT_GRAVITY;
    private double cachedJumpStrength = Double.NaN;
    private int lastAttributeTick = Integer.MIN_VALUE;
    private boolean attributesInitialized;

    public GravityD(Profile profile) {
        super(profile, CheckType.GRAVITY, "D", "Checks for modified downward gravity during jumps and falls.");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        long profiler = Profiler.start();
        try {
            MovementData data = profile.getMovementData();
            if (data == null) return;

            final int transTicks = GravityUtility.getLagCompensationTicks(profile);
            if (isExempt(data, transTicks)) return;

            if (refreshMovementAttributes(data)) {
                resetGravityD("movementAttributeChange");
                return;
            }

            processGravity(data, transTicks);
        } finally {
            Profiler.stop("Gravity D (Total)", profiler);
        }
    }

    // =========================================================================
    //  Exemption Handling
    // =========================================================================

    private boolean isExempt(MovementData data, int transTicks) {
        if (GravityUtility.isCommonGravityExempt(profile, data, "GravityD", true)) {
            resetGravityD("commonExempt");
            return true;
        }
        if (data.isNearWall() || data.isNearPane()) {
            resetGravityD("wall");
            return true;
        }
        if (data.isNearStepMaterial() || GravityUtility.hasRecentGravitySupportChange(profile, 5 + (transTicks * 2))) {
            resetGravityD("supportChange");
            return true;
        }
        return false;
    }

    // =========================================================================
    //  Unified Gravity & Fast-Fall Processing
    // =========================================================================

    private void processGravity(MovementData data, int transTicks) {
        final boolean grounded = isGrounded(data);
        final int tick = data.getTick();

        // 1. Non-moving packet (stationary player or airborne without position packet)
        if (!data.isPacketMoving()) {
            if (grounded) {
                lastObservedDY = 0.0D;
                lastSampleTick = tick;
                resetTracking();
                if (data.getClientGroundTicks() > 3 || data.getServerGroundTicks() > 3) {
                    cumulativeExcess = 0.0D;
                    streak = Math.max(0.0D, streak - 0.75D);
                    decreaseBufferBy(0.50D);
                }
            } else if (!profile.isBedrockPlayer() && trackingFall && Double.isFinite(lastObservedDY)
                    && lastObservedDY > 0.080D && lastSampleTick != Integer.MIN_VALUE) {
                int sampleTicks = tick - lastSampleTick;
                if (sampleTicks > 0 && sampleTicks <= MAX_AGGREGATED_GRAVITY_TICKS) {
                    double gravity = cachedGravity;
                    GravityMotion expectedMotion = GravityUtility.simulateGravity(lastObservedDY, gravity, sampleTicks);
                    if (expectedMotion.displacementY > 0.0305D) {
                        double doubleGravityDY = GravityUtility.predictGravityDY(expectedMotion.terminalDY, gravity);
                        flag(data, "airborne-no-position", 0.0D, lastObservedDY, expectedMotion.terminalDY,
                                expectedMotion.terminalDY, expectedMotion.displacementY, 0.0305D,
                                doubleGravityDY, 0.75D, 3.15D, getAirTicks(data), data.getFallDistance());
                    }
                }
            }
            lastExactGroundSupport = hasExactGroundSupport(data);
            return;
        }

        // 2. Moving packet
        final double displacementY = data.getDeltaY();
        final double fallDist = data.getFallDistance();
        if (!Double.isFinite(displacementY)) {
            resetGravityD("invalidMotion");
            return;
        }

        final boolean prevExactSupport = lastExactGroundSupport;
        lastExactGroundSupport = hasExactGroundSupport(data);
        final int airTicks = getAirTicks(data);

        // Ground landing without tracking mid-fall
        if (grounded && !trackingFall) {
            lastSampleTick = tick;
            lastObservedDY = 0.0D;
            decayEvidence(0.45D, 0.20D);
            return;
        }

        if (lastSampleTick == Integer.MIN_VALUE || !Double.isFinite(lastObservedDY)) {
            lastSampleTick = tick;
            synchronize(data, displacementY, grounded);
            return;
        }

        final int sampleTicks = tick - lastSampleTick;
        if (sampleTicks <= 0) return;
        lastSampleTick = tick;

        if (sampleTicks > MAX_AGGREGATED_GRAVITY_TICKS) {
            synchronize(data, displacementY, grounded);
            decayEvidence(0.75D, 0.35D);
            return;
        }

        final double lastDY = lastObservedDY;
        final double gravity = cachedGravity;
        final double jumpMotion = getExpectedJumpMotion();

        final boolean launch = sampleTicks == 1 && displacementY > 0.0D && !grounded
                && Math.abs(lastDY) < 0.035D && (prevExactSupport || data.isLastOnGround() || data.getClientAirTicks() <= 2);

        PredictionResult expectedResult;
        GravityMotion localMotion;

        if (launch) {
            expectedResult = GravityUtility.selectGravityPrediction(profile, data, profile.getActionData(), jumpMotion, true, jumpMotion, transTicks);
            localMotion = new GravityMotion(expectedResult.prediction, expectedResult.prediction);
        } else {
            PredictionResult recovered = sampleTicks == 1
                    ? GravityUtility.getRecoveredGravityPhasePrediction(profile, data, lastDY, displacementY, fallDist, jumpMotion, gravity)
                    : null;
            if (recovered != null) {
                expectedResult = recovered;
                localMotion = new GravityMotion(recovered.prediction, recovered.prediction);
            } else {
                GravityMotion normal = GravityUtility.simulateGravity(lastDY, gravity, sampleTicks);
                expectedResult = sampleTicks == 1
                        ? GravityUtility.selectGravityPrediction(profile, data, profile.getActionData(), normal.terminalDY, false, jumpMotion, transTicks)
                        : new PredictionResult(normal.terminalDY, "normal-aggregate", 0.0D);
                localMotion = sampleTicks == 1 ? new GravityMotion(expectedResult.prediction, expectedResult.prediction) : normal;
            }
        }

        final boolean hasTrajectory = trackingFall && Double.isFinite(predictedDY) && !expectedResult.type.startsWith("recovered-");
        final GravityMotion trajectoryMotion = (hasTrajectory && !launch)
                ? GravityUtility.simulateGravity(predictedDY, gravity, sampleTicks)
                : localMotion;

        final boolean useLocal = localMotion.displacementY <= trajectoryMotion.displacementY;
        final double expectedDisplacement = useLocal ? localMotion.displacementY : trajectoryMotion.displacementY;
        final double expectedDY = useLocal ? localMotion.terminalDY : trajectoryMotion.terminalDY;
        final String expectedType = useLocal ? expectedResult.type : "trajectory";

        final double currentDY = GravityUtility.estimateTerminalDY(displacementY, sampleTicks, launch, localMotion, jumpMotion);
        final double normalExpectedDY = launch ? jumpMotion : GravityUtility.simulateGravity(lastDY, gravity, sampleTicks).terminalDY;

        final double allowed = getAllowed(data, currentDY, lastDY, expectedDY, sampleTicks);
        final double excess = expectedDisplacement - displacementY; // positive = falling faster than expected
        final double doubleGravityDY = sampleTicks == 1 ? GravityUtility.predictGravityDY(expectedDY, gravity) : Double.NaN;

        // Vanilla micro-fall transition
        if (!profile.isBedrockPlayer() && sampleTicks == 1
                && GravityUtility.isVanillaMicroFallTransition(currentDY, lastDY, expectedDY, doubleGravityDY)) {
            updateTracking(currentDY, displacementY, sampleTicks);
            decayEvidence(0.45D, 0.20D);
            return;
        }

        // =====================================================================
        //  Unified Physical Fast-Fall Detection Model
        // =====================================================================
        // 1. Direct Anomaly Evaluation (immediate blatant physical impossibilities)
        final double perTickExcess = expectedDY - currentDY;
        final double terminalVel = GravityUtility.getTerminalVelocity(gravity);

        boolean terminalBreak = sampleTicks == 1 && currentDY < terminalVel - allowed;
        boolean hardDrop = sampleTicks == 1 && airTicks >= 2 && currentDY < -0.520D && perTickExcess > Math.max(0.105D, allowed * 2.0D);
        boolean midAirDrop = sampleTicks == 1 && airTicks >= 2 && (lastDY <= 0.040D || expectedDY <= 0.0D) && currentDY < -0.300D && perTickExcess > Math.max(0.095D, allowed * 1.75D);
        boolean ascentCut = sampleTicks == 1 && airTicks <= 5 + transTicks && expectedDY > 0.040D && perTickExcess > Math.max(0.090D, allowed * 1.40D);
        boolean launchCut = sampleTicks == 1 && launch && prevExactSupport && currentDY > 0.015D && (jumpMotion - currentDY) > (profile.isBedrockPlayer() ? 0.100D : 0.028D);
        boolean impossibleLanding = sampleTicks == 1 && grounded && trackingFall && predictedTicks >= 4 && predictedFallDist > 0.20D
                && !data.isLastServerGround() && !data.isLastPositionYGround() && currentDY < -0.300D && perTickExcess > Math.max(0.165D, allowed * 2.75D);

        boolean directAnomaly = terminalBreak || hardDrop || midAirDrop || ascentCut || launchCut || impossibleLanding;

        if (directAnomaly) {
            double required = (terminalBreak || impossibleLanding || hardDrop) ? 0.75D : 1.25D;
            double added = terminalBreak ? 3.50D : impossibleLanding ? 3.25D : hardDrop ? 3.15D : 2.50D;
            if (flag(data, "direct-" + expectedType, currentDY, lastDY, expectedDY, normalExpectedDY,
                    perTickExcess, allowed, doubleGravityDY, required, added, airTicks, fallDist)) {
                return;
            }
        }

        // 2. Trajectory, Cumulative & Multi-tick Fast Fall Evaluation
        final double trajExcess = trajectoryMotion.displacementY - displacementY;
        final double trajAllowed = getTrajectoryAllowance(sampleTicks);
        final boolean trajEvidence = hasTrajectory && predictedTicks >= 2 && trajExcess > trajAllowed;

        updateCumulativeExcess(localMotion.displacementY - displacementY, allowed, grounded);
        final double cumulativeReq = (profile.isBedrockPlayer() ? 0.105D : 0.040D) + (gravityNoiseEma * 2.0D);
        final boolean cumulativeEvidence = airTicks >= 3 && cumulativeExcess > cumulativeReq;

        boolean doubleGravityMatch = sampleTicks == 1 && excess > allowed && Double.isFinite(doubleGravityDY)
                && doubleGravityDY < expectedDY && Math.abs(currentDY - doubleGravityDY) < Math.abs(currentDY - expectedDY);

        boolean motionExcess = excess > allowed || trajEvidence || cumulativeEvidence;

        if (motionExcess) {
            double severity = Math.max(excess / Math.max(1.0E-4D, allowed), trajExcess / Math.max(1.0E-4D, trajAllowed));
            boolean hardFastFall = terminalBreak || trajEvidence || cumulativeEvidence || (doubleGravityMatch && airTicks >= 3) || (excess > 0.095D && severity > 3.0D);
            double required = hardFastFall ? 1.75D : 3.50D;
            double added = 0.75D + (doubleGravityMatch ? 0.85D : 0.0D) + (trajEvidence ? 0.75D : 0.0D) + (cumulativeEvidence ? 0.65D : 0.0D)
                    + (severity > 3.0D ? 1.0D : severity > 2.0D ? 0.5D : 0.0D);

            if (flag(data, expectedType, currentDY, lastDY, expectedDY, normalExpectedDY,
                    excess, allowed, doubleGravityDY, required, added, airTicks, fallDist)) {
                return;
            }
        } else {
            decayEvidence(0.35D, 0.12D);
        }

        // Update noise tracker & state
        double residual = Math.abs(expectedDisplacement - displacementY) / sampleTicks;
        updateNoise(residual, !motionExcess && !grounded);

        lastObservedDY = currentDY;

        if (grounded) {
            resetTracking();
            lastObservedDY = 0.0D;
            decayEvidence(0.45D, 0.20D);
            return;
        }

        updateTracking(currentDY, displacementY, sampleTicks);
    }

    // =========================================================================
    //  Physics, Tracking & Calculation Helpers
    // =========================================================================

    private double getAllowed(MovementData data, double dy, double lastDy, double expectedDY, int sampleTicks) {
        boolean bedrock = profile.isBedrockPlayer();
        double perTick = bedrock ? 0.040D : 0.008D;
        perTick += Math.min(bedrock ? 0.022D : 0.010D, Math.abs(expectedDY) * (bedrock ? 0.045D : 0.025D));
        perTick += Math.min(bedrock ? 0.018D : 0.008D, Math.abs(lastDy) * (bedrock ? 0.030D : 0.015D));
        perTick += gravityNoiseEma;
        if (data.getCustomAirTicks() <= 2) perTick += bedrock ? 0.025D : 0.012D;
        if (Math.abs(lastDy) <= 1.0E-6D && dy < 0.0D) perTick += bedrock ? 0.055D : 0.035D;
        if (data.getSinceCollideTicks() < 5 + GravityUtility.getLagCompensationTicks(profile)) perTick += 0.025D;
        if (bedrock) perTick += 0.015D;
        else if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) perTick += 0.002D;
        perTick = Math.min(bedrock ? 0.120D : 0.060D, perTick);

        int ticks = Math.max(1, sampleTicks);
        return perTick * (1.0D + ((ticks - 1) * 0.65D)) + (gravityNoiseEma * ticks);
    }

    private double getTrajectoryAllowance(int sampleTicks) {
        boolean bedrock = profile.isBedrockPlayer();
        double base = bedrock ? 0.055D : 0.026D;
        double growth = Math.min(bedrock ? 0.090D : 0.050D, Math.max(0, predictedTicks) * (bedrock ? 0.006D : 0.0035D));
        double aggregation = Math.max(0, sampleTicks - 1) * (bedrock ? 0.025D : 0.012D);
        return base + growth + aggregation + (gravityNoiseEma * Math.max(1, sampleTicks) * 1.5D);
    }

    private void updateCumulativeExcess(double excess, double allowed, boolean grounded) {
        double noiseFloor = Math.max(profile.isBedrockPlayer() ? 0.0035D : 0.0015D, Math.min(0.012D, allowed * 0.18D));
        double evidence = excess - noiseFloor;
        if (evidence > 0.0D) {
            cumulativeExcess = Math.min(1.50D, (cumulativeExcess * 0.92D) + evidence);
        } else {
            cumulativeExcess *= grounded ? 0.45D : 0.62D;
        }
    }

    private void updateNoise(double residual, boolean cleanSample) {
        if (!cleanSample || !Double.isFinite(residual)) return;
        double cap = profile.isBedrockPlayer() ? 0.030D : 0.010D;
        if (residual <= (profile.isBedrockPlayer() ? 0.008D : 0.0015D)) {
            gravityNoiseEma = Math.min(cap, (gravityNoiseEma * 0.90D) + (Math.min(cap, residual) * 0.10D));
        }
    }

    private void updateTracking(double currentDY, double displacementY, int sampleTicks) {
        trackingFall = true;
        predictedTicks = Math.max(predictedTicks + sampleTicks, profile.getMovementData().getCustomAirTicks());
        predictedDY = GravityUtility.predictGravityDY(currentDY, cachedGravity);
        if (displacementY < 0.0D) {
            predictedFallDist += -displacementY;
        } else if (currentDY > 0.0D) {
            predictedFallDist = 0.0D;
        }
    }

    private void synchronize(MovementData data, double displacementY, boolean grounded) {
        resetTracking();
        cumulativeExcess = 0.0D;
        if (grounded) {
            lastObservedDY = 0.0D;
            return;
        }
        double maxUpward = Math.max(0.62D, getExpectedJumpMotion() + 0.30D);
        double seed = Math.max(-64.0D, Math.min(maxUpward, displacementY));
        lastObservedDY = seed;
        predictedDY = seed;
        predictedTicks = Math.max(1, data.getCustomAirTicks());
        predictedFallDist = Math.max(0.0D, -seed);
        trackingFall = true;
    }

    private boolean flag(MovementData data, String type, double dy, double lastDy, double expectedDY,
                         double normalExpectedDY, double excess, double allowed, double doubleGravityDY,
                         double required, double added, int airTicks, double fallDist) {
        streak += added;
        double bufferAdded = Math.max(0.25D, Math.min(1.0D, added * 0.30D));

        if (fallDist == 0 && airTicks == 0 && dy == 0 && Math.abs(lastDy - 0.59260459763505993D) < 1E-6
                && !data.isCustomInAir() && data.isOnGround() && Math.abs(expectedDY - 0.502352515459515D) < 1E-6) {
            return false;
        }

        String theme = MsgType.MAIN_THEME_COLOR.getMessage();
        String info = "type " + theme + type
                + "\ndy " + theme + dy
                + "\nlastDY " + theme + lastDy
                + "\nexpectedDY " + theme + expectedDY
                + "\nnormalExpectedDY " + theme + normalExpectedDY
                + "\ndoubleGravityDY " + theme + doubleGravityDY
                + "\nexcess " + theme + excess
                + "\nallowed " + theme + allowed
                + "\nairTicks " + theme + airTicks
                + "\nfallDist " + theme + fallDist
                + "\nstreak " + theme + streak;

        verbose(getClass().getSimpleName(), streak, required, info);

        if (increaseBufferBy(bufferAdded) > required || streak > required) {
            fail("Negative Gravity Modification", info);
            resetTracking();
            lastObservedDY = dy;
            lastSampleTick = data.getTick();
            cumulativeExcess = 0.0D;
            streak = Math.max(required + 1.0D, streak);
            return true;
        }
        return false;
    }

    private boolean isGrounded(MovementData data) {
        if (!hasExactGroundSupport(data) || data.getDeltaY() > 1.0E-7D) return false;
        return data.isOnGround() || data.isServerGround() || data.isServerYGround() || data.isPositionYGround();
    }

    private boolean hasExactGroundSupport(MovementData data) {
        try {
            return data != null && data.getNearbyBlocksResult() != null && data.getNearbyBlocksResult().hasExactGroundSupport();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getAirTicks(MovementData data) {
        return Math.max(data.getCustomAirTicks(), Math.max(data.getClientAirTicks(), data.getServerAirTicks()));
    }

    private void decayEvidence(double streakAmount, double bufferAmount) {
        streak = Math.max(0.0D, streak - streakAmount);
        cumulativeExcess *= 0.45D;
        decreaseBufferBy(bufferAmount);
    }

    private void resetTracking() {
        trackingFall = false;
        predictedDY = Double.NaN;
        predictedFallDist = 0.0D;
        predictedTicks = 0;
    }

    private void resetGravityD(String reason) {
        ChatUtils.debugExempt(reason, "GravityD");
        streak = 0.0D;
        cumulativeExcess = 0.0D;
        gravityNoiseEma = 0.0D;
        lastSampleTick = Integer.MIN_VALUE;
        lastObservedDY = Double.NaN;
        lastExactGroundSupport = false;
        resetBuffer();
        resetTracking();
    }

    private double getExpectedJumpMotion() {
        if (!profile.isBedrockPlayer() && Double.isFinite(cachedJumpStrength)) {
            double motion = cachedJumpStrength;
            int boost = SpeedUtilities.getJumpBoostPotionLevelInstant(profile);
            return boost > 0 ? motion + boost * 0.10D : motion;
        }
        return GravityUtility.getExpectedJumpMotion(profile);
    }

    private boolean refreshMovementAttributes(MovementData data) {
        if (profile.isBedrockPlayer() || data == null) return false;
        int tick = data.getTick();
        if (lastAttributeTick != Integer.MIN_VALUE && tick - lastAttributeTick >= 0 && tick - lastAttributeTick < 10) {
            return false;
        }
        lastAttributeTick = tick;
        double gravity = GravityUtility.readBukkitAttribute(profile, "GRAVITY", "GENERIC_GRAVITY");
        double jumpStrength = GravityUtility.readBukkitAttribute(profile, "JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH");

        if (!Double.isFinite(gravity) || gravity < 0.0D || gravity > 4.0D) gravity = DEFAULT_GRAVITY;
        if (!Double.isFinite(jumpStrength) || jumpStrength < 0.0D || jumpStrength > 4.0D) jumpStrength = Double.NaN;

        boolean changed = attributesInitialized
                && (Math.abs(cachedGravity - gravity) > 1.0E-7D || (Double.isFinite(cachedJumpStrength) != Double.isFinite(jumpStrength)
                || (Double.isFinite(cachedJumpStrength) && Math.abs(cachedJumpStrength - jumpStrength) > 1.0E-7D)));

        cachedGravity = gravity;
        cachedJumpStrength = jumpStrength;
        attributesInitialized = true;
        return changed;
    }
}
