package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.util.Vector;

// this is probably the most powerful check here, it accounts for all jump height speeds, and bedrock can work if you ignore issues with velocity transactions
// the check accounts for all jump height possible speeds, depth strider, head hitters, trident riptide, soul speed, attribute and speed potions up to level 10,
// it is not perfect, it simply just accounts for alot of scenarios, the goal though is to slowly make it more accurate for all scenarios it doesn't account for,
// such is ground speed, and some others, the ground speed limit isn't as good as the air one
// strafe also does the job but needs improvements, Speed B does most of the job for strafes

public class SpeedA extends Check {
    public SpeedA(Profile profile) {
        super(profile, CheckType.SPEED, "A", "Checks if the player is following vanilla speed");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            MovementData movementData = profile.getMovementData();
            VelocityData velocityData = profile.getVelocityData();
            ActionData actionData = profile.getActionData();

            double deltaXZ = movementData.getDeltaXZ();
            double deltaY = movementData.getDeltaY();
            double deltaX = movementData.getDeltaX();
            double deltaZ = movementData.getDeltaZ();
            double lastDeltaX = movementData.getLastDeltaX();
            double lastDeltaZ = movementData.getLastDeltaZ();
            double velocityH = velocityData.getTotalHorizontalVelocity();
            double blockFriction = movementData.getFrictionFactor();

            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();
            boolean sprinting = actionData.isSprinting();
            int clientAirTicks = movementData.getClientAirTicks();
            int serverGroundTicks = movementData.getServerGroundTicks();
            int serverAirTicks = movementData.getCustomAirTicks();
            double movingSlimeTicks = movementData.getMovingOnSlimeTicks();
            int movingHoneyTicks = movementData.getMovingOnHoneyTicks();
            float movingIceTicks = movementData.getMovingOnIceTicks();
            float underBlockMoveTime = movementData.getMovingUnderblockTicks();


            calculateAir(movementData, movingIceTicks, movingSlimeTicks, movingHoneyTicks, underBlockMoveTime, velocityH, deltaY, deltaXZ, clientAirTicks, clientGround, serverGround);

            calculateGround(movementData, velocityData, deltaXZ, deltaY, movingIceTicks, serverGround, serverGroundTicks, clientAirTicks, blockFriction);

            calculateStrafe(movementData, deltaXZ, deltaX, deltaZ, lastDeltaX, lastDeltaZ, sprinting, blockFriction, clientAirTicks);
        }
    }


    double groundBuffer;

    private static final double DEFAULT_BASE_PER_TICK = 0.2778085125D;

    private static final double SPRINT_BASE = 0.1478085125D;
    private static final double NO_SPRINT_BASE = 0.1778085125D;
    private static final double GROUND_ICE_INCREMENT_PER_TICK = 0.01;
    private static final double GROUND_MAX_ICE_SPEED_BOOST = 4.5;
    private static final double DIAGONAL_TOLERANCE = 1.01125;

    public void calculateGround(MovementData movementData, VelocityData velocityData, double deltaXZ, double deltaY, float movingIceTicks, boolean serverGround, int serverGroundTicks, int airTicks, double blockFriction) {
        if (profile.shouldCancel()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - shouldCancel");
            return;
        }

        if (movementData.isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - isOnBoat");
            return;
        }

        if (profile.isExempt().isTeleports()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - teleports");
            return;
        }

        if (movementData.isIntersecting()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - intersecting");
            return;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - notRespawned");
            return;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - vehicle");
            return;
        }

        if (profile.getExempt().isReelingIn()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - reelingIn");
            return;
        }

        if (movementData.getSinceRiptidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - isRiptiding");
            groundBuffer = 0;
            return;
        }

        if (movementData.getSinceOnGhostBlock() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - recentlyOnGhostBlock()");
            return;
        }

        if (movementData.isNearBuggyBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - nearBuggyBlock");
            return;
        }

        if (movementData.isNearBed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - nearBed");
            return;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - recentlyInVehicle");
            return;
        }

        if (movementData.isUnderblock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - underblock");
            return;
        }

        if (movementData.getSinceGlidingTicks() < 10) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - recentlyGliding");
            return;
        }

        if (movementData.getMovingOnSoulBlocksTicks() > 0) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - soulsoil");
            return;
        }

