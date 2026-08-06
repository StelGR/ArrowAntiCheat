package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

// i think the way i did this, may or may not work...

@Experimental
public class BadPacketsE extends Check {
    public BadPacketsE(Profile profile) {
        super(profile, CheckType.BADPACKETS, "E", "Checks if the player is sending movement packets");
    }

    int streak;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            streak = 0;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)) {
            if (profile.getVehicleData().getSinceVehicleTicks() > 1
                    && !profile.shouldCancel()
                    && !profile.isExempt().isTeleports()) {
                streak = 0;
            }

            if (++streak > 21) {
                fail("Did not send position packet","(No Debug Provided)");
            }
        }

    }
}
