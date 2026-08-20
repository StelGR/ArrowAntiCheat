package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PotionData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.util.Vector;

// this is probably the most powerful check here, it accounts for all jump height speeds, and bedrock can work if you ignore issues with velocity transactions
// the check accounts for all jump height possible speeds, depth strider, head hitters, trident riptide, soul speed, attribute and speed potions up to level 10,
// it is not perfect, it simply just accounts for alot of scenarios, the goal though is to slowly make it more accurate for all scenarios it doesn't account for,
// such is ground speed, and some others, the ground speed limit isn't as good as the air one
// strafe also does the job but needs improvements, Speed B does most of the job for strafes

// update 107-pre1, velocity issues are fixed on bedrock, but now we have a jump height issue.
// bedrock can't stop giving me brain damage, but I won't give up.

// strafe has moved to illegalmoveb

// update 107-pre3, jump height has been somewhat been fixed

public class SpeedA extends Check {
    public SpeedA(Profile profile) {
        super(profile, CheckType.SPEED, "A", "Checks if the player is following vanilla speed");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();

        double deltaXZ = movementData.getDeltaXZ();
        double deltaY = movementData.getDeltaY();
        double velocityH = velocityData.getTotalHorizontalVelocity();
        double blockFriction = movementData.getFrictionFactor();

        boolean serverGround = movementData.isServerGround();
        boolean clientGround = movementData.isOnGround();
        int clientAirTicks = movementData.getClientAirTicks();
        int serverGroundTicks = movementData.getServerGroundTicks();
        double movingSlimeTicks = movementData.getMovingOnSlimeTicks();
        int movingHoneyTicks = movementData.getMovingOnHoneyTicks();
        float movingIceTicks = movementData.getMovingOnIceTicks();
        float underBlockMoveTime = movementData.getMovingUnderblockTicks();

        calculateAir(movementData, movingIceTicks, movingSlimeTicks, movingHoneyTicks, underBlockMoveTime, velocityH, deltaY, deltaXZ, clientAirTicks, clientGround, serverGround);

        calculateGround(movementData, velocityData, deltaXZ, deltaY, movingIceTicks, serverGround, serverGroundTicks, clientAirTicks, blockFriction);
    }


    double groundBuffer;

    double DEFAULT_BASE_PER_TICK = 0.27397D;

//    private static final double SPRINT_BASE = 0.1477285125D;
//    private static final double NO_SPRINT_BASE = 0.1777285125D;
    double DIAGONAL_TOLERANCE = 1.022425;

