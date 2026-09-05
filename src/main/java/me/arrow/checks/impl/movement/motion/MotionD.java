package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.customutils.OtherUtility;

// i think i don't need to explain this, very simple check

public class MotionD extends Check {

    public MotionD(Profile profile) {
        super(profile, CheckType.MOTION, "D", "Checks for invalid vertical motion");
    }

    private double buffer;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            long profiler = Profiler.start();

            try {

                MovementData movementData = profile.getMovementData();

                ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

                if (world.shouldExemptMovementChecks()
                        || world.physicsMismatch
                        || world.onGhostBlock
                        || world.underGhostBlock
                        || world.insideGhostBlock) {
                    return;
                }

                double deltaY = movementData.getDeltaY();
                double lastDeltaY = movementData.getLastDeltaY();

                if (profile.shouldCancel()
                        || profile.isBouncingOnSlime()
                        || movementData.isOnSlime()
                        || movementData.getMovingUnderblockTicks() > 0
                        || movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4)
                        || movementData.isUnderblock()
                        || movementData.isNearBed()
                        || movementData.isOnBoat()
                        || movementData.isNearWebs()
                        || movementData.isNearBoat()
                        || movementData.isNearWater()
                        || movementData.isNearLava()
                        || movementData.isNearClimbable()
                        || movementData.getSinceGlidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 4)
                        || profile.getBlockProcessor().isCancelledBlockPlaceAbove(12 + (profile.getConnectionData().getClientTickTrans() * 2))
                        || (profile.getVelocityData().isTakingVelocity() && profile.getVelocityData().getVelocityTicks() < 10)
                        || profile.getPlayer().isInsideVehicle()) {
                    buffer = 0;
                    return;
                }

                if (movementData.getSincePredictUpwardsTicks() < 10) {
                    buffer -= Math.min(buffer, 0.5);
                    return;
                }

                if (deltaY != 0)
                    verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * lastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                            + "\n * underBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());


                boolean invalid = deltaY == -lastDeltaY && deltaY != 0.0;

                if (invalid) {
                    if (++buffer > 3) {
                        fail("Impossible Vertical Motion",
                                "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                                        + "\nunderBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
                    }

                    verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * lastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                            + "\n * underBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
                } else {
                    buffer -= Math.min(buffer, 0.05);
                }
            } finally {
                Profiler.stop("Motion E", profiler);
            }
        }
    }
}
