package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

// in bukkit listener.

@Experimental
public class InteractB extends Check {

    public InteractB(Profile profile) {
        super(profile, CheckType.INTERACT, "B", "Checks for interact through walls (beds only)");
    }

    @Override
    public void handle( PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

    }



}