    public void calculateGround(MovementData movementData, VelocityData velocityData, double deltaXZ, double deltaY, float movingIceTicks, boolean serverGround, int serverGroundTicks, int airTicks, double blockFriction) {
        long profiler = Profiler.start();

        try {
            double predicted = deltaXZ * blockFriction;

            double groundLimit = SpeedUtilities.computeGroundLimit(profile, velocityData, DEFAULT_BASE_PER_TICK);

            double frictionMultiplier = SpeedUtilities.friction(blockFriction);
            double allowedLimit = groundLimit * frictionMultiplier;

            RotationData rotationData = profile.getRotationData();

            double deltaX = movementData.getDeltaX();
            double deltaZ = movementData.getDeltaZ();
            double lastDeltaX = movementData.getLastDeltaX();
            double lastDeltaZ = movementData.getLastDeltaZ();
            float yaw = rotationData.getYaw();

            double inputX = deltaX - (lastDeltaX * movementData.getFrictionFactor());
            double inputZ = deltaZ - (lastDeltaZ * movementData.getFrictionFactor());

            MovementPredictionUtil.DirectionalMovement inputDirection =
                    MovementPredictionUtil.predictDirectionalMovement(inputX, inputZ, yaw);

            if (inputDirection.isForwardStrafe()) allowedLimit *= DIAGONAL_TOLERANCE;

            allowedLimit += 0.004;
            double depthStriderBoost = SpeedUtilities.getDepthStriderBoost(profile);
            if (movementData.isInsideWater()) allowedLimit += depthStriderBoost; // apply always if in water


            allowedLimit += movementData.getDolphinGraceBoost();

            if (movementData.getSinceCollideTicks() < 12 + profile.getConnectionData().getClientTickTrans()) {
                allowedLimit += 0.0275;
            }

            allowedLimit += movementData.elytraMomentum();
            allowedLimit += movementData.getDolphinGraceBoost();
            allowedLimit += movementData.isColliding() ? 0.05 : 0;

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                allowedLimit += 0.2;
            }

            //if (profile.isSwimming() && movementData.isNearWater()) allowedLimit += 0.137;

            if (profile.getActionData().hasRecentPistonUpdate(5 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed C: is extending (Piston Update)");
                allowedLimit += 0.1295;
            }

            if (serverGroundTicks <= 20) allowedLimit += 0.03;

            if (movementData.getMovingOnSlimeTicks() > 0) {
                allowedLimit += 0.05;
            }

            if (movementData.getSincePredictUpwardsTicks() < 10) {
                allowedLimit *= 2;
            }

            if (movingIceTicks > 0) {
                allowedLimit += 0.04;

                if (movementData.getMovingUnderblockTicks() > 0 && serverGroundTicks < 23) {
                    allowedLimit += 0.3;
                }
            }

            allowedLimit = Math.max(DEFAULT_BASE_PER_TICK * frictionMultiplier, allowedLimit);

            if (serverGround && deltaXZ != 0) {
                verbose(this.getClass().getSimpleName(), predicted, allowedLimit,
                        MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Ground)\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predicted
                                + "\n * expected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedLimit
                                + "\n * expected deltaXZ (No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + (allowedLimit / Math.max(0.0001, frictionMultiplier))
                                + "\n * expected deltaXZ (No Diag/No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                                + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\n * frictionMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + frictionMultiplier
                                + "\n * movementSpeedBase " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getMovementSpeedAttribute(profile)
                                + "\n * movementSpeedEffective " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getEffectiveMovementSpeedGround(profile)
                                + "\n * movementSpeedScale " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getEffectiveMovementScaleGround(profile)
                                + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                + "\n * isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                                + "\n * upTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicks()
                                + "\n * upTicksWM " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicksWithoutMaterial()
                                + "\n * downTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicks()
                                + "\n * downTicksWM " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicksWithoutMaterial()
                                + "\n * nearStepMaterial " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isNearStepMaterial()
                                + "\n * attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                                + "\n * potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                                + "\n * comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile)
                                + "\n * serverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGroundTicks);
            }

            if (isExemptGround(movementData)) return;

            double difference = predicted - allowedLimit;
            double bufferAmount = difference > 0.7 ? 0 : 10;
            double serverGroundMaxTicks = 4;

            if (difference > 0.6) serverGroundMaxTicks = 2;

            if (serverGroundTicks > serverGroundMaxTicks && predicted > allowedLimit) {
                if (++groundBuffer > bufferAmount) {
                    fail("Speed limit exceeded (Ground)",
                            "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predicted
                                    + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedLimit
                                    + "\nblock friction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                    + "\nstrafe sector " + MsgType.MAIN_THEME_COLOR.getMessage() + inputDirection.getSector()
                                    + "\nisSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                                    + "\nserverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGroundTicks);
                    groundBuffer = Math.max(bufferAmount + 2, groundBuffer);
                }
            } else groundBuffer = Math.max(0, groundBuffer - 0.05);
        } finally {
            Profiler.stop("Speed A (Ground)", profiler);
        }
    }

    double airBuffer;
    final double AIR_BASE_SPEED = 0.35301212D;

    final double SPRINT_BASE_SPEED = 0.22301212D;
    final double NO_SPRINT_BASE_SPEED = 0.25301212D;

    final double AIR_HONEY_INCREMENT_PER_TICK = 0.0021;

    final double AIR_MAX_HONEY_SPEED_BOOST = 1.1;

    private long lastDecayTick = -1L;

