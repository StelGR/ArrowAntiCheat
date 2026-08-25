package me.arrow.checks.impl.simulation;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

public class World extends Check {
    public World(Profile profile) {
        super(profile, CheckType.WORLD, "", "World/misc analysis");
    }


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

    }
}
