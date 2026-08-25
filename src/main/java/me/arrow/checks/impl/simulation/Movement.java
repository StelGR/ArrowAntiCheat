package me.arrow.checks.impl.simulation;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

// this is "simulation" mode. (to poke fun at grim), all it will do when you enable simulation mode, is instead of flagging Speed A, it will call the fail in this class instead. I will add some basic prediction in the future as well, but for now this is it.

// the same thing will happen for combat, and world.

public class Movement extends Check {
    public Movement(Profile profile) {
        super(profile, CheckType.MOVEMENT, "", "Movement simulation");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

    }
}