//        if (profile.getBlockData().isSoulsand()) {
//            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - soulsand");
//            return;
//        }

        if (movementData.getMovingOnSlimeTicks() > 0) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): Exempt - collideSlime");
            return;
        }

        if (movementData.isMovingUp() || movementData.getSinceMovingUpTicks() < 10) {
            groundBuffer = 0;
            return;
        }


        //double extraFromBlocks = (profile.getBlockData().slabTicks > 0 || profile.getBlockData().stairTicks > 0) ? 0.475D : 0.0D;

        double predicted = deltaXZ * blockFriction;

        double iceBoost = SpeedUtilities.getIceSpeedBoost(GROUND_ICE_INCREMENT_PER_TICK, movingIceTicks, GROUND_MAX_ICE_SPEED_BOOST);

        double groundLimit = SpeedUtilities.computeGroundLimit(profile, velocityData, blockFriction, iceBoost, SPRINT_BASE, NO_SPRINT_BASE, DEFAULT_BASE_PER_TICK);

        double frictionMultiplier = SpeedUtilities.friction(blockFriction);
        double allowedLimit = groundLimit * DIAGONAL_TOLERANCE * frictionMultiplier;

        allowedLimit += 0.004;
        double depthStriderBoost = SpeedUtilities.getDepthStriderBoost(profile);
        if (movementData.isInsideWater()) allowedLimit += depthStriderBoost; // apply always if in water

        allowedLimit += movementData.getDolphinGraceBoost();

        if (movementData.getSinceCollideTicks() < 12 + profile.getConnectionData().getClientTickTrans() ) {
            allowedLimit += 0.12;
        }

        allowedLimit += movementData.elytraMomentum();
        allowedLimit += movementData.getDolphinGraceBoost();

        if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Ground): is Exempting (ghostblock liquid/web)");
            allowedLimit += 0.2;
        }



        if (serverGround && deltaXZ != 0) {
            verbose(this.getClass().getSimpleName(), predicted, allowedLimit,
                    MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Ground)\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predicted
                            + "\n * expected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedLimit
                            + "\n * expected deltaXZ (No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + (allowedLimit / Math.max(0.0001, frictionMultiplier))
                            + "\n * expected deltaXZ (No Diag/No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                            + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                            + "\n * frictionMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + frictionMultiplier
                            + "\n * movementSpeedBase " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getMovementSpeedAttribute(profile)
                            + "\n * movementSpeedEffective " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getEffectiveMovementSpeed(profile)
                            + "\n * movementSpeedScale " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getEffectiveMovementScale(profile)
                            + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                            + "\n * isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                            + "\n * attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                            + "\n * potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                            + "\n * comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile)
                            + "\n * serverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGroundTicks
                            + "\n * iceSpeedBoost " + MsgType.MAIN_THEME_COLOR.getMessage() + iceBoost);
        }


        double difference = predicted - allowedLimit;
        double bufferAmount = difference > 0.5 ? 0 : 6;
        double serverGroundMaxTicks = 8;
