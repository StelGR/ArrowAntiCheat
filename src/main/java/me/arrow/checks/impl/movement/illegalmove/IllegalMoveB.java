package me.arrow.checks.impl.movement.illegalmove;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.Arrow;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.OtherUtility;

import static me.arrow.utils.ChatUtils.debugExempt;

public class IllegalMoveB extends Check {
    public IllegalMoveB(Profile profile) {
        super(profile, CheckType.ILLEGALMOVE, "B", "Checks if the player is strafing correctly");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

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


    double resetRateStrafeBuffer = 0.025;

    public void calculateStrafe(MovementData movementData, double deltaXZ, double deltaX, double deltaZ, double lastDeltaX, double lastDeltaZ, boolean sprinting, double blockFriction, int airTicks) {
        long profiler = Profiler.start();

        try {
            if (isExempt(movementData)) return;

            double limit = 0.25;

            float movingSlimeTicks = movementData.getMovingOnSlimeTicks();
            float movingIceTicks = movementData.getMovingOnIceTicks();

            //temporeraly exempt ice until I fix it
            if (exempt("movingOnIce", movingIceTicks > 0)) return;
            if (exempt("movingOnSlime", movingSlimeTicks > 0)) return;

            //int extraTicks = getExtraTicks();

            int clientTickTrans = profile.getConnectionData().getClientTickTrans();
            int transPing = profile.getConnectionData().getTransPing();
            boolean blockInHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType().isBlock();
            boolean blockInOffHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType().isBlock();
            boolean holdingBlock = blockInHand || blockInOffHand;

            int blockPlaceLimit = clientTickTrans == 0 ? 3 : Math.min(3 + transPing / clientTickTrans, 20);
            boolean recentlyPlaced = profile.getActionData().hasRecentConfirmedUnderPlace(blockPlaceLimit);

            final double predictedX = lastDeltaX * 0.9100000262260437;
            final double predictedZ = lastDeltaZ * 0.9100000262260437;
            final double differenceX = deltaX - predictedX;
            final double differenceZ = deltaZ - predictedZ;
            double difference = Math.hypot(differenceX, differenceZ);
            double predictedXZ = Math.hypot(predictedX, predictedZ);
            difference /= (this.wasSprinting ? 1.3 : 1.0);
            difference -= sprinting ? 0.02589 : 0.02;

            double airticklimit = movementData.getSinceCollideTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2) ? 6 : ((recentlyPlaced && holdingBlock) ? 7 : 3);

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 4)) {
                limit += 0.2;
            }

            if (exempt("velocity", profile.getVelocityData().isTakingVelocity())) return;

            final boolean invalid = difference > 0.00747 && deltaXZ > limit && airTicks > airticklimit;

            String data = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Strafe)\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                    + "\n * limit " + MsgType.MAIN_THEME_COLOR.getMessage() + limit
                    + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                    + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                    + "\n * airTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airticklimit
                    + "\n * predictedX " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedX
                    + "\n * predictedZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedZ
                    + "\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedXZ
                    + "\n * difference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                    + "\n * sprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting
                    + "\n * clientGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientGroundTicks();
            if (difference != 0 && deltaXZ != 0) verbose(this.getClass().getSimpleName(), deltaXZ, limit, data);

            if (invalid) {
                if (++strafeBuffer > maxStrafeBuffer) {
                    fail("Improbable air strafe",
                            "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                    + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                    + "\nairTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airticklimit
                                    + "\npredicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedXZ
                                    + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference
                                    + "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting);

                    strafeBuffer = Math.max(7, strafeBuffer);
                }
            } else {
                strafeBuffer -= Math.min(strafeBuffer, resetRateStrafeBuffer);
            }
            this.wasSprinting = sprinting;
        } finally {
            Profiler.stop("IllegalMove B", profiler);
        }
    }

    boolean isExempt(MovementData movementData) {

        if (exempt("cancelled", profile.shouldCancel())) return true;
        if (exempt("teleports", movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4))) return true;
        if (exempt("notRespawned", !profile.isExempt().isRespawned())) return true;
        if (exempt("dead", profile.getPlayer().isDead())) return true;
        if (exempt("vehicle", profile.isExempt().vehicle())) return true;
        if (exempt("recentVehicle", profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2))) return true;

        if (exempt("riptiding", movementData.getSinceRiptidingTicks() < 15)) {
            strafeBuffer = 0;
            return true;
        }

        if (exempt("gliding", movementData.isGlidingOrRecentlyGlided(30))) return true;
        if (exempt("onBoat", movementData.isOnBoat())) return true;
        if (exempt("nearBoat", movementData.isNearBoat())) return true;
        if (exempt("nearWall", movementData.isNearWall())) return true;
        if (exempt("nearWater", movementData.isNearWater())) return true;
        if (exempt("nearLava", movementData.isNearLava())) return true;
        if (exempt("nearClimbable", movementData.isNearClimbable())) return true;
        if (exempt("climbing", movementData.isClimb())) return true;
        if (exempt("nearWebs", movementData.isNearWebs())) return true;
        if (exempt("reelingIn", profile.getRodData().isRodExempt())) return true;
        if (exempt("nearGhast", movementData.isNearGhast())) return true;
        if (exempt("powderSnow", movementData.getSincePowderSnowTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2))) return true;
        return false;
    }
}