    public void calculateAir(MovementData movementData, float movingIceTicks, double movingSlimeTicks, int movingHoneyTicks, float underBlockMoveTime, double velocityH, double deltaY, double deltaXZ, int clientAirTicks, boolean clientGround, boolean serverGround) {
        long profiler = Profiler.start();

        try {
            int speedLevel = SpeedUtilities.getSpeedPotionLevel(profile);

            double air_honeySpeedBoost = Math.min(AIR_HONEY_INCREMENT_PER_TICK * movingHoneyTicks, AIR_MAX_HONEY_SPEED_BOOST);

            double attr = SpeedUtilities.getMovementSpeedAttribute(profile);
            double effectiveAttr = SpeedUtilities.getEffectiveMovementSpeedAir(profile);
            double movementScale = SpeedUtilities.getEffectiveMovementScaleAir(profile);

            double expectedSpeed = SpeedUtilities.computeAirLimit(profile, AIR_BASE_SPEED);

            int soulSpeedLevel = SpeedUtilities.getSoulSpeedLevel(profile);

            if (soulSpeedLevel > 0 && movementData.getMovingOnSoulBlocksTicks() > 0) {
                expectedSpeed += soulSpeedLevel * 0.075D;
            }

            expectedSpeed += expectedSpeed * (air_honeySpeedBoost);
            if (movementData.getSinceSpeedPotionEffectTicks() < 15) expectedSpeed += 0.05;
            VelocityData vd = profile.getVelocityData();

            double explosionH = 0.0;

            Vector expFvc = vd.getExplosionKnockback();
            if (expFvc != null) {
                explosionH = Math.hypot(expFvc.getX(), expFvc.getZ());
            }

            double kbComponent = Math.max(vd.getVelocityH(), 0.0);

            if (vd.getVelocityTicks() == 1) {
                expectedSpeed += 0.03;
            }

            double explosionComponent = 0.0;
            if (explosionH > 0.0) {
                explosionComponent = (explosionH * 6) + 0.2D;
            }

            expectedSpeed += kbComponent + explosionComponent;

            double depthStriderBoost = SpeedUtilities.getDepthStriderBoost(profile);
            if (movementData.isInsideWater()) expectedSpeed += depthStriderBoost;

            boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() < 15 + profile.getConnectionData().getClientTickTrans();

            if (currentlyRiptiding) {
                double riptideCap = 3.75 + (1.5 * profile.getPredictionData().riptideLevel());
                expectedSpeed += riptideCap;
            }

            double maxJumpHeight = MoveUtils.getJumpMotion(profile);

            if (isVanillaJumpStart(deltaY, maxJumpHeight, clientAirTicks)) {
                expectedSpeed = applyAfterJumpAllowance(expectedSpeed, speedLevel);
            }

            double expected = -0.0784000015258789D;
            if (Math.abs(deltaY - expected) < 1E-6 && clientAirTicks == 1) {
                expectedSpeed += speedLevel > 0 ? (0.06125 + (0.008D * speedLevel)) : 0.06125;
            }

            double expected2 = 0.33319999363422426D;

            if (clientAirTicks == 2 && Math.abs(deltaY - expected2) < 1E-6) {
                expectedSpeed += speedLevel > 0 ? (0.002 + (0.008D * speedLevel)) : 0.01081;
                expectedSpeed += movementData.getSincePredictUpwardsTicksWithoutMaterial() <= 7 ? 0.013 : 0;
            }

            double expected3 = 0.24813599859094637D;

            if (clientAirTicks == 3 && Math.abs(deltaY - expected3) < 1E-6) {
                expectedSpeed += speedLevel > 0 ? (0.007 + (0.008D * speedLevel)) : 0.007;
                expectedSpeed += movementData.getSincePredictUpwardsTicksWithoutMaterial() <= 7 ? 0.00925 : 0;
            }

            if (movementData.getSinceMovingOnIceTicks() < 20 || movementData.getSinceMovingOnSlimeTicks() < 20) {
                expectedSpeed += 0.01D;
            }

            if (movementData.getSinceCollideTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                expectedSpeed += 0.125;
            }

            if (movementData.getSinceMovingOnIceTicks() > 0 && movementData.getSinceMovingOnIceTicks() < 120) {
                expectedSpeed += 0.003;
            }

            if (profile.getBlockProcessor().isCancelledBlockPlacementExempt(10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                expectedSpeed += 0.005;
            }

            if (SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) {
                expectedSpeed += 0.004;
            }

            PotionData potions = profile.getPotionData();

            if (movingIceTicks > 0 && !potions.isHasSpeed()) {
                expectedSpeed += 0.35;
            }

            if (movingSlimeTicks > 0 && movementData.getLastFallDistance() > 1) {
                expectedSpeed += 0.3;
            } else if (movingSlimeTicks > 0 && movementData.getLastFallDistance() < 1) {
                expectedSpeed += 0.08;
            }

            if (underBlockMoveTime > 0) {
                expectedSpeed += potions.isHasSpeed() ? 0.36 : 0.3325;
            }

            expectedSpeed += movementData.elytraMomentum();
            expectedSpeed += movementData.getDolphinGraceBoost();

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                expectedSpeed += 0.2;
            }

            if (movementData.getSincePredictUpwardsTicks() < 10) {
                expectedSpeed += 0.03;
            }

            expectedSpeed = Math.max(AIR_BASE_SPEED, expectedSpeed);

            String format = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Air)\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* speedLevel " + MsgType.MAIN_THEME_COLOR.getMessage() + speedLevel + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* slimeMoveTime " + MsgType.MAIN_THEME_COLOR.getMessage() + movingSlimeTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* iceMoveTime " + MsgType.MAIN_THEME_COLOR.getMessage() + movingIceTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* underBlockMoveTime " + MsgType.MAIN_THEME_COLOR.getMessage() + underBlockMoveTime + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* aSpeedBase " + MsgType.MAIN_THEME_COLOR.getMessage() + attr + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* aSpeedEffective " + MsgType.MAIN_THEME_COLOR.getMessage() + effectiveAttr + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* aSpeedScale " + MsgType.MAIN_THEME_COLOR.getMessage() + movementScale + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* bSpeed (s/ns) " + MsgType.MAIN_THEME_COLOR.getMessage() + SPRINT_BASE_SPEED + "/" + NO_SPRINT_BASE_SPEED + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* moving Up Ticks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicks() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* moving Down Ticks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicks() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* nearStepMaterial " + movementData.isNearStepMaterial() + "\n"
                    + "* iceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movingIceTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* slimeTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movingSlimeTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* honeyTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movingHoneyTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* underblockTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + underBlockMoveTime + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributeBonus(profile) + "\n"
                    + "* potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirPotionBonus(profile) + "\n"
                    + "* comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributePotionBonus(profile) + "\n"
                    + "* velocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* velocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + vd.getVelocityV() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* airticks c|s " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks + "|" + movementData.getCustomAirTicks() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* maxJumpHeight " + MsgType.MAIN_THEME_COLOR.getMessage() + maxJumpHeight + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* isTeleporting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.isExempt().isTeleports() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* expectedSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedSpeed + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* elytraMomentumBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.elytraMomentum() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* dolhinGraceMomentumBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDolphinGraceBoost() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                    + "* difference " + MsgType.MAIN_THEME_COLOR.getMessage() + (expectedSpeed - deltaXZ);

            if (deltaXZ != 0 && !serverGround) {
                verbose(this.getClass().getSimpleName(), deltaXZ, expectedSpeed, format);
            }

            long currentTick = System.currentTimeMillis() / 50L;

            if (currentTick - lastDecayTick >= 2) {
                lastDecayTick = currentTick;


                if (profile.getVelocityData().getVelocityH() > 0.0D) {
                    double totalH = profile.getVelocityData().getVelocityH();

                    totalH *= movementData.isOnGround()
                            ? movementData.getFrictionFactor()
                            : 0.91F;

                    profile.getVelocityData().setVelocityH(Math.max(totalH - 0.001D, 0.0D));
                }

                if (profile.getVelocityData().getVelocityV() > 0.0D) {
                    double totalV = profile.getVelocityData().getVelocityV();

                    totalV = (totalV * (movementData.isOnGround()
                            ? movementData.getFrictionFactor()
                            : 0.91F)) - 0.04D;

                    profile.getVelocityData().setVelocityV(Math.max(totalV, 0.0D));
                }

                if (profile.getVelocityData().getVelocityV() < 0.0001) {
                    profile.getVelocityData().setVelocityV(0.0D);
                    profile.getVelocityData().setVelocityVSustain(0.0D);
                }

                if (profile.getVelocityData().getVelocityH() < 0.0001) {
                    profile.getVelocityData().setVelocityH(0.0D);
                    profile.getVelocityData().setVelocityHSustain(0.0D);
                }
            }

            if (isExemptAir(movementData)) return;

            if (deltaXZ > expectedSpeed
                    && !serverGround) {

                double difference = deltaXZ - expectedSpeed;
                double bufferAmount = 3;

                if (difference > 0.7) bufferAmount = 1;
                if (++airBuffer > bufferAmount) {
                    fail("Speed limit exceeded (Air)",
                            "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                    + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedSpeed
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                    + "\nserverAirTicks  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                                    + "\nupwardsTicks  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicks()
                                    + "\ndownwardsTicks  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicks()
                                    + "\nPUT  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicksWithoutMaterial()
                                    + "\nPDT  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicksWithoutMaterial()
                                    + "\nisSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting());
                    airBuffer = Math.max(8, airBuffer);
                }

            } else airBuffer = Math.max(0, airBuffer - 0.005D);
        } finally {
            Profiler.stop("Speed A (Air)", profiler);
        }
    }

