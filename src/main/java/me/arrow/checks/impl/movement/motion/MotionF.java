package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
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

            if (profile.shouldCancel()
                    || profile.getPlayer().isDead()
                    || !profile.isExempt().isRespawned()
                    || profile.isExempt().isTeleports()
                    || profile.isBouncingOnSlime()
                    || movementData.isNearWater()
                    || velocityData.getTotalVerticalVelocity() > 0
            ) return;

            if (profile.getPlayer().isInsideVehicle()) return;

            double deltaY = movementData.getDeltaY();
            double lastDeltaY = movementData.getLastDeltaY();
            int clientAirTicks = movementData.getClientAirTicks();
            int serverAirTicks = movementData.getCustomAirTicks();


            boolean exempt = profile.isBouncingOnSlime()
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearBubble()
                    || movementData.getSincePowderSnowTicks() < 5
                    || movementData.getLadderTicks() < 10
                    || movementData.isOnGround()
                    || movementData.isLastOnGround()
                    || (profile.getMovementData().getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0)
                    || clientAirTicks < 6;

            double expected = profile.isBedrockPlayer() ? 0.21 : 0.11760000228885;

            if (!exempt) {
                if (deltaY > expected
                        && movementData.isClimb()) {
                    fail("Fast Ladder?", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                            + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                            + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                            + "\nladderTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLadderTicks());
                }
            }
        } finally {
            Profiler.stop("Motion F", profiler);
        }
    }
}
