package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
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

                if (exempt("worldTrackerMovement", world.shouldExemptMovementChecks())) return;
                if (exempt("worldPhysicsMismatch", world.physicsMismatch)) return;
                if (exempt("worldOnGhostBlock", world.onGhostBlock)) return;
                if (exempt("worldUnderGhostBlock", world.underGhostBlock)) return;
                if (exempt("worldInsideGhostBlock", world.insideGhostBlock)) return;

                double deltaY = movementData.getDeltaY();
                double lastDeltaY = movementData.getLastDeltaY();

                if (exempt("cancelled", profile.shouldCancel())) { resetMotionBuffer(); return; }
                if (exempt("slimeBounce", profile.isBouncingOnSlime())) { resetMotionBuffer(); return; }
                if (exempt("onSlime", movementData.isOnSlime())) { resetMotionBuffer(); return; }
                if (exempt("movingUnderBlock", movementData.getMovingUnderblockTicks() > 0)) { resetMotionBuffer(); return; }
                if (exempt("teleports", movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4))) { resetMotionBuffer(); return; }
                if (exempt("underBlock", movementData.isUnderblock())) { resetMotionBuffer(); return; }
                if (exempt("nearBed", movementData.isNearBed())) { resetMotionBuffer(); return; }
                if (exempt("onBoat", movementData.isOnBoat())) { resetMotionBuffer(); return; }
                if (exempt("nearWebs", movementData.isNearWebs())) { resetMotionBuffer(); return; }
                if (exempt("nearBoat", movementData.isNearBoat())) { resetMotionBuffer(); return; }
                if (exempt("nearWater", movementData.isNearWater())) { resetMotionBuffer(); return; }
                if (exempt("nearLava", movementData.isNearLava())) { resetMotionBuffer(); return; }
                if (exempt("nearClimbable", movementData.isNearClimbable())) { resetMotionBuffer(); return; }
                if (exempt("gliding", movementData.isGlidingOrRecentlyGlided(30))) { resetMotionBuffer(); return; }
                if (exempt("cancelledBlockPlaceAbove", profile.getBlockProcessor().isCancelledBlockPlaceAbove(12 + (profile.getConnectionData().getClientTickTrans() * 2)))) { resetMotionBuffer(); return; }
                if (exempt("recentVelocity", profile.getVelocityData().isTakingVelocity() && profile.getVelocityData().getVelocityTicks() < 10)) { resetMotionBuffer(); return; }
                if (exempt("insideVehicle", profile.getPlayer().isInsideVehicle())) { resetMotionBuffer(); return; }

                if (exempt("predictUpwards", movementData.getSincePredictUpwardsTicks() < 10)) {
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

    private void resetMotionBuffer() {
        buffer = 0.0D;
    }
}
