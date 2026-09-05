package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

@Experimental
public class BadPacketsE extends Check {
    public BadPacketsE(Profile profile) {
        super(profile, CheckType.BADPACKETS, "E", "Checks if the player is sending actions without movement packets");
    }

    private int streak;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            streak = 0;
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)
                || event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {

            if (profile.shouldCancel()
                    || profile.isExempt().isTeleports()
                    || profile.isExempt().isDead()
                    || (profile.getVehicleData() != null && profile.getVehicleData().getSinceVehicleTicks() < 20)) {
                streak = 0;
                return;
            }

            if (++streak > 23) {
                fail("Attacking/Interacting without sending a position packet",
                        "Streak: " + MsgType.MAIN_THEME_COLOR.getMessage() + streak);
            }
        }
    }
}