    private boolean isVanillaJumpStart(double deltaY, double maxJumpHeight, int clientAirTicks) {
        return clientAirTicks == 1
                && deltaY > maxJumpHeight - 0.045D
                && deltaY <= maxJumpHeight + 1.0E-6D;
    }

    private double applyAfterJumpAllowance(double expectedSpeed, int speedLevel) {
        double afterJump = SpeedUtilities.getAfterJumpSpeed(profile);

        if (afterJump <= 0.0D || Double.isNaN(afterJump) || Double.isInfinite(afterJump)) {
            afterJump = 0.73D;
        }

        double bounded = expectedSpeed / afterJump;
        double jumpBoost = speedLevel > 0 ? 0.27525D + (0.006D * speedLevel) : 0.27525D;

        return Math.max(expectedSpeed, bounded + jumpBoost);
    }


    boolean isExemptGround(MovementData movementData) {
        if (profile.shouldCancel()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - shouldCancel");
            return true;
        }

        if (movementData.isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - isOnBoat");
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - teleports");
            return true;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - notRespawned");
            return true;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - vehicle");
            return true;
        }

        if (profile.getExempt().isReelingIn()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - reelingIn");
            return true;
        }

        if (movementData.getSinceRiptidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - isRiptiding");
            groundBuffer = 0;
            return true;
        }

        if (movementData.getSinceOnGhostBlock() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean())
                OtherUtility.log("Speed A (Ground): Exempt - recentlyOnGhostBlock()");
            return true;
        }

        if (movementData.isNearBed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - nearBed");
            return true;
        }

//        if (movementData.isUnderblock()) {
//            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - underblock");
//            return true;
//        }

        if (movementData.getSinceGlidingTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - recentlyGliding");
            return true;
        }

        if (movementData.getMovingOnSoulBlocksTicks() > 0) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - soulsoil");
            return true;
        }

        return false;
    }

    boolean isExemptAir(MovementData movementData) {
        if (profile.shouldCancel()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - shouldCancel");
            return true;
        }

        if (movementData.getSinceTeleportTicks() < 5) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - teleporting");
            return true;
        }


        if (profile.getMovementData().isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - isOnBoat");
            return true;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - notRespawned");
            return true;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - vehicle");
            return true;
        }

        if (movementData.isNearBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - near boat");
            return true;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - vehicle");
            return true;
        }

        if (profile.getExempt().isReelingIn()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - reelingIn");
            return true;
        }


        if (profile.getMovementData().isNearBed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - nearBed");
            return true;
        }

        if (profile.getMovementData().getSinceGlidingTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - recentlyGliding");
            return true;
        }

        return false;
    }
}
