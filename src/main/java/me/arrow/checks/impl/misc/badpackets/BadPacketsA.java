package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;

// do we even need this, wtf is the point lmao

public class BadPacketsA extends Check {
    public BadPacketsA(Profile profile) {
        super(profile, CheckType.BADPACKETS, "A", "Checks for invalid pitch");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isRotation(event.getPacketType())) return;

        double pitch = profile.getRotationData().getPitch();

        if (pitch > MoveUtils.MAXIMUM_PITCH) {
            fail("Invalid Pitch","Pitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch);
        }
    }
}