//
//        if (difference > 0.9D + profile.getVelocityData().getTotalHorizontalVelocity()) {
//            serverGroundMaxTicks = 1;
//            bufferAmount = 0;
//        }

        if (serverGroundTicks > serverGroundMaxTicks && predicted > allowedLimit) {

            if (++groundBuffer > bufferAmount) {
                fail("Speed limit exceeded (Ground)",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predicted
                                + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedLimit
                                + "\nblockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\nserverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGroundTicks);
                groundBuffer = Math.max(bufferAmount + 2, groundBuffer);
            }
        } else groundBuffer = Math.max(0, groundBuffer - 0.01);

    }

    double airBuffer;
    final double AIR_BASE_SPEED = 0.35301212D;

    final double SPRINT_BASE_SPEED = 0.22301212D;
    final double NO_SPRINT_BASE_SPEED = 0.25301212D;

    final double AIR_SLIME_INCREMENT_PER_TICK = 0.12;
    final double AIR_ICE_INCREMENT_PER_TICK = 0.1225;
    final double AIR_ICE_INCREMENT_PER_TICK_SMALLER = 0.0625;

    final double AIR_UNDER_BLOCK_SPEED_DECREMENT = 0.19;
    final double AIR_HONEY_INCREMENT_PER_TICK = 0.0021;

    final double AIR_MAX_SLIME_SPEED_BOOST = 2.4;
    final double AIR_MAX_ICE_SPEED_BOOST = 6.25;
    final double AIR_MAX_UNDER_BLOCK_SPEED_BOOST = 0.6;
    final double AIR_MAX_HONEY_SPEED_BOOST = 1.1;

    public void calculateAir(MovementData movementData, float movingIceTicks, double movingSlimeTicks, int movingHoneyTicks, float underBlockMoveTime, double velocityH, double deltaY, double deltaXZ, int clientAirTicks, boolean clientGround, boolean serverGround) {

        if (profile.shouldCancel()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - shouldCancel");
            return;
        }

        if (profile.isExempt().isTeleports()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - teleporting");
            return;
        }


        if (profile.getMovementData().isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - isOnBoat");
            return;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - notRespawned");
            return;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - vehicle");
            return;
        }

//        if (profile.getMovementData().getSinceGlitchedInsideBlockTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
//            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - glitchedInsideBlock");
//            return;
//        }

        if (profile.getExempt().isReelingIn()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - reelingIn");
            return;
        }

        if (profile.getMovementData().isNearBuggyBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - nearBuggyBlock");
            return;
        }

        if (profile.getMovementData().isNearBed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - nearBed");
            return;
        }

        if (profile.getMovementData().getSinceGlidingTicks() < 5 + profile.getConnectionData().getClientTickTrans() ) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - recentlyInVehicle");
            return;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 5 + profile.getConnectionData().getClientTickTrans() ) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - recentlyInVehicle");
            return;
        }

        if (movementData.isMovingUp()) {
            airBuffer = 0;
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - movingUp");
            return;
        }

        int speedLevel = SpeedUtilities.getSpeedPotionLevel(profile);

        double air_iceSpeedBoost;
        if (movingIceTicks < 15) air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);
        else air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK_SMALLER * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);
        double air_slimeSpeedBoost = Math.min(AIR_SLIME_INCREMENT_PER_TICK * movingSlimeTicks, AIR_MAX_SLIME_SPEED_BOOST);
        double air_underBlockSpeedReduction = Math.min(AIR_UNDER_BLOCK_SPEED_DECREMENT * underBlockMoveTime, AIR_MAX_UNDER_BLOCK_SPEED_BOOST);

        double air_honeySpeedBoost = Math.min(AIR_HONEY_INCREMENT_PER_TICK * movingHoneyTicks, AIR_MAX_HONEY_SPEED_BOOST);

        double attr = SpeedUtilities.getMovementSpeedAttribute(profile);
        double effectiveAttr = SpeedUtilities.getEffectiveMovementSpeed(profile);
        double movementScale = SpeedUtilities.getEffectiveMovementScale(profile);

        double expectedSpeed = SpeedUtilities.computeAirLimit(profile, AIR_BASE_SPEED);

        int soulSpeedLevel = SpeedUtilities.getSoulSpeedLevel(profile);

        if (soulSpeedLevel > 0 && movementData.getMovingOnSoulBlocksTicks() > 0) {
            expectedSpeed += soulSpeedLevel * 0.05D;
        }

        expectedSpeed += expectedSpeed * (air_honeySpeedBoost + air_iceSpeedBoost + air_slimeSpeedBoost + air_underBlockSpeedReduction);
        if (movementData.getSinceSpeedPotionEffectTicks() < 15) expectedSpeed += 0.05;
        VelocityData vd = profile.getVelocityData();

        double explosionH = 0.0;

        Vector expFvc = vd.getExplosionKnockback();
        if (expFvc != null) {
            explosionH = Math.hypot(expFvc.getX(), expFvc.getZ());
        }

        double kbComponent = Math.max(vd.getVelocityH(), 0.0);

        double explosionComponent = 0.0;
        if (explosionH > 0.0) {
            explosionComponent = (explosionH * 6) + 0.2D;
            //explosionComponent += 0.08;
        }

        expectedSpeed += kbComponent + explosionComponent;

