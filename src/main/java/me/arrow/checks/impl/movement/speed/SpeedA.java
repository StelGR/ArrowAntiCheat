package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
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

// Update 108-pre1, dynamic slime and underblock and ice speed limit has been changed to a hard coded limit, like the rest of the check, more improvement is needed

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
        RotationData rotationData = profile.getRotationData();

        // Elytra physics is handled by the dedicated checks. Never feed an
        // active glide (or its packet transition window) into ground/air speed.
        if (movementData.isGlidingNow()) {
            resetBuffer();
            return;
        }

        double deltaXZ = movementData.getDeltaXZ();
        double deltaY = movementData.getDeltaY();
        double blockFriction = movementData.getFrictionFactor();
        predictedDifference = 0.0D;

        boolean serverGround = movementData.isServerGround();
        boolean clientGround = movementData.isOnGround();
        int clientAirTicks = movementData.getClientAirTicks();
        int serverGroundTicks = movementData.getServerGroundTicks();
        double movingSlimeTicks = movementData.getMovingOnSlimeTicks();
        int movingHoneyTicks = movementData.getMovingOnHoneyTicks();
        float movingIceTicks = movementData.getMovingOnIceTicks();
        float underBlockMoveTime = movementData.getMovingUnderblockTicks();

        expectedSpeed = 0.0D;
        calculateSpeed(movementData, velocityData, rotationData, deltaXZ, deltaY, blockFriction,
                clientGround, serverGround, clientAirTicks, serverGroundTicks, movingSlimeTicks,
                movingHoneyTicks, movingIceTicks, underBlockMoveTime);
    }

    private double expectedSpeed;
    private double predictedDifference;
    private String airLimitDebug;

    private void calculateSpeed(MovementData movementData, VelocityData velocityData, RotationData rotationData,
                                double deltaXZ, double deltaY, double blockFriction, boolean clientGround,
                                boolean serverGround, int clientAirTicks, int serverGroundTicks,
                                double movingSlimeTicks, int movingHoneyTicks, float movingIceTicks,
                                float underBlockMoveTime) {
        long profiler = Profiler.start();
        boolean exempt;
        double difference = 0.0D;
        String failDetails = "";
        expectedSpeed = 0.0D;
        predictedDifference = 0.0D;
        airLimitDebug = "default";

        try {
            if (serverGround) {
                exempt = isExemptGround(movementData);
                if (!exempt) {
                    double allowedLimit = SpeedUtilities.computeGroundLimit(profile, MoveUtils.GROUND_BASE_SPEED);

                    double deltaX = movementData.getDeltaX();
                    double deltaZ = movementData.getDeltaZ();
                    double lastDeltaX = movementData.getLastDeltaX();
                    double lastDeltaZ = movementData.getLastDeltaZ();
                    float yaw = rotationData.getYaw();

                    double inputX = deltaX - (lastDeltaX * movementData.getFrictionFactor());
                    double inputZ = deltaZ - (lastDeltaZ * movementData.getFrictionFactor());

                    MovementPredictionUtil.DirectionalMovement inputDirection =
                            MovementPredictionUtil.predictDirectionalMovement(inputX, inputZ, yaw);

                    if (inputDirection.isForwardStrafe()) allowedLimit += MoveUtils.GROUND_DIAGONAL_TOLERANCE;

                    if (profile.isBedrockPlayer()) {
                        allowedLimit += 0.0016;
                    }

                    if (movementData.getSinceCollideTicks() < 12 + profile.getConnectionData().getClientTickTrans()) {
                        allowedLimit += 0.0275;
                    }

                    if (profile.getActionData().hasRecentPistonUpdate(8 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                        if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed C: is extending (Piston Update)");
                        allowedLimit += 0.354;
                    }

                    if (serverGroundTicks <= 6) allowedLimit += 0.095;

                    if (serverGroundTicks <= 20) allowedLimit += 0.03;

                    if (movementData.getMovingOnSlimeTicks() > 0) {
                        allowedLimit += 0.05;
                    }

                    if (movementData.getSincePredictUpwardsTicks() < 10) {
                        allowedLimit *= 2;
                    }

                    if (movingIceTicks > 0) {
                        allowedLimit += 0.05;

                        if ((movementData.getMovingUnderblockTicks() > 0
                                || movementData.isUnderBoat()
                                || movementData.isLastUnderBoat())
                                && serverGroundTicks < 23) {
                            allowedLimit += 0.3;
                        }
                    }

                    if (movementData.isWaterPredictionActive()) {

                        if (movementData.isInsideWater()) {

                            /*
                             * Actually in water.
                             */
                            double expectedWaterXZ =
                                    movementData.getExpectedWaterDeltaXZ();

                            predictedDifference = expectedWaterXZ;
                            allowedLimit = expectedWaterXZ + 0.005D;

                        } else {

                            /*
                             * Left water.
                             *
                             * Keep the normal ground limit and ADD the retained
                             * water momentum on top.
                             */
                            double waterMomentum =
                                    movementData.getWaterMomentumBonus();

                            predictedDifference = waterMomentum;
                            allowedLimit += waterMomentum;
                        }
                    }

                    boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() < 30;

                    if (currentlyRiptiding) {
                        double riptideCap = 0.65 + (0.75 * profile.getPredictionData().riptideLevel());

                        if (movementData.getDolphinGraceBoost() > 0) riptideCap += 2;
                        if (movementData.isInsideWater()) riptideCap += 1;

                        if (movementData.getSinceRiptidingTicks() > 10) riptideCap /= 2;

                        allowedLimit += riptideCap;
                    }

                    if (profile.getExempt().isReelingIn()) {
                        allowedLimit += 0.3;
                        if (!profile.getRodData().isSelf()) allowedLimit += 1.7;
                    }

                    if (velocityData.isTakingVelocity()) {
                        allowedLimit += velocityData.getTotalHorizontalVelocity() * 1.5;
                    }

                    int soulSpeedLevel = SpeedUtilities.getSoulSpeedLevel(profile);

                    if (soulSpeedLevel > 0 && movementData.getMovingOnSoulBlocksTicks() > 0) {
                        allowedLimit += 0.08D;
                    }

                    allowedLimit += movementData.isColliding() ? 0.05 : 0;

                    allowedLimit += movementData.elytraMomentum();

                    expectedSpeed = allowedLimit;

                    difference = deltaXZ - expectedSpeed;
                    failDetails = "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                            + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedSpeed
                            + "\nblock friction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                            + "\npredictedWater " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedDifference
                            + "\nstrafe sector " + MsgType.MAIN_THEME_COLOR.getMessage() + inputDirection.getSector()
                            + "\nisSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                            + "\nserverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGroundTicks;
                }
            } else {
                exempt = isExemptAir(movementData);
                if (!exempt) {
                    airLimitDebug = "default";
                    int speedLevel = SpeedUtilities.getSpeedPotionLevel(profile);
                    double airLimit = MoveUtils.AIR_BASE_SPEED;
                    double attr = SpeedUtilities.getMovementSpeedAttribute(profile);
                    boolean hasAttr = attr > 0.13005D;
                    if (hasAttr) {
                        airLimit += attr - 0.13D;
                    }

                    int soulSpeedLevel = SpeedUtilities.getSoulSpeedLevel(profile);

                    if (soulSpeedLevel > 0 && movementData.getMovingOnSoulBlocksTicks() > 0) {
                        airLimit += soulSpeedLevel * 0.075D;
                        airLimitDebug += ", soulSpeed";
                    }

                    if (movingHoneyTicks > 0) {
                        airLimit += 0.125;
                        airLimitDebug += ", movingOnHoneyTicks";
                    }

                    double maxJumpHeight = MoveUtils.getJumpMotion(profile);

                    if (isVanillaJumpStart(deltaY, maxJumpHeight, clientAirTicks)) {
                        airLimit += speedLevel > 0 ? 0.259525D + (0.0425D * speedLevel) : 0.259525D;
                        airLimit += hasAttr ? 1.005 * (attr - 0.13D) : 0;
                        airLimitDebug += ", firstTickJump";
                    }

                    double expected = -0.0784000015258789D;
                    if (Math.abs(deltaY - expected) < 1E-6 && clientAirTicks == 1) {
                        airLimit += speedLevel > 0 ? (0.05775 + (0.0425D * speedLevel)) : 0.05775;
                        airLimitDebug += ", 1st/2ndTickJump 1.8";
                    }

                    double expected2 = 0.33319999363422426D;

                    if (clientAirTicks == 2 && Math.abs(deltaY - expected2) < 1E-6) {
                        airLimit += speedLevel > 0 ? (0.0082 + (0.0425D * speedLevel)) : 0.0082;
                        airLimit += movementData.getSincePredictUpwardsTicksWithoutMaterial() <= 7 ? 0.013 : 0;
                        airLimitDebug += ", 2ndTickJump Modern";
                    }

                    double expected3 = 0.24813599859094637D;

                    if (clientAirTicks == 3 && Math.abs(deltaY - expected3) < 1E-6) {
                        airLimit += speedLevel > 0 ? (0.0015 + (0.0425D * speedLevel)) : 0.0015;
                        airLimit += movementData.getSincePredictUpwardsTicksWithoutMaterial() <= 7 ? 0.00925 : 0;
                        airLimitDebug += ", 3rdTickJump";
                    }

                    if ((movementData.getSinceMovingOnIceTicks() < 20 && movementData.getSinceMovingOnIceTicks() > 5)
                            || (movementData.getSinceMovingOnSlimeTicks() < 20 && movementData.getSinceMovingOnSlimeTicks() > 5)) {
                        airLimit += 0.01D;
                        airLimitDebug += ", 20TicksSinceIce/Slime";
                    }

                    if (movementData.getSinceMovingOnIceTicks() > 0 && movementData.getSinceMovingOnIceTicks() < 120) {
                        airLimit += 0.003;
                        airLimitDebug += ", movingOnIce";
                    }

                    if (profile.getBlockProcessor().isCancelledBlockPlacementExempt(10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                        airLimit += 0.005;
                        airLimitDebug += ", blockCancel";
                    }

                    if (SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) {
                        airLimit += 0.004;
                        airLimitDebug += ", jumpBoost";
                    }

                    PotionData potions = profile.getPotionData();

                    if (movingSlimeTicks > 0 && movementData.getLastFallDistance() > 1) {
                        airLimit += 0.3;
                        airLimitDebug += ", movingSlimeBig";
                    } else if (movingSlimeTicks > 0 && movementData.getLastFallDistance() < 1) {
                        airLimit += 0.08;
                        airLimitDebug += ", movingSlimeSmall";
                    }

                    if (underBlockMoveTime > 0
                            || movementData.isUnderBoat()
                            || movementData.isLastUnderBoat()) {
                        airLimit += potions.isHasSpeed() ? 0.3825 : 0.33125;
                        airLimitDebug += ", underBlock";
                    }

                    if ((movingIceTicks > 0
                            || movementData.isUnderBoat()
                            || movementData.isLastUnderBoat()) && !potions.isHasSpeed()) {
                        airLimit += 0.35;
                        airLimit += underBlockMoveTime > 0 ? 0.23745 : 0;
                        airLimitDebug += ", movingIce";
                    }

                    if (movementData.isWaterPredictionActive()) {
                        if (movementData.isInsideWater()) {

                            double expectedWaterXZ =
                                    movementData.getExpectedWaterDeltaXZ();

                            airLimit =
                                    expectedWaterXZ + 0.015D;

                            if (movementData.isNearBoat()) airLimit += 0.1D;

                            airLimitDebug +=
                                    ", waterPrediction(" + expectedWaterXZ + ")";

                        } else {

                            double waterMomentum =
                                    movementData.getWaterMomentumBonus();

                            airLimit += waterMomentum;

                            airLimitDebug +=
                                    ", waterMomentum(" + waterMomentum + ")";
                        }
                    }

                    boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 2);

                    if (currentlyRiptiding) {
                        double riptideCap = 0.3 + (0.75 * profile.getPredictionData().riptideLevel());

                        if (movementData.getDolphinGraceBoost() > 0) riptideCap += 2;
                        if (movementData.isInsideWater()) riptideCap += 1;

                        if (movementData.getSinceRiptidingTicks() > 10) riptideCap /= 2;

                        airLimit += riptideCap;
                        airLimitDebug += ", riptide";
                    }

                    if (movementData.getSinceSpeedPotionEffectTicks() < 15 && movementData.getSinceSpeedPotionEffectTicks() > 0) {
                        airLimit += 0.0575;
                        airLimitDebug += ", sincePotionEffectTicks < 15";
                    }
                    VelocityData vd = profile.getVelocityData();

                    double explosionH = 0.0;

                    Vector expFvc = vd.getExplosionKnockback();
                    if (expFvc != null) {
                        explosionH = Math.hypot(expFvc.getX(), expFvc.getZ());
                    }

                    double kbComponent = Math.max(Math.abs(vd.getVelocityH()), 0.0);

                    if (vd.getVelocityTicks() == 1) {
                        airLimit += 0.03;
                        airLimitDebug += ", velocityTick 1";
                    }

                    double explosionComponent = 0.0;
                    if (explosionH > 0.0) {
                        explosionComponent = (explosionH * 6) + 0.2D;
                    }

                    airLimit += kbComponent + explosionComponent;

                    if (kbComponent > 0) {
                        airLimitDebug += ", velocity";
                    }

                    if (explosionComponent > 0) {
                        airLimitDebug += ", explosionVelocity";
                    }

                    if (movementData.getSincePredictUpwardsTicks() < 10) {
                        airLimit += 0.03;
                        airLimitDebug += ", predictUpwards";
                    }

                    if (profile.getExempt().isReelingIn()) {
                        airLimit += 0.3;
                        if (!profile.getRodData().isSelf()) airLimit += 2.15;
                    }

                    if (movementData.getSinceCollideTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                        airLimit += 0.125;
                        airLimitDebug += ", sinceEntityPush";
                    }

                    if (movementData.isNearBoat()) {
                        airLimit += 0.1;
                    }

                    airLimit += movementData.elytraMomentum();

                    expectedSpeed = airLimit;
                    difference = deltaXZ - expectedSpeed;
                    failDetails = "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                            + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedSpeed
                            + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                            + "\npredictedWater " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getExpectedWaterDeltaXZ() + " | " + movementData.getWaterMomentumBonus()
                            + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                            + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                            + "\nupwardsTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicks()
                            + "\ndownwardsTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicks()
                            + "\nPUT " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictUpwardsTicksWithoutMaterial()
                            + "\nPDT " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSincePredictDownwardsTicksWithoutMaterial()
                            + "\nisSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting();
                }
            }

            if (!exempt && deltaXZ != 0.0D) {
                verbose(this.getClass().getSimpleName(), deltaXZ, expectedSpeed,
                        speedDebug(movementData, velocityData, deltaXZ, deltaY, blockFriction,
                                clientGround, serverGround, clientAirTicks, serverGroundTicks,
                                movingSlimeTicks, movingHoneyTicks, movingIceTicks, underBlockMoveTime));
            }

            if (!exempt) {
                double expectedBuffer = difference > 0.7D ? 0.0D : 6.0D;
                if (deltaXZ > expectedSpeed) {
                    if (increaseBuffer() > expectedBuffer) {
                        fail("Speed limit exceeded (" + (clientGround ? "Ground" : "Air") + ")", failDetails);
                        setBuffer(Math.max(8.0D, getBuffer()));
                    }
                } else {
                    decreaseBufferBy(0.05D);
                }
            }
        } finally {
            Profiler.stop("Speed A", profiler);
        }
    }

    private String speedDebug(MovementData movementData, VelocityData velocityData, double deltaXZ, double deltaY,
                              double blockFriction, boolean clientGround, boolean serverGround,
                              int clientAirTicks, int serverGroundTicks, double movingSlimeTicks,
                              int movingHoneyTicks, float movingIceTicks, float underBlockMoveTime) {
        String main = MsgType.MAIN_THEME_COLOR.getMessage();
        String second = MsgType.SECOND_THEME_COLOR.getMessage();
        return main + "* Verbose (" +
                (clientGround ? "Ground" : "Air") + ')' +
                "\n * " + second + "deltaXZ " + main + deltaXZ +
                "\n * " + second + "expectedSpeed " + main + expectedSpeed +
                "\n * " + second + "blockFriction " + main + blockFriction +
                "\n * " + second + "movementSpeedBase " + main + SpeedUtilities.getMovementSpeedAttribute(profile) +
                "\n * " + second + "movementSpeedMin " + main + SpeedUtilities.getMinMovementSpeedGround(profile) +
                "\n * " + second + "movementSpeedScale " + main + SpeedUtilities.getMovementScaleGround(profile) +
                "\n * " + second + "deltaY " + main + deltaY +
                "\n * " + second + "reeling " + main + profile.isExempt().isReelingIn() +
                "\n * " + second + "predictedWater " + main + predictedDifference +
                "\n * " + second + "isSprinting " + main + profile.getActionData().isSprinting() +
                "\n * " + second + "upTicks " + main + movementData.getSincePredictUpwardsTicks() +
                "\n * " + second + "upTicksWM " + main + movementData.getSincePredictUpwardsTicksWithoutMaterial() +
                "\n * " + second + "downTicks " + main + movementData.getSincePredictDownwardsTicks() +
                "\n * " + second + "downTicksWM " + main + movementData.getSincePredictDownwardsTicksWithoutMaterial() +
                "\n * " + second + "nearStepMaterial " + main + movementData.isNearStepMaterial() +
                "\n * " + second + "groundAttributeBonus " + main + SpeedUtilities.getGroundAttributeBonus(profile) +
                "\n * " + second + "groundPotionBonus " + main + SpeedUtilities.getGroundPotionBonus(profile) +
                "\n * " + second + "groundComboBonus " + main + SpeedUtilities.getGroundAttributePotionBonus(profile) +
                "\n * " + second + "serverGroundTicks " + main + serverGroundTicks +
                "\n * " + second + "clientGround " + main + clientGround +
                "\n * " + second + "serverGround " + main + serverGround +
                "\n * " + second + "speedLevel " + main + SpeedUtilities.getSpeedPotionLevel(profile) +
                "\n * " + second + "slimeTicks " + main + movingSlimeTicks +
                "\n * " + second + "iceTicks " + main + movingIceTicks +
                "\n * " + second + "underBlockMoveTime " + main + underBlockMoveTime +
                "\n * " + second + "honeyTicks " + main + movingHoneyTicks +
                "\n * " + second + "airAttributeBonus " + main + SpeedUtilities.getAirAttributeBonus(profile) +
                "\n * " + second + "airPotionBonus " + main + SpeedUtilities.getAirPotionBonus(profile) +
                "\n * " + second + "airComboBonus " + main + SpeedUtilities.getAirAttributePotionBonus(profile) +
                "\n * " + second + "velocityH " + main + velocityData.getTotalHorizontalVelocity() +
                "\n * " + second + "velocityV " + main + velocityData.getVelocityV() +
                "\n * " + second + "airTicks c|s " + main + clientAirTicks + '|' + movementData.getCustomAirTicks() +
                "\n * " + second + "maxJumpHeight " + main + MoveUtils.getJumpMotion(profile) +
                "\n * " + second + "isTeleporting " + main + profile.isExempt().isTeleports() +
                "\n * " + second + "elytraMomentumBonus " + main + movementData.elytraMomentum() +
                "\n * " + second + "dolphinGraceMomentumBonus " + main + movementData.getDolphinGraceBoost() +
                "\n * " + second + "airTickAdditions " + main + airLimitDebug +
                "\n * " + second + "difference " + main + (deltaXZ - expectedSpeed);
    }

    private boolean isVanillaJumpStart(double deltaY, double maxJumpHeight, int clientAirTicks) {
        return clientAirTicks == 1
                && deltaY > maxJumpHeight - 0.045D
                && deltaY <= maxJumpHeight + 1.0E-6D;
    }


    boolean isExemptGround(MovementData movementData) {
        if (exempt("cancelled", profile.shouldCancel())) return true;
        if (exempt("onBoat", movementData.isOnBoat())) return true;
        if (exempt("teleports", movementData.getSinceTeleportTicks() <= 2 || profile.isExempt().isTeleports())) return true;
        if (exempt("notRespawned", !profile.isExempt().isRespawned())) return true;
        if (exempt("vehicle", profile.isExempt().vehicle() || profile.getVehicleData().getSinceVehicleTicks() <= 2)) return true;
        if (exempt("recentGhostBlock", movementData.getSinceOnGhostBlock() < 2 + (profile.getConnectionData().getClientTickTrans() * 2))) return true;

        if (exempt("gliding", movementData.isGlidingOrRecentlyGlided(30))) return true;
        if (exempt("soulSoil", movementData.getMovingOnSoulBlocksTicks() > 0)) return true;

        return false;
    }

    boolean isExemptAir(MovementData movementData) {
        if (exempt("cancelled", profile.shouldCancel())) return true;
        if (exempt("teleports", movementData.getSinceTeleportTicks() <= 2 || profile.isExempt().isTeleports())) return true;
        if (exempt("notRespawned", !profile.isExempt().isRespawned())) return true;
        if (exempt("vehicle", profile.isExempt().vehicle() || profile.getVehicleData().getSinceVehicleTicks() <= 2)) return true;
        return false;
    }
}
