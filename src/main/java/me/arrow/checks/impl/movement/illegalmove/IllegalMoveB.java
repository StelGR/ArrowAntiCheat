package me.arrow.checks.impl.movement.illegalmove;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.customutils.OtherUtility;

public class IllegalMoveB extends Check {
    public IllegalMoveB(Profile profile) {
        super(profile, CheckType.ILLEGALMOVE, "B", "Checks if the player is strafing correctly");
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

            calculateStrafe(movementData, deltaXZ, deltaX, deltaZ, lastDeltaX, lastDeltaZ, sprinting, blockFriction, clientAirTicks);
        }
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

        if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed A (Air): Exempt - vehicle");
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

        double horizontal = Math.max(
                profile.getVelocityData().getTotalHorizontalVelocitySustain(),
                profile.getVelocityData().getStackedHorizontalVelocity()
        );
        double vertical = Math.max(
                profile.getVelocityData().getTotalVerticalVelocitySustain(),
                profile.getVelocityData().getStackedVerticalVelocity()
        );
        double velMag = horizontal + vertical;

        int extraTicks = (int) (velMag * 125);

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
                fail("Improbable air strafe",
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

