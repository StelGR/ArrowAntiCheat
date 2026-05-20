package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;

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
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            double deltaY = movementData.getDeltaY();
            double lastDeltaY = movementData.getLastDeltaY();

            if (profile.shouldCancel()
                    || profile.isBouncingOnSlime()
                    || movementData.isOnSlime()
                    || movementData.getMovingUnderblockTicks() > 0
                    || profile.isExempt().isTeleports()
                    || movementData.isUnderblock()
                    || movementData.isNearBed()
                    || movementData.isOnBoat()
                    || movementData.isNearWebs()
                    || movementData.isNearBoat()
                    || movementData.isNearWater()
                    || movementData.isNearLava()
                    || movementData.isNearClimbable()
                    || profile.getVelocityData().isTakingVelocity()
                    || profile.getPlayer().isInsideVehicle()) {
                return;
            }

            if (movementData.isMovingUp()) {
                buffer -= Math.min(buffer, 0.5);
                return;
            }

            if (deltaY != 0)
                verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n * lastDeltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                    + "\n * underBlock "+ MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());


            boolean invalid = deltaY == -lastDeltaY && deltaY != 0.0;

            if (invalid) {
                if (++buffer > 7) {
                    fail("Impossible Vertical Motion",
                            "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                            + "\nunderBlock " + MsgType.MAIN_THEME_COLOR.getMessage()+ movementData.isUnderblock());
                }

                verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose\n * deltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * lastDeltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                        + "\n * underBlock "+ MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
            } else {
                buffer -= Math.min(buffer, 0.125);
            }
        }
    }
}
