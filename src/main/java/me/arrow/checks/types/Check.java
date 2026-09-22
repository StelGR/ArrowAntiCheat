package me.arrow.checks.types;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.ChatUtils;

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

    /**
     * Records one named exemption before its caller returns from a movement
     * check. Keeping this here gives every check the same debug format.
     */
    protected final boolean exempt(String reason, boolean condition) {
        if (!condition) {
            return false;
        }

        ChatUtils.debugExempt(reason, getClass().getSimpleName());
        return true;
    }

    public abstract void handle(PacketSendEvent event);
    public abstract void handle(PacketReceiveEvent event);
}
