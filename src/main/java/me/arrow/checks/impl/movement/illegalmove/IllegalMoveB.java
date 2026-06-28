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
            ActionData actionData = profile.getActionData();

            double deltaXZ = movementData.getDeltaXZ();
            double deltaX = movementData.getDeltaX();
            double deltaZ = movementData.getDeltaZ();
            double lastDeltaX = movementData.getLastDeltaX();
            double lastDeltaZ = movementData.getLastDeltaZ();
            double blockFriction = movementData.getFrictionFactor();
            boolean sprinting = actionData.isSprinting();
            int clientAirTicks = movementData.getClientAirTicks();

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
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - shouldCancel()");
            return;
        }

        if (profile.isExempt().isTeleports()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - teleport");
            return;
        }

        if (!profile.isExempt().isRespawned()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - not respawned");
            return;
        }

        if (profile.getPlayer().isDead()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - player dead");
            return;
        }

        if (profile.isExempt().vehicle()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - in vehicle");
            return;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - vehicle");
            return;
        }

        if (movementData.getSinceRiptidingTicks() < 15) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - riptiding");
            strafeBuffer = 0;
            return;
        }

        if (movementData.getSinceGlidingTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - just gliding");
            return;
        }

        if (movementData.isOnBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - on boat");
            return;
        }

        if (movementData.isNearBoat()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near boat");
            return;
        }

        if (movementData.isNearWall()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near wall");
            return;
        }

        if (movementData.isNearWater()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near water");
            return;
        }

        if (movementData.isNearLava()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near lava");
            return;
        }

        if (movementData.isNearClimbable() || movementData.isClimb()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near climbable");
            return;
        }

        if (movementData.isNearWebs()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): Exempt - near webs");
            return;
        }

        if (profile.getRodData().isRodExempt()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): is Exempting (reelingIn)");
            return;
        }

        if (movementData.isNearGhast()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): is Exempting (Ghast)");
            return;
        }

        if (movementData.getSincePowderSnowTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove B (Strafe): is Exempting (Powder Snow)");
            return;
        }

        float movingSlimeTicks = movementData.getMovingOnSlimeTicks();
        float movingIceTicks = movementData.getMovingOnIceTicks();
        //temporeraly exempt ice until I fix it
        if (movingIceTicks > 0 || movingSlimeTicks > 0) return;

        int extraTicks = getExtraTicks();

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

        int ghostLiquidWebTicks = Math.min(
                profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
        );

        if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
            limit += 0.2;
        }

        if (profile.getVelocityData().isTakingVelocity() ) airticklimit += extraTicks;

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
        }
        else {
            strafeBuffer -= Math.min(strafeBuffer, resetRateStrafeBuffer);
        }
        this.wasSprinting = sprinting;
    }

    private int getExtraTicks() {
        double horizontal =  profile.getVelocityData().getTotalHorizontalVelocity();
        double vertical = profile.getVelocityData().getTotalVerticalVelocity();

        double velMag = horizontal + vertical;

        double baseTicksVel = 2;
        double baseVelocity = 0.000001;
        double scale = 13;

        double extraFromVel = velMag <= baseVelocity ? 0 : baseTicksVel + (scale * (velMag - baseVelocity));
        return (int) Math.ceil(extraFromVel);
    }
}

