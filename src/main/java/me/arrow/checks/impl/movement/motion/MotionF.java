package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.customutils.OtherUtility;

// very basic fast ladder check, falses alot

// old MotionG

@Experimental
public class MotionF extends Check {

    public MotionF(Profile profile) {
        super(profile, CheckType.MOTION, "F", "Fast Ladder Check (Basic)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        long profiler = Profiler.start();

        try {
            MovementData movementData = profile.getMovementData();
            VelocityData velocityData = profile.getVelocityData();

            if (exempt("cancelled", profile.shouldCancel())) return;
            if (exempt("dead", profile.getPlayer().isDead())) return;
            if (exempt("notRespawned", !profile.isExempt().isRespawned())) return;
            if (exempt("teleports", movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4))) return;
            if (exempt("slimeBounce", profile.isBouncingOnSlime())) return;
            if (exempt("nearWater", movementData.isNearWater())) return;
            if (exempt("verticalVelocity", velocityData.getTotalVerticalVelocity() > 0)) return;
            if (exempt("gliding", movementData.isGlidingOrRecentlyGlided(30))) return;

            if (exempt("insideVehicle", profile.getPlayer().isInsideVehicle())) return;

            double deltaY = movementData.getDeltaY();
            double lastDeltaY = movementData.getLastDeltaY();
            int clientAirTicks = movementData.getClientAirTicks();
            int serverAirTicks = movementData.getCustomAirTicks();


            if (exempt("nearShulker", movementData.isNearShulker())) return;
            if (exempt("nearShulkerBox", movementData.isNearShulkerBox())) return;
            if (exempt("nearBubble", movementData.isNearBubble())) return;
            if (exempt("powderSnow", movementData.getSincePowderSnowTicks() < 5)) return;
            if (exempt("recentLadder", movementData.getLadderTicks() < 10)) return;
            if (exempt("onGround", movementData.isOnGround())) return;
            if (exempt("lastOnGround", movementData.isLastOnGround())) return;
            if (exempt("levitation", profile.getMovementData().getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0)) return;
            if (exempt("lowAirTicks", clientAirTicks < 6)) return;

            double expected = profile.isBedrockPlayer() ? 0.21 : 0.11760000228885;

            if (deltaY > expected
                    && movementData.isClimb()) {
                fail("Fast Ladder?", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                        + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                        + "\nladderTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLadderTicks());
            }
        } finally {
            Profiler.stop("Motion F", profiler);
        }
    }
}
