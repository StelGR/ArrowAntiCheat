package me.arrow.checks.impl.misc.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.Math.MathUtil;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

// these only work on below 1.12.2, idk where they removed open inventory achievement packet, but yeah, only inventory B works
// on modern

public class InventoryC extends Check {

    public InventoryC(Profile profile) {
        super(profile, CheckType.INVENTORY, "C", "Checks for invalid inventory interactions");
    }

    private double invTicks;

    @Override
    public void handle( PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(CLICK_WINDOW)) {
            WrapperPlayClientClickWindow clickPacket = new WrapperPlayClientClickWindow(event);

            MovementData movementData = profile.getMovementData();

            if (profile.shouldCancel()) return;

            if (clickPacket.getWindowId() == 0
                    && !(movementData.isNearWater()
                    || movementData.isNearLava()
                    || movementData.isNearWebs())) {
                if (!profile.getActionData().isInInventory() && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
                    fail("Clicking in inventory while its not open", "(No Debug Prodived)");
                    TaskUtils.task(() -> profile.getPlayer().closeInventory());
                    profile.getActionData().setInInventory(false);
                }
            }


            if (profile.isExempt().isTeleports()
                    || profile.getVelocityData().getTotalHorizontalVelocity() > 0) {
                invTicks = 0;
                return;
            }


            if (invTicks > 7) {
                if (movementData.getDeltaXZ() > MathUtil.getBaseSpeed(profile.getPlayer())
                        && !(movementData.isNearWater()
                        || movementData.isNearLava()
                        || movementData.isNearWebs())
                        && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
                    fail("Clicking in inventory while moving", "(No Debug Prodived)");
                    TaskUtils.task(() -> profile.getPlayer().closeInventory());
                    profile.getActionData().setInInventory(false);
                }
            }
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PLAYER_POSITION)
                || event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PLAYER_ROTATION)) {
            if (profile.getActionData().isInInventory() && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
                invTicks++;
            } else {
                invTicks -= Math.min(invTicks, 1);
            }
        } else if (event.getPacketType().equals(INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity wrapperPlayClientInteractEntity = new WrapperPlayClientInteractEntity(event);

            if (wrapperPlayClientInteractEntity.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                if (profile.getActionData().isInInventory() && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
                    fail("Attacking while in inventory", "(No Debug Prodived)");
                    TaskUtils.task(() -> profile.getPlayer().closeInventory());
                    profile.getActionData().setInInventory(false);
                }
            }
        }

    }
}
