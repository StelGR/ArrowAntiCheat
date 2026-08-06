package me.arrow.checks.impl.misc.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.Math.MathUtil;

// Marked for removal

public class InventoryA extends Check {

    public InventoryA(Profile profile) {
        super(profile, CheckType.INVENTORY, "A", "Checks if the player is moving while in inventory");
    }

    private double threshold, invTicks;

    @Override
    public void handle( PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.shouldCancel()
                    || profile.getVelocityData().getVelocityTicks() <= 9 + profile.getConnectionData().getClientTickTrans()
                    || profile.isExempt().isTeleports()) {
                threshold = invTicks = 0;
                return;
            }

            double max = invTicks <= 45 ? MathUtil.getBaseSpeed(profile.getPlayer()) : 0.01;

            MovementData movementData = profile.getMovementData();


            if (profile.getActionData().isInInventory() && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
                invTicks++;
                if (invTicks > 12) {
                    if (movementData.getDeltaXZ() > max &&
                            !(movementData.isNearWater()
                                    || movementData.isNearLava()
                                    || movementData.isNearWebs()
                                    || profile.getVelocityData().isTakingVelocity())) {
                        if (++threshold > 15) {
                            fail("Moving while inside the inventory", "invTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + invTicks);
                            profile.getPlayer().closeInventory();
                            profile.getActionData().setInInventory(false);
                        }
                    } else {
                        threshold -= Math.min(threshold, 0.5);
                    }
                } else {
                    threshold -= Math.min(threshold, 0.5);
                }
            } else {
                threshold = invTicks = 0;
            }
        }
    }
}