//        boolean inWater = movementData.isInsideWater() || movementData.isBottomOfWater() || movementData.isNearWater();

        double depthStriderBoost = SpeedUtilities.getDepthStriderBoost(profile);
        if (movementData.isInsideWater()) expectedSpeed += depthStriderBoost;





        boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() < 15 + profile.getConnectionData().getClientTickTrans();

        if (currentlyRiptiding) {
            double riptideCap = 3.75 + (1.5 * profile.getPredictionData().riptideLevel());
            expectedSpeed += riptideCap;
        }

        double maxJumpHeight = MoveUtils.getJumpMotion(profile);

        //if (profile.getPotionData().isHasJump()) maxJumpHeight += (profile.getPotionData().getJumpAmplifier() * 0.1F);

        if (deltaY > 0 && deltaY <= maxJumpHeight && clientAirTicks == 1) {
            expectedSpeed = deltaXZ / SpeedUtilities.getAfterJumpSpeed(profile);
        }

        double expected = -0.0784000015258789D;
        if (Math.abs(deltaY - expected) < 1E-6 && clientAirTicks == 1) {
            expectedSpeed = deltaXZ / SpeedUtilities.getAfterJumpSpeed(profile);
        }

        double expected2 = 0.33319999363422426D;

        if (clientAirTicks == 2 && Math.abs(deltaY - expected2) < 1E-6) {
            expectedSpeed += speedLevel > 0 ? (0.0096 + (0.008D * speedLevel)) : 0.0096;
            expectedSpeed += movementData.getSincePredictUpwardsTicks() <= 7 ? 0.01225 : 0;
        }

        double expected3 = 0.24813599859094637D;

        if (clientAirTicks == 3 && Math.abs(deltaY - expected3) < 1E-6) {
            expectedSpeed += speedLevel > 0 ? (0.0075 + (0.008D * speedLevel)) : 0.0075;
            expectedSpeed += movementData.getSincePredictUpwardsTicks() <= 7 ? 0.0095 : 0;
        }


        if (movementData.getSinceMovingOnIceTicks() < 20 || movementData.getSinceMovingOnSlimeTicks() < 20) {
            expectedSpeed += 0.01D;
        }

        if (movementData.getSinceCollideTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2) ) {
            expectedSpeed += 0.20;
        }

        if (movementData.getSinceMovingOnIceTicks() > 0 && movementData.getSinceMovingOnIceTicks() < 120) {
            expectedSpeed += 0.003;
        }

        expectedSpeed += movementData.elytraMomentum();
        expectedSpeed += movementData.getDolphinGraceBoost();

        if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): is Exempting (ghostblock liquid/web)");
            expectedSpeed += 0.3;
        }

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
                + "* iceMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + AIR_ICE_INCREMENT_PER_TICK + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* maxIceSpeedBoost " + MsgType.MAIN_THEME_COLOR.getMessage() + AIR_MAX_ICE_SPEED_BOOST + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* slimeMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + AIR_SLIME_INCREMENT_PER_TICK + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* maxSlimeSpeedBoost " + MsgType.MAIN_THEME_COLOR.getMessage() + AIR_MAX_SLIME_SPEED_BOOST + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* headHitMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + AIR_UNDER_BLOCK_SPEED_DECREMENT + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributeBonus(profile) + "\n"
                + "* potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirPotionBonus(profile) + "\n"
                + "* comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributePotionBonus(profile) + "\n"
                + "* velocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* velocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + vd.getVelocityV() + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
                + "* airticks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks + "\n" + MsgType.SECOND_THEME_COLOR.getMessage()
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

        if (deltaXZ > expectedSpeed
                && !serverGround) {

            double difference = deltaXZ - expectedSpeed;
            double bufferAmount = 4;

            if (difference > 0.7) bufferAmount = 0;
            if (++airBuffer > bufferAmount) {
                fail("Speed limit exceeded (Air)",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedSpeed
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                                + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                + "\nserverAirTicks  " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                                + "\nisSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting());
                airBuffer = Math.max(8, airBuffer);
            }

            //verbose(this.getClass().getSimpleName(), airBuffer, 6, format);
        } else airBuffer = Math.max(0, airBuffer - 0.005D);
    }

    private boolean wasSprinting;
    private double strafeBuffer = 0;
    double maxStrafeBuffer = 4;

    double limit = 0.25;
    double resetRateStrafeBuffer = 0.025;

    public void calculateStrafe(MovementData movementData, double deltaXZ, double deltaX, double deltaZ, double lastDeltaX, double lastDeltaZ, boolean sprinting, double blockFriction, int airTicks) {

        if (profile.shouldCancel()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - shouldCancel()");
            return;
        }

        if (profile.isExempt().isTeleports()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - teleport");
            return;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - not respawned");
            return;
        }

        if (profile.getPlayer().isDead()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - player dead");
            return;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - in vehicle");
            return;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 8 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - just left vehicle");
            return;
        }

        if (movementData.getSinceRiptidingTicks() < 15) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - riptiding");
            strafeBuffer = 0;
            return;
        }

        if (movementData.getSinceGlidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - just gliding");
            return;
        }

        if (movementData.isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - on boat");
            return;
        }

        if (movementData.isNearBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near boat");
            return;
        }

        if (movementData.isNearWall()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near wall");
            return;
        }

