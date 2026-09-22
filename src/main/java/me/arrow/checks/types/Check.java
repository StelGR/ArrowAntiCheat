package me.arrow.checks.types;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.managers.profile.Profile;

/*
 * Abstract class for Checks
 */
public abstract class Check extends AbstractCheck {

    public Check(Profile profile, CheckType check, String type, String description) {
        super(profile, check, type, description);
    }

    public Check(Profile profile, CheckType check, String description) {
        super(profile, check, "", description);
    }

    public abstract void handle(PacketSendEvent event);
    public abstract void handle(PacketReceiveEvent event);
}