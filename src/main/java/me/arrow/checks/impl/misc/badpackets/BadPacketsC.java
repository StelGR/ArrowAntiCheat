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

// never seen this flag, but tbh never seen most people use this type of disablers anyway......

@Experimental
public class BadPacketsC extends Check {
    public BadPacketsC(Profile profile) {
        super(profile, CheckType.BADPACKETS, "C", "Checks for invalid ping");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    double buffer;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            if (profile.getTick() < 10) {
                return;
            }

            float ping = profile.getPing();
            float transPing = profile.getConnectionData().getTransPing();

            if ((ping == 0L && transPing > 1L) || (ping > 1L && transPing == 0L)) {
                if (buffer > 5.0D) {
                    fail("Invalid Ping", "KPing " + MsgType.MAIN_THEME_COLOR.getMessage() + ping
                            + "\nTPing " + MsgType.MAIN_THEME_COLOR.getMessage() + transPing);
                }
            } else {
                buffer = 0.0D;
            }
        }
    }
}
