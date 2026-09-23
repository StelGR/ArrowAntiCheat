package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

public class MotionC extends Check {

    public MotionC(Profile profile) {
        super(profile, CheckType.MOTION, "C", "Checks for wallclimb");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
    }
}