//        if (profile.getLastBlockPlaceCancelTimer().hasNotPassed(3 + profile.getConnectionData().getClientTickTrans())) {
//            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - recent block place");
//            return;
//        }

        if (movementData.isNearWater()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near water");
            return;
        }

        if (movementData.isNearLava()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near lava");
            return;
        }

        if (movementData.isNearClimbable() || movementData.isClimb()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near climbable");
            return;
        }

        if (movementData.isNearWebs()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): Exempt - near webs");
            return;
        }



        float movingSlimeTicks = movementData.getMovingOnSlimeTicks();
        float movingIceTicks = movementData.getMovingOnIceTicks();
        //temporeraly exempt ice until I fix it
        if (movingIceTicks > 0 || movingSlimeTicks > 0) return;

        if (profile.getRodData().isRodExempt()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): is Exempting (reelingIn)");
            return;
        }

        if (movementData.getSincePowderSnowTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): is Exempting (Powder Snow)");
            return;
        }

        //limit += movementData.elytraMomentum();

        //limit += profile.getVelocityData().getTotalHorizontalVelocity() * 1.25;


        int extraTicks = (int) (profile.getVelocityData().getTotalHorizontalVelocity() * 125);



        int clientTickTrans = profile.getConnectionData().getClientTickTrans();
        int transPing = profile.getConnectionData().getTransPing();
        boolean blockInHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType().isBlock();
        boolean blockInOffHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType().isBlock();
        boolean holdingBlock = blockInHand || blockInOffHand;

        int blockPlaceLimit = clientTickTrans == 0 ? 3 : Math.min(3 + transPing / clientTickTrans, 20);
        boolean recentlyPlaced = profile.getLastBlockPlaceTimer().hasNotPassed(blockPlaceLimit);


        final double predictedX = lastDeltaX * 0.9100000262260437;
        final double predictedZ = lastDeltaZ * 0.9100000262260437;
        final double differenceX = deltaX - predictedX;
        final double differenceZ = deltaZ - predictedZ;
        double difference = Math.hypot(differenceX, differenceZ);
        double predictedXZ = Math.hypot(predictedX, predictedZ);
        difference /= (this.wasSprinting ? 1.3 : 1.0);
        difference -= sprinting ? 0.02589 : 0.02;

        double airticklimit = movementData.getSinceCollideTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2) ? 15 : ((recentlyPlaced && holdingBlock) ? 7 : 3);

        if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 15 + profile.getConnectionData().getClientTickTrans()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Strafe): is Exempting (ghostblock liquid/web)");
            airticklimit += 10;
        }

        if (profile.getVelocityData().isTakingVelocity() ) airticklimit +=  (extraTicks + 4);

//        if (deltaXZ > 0.9D + profile.getVelocityData().getTotalHorizontalVelocity()) {
//            airticklimit = 1;
//            maxStrafeBuffer = 0;
//        }


        final boolean invalid = difference > 0.00747 && deltaXZ > limit && airTicks > airticklimit ;




        String data = MsgType.MAIN_THEME_COLOR.getMessage()+"* Verbose (Strafe)\n * deltaXZ "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                + "\n * limit "+MsgType.MAIN_THEME_COLOR.getMessage() + limit
                + "\n * blockFriction "+MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                + "\n * airTicks "+MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                + "\n * airTickLimit "+MsgType.MAIN_THEME_COLOR.getMessage() + airticklimit
                + "\n * predictedX "+MsgType.MAIN_THEME_COLOR.getMessage() + predictedX
                + "\n * predictedZ "+MsgType.MAIN_THEME_COLOR.getMessage() + predictedZ
                + "\n * predicted "+MsgType.MAIN_THEME_COLOR.getMessage() + predictedXZ
                + "\n * difference "+MsgType.MAIN_THEME_COLOR.getMessage() + difference
                + "\n * sprinting "+MsgType.MAIN_THEME_COLOR.getMessage() + sprinting
                + "\n * clientGroundTicks "+MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientGroundTicks();
        if (difference != 0 && deltaXZ != 0) verbose(this.getClass().getSimpleName(),deltaXZ, limit, data);


        if (invalid) {
            if (++strafeBuffer > maxStrafeBuffer) {
                fail("Speed limit exceeded (Strafe)",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                + "\nairTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airticklimit
                                + "\npredicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedXZ
                                + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                                + "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting);

                strafeBuffer = Math.max(maxStrafeBuffer + 2, strafeBuffer);
            }
            //verbose(this.getClass().getSimpleName(), strafeBuffer, maxStrafeBuffer, data);
        }
        else {
            strafeBuffer -= Math.min(strafeBuffer, resetRateStrafeBuffer);
        }
        this.wasSprinting = sprinting;
    }
}
