package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

public class BadPacketsD extends Check {
    public BadPacketsD(Profile profile) {
        super(profile, CheckType.BADPACKETS, "D", "Checks if player is sending dig state while interacting with an entity");
    }

    boolean sentDigging;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING))
        {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            if (digging.getAction() == DiggingAction.START_DIGGING
                    || digging.getAction() == DiggingAction.FINISHED_DIGGING
                    || digging.getAction() == DiggingAction.CANCELLED_DIGGING) {
                this.sentDigging = true;
            }
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            this.sentDigging = false;
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            if (this.sentDigging && !profile.shouldCancel()) {
                if (increaseBuffer() > 10.0) {
                    fail("Digging while attacking","(No Debug Provided)");
                }
            } else {
               decreaseBufferBy(0.005);
            }
        }
    }
}
