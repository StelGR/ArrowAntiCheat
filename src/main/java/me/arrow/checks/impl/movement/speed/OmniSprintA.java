package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.desync.DesyncType;
import me.arrow.utils.customutils.Math.MathUtil;

// Omni sprint check, alot of the examples i saw on open source anticheats, have alot of issues with the direction, and it seems like this isn't immune either
// i tried to get gpt to help me make a proper direction check, and it did a decent job, but world desync still falses this alot
// the buffer is there for that very reason, so yeah, if you can improve the direction getter, then it would be perfect

@Experimental
public class OmniSprintA extends Check {

    private double buffer;

    public OmniSprintA(Profile profile) {
        super(profile, CheckType.OMNISPRINT, "A", "Checks for omnisprint");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!(event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION))) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();

        boolean exempt =
                movementData.isInsideWater()
                        || movementData.isNearWebs()
                        || movementData.isNearClimbable()
                        || movementData.isOnSlime()
                        || movementData.isOnSoulSand()
                        || movementData.isOnHoney()
                        || movementData.isNearBoat()
                        || movementData.isOnBoat()
                        || profile.getVelocityData().isTakingVelocity()
                        || profile.getExempt().isVehicle()
                        || profile.getExempt().isTeleports()
                        || profile.shouldCancel()
                        || (profile.getVehicleData().getSinceVehicleTicks() > 0 && profile.getVehicleData().getSinceVehicleTicks() < 5 + profile.getConnectionData().getClientTickTrans() );

        if (exempt) {
            buffer = Math.max(0.0, buffer - 0.5);
            return;
        }


        double deltaXZ = movementData.getDeltaXZ();

        if (deltaXZ < 0.12) {
            buffer = Math.max(0.0, buffer - 0.5);
        }

        boolean sprinting = actionData.isSprinting();
        if (!sprinting && !actionData.isLastSprinting()) {
            buffer = Math.max(0.0, buffer - 0.5);
        }


        //float moveAngle = MathUtil.getMoveAngle(movementData.getLastLocation(), movementData.getLocation());

        double moveLimit = MathUtil.getBaseSpeed_2(profile.getPlayer());

        //float maxAllowed = 90.0F;


        boolean invalid = (sprinting || deltaXZ > moveLimit) && movementData.getRelative() == MovementPredictionUtil.RelativeMove.BACKWARD
               //
                ;

        verbose(this.getClass().getSimpleName(), buffer, 8,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Ground)\n * direction " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getRelative() +
                        "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ +
                        "\nmaxML " + MsgType.MAIN_THEME_COLOR.getMessage() +  moveLimit +
                        "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting +
                        "\nlastSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + actionData.isLastSprinting());

        if (invalid) {
            if (++buffer > 15.0) {
                fail("Sprinting in impossible direction",
                        "direction " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getRelative() +
                                "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ +
                                "\nmaxML " + MsgType.MAIN_THEME_COLOR.getMessage() +  moveLimit +
                                "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting +
                                "\nlastSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + actionData.isLastSprinting());
                buffer = 9;
                actionData.getDesync().fix(DesyncType.SPRINTING);
            }

        } else {
            buffer = Math.max(0.0, buffer - 0.125);
        }
    }
}
