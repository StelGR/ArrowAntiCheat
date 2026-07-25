package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSlotStateChange;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

@Experimental
public class BadPacketsF extends Check {
    public BadPacketsF(Profile profile) {
        super(profile, CheckType.BADPACKETS, "F", "Checks for invalid slot");
    }

    private int lastSlot;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.HELD_ITEM_CHANGE)) {
            WrapperPlayClientSlotStateChange slot = new WrapperPlayClientSlotStateChange(event);

            if (!profile.shouldCancel()
                    && !profile.isExempt().isTeleports()) {
                resetBuffer();
                return;
            }

            int currentSlot = slot.getSlot();

            if (currentSlot == lastSlot) {
                if (increaseBuffer() > 1) {
                    fail("Invalid Slot ID","currentSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + currentSlot
                            + "\nlastSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + lastSlot);
                }
            } else decreaseBufferBy(0.25);

            lastSlot = currentSlot;
        }

    }
}

